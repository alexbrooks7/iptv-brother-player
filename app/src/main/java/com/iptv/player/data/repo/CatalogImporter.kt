package com.iptv.player.data.repo

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.iptv.player.analytics.IptvAnalytics
import com.iptv.player.data.db.AppDatabase
import com.iptv.player.data.db.CategoryEntity
import com.iptv.player.data.db.ChannelEntity
import com.iptv.player.data.db.MediaKind
import com.iptv.player.data.db.SeriesEntity
import com.iptv.player.data.db.SourceEntity
import com.iptv.player.data.db.SourceType
import com.iptv.player.data.db.VodEntity
import com.iptv.player.data.parser.M3uEntry
import com.iptv.player.data.parser.M3uParser
import com.iptv.player.data.prefs.CredentialCrypto
import com.iptv.player.data.remote.Http
import com.iptv.player.data.remote.HttpFailure
import com.iptv.player.data.remote.XtreamClient
import com.iptv.player.data.remote.asInt
import com.iptv.player.data.remote.asLong
import com.iptv.player.data.remote.asString
import com.iptv.player.data.remote.firstOf
import com.iptv.player.util.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.InputStream

/** What the UI shows while a playlist is loading. */
sealed interface SyncProgress {
    data object Connecting : SyncProgress
    data object Downloading : SyncProgress
    data class Reading(val items: Int) : SyncProgress
    data object Saving : SyncProgress
    data object LoadingGuide : SyncProgress
}

data class SyncStats(
    val channels: Int = 0,
    val movies: Int = 0,
    val series: Int = 0,
    val programmes: Int = 0,
    /** Entries the playlist contained but that could not be used. */
    val skipped: Int = 0,
    val duplicates: Int = 0,
)

sealed interface SyncResult {
    data class Success(val stats: SyncStats) : SyncResult
    data class Failure(val failure: HttpFailure?, val message: String) : SyncResult
}

/**
 * The one place `source_synced` is reported, called from both places a sync
 * can be kicked off: [com.iptv.player.ui.vm.SourcesViewModel] for an add or a
 * user-pressed refresh, and [com.iptv.player.work.RefreshWorker] for the
 * scheduled background one. Kept here rather than duplicated in each,
 * because [trigger] is the one property that makes this event worth
 * anything — "which of the two possible causes" is a fact about [SyncResult]
 * that should not have two independently-maintained copies of its mapping.
 */
fun reportSourceSynced(sourceType: SourceType, trigger: String, result: SyncResult) {
    IptvAnalytics.event(
        "source_synced",
        when (result) {
            is SyncResult.Success -> mapOf(
                "source_type" to sourceType.name.lowercase(),
                "trigger" to trigger,
                "success" to true,
                "channels" to result.stats.channels,
                "movies" to result.stats.movies,
                "series" to result.stats.series,
            )
            is SyncResult.Failure -> mapOf(
                "source_type" to sourceType.name.lowercase(),
                "trigger" to trigger,
                "success" to false,
                "reason" to (result.failure?.kind?.name?.lowercase() ?: "unknown"),
            )
        },
    )
}

/**
 * Turns a configured source into rows in the database.
 *
 * The import is destructive per source: a section's rows are deleted and
 * rewritten. Favourites and watch history are *not* touched — they key off
 * `streamKey`, which is stable across refreshes precisely so that a provider
 * renumbering its line-up does not wipe a user's favourites.
 *
 * **Why the whole import is not one transaction.** It would be the obvious way
 * to guarantee that a refresh dying halfway leaves the old catalogue intact,
 * and it is wrong here: an Xtream catalogue arrives as a 10–30 MB streamed
 * JSON response, so a single transaction would hold SQLite's write lock open
 * across minutes of network I/O. Every other writer — toggling a favourite,
 * saving a resume position during playback — would block on it, which the user
 * experiences as the UI freezing whenever a background refresh runs. So each
 * chunk commits on its own, and an interrupted refresh leaves a partial
 * catalogue plus a `lastSyncError` the Playlists screen renders with a Retry
 * action. A partial channel list that the user can see and fix is a better
 * failure than a frozen remote.
 *
 * The M3U path *is* transactional, because there the parse has already
 * finished before the first write — no network I/O happens inside the lock.
 */
class CatalogImporter(
    private val context: Context,
    private val db: AppDatabase,
    private val epgRepository: EpgRepository,
) {

    /** Rows per insert. Big enough to amortise transaction overhead, small
     *  enough that a 60,000-item VOD catalogue never sits in memory at once. */
    private val chunkSize = 2_000

    suspend fun sync(
        source: SourceEntity,
        onProgress: (SyncProgress) -> Unit = {},
    ): SyncResult = withContext(Dispatchers.IO) {
        try {
            val stats = when (source.type) {
                SourceType.XTREAM -> syncXtream(source, onProgress)
                SourceType.M3U_URL, SourceType.M3U_FILE -> syncM3u(source, onProgress)
            }
            db.sources().markSynced(
                id = source.id,
                live = stats.channels,
                movies = stats.movies,
                series = stats.series,
                at = System.currentTimeMillis(),
            )
            Diagnostics.info("import", "${source.name}: ${stats.channels} channels, ${stats.movies} movies, ${stats.series} series, ${stats.skipped} skipped")
            SyncResult.Success(stats)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val failure = e as? HttpFailure
            // CREDENTIALS_MISSING gets its own copy rather than HttpFailure's
            // generic "kind (code)" text — the Playlists screen also shows a
            // dedicated "Sign in" banner for it (SourcesScreen checks
            // credentialsCipher directly), but this string is still what ends
            // up in the list row and the diagnostics log, and "credentials
            // missing" reads better there than the raw enum name.
            val message = when (failure?.kind) {
                HttpFailure.Kind.CREDENTIALS_MISSING -> "No username or password set"
                else -> e.message ?: e::class.java.simpleName
            }
            db.sources().markSyncFailed(source.id, message)
            Diagnostics.error("import", "${source.name} failed", e)
            SyncResult.Failure(failure, message)
        }
    }

    // ---- M3U ------------------------------------------------------------

    private suspend fun syncM3u(source: SourceEntity, onProgress: (SyncProgress) -> Unit): SyncStats {
        onProgress(SyncProgress.Downloading)
        val playlist = openSource(source).use { M3uParser.parse(it) }
        currentCoroutineContext().ensureActive()

        onProgress(SyncProgress.Reading(playlist.entries.size))

        // Groups become categories. Insertion order is preserved so the
        // category list matches the order the provider wrote them in, which is
        // what users are used to from other players.
        val groups = LinkedHashSet<String>()
        playlist.entries.forEach { entry -> entry.group?.let(groups::add) }

        val categoryRows = groups.mapIndexed { index, name ->
            CategoryEntity(
                sourceId = source.id,
                kind = MediaKind.LIVE,
                remoteId = null,
                name = name,
                sortOrder = index,
                adult = CategoryClassifier.isAdult(name),
            )
        }

        onProgress(SyncProgress.Saving)
        db.withTransaction {
            db.channels().deleteFor(source.id)
            db.categories().deleteFor(source.id, MediaKind.LIVE)
            val ids = db.categories().insertAll(categoryRows)
            val idByName = groups.mapIndexed { index, name -> name to ids.getOrNull(index) }.toMap()

            playlist.entries.mapIndexed { index, entry ->
                entry.toChannel(source.id, index, idByName[entry.group])
            }.chunked(chunkSize).forEach { db.channels().insertAll(it) }
        }

        // A playlist can carry its own EPG pointer in the #EXTM3U header; the
        // brief also wants a separately configured URL to merge in. Both are
        // imported, later programmes simply adding to the same table.
        val epgUrls = (playlist.epgUrls + listOfNotNull(source.epgUrl)).distinct()
        val programmes = if (epgUrls.isEmpty()) 0 else {
            onProgress(SyncProgress.LoadingGuide)
            epgRepository.importFrom(source, epgUrls)
        }

        return SyncStats(
            channels = playlist.entries.size,
            programmes = programmes,
            skipped = playlist.skipped,
            duplicates = playlist.duplicates,
        )
    }

    private fun M3uEntry.toChannel(sourceId: Long, index: Int, categoryId: Long?) = ChannelEntity(
        sourceId = sourceId,
        categoryId = categoryId,
        streamKey = streamKey(),
        name = name,
        url = url,
        logo = logo,
        tvgId = tvgId,
        groupTitle = group,
        number = number,
        sortOrder = index,
        catchupDays = catchupDays,
        catchupSource = catchupSource,
    )

    /**
     * A channel's identity within its source. tvg-id when the playlist
     * provides one (it survives the provider reordering or renaming things);
     * otherwise a hash of name+URL, which survives reordering but not renaming
     * — the best available with no identifier to work from.
     */
    private fun M3uEntry.streamKey(): String =
        tvgId?.takeIf { it.isNotBlank() } ?: "h:${(name + '|' + url).stableHash()}"

    private fun openSource(source: SourceEntity): InputStream = when (source.type) {
        SourceType.M3U_FILE -> context.contentResolver.openInputStream(Uri.parse(source.url))
            ?: throw HttpFailure(HttpFailure.Kind.NOT_FOUND)

        else -> {
            val response = Http.client()
                .newCall(Http.request(source.url, source.userAgent))
                .execute()
            if (!response.isSuccessful) {
                response.close()
                throw HttpFailure.fromStatus(response.code)
            }
            response.body?.byteStream() ?: throw HttpFailure(HttpFailure.Kind.BAD_RESPONSE)
        }
    }

    // ---- Xtream ---------------------------------------------------------

    private suspend fun syncXtream(source: SourceEntity, onProgress: (SyncProgress) -> Unit): SyncStats {
        onProgress(SyncProgress.Connecting)
        val credentials = CredentialCrypto.decrypt(source.credentialsCipher)
            ?: throw HttpFailure(HttpFailure.Kind.CREDENTIALS_MISSING)
        val client = XtreamClient(source.url, credentials, source.userAgent)

        client.authenticate() ?: throw HttpFailure(HttpFailure.Kind.FORBIDDEN)
        currentCoroutineContext().ensureActive()

        onProgress(SyncProgress.Downloading)

        var liveCount = 0
        var movieCount = 0
        var seriesCount = 0

        // Live -----------------------------------------------------------
        // Categories are fetched before the transaction opens, never inside
        // one — same reason as the class doc: no network I/O under the lock.
        val liveCategories = client.liveCategories()
        val liveIds = db.withTransaction {
            db.channels().deleteFor(source.id)
            db.categories().deleteFor(source.id, MediaKind.LIVE)
            insertCategories(source.id, MediaKind.LIVE, liveCategories)
        }
        val channelBuffer = ArrayList<ChannelEntity>(chunkSize)
        client.liveStreams { sequence ->
            for (obj in sequence) {
                currentCoroutineContext().ensureActive()
                obj.toChannel(source.id, liveIds, liveCount)?.let {
                    channelBuffer += it
                    liveCount++
                }
                if (channelBuffer.size >= chunkSize) {
                    flushChannels(channelBuffer)
                    onProgress(SyncProgress.Reading(liveCount))
                }
            }
            flushChannels(channelBuffer)
        }

        // Movies ---------------------------------------------------------
        val vodCategories = client.vodCategories()
        val vodIds = db.withTransaction {
            db.vod().deleteFor(source.id)
            db.categories().deleteFor(source.id, MediaKind.MOVIE)
            insertCategories(source.id, MediaKind.MOVIE, vodCategories)
        }
        val vodBuffer = ArrayList<VodEntity>(chunkSize)
        client.vodStreams { sequence ->
            for (obj in sequence) {
                currentCoroutineContext().ensureActive()
                obj.toVod(source.id, vodIds, movieCount)?.let {
                    vodBuffer += it
                    movieCount++
                }
                if (vodBuffer.size >= chunkSize) {
                    flushVod(vodBuffer)
                    onProgress(SyncProgress.Reading(movieCount))
                }
            }
            flushVod(vodBuffer)
        }

        // Series ---------------------------------------------------------
        val seriesCategories = client.seriesCategories()
        val seriesIds = db.withTransaction {
            db.episodes().deleteFor(source.id)
            db.series().deleteFor(source.id)
            db.categories().deleteFor(source.id, MediaKind.SERIES)
            insertCategories(source.id, MediaKind.SERIES, seriesCategories)
        }
        val seriesBuffer = ArrayList<SeriesEntity>(chunkSize)
        client.seriesList { sequence ->
            for (obj in sequence) {
                currentCoroutineContext().ensureActive()
                obj.toSeries(source.id, seriesIds, seriesCount)?.let {
                    seriesBuffer += it
                    seriesCount++
                }
                if (seriesBuffer.size >= chunkSize) {
                    flushSeries(seriesBuffer)
                    onProgress(SyncProgress.Reading(seriesCount))
                }
            }
            flushSeries(seriesBuffer)
        }

        // Guide ----------------------------------------------------------
        onProgress(SyncProgress.LoadingGuide)
        val epgUrls = (listOf(client.epgUrl()) + listOfNotNull(source.epgUrl)).distinct()
        val programmes = epgRepository.importFrom(source, epgUrls)

        return SyncStats(
            channels = liveCount,
            movies = movieCount,
            series = seriesCount,
            programmes = programmes,
        )
    }

    private suspend fun insertCategories(
        sourceId: Long,
        kind: MediaKind,
        categories: List<XtreamClient.RemoteCategory>,
    ): Map<String, Long> {
        val rows = categories.mapIndexed { index, category ->
            CategoryEntity(
                sourceId = sourceId,
                kind = kind,
                remoteId = category.id,
                name = category.name,
                sortOrder = index,
                adult = CategoryClassifier.isAdult(category.name),
            )
        }
        val ids = db.categories().insertAll(rows)
        return categories.mapIndexedNotNull { index, category ->
            ids.getOrNull(index)?.let { category.id to it }
        }.toMap()
    }

    private suspend fun flushChannels(buffer: MutableList<ChannelEntity>) {
        if (buffer.isEmpty()) return
        db.channels().insertAll(buffer.toList())
        buffer.clear()
    }

    private suspend fun flushVod(buffer: MutableList<VodEntity>) {
        if (buffer.isEmpty()) return
        db.vod().insertAll(buffer.toList())
        buffer.clear()
    }

    private suspend fun flushSeries(buffer: MutableList<SeriesEntity>) {
        if (buffer.isEmpty()) return
        db.series().insertAll(buffer.toList())
        buffer.clear()
    }

    // Field names below are checked in several spellings on purpose — see the
    // XtreamClient class doc for why nothing about this API can be assumed.

    private fun JsonObject.toChannel(sourceId: Long, categoryIds: Map<String, Long>, index: Int): ChannelEntity? {
        val streamId = firstOf("stream_id", "id")?.asString() ?: return null
        val name = firstOf("name", "title")?.asString() ?: return null
        val catchupDays = firstOf("tv_archive_duration", "tv_archive")?.asInt() ?: 0
        return ChannelEntity(
            sourceId = sourceId,
            categoryId = firstOf("category_id")?.asString()?.let(categoryIds::get),
            streamKey = streamId,
            name = name,
            // Empty rather than the real URL: it embeds the account password,
            // and the whole point of encrypting credentials is undone if a
            // copy sits in plaintext on 10,000 channel rows. Built at play
            // time by StreamUrlResolver instead.
            url = "",
            logo = firstOf("stream_icon", "icon")?.asString(),
            tvgId = firstOf("epg_channel_id")?.asString(),
            groupTitle = null,
            number = firstOf("num")?.asInt(),
            sortOrder = index,
            catchupDays = catchupDays,
        )
    }

    private fun JsonObject.toVod(sourceId: Long, categoryIds: Map<String, Long>, index: Int): VodEntity? {
        val streamId = firstOf("stream_id", "vod_id", "id")?.asString() ?: return null
        val name = firstOf("name", "title")?.asString() ?: return null
        return VodEntity(
            sourceId = sourceId,
            categoryId = firstOf("category_id")?.asString()?.let(categoryIds::get),
            streamKey = streamId,
            name = name,
            url = null,
            containerExtension = firstOf("container_extension")?.asString(),
            poster = firstOf("stream_icon", "cover", "movie_image")?.asString(),
            year = firstOf("year", "releaseDate", "release_date")?.asString()?.take(4),
            rating = firstOf("rating", "rating_5based")?.asString(),
            genre = firstOf("genre")?.asString(),
            plot = firstOf("plot", "description")?.asString(),
            durationSecs = firstOf("episode_run_time")?.asInt()?.times(60),
            sortOrder = index,
        )
    }

    private fun JsonObject.toSeries(sourceId: Long, categoryIds: Map<String, Long>, index: Int): SeriesEntity? {
        val seriesId = firstOf("series_id", "id")?.asString() ?: return null
        val name = firstOf("name", "title")?.asString() ?: return null
        return SeriesEntity(
            sourceId = sourceId,
            categoryId = firstOf("category_id")?.asString()?.let(categoryIds::get),
            streamKey = seriesId,
            name = name,
            poster = firstOf("cover", "stream_icon")?.asString(),
            year = firstOf("year", "releaseDate", "release_date")?.asString()?.take(4),
            rating = firstOf("rating", "rating_5based")?.asString(),
            genre = firstOf("genre")?.asString(),
            plot = firstOf("plot", "description")?.asString(),
            sortOrder = index,
        )
    }
}

/**
 * Stable across processes and app versions, unlike [String.hashCode], which
 * Android does not guarantee between runtimes. Favourites are keyed on this,
 * so a change in the algorithm silently orphans them — treat it as a
 * persisted format, not an implementation detail.
 */
fun String.stableHash(): String {
    // FNV-1a, 64-bit. Offset basis 0xcbf29ce484222325 written as its signed
    // Long equivalent, since Kotlin has no unsigned hex literal for Long.
    var h = -0x340D631B7BDDDCDBL
    for (element in this) {
        h = h xor element.code.toLong()
        h *= 0x100000001B3L
    }
    return java.lang.Long.toHexString(h)
}
