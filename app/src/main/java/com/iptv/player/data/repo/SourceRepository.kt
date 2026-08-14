package com.iptv.player.data.repo

import com.iptv.player.data.db.AppDatabase
import com.iptv.player.data.db.SourceEntity
import com.iptv.player.data.db.SourceType
import com.iptv.player.data.prefs.CredentialCrypto
import com.iptv.player.data.prefs.Credentials
import com.iptv.player.data.prefs.SettingsStore
import com.iptv.player.data.remote.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** CRUD over configured playlists, plus the credential encryption boundary. */
class SourceRepository(
    private val db: AppDatabase,
    private val settings: SettingsStore,
) {

    fun observeAll(): Flow<List<SourceEntity>> = db.sources().observeAll()

    fun observe(id: Long): Flow<SourceEntity?> = db.sources().observeById(id)

    suspend fun get(id: Long): SourceEntity? = withContext(Dispatchers.IO) { db.sources().getById(id) }

    suspend fun all(): List<SourceEntity> = withContext(Dispatchers.IO) { db.sources().getAll() }

    suspend fun addM3u(
        name: String,
        url: String,
        epgUrl: String?,
        userAgent: String?,
        fromFile: Boolean,
    ): Long = withContext(Dispatchers.IO) {
        val source = SourceEntity(
            name = name.trim(),
            type = if (fromFile) SourceType.M3U_FILE else SourceType.M3U_URL,
            url = url.trim(),
            epgUrl = epgUrl?.trim()?.ifBlank { null },
            userAgent = userAgent?.trim()?.ifBlank { null },
            sortOrder = db.sources().maxSortOrder() + 1,
        )
        db.sources().insert(source).also { activateIfFirst(it) }
    }

    suspend fun addXtream(
        name: String,
        serverUrl: String,
        username: String,
        password: String,
        epgUrl: String?,
        userAgent: String?,
    ): Long = withContext(Dispatchers.IO) {
        val source = SourceEntity(
            name = name.trim(),
            type = SourceType.XTREAM,
            url = XtreamClient.normaliseBase(serverUrl),
            epgUrl = epgUrl?.trim()?.ifBlank { null },
            credentialsCipher = CredentialCrypto.encrypt(Credentials(username.trim(), password)),
            userAgent = userAgent?.trim()?.ifBlank { null },
            sortOrder = db.sources().maxSortOrder() + 1,
        )
        db.sources().insert(source).also { activateIfFirst(it) }
    }

    /**
     * Registers an Xtream source with no login yet — the shape a backup import
     * produces, since exports never carry credentials (see [ConfigBackup]).
     * The row is real and shows up in Playlists immediately; a sync attempt
     * against it fails with [com.iptv.player.data.remote.HttpFailure.Kind.CREDENTIALS_MISSING]
     * until [setXtreamCredentials] is called.
     */
    suspend fun addXtreamPending(
        name: String,
        serverUrl: String,
        epgUrl: String?,
        userAgent: String?,
    ): Long = withContext(Dispatchers.IO) {
        val source = SourceEntity(
            name = name.trim(),
            type = SourceType.XTREAM,
            url = XtreamClient.normaliseBase(serverUrl),
            epgUrl = epgUrl?.trim()?.ifBlank { null },
            credentialsCipher = null,
            userAgent = userAgent?.trim()?.ifBlank { null },
            sortOrder = db.sources().maxSortOrder() + 1,
        )
        db.sources().insert(source).also { activateIfFirst(it) }
    }

    /** Attaches or replaces the login on an existing Xtream source. */
    suspend fun setXtreamCredentials(id: Long, username: String, password: String) =
        withContext(Dispatchers.IO) {
            val source = db.sources().getById(id) ?: return@withContext
            db.sources().update(
                source.copy(
                    credentialsCipher = CredentialCrypto.encrypt(Credentials(username.trim(), password)),
                    lastSyncError = null,
                )
            )
        }

    suspend fun update(source: SourceEntity) = withContext(Dispatchers.IO) { db.sources().update(source) }

    suspend fun rename(id: Long, name: String) = withContext(Dispatchers.IO) {
        db.sources().rename(id, name.trim())
    }

    /**
     * Removes the playlist and everything derived from it. Room has no
     * cascading foreign keys here — the child tables carry a plain `sourceId`
     * column rather than a real FK — so the deletes are explicit. That was a
     * deliberate schema choice: FK enforcement costs an index lookup on every
     * one of the ~10,000 inserts a refresh performs, and this is the only
     * place the cascade would ever have run.
     */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        db.channels().deleteFor(id)
        db.vod().deleteFor(id)
        db.episodes().deleteFor(id)
        db.series().deleteFor(id)
        db.programmes().deleteFor(id)
        db.history().clearFor(id)
        db.sources().delete(id)

        // Deleting the active playlist has to leave a valid one selected, or
        // the home screen renders against an id that no longer exists.
        val remaining = db.sources().getAll()
        settings.setActiveSource(remaining.firstOrNull()?.id ?: 0)
    }

    suspend fun reorder(orderedIds: List<Long>) = withContext(Dispatchers.IO) {
        orderedIds.forEachIndexed { index, id -> db.sources().setSortOrder(id, index) }
    }

    suspend fun credentials(source: SourceEntity): Credentials? =
        CredentialCrypto.decrypt(source.credentialsCipher)

    suspend fun client(source: SourceEntity): XtreamClient? {
        if (source.type != SourceType.XTREAM) return null
        val credentials = credentials(source) ?: return null
        return XtreamClient(source.url, credentials, source.userAgent)
    }

    private suspend fun activateIfFirst(newId: Long) {
        if (db.sources().getAll().size == 1) settings.setActiveSource(newId)
    }
}
