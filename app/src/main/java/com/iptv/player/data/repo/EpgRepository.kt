package com.iptv.player.data.repo

import com.iptv.player.data.db.AppDatabase
import com.iptv.player.data.db.ProgrammeEntity
import com.iptv.player.data.db.SourceEntity
import com.iptv.player.data.parser.XmltvParser
import com.iptv.player.data.remote.Http
import com.iptv.player.data.remote.HttpFailure
import com.iptv.player.util.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Now/next pair for the channel banner. Either half may be absent. */
data class NowNext(val now: ProgrammeEntity?, val next: ProgrammeEntity?)

/**
 * Owns everything EPG: importing XMLTV, and the two read shapes the UI needs
 * (a time window for the guide grid, and now/next for the channel banner).
 */
class EpgRepository(private val db: AppDatabase) {

    /**
     * Downloads and stores every feed in [urls] against [source], merging them
     * into one table. Returns the number of programmes stored.
     *
     * Failure of one feed does not fail the others: a user with a working
     * playlist EPG plus a dead third-party URL should still get a guide.
     */
    suspend fun importFrom(source: SourceEntity, urls: List<String>): Int = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) return@withContext 0

        db.programmes().deleteFor(source.id)
        var stored = 0
        var skippedExpired = 0
        val displayNameToId = HashMap<String, String>(512)

        // Anything that finished before this is not worth writing to disk.
        //
        // XMLTV feeds routinely carry several days of *history* — the profiled
        // feed spanned 4.6 days, of which 3 were already over. Storing them
        // cost 53 MB of description text in a 156 MB database on a device with
        // 138 MB of free memory, and not one row of it can ever be displayed:
        // the guide starts at "now" and the only backwards affordance is
        // catch-up, which streams from the provider rather than from this
        // table. Dropping them here rather than deleting them afterwards also
        // takes the write out of the import entirely, which is most of what
        // made importing a large EPG slow.
        val keepFrom = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(RETAIN_ENDED_HOURS)

        urls.forEach { url ->
            runCatching {
                openFeed(url, source.userAgent).use { stream ->
                    val result = XmltvParser.parse(stream) { batch ->
                        val fresh = batch.filter { it.endUtc >= keepFrom }
                        skippedExpired += batch.size - fresh.size
                        // The parser calls back on this thread; bridging with
                        // runBlocking keeps the parse streaming rather than
                        // buffering a whole feed to hand to a suspend function.
                        if (fresh.isNotEmpty()) {
                            runBlocking {
                                db.programmes().insertAll(
                                    fresh.map { p ->
                                        ProgrammeEntity(
                                            sourceId = source.id,
                                            channelId = p.channelId,
                                            title = p.title,
                                            description = p.description,
                                            category = p.category,
                                            startUtc = p.startUtc,
                                            endUtc = p.endUtc,
                                            iconUrl = p.iconUrl,
                                        )
                                    }
                                )
                            }
                        }
                        stored += fresh.size
                    }
                    result.channels.forEach { channel ->
                        channel.displayNames.forEach { name ->
                            displayNameToId.putIfAbsent(name.normaliseChannelName(), channel.id)
                        }
                    }
                    Diagnostics.info(
                        "epg",
                        "$url -> ${result.programmeCount} programmes, ${result.channels.size} channels" +
                            if (result.malformed > 0) ", ${result.malformed} malformed" else "",
                    )
                }
            }.onFailure { Diagnostics.error("epg", "Feed failed: $url", it) }
        }

        linkChannelsByName(source, displayNameToId)
        if (skippedExpired > 0) {
            Diagnostics.info("epg", "Skipped $skippedExpired already-ended programme(s)")
        }
        // Housekeeping while we are already touching this table. Still needed
        // despite the filter above: a source that is never refreshed again
        // would otherwise keep listings from the last import forever.
        purgeExpired(source.id)
        stored
    }

    /**
     * Deletes listings that have already finished.
     *
     * Separate from the import, and called on start-up as well, because the
     * import is the one moment this *cannot* be relied on to happen. A user who
     * imports a guide and then leaves the app alone for a week — or whose
     * scheduled refresh keeps failing because their provider is down — is
     * exactly the user whose database fills with dead rows, and they are also
     * the user least likely to trigger the cleanup that lived only at the tail
     * of a successful import. Returns the number of rows removed.
     */
    suspend fun purgeExpired(sourceId: Long): Int = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(RETAIN_ENDED_HOURS)
        val doomed = db.programmes().countEndedBefore(sourceId, cutoff)
        if (doomed == 0) return@withContext 0
        // NonCancellable: this runs from start-up and from a worker, both of
        // which can be torn down mid-flight, and a delete that is abandoned
        // halfway leaves the table exactly as bloated as before while having
        // already paid for the scan.
        withContext(NonCancellable) { db.programmes().deleteEndedBefore(sourceId, cutoff) }
        Diagnostics.info("epg", "Purged $doomed expired programme(s)")
        doomed
    }

    /**
     * Attaches guide data to channels whose playlist entry carried no
     * `tvg-id`, by matching the channel name against the XMLTV feed's
     * `display-name` values.
     *
     * This matters more than it sounds: a large share of M3U playlists ship
     * with no tvg-id at all, and without this fallback those users see an
     * empty guide next to a perfectly good EPG feed. The match is deliberately
     * conservative — punctuation, case, and the quality suffixes providers
     * append ("HD", "FHD", "4K", "RAW") are normalised away, but nothing
     * fuzzier is attempted, because a wrong match shows the wrong programme,
     * which is worse than showing none.
     */
    private suspend fun linkChannelsByName(source: SourceEntity, displayNameToId: Map<String, String>) {
        if (displayNameToId.isEmpty()) return
        val unlinked = db.channels().withoutTvgId(source.id)
        if (unlinked.isEmpty()) return

        var linked = 0
        unlinked.forEach { channel ->
            displayNameToId[channel.name.normaliseChannelName()]?.let { tvgId ->
                db.channels().setTvgId(channel.id, tvgId)
                linked++
            }
        }
        if (linked > 0) Diagnostics.info("epg", "Matched $linked channels to guide data by name")
    }

    /**
     * Programmes overlapping [from]..[to] for the given XMLTV channel ids,
     * grouped by channel id. The guide renders straight off this map.
     *
     * SQLite caps a statement at 999 bound parameters, so the channel list is
     * queried in chunks — a guide page shows a dozen rows, but "jump to now"
     * across a 5,000-channel playlist can ask for far more.
     */
    suspend fun window(
        sourceId: Long,
        channelIds: List<String>,
        from: Long,
        to: Long,
    ): Map<String, List<ProgrammeEntity>> = withContext(Dispatchers.IO) {
        if (channelIds.isEmpty()) return@withContext emptyMap()
        channelIds.chunked(900)
            .flatMap { db.programmes().inWindow(sourceId, it, from, to) }
            .groupBy { it.channelId }
    }

    suspend fun nowNext(sourceId: Long, tvgId: String?, now: Long): NowNext = withContext(Dispatchers.IO) {
        if (tvgId.isNullOrBlank()) return@withContext NowNext(null, null)
        val upcoming = db.programmes().upcoming(sourceId, tvgId, now, limit = 2)
        val current = upcoming.firstOrNull()?.takeIf { it.startUtc <= now }
        NowNext(current, if (current == null) upcoming.firstOrNull() else upcoming.getOrNull(1))
    }

    suspend fun hasData(sourceId: Long): Boolean = withContext(Dispatchers.IO) {
        db.programmes().count(sourceId) > 0
    }

    suspend fun clear(sourceId: Long) = withContext(Dispatchers.IO) {
        db.programmes().deleteFor(sourceId)
    }

    companion object {
        /**
         * How long a finished programme is kept.
         *
         * Not zero, because "now" is a moving target and the guide's window
         * starts at the previous half hour — dropping a programme the instant
         * it ends would blank the leftmost column of the grid. Twelve hours is
         * generous cover for that and for a clock that is wrong by a timezone.
         */
        const val RETAIN_ENDED_HOURS = 12L
    }

    private fun openFeed(url: String, userAgent: String?): InputStream {
        val response = Http.client().newCall(Http.request(url, userAgent)).execute()
        if (!response.isSuccessful) {
            response.close()
            throw HttpFailure.fromStatus(response.code)
        }
        return response.body?.byteStream() ?: throw HttpFailure(HttpFailure.Kind.BAD_RESPONSE)
    }
}

/**
 * Reduces a channel name to something comparable across a playlist and an
 * XMLTV feed: lower case, no punctuation, no quality suffix, no country
 * prefix of the "UK | " form that group-based playlists use.
 */
fun String.normaliseChannelName(): String {
    var text = lowercase(Locale.US)
    // "UK: Sky Sports HD" / "UK | Sky Sports" -> "sky sports"
    val separator = text.indexOfFirst { it == '|' || it == ':' }
    if (separator in 1..4) text = text.substring(separator + 1)

    val tokens = text.replace(NON_ALNUM, " ").split(' ').filter { it.isNotBlank() }.toMutableList()
    // Strip trailing quality markers, possibly several ("Sky Sports FHD RAW").
    while (tokens.size > 1 && tokens.last() in QUALITY_SUFFIXES) tokens.removeAt(tokens.lastIndex)
    return tokens.joinToString(" ")
}

private val NON_ALNUM = Regex("[^a-z0-9]+")
private val QUALITY_SUFFIXES = listOf("uhd", "fhd", "hd", "sd", "4k", "raw", "hevc", "h265")
