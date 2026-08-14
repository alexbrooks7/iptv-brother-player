package com.iptv.player.data.backup

import android.content.Context
import android.net.Uri
import com.iptv.player.data.db.AppDatabase
import com.iptv.player.data.db.FavoriteEntity
import com.iptv.player.data.db.MediaKind
import com.iptv.player.data.db.SourceType
import com.iptv.player.data.repo.SourceRepository
import com.iptv.player.util.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupFile(
    val version: Int = 1,
    val exportedAt: Long = 0,
    val sources: List<BackupSource> = emptyList(),
)

@Serializable
data class BackupSource(
    val name: String,
    val type: String,
    val url: String,
    val epgUrl: String? = null,
    val userAgent: String? = null,
    /**
     * Always null on export — see the class doc on [ConfigBackup]. The fields
     * exist so a hand-edited or externally produced file can still carry a
     * login and import in one step; nothing the app itself writes ever
     * populates them.
     */
    val username: String? = null,
    val password: String? = null,
    val favoriteChannelKeys: List<String> = emptyList(),
)

/**
 * Import and export of the playlist configuration, for backup or for moving to
 * a second box.
 *
 * **On credentials: exports never contain them.** The encrypted blob in the
 * database is bound to *this* device's Keystore, so copying it achieves
 * nothing — a restore on another box cannot decrypt it. The only way to make
 * an export "complete" for an Xtream source would be to write the account
 * password in plain text, and a JSON file is not a safe place for that: it can
 * end up on a shared USB stick, in a cloud-synced folder, or attached to a
 * support request without anyone thinking about it as a credential. So
 * [export] and [exportToFile] always call [buildPayload] with credentials
 * omitted — there is no flag to turn that back on. An imported Xtream source
 * lands in Playlists via [SourceRepository.addXtreamPending] and shows a
 * "sign in" prompt (see `HttpFailure.Kind.CREDENTIALS_MISSING`) until the user
 * re-enters its login; everything else — server URL, EPG URL, user agent,
 * favourites — restores immediately.
 *
 * Favourites travel with the export because they key off `streamKey`, which is
 * derived from the provider's own identifiers and therefore means the same
 * thing on the new device.
 */
class ConfigBackup(
    private val context: Context,
    private val db: AppDatabase,
    private val sources: SourceRepository,
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Where a backup goes when the device has no document picker.
     *
     * Plenty of TV devices ship without one — bare AOSP boxes frequently have
     * no DocumentsUI at all, and launching `ACTION_CREATE_DOCUMENT` there dies
     * with a system toast the app cannot intercept. This directory needs no
     * permission on any supported API level and is reachable over adb or by
     * mounting the box, so the export still produces a file the user can get
     * at rather than silently failing.
     */
    fun fallbackExportFile(): java.io.File =
        java.io.File(context.getExternalFilesDir(null), SUGGESTED_FILENAME)

    /** Export to a plain file, used when no document picker is available. */
    suspend fun exportToFile(target: java.io.File): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                target.parentFile?.mkdirs()
                target.writeText(buildPayload())
                val count = db.sources().getAll().size
                Diagnostics.info("backup", "Exported $count playlists to ${target.absolutePath}")
                count
            }.onFailure { Diagnostics.error("backup", "File export failed", it) }
        }

    suspend fun export(target: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = buildPayload()
            context.contentResolver.openOutputStream(target)?.use { out -> out.write(text.toByteArray()) }
                ?: error("Could not open the chosen file for writing")
            val count = db.sources().getAll().size
            Diagnostics.info("backup", "Exported $count playlists")
            count
        }.onFailure { Diagnostics.error("backup", "Export failed", it) }
    }

    /**
     * Builds the export payload. There is deliberately no parameter here to
     * include credentials — see the class doc — so nothing upstream can flip
     * that back on by passing a flag.
     */
    private suspend fun buildPayload(): String {
        val entries = db.sources().getAll().map { source ->
            BackupSource(
                name = source.name,
                type = source.type.name,
                url = source.url,
                epgUrl = source.epgUrl,
                userAgent = source.userAgent,
                favoriteChannelKeys = favoriteKeys(source.id),
            )
        }
        return json.encodeToString(
            BackupFile.serializer(),
            BackupFile(version = 1, exportedAt = System.currentTimeMillis(), sources = entries),
        )
    }

    /**
     * Adds every playlist in the file. Existing playlists are left alone and
     * duplicates by URL are skipped, so importing the same backup twice is
     * harmless — a re-import after a partial restore is exactly when a user
     * reaches for this.
     */
    suspend fun import(source: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(source)?.use { it.readBytes().decodeToString() }
                ?: error("Could not read the chosen file")
            val payload = json.decodeFromString(BackupFile.serializer(), text)

            val existingUrls = db.sources().getAll().map { it.url }.toSet()
            var added = 0
            payload.sources.forEach { entry ->
                if (entry.url in existingUrls) return@forEach
                val type = runCatching { SourceType.valueOf(entry.type) }.getOrNull() ?: return@forEach

                val newId = when (type) {
                    SourceType.XTREAM -> {
                        // Exports never carry a password (see the class doc
                        // above), so this is always the pending path: the
                        // source appears in Playlists right away, and its
                        // sync fails with a "sign in" prompt — see
                        // HttpFailure.Kind.CREDENTIALS_MISSING — until the
                        // user enters a login on the Playlists screen. The
                        // username/password fields on BackupSource are kept
                        // only so a hand-edited or older-format file that
                        // does carry them still imports in one step.
                        val username = entry.username
                        val password = entry.password
                        if (username != null && password != null) {
                            sources.addXtream(entry.name, entry.url, username, password, entry.epgUrl, entry.userAgent)
                        } else {
                            sources.addXtreamPending(entry.name, entry.url, entry.epgUrl, entry.userAgent)
                        }
                    }
                    // A M3U_FILE points at a content:// URI that a different
                    // device has no permission for, so it is imported as a URL
                    // source; if it does not resolve, the user re-picks the
                    // file. Better than silently creating a broken playlist.
                    SourceType.M3U_FILE, SourceType.M3U_URL ->
                        sources.addM3u(entry.name, entry.url, entry.epgUrl, entry.userAgent, fromFile = false)
                }
                added++

                val now = System.currentTimeMillis()
                entry.favoriteChannelKeys.forEach { key ->
                    db.favorites().add(FavoriteEntity(newId, MediaKind.LIVE, key, now))
                }
            }
            Diagnostics.info("backup", "Imported $added playlists")
            added
        }.onFailure { Diagnostics.error("backup", "Import failed", it) }
    }

    private suspend fun favoriteKeys(sourceId: Long): List<String> =
        db.favorites().keysOnce(sourceId, MediaKind.LIVE)

    companion object {
        const val SUGGESTED_FILENAME = "iptv-player-playlists.json"
        const val MIME_TYPE = "application/json"
    }
}
