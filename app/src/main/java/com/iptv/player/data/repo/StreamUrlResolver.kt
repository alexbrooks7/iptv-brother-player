package com.iptv.player.data.repo

import com.iptv.player.data.db.ChannelEntity
import com.iptv.player.data.db.EpisodeEntity
import com.iptv.player.data.db.MediaKind
import com.iptv.player.data.db.SourceEntity
import com.iptv.player.data.db.SourceType
import com.iptv.player.data.db.VodEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Everything the player needs to open one thing. */
data class PlayableStream(
    val url: String,
    val title: String,
    val subtitle: String? = null,
    val artwork: String? = null,
    val userAgent: String? = null,
    val kind: MediaKind = MediaKind.LIVE,
    val sourceId: Long = 0,
    val streamKey: String = "",
    /** Live streams have no meaningful duration and must not show a seek bar. */
    val isLive: Boolean = kind == MediaKind.LIVE,
    val resumeFromMs: Long = 0,
)

/**
 * Builds playable URLs.
 *
 * This exists as a separate step — rather than storing a ready-made URL on
 * every row — because Xtream URLs embed the account password in the path.
 * Persisting them would put a plaintext copy of the credentials on all 10,000
 * channel rows and quietly defeat the Keystore encryption two files over. So
 * Xtream rows store only the stream id, and the URL is assembled here, on
 * demand, from credentials decrypted for that one call.
 */
class StreamUrlResolver(private val sources: SourceRepository) {

    suspend fun forChannel(source: SourceEntity, channel: ChannelEntity): PlayableStream? {
        val url = when (source.type) {
            SourceType.XTREAM -> sources.client(source)?.liveStreamUrl(channel.streamKey) ?: return null
            else -> channel.url.ifBlank { return null }
        }
        return PlayableStream(
            url = url,
            title = channel.name,
            artwork = channel.logo,
            userAgent = source.userAgent,
            kind = MediaKind.LIVE,
            sourceId = source.id,
            streamKey = channel.streamKey,
            isLive = true,
        )
    }

    suspend fun forMovie(source: SourceEntity, movie: VodEntity, resumeFromMs: Long = 0): PlayableStream? {
        val url = when (source.type) {
            SourceType.XTREAM ->
                sources.client(source)?.movieUrl(movie.streamKey, movie.containerExtension) ?: return null
            else -> movie.url?.ifBlank { null } ?: return null
        }
        return PlayableStream(
            url = url,
            title = movie.name,
            subtitle = movie.year,
            artwork = movie.poster,
            userAgent = source.userAgent,
            kind = MediaKind.MOVIE,
            sourceId = source.id,
            streamKey = movie.streamKey,
            isLive = false,
            resumeFromMs = resumeFromMs,
        )
    }

    suspend fun forEpisode(
        source: SourceEntity,
        episode: EpisodeEntity,
        seriesName: String,
        artwork: String?,
        resumeFromMs: Long = 0,
    ): PlayableStream? {
        val url = when (source.type) {
            SourceType.XTREAM ->
                sources.client(source)?.episodeUrl(episode.streamKey, episode.containerExtension) ?: return null
            else -> episode.url?.ifBlank { null } ?: return null
        }
        return PlayableStream(
            url = url,
            title = episode.title,
            subtitle = "$seriesName · S${episode.season}E${episode.episode}",
            artwork = episode.still ?: artwork,
            userAgent = source.userAgent,
            kind = MediaKind.SERIES,
            sourceId = source.id,
            streamKey = episode.streamKey,
            isLive = false,
            resumeFromMs = resumeFromMs,
        )
    }

    /**
     * Catch-up playback of a past programme, or null when the channel does not
     * support it — the brief is explicit that this must degrade rather than be
     * assumed universal.
     */
    suspend fun forCatchup(
        source: SourceEntity,
        channel: ChannelEntity,
        programmeTitle: String,
        startUtcMillis: Long,
        endUtcMillis: Long,
    ): PlayableStream? {
        if (channel.catchupDays <= 0) return null
        // Outside the provider's retention window there is nothing to fetch.
        val oldestAvailable = System.currentTimeMillis() - channel.catchupDays * DAY_MS
        if (startUtcMillis < oldestAvailable || startUtcMillis > System.currentTimeMillis()) return null

        val durationMinutes = ((endUtcMillis - startUtcMillis) / 60_000L).toInt().coerceIn(1, 24 * 60)

        val url = when {
            // An explicit template from the playlist always wins: providers
            // that publish one have usually done so because their default
            // endpoint does not work.
            !channel.catchupSource.isNullOrBlank() ->
                expandTemplate(channel.catchupSource, startUtcMillis, endUtcMillis, durationMinutes)

            source.type == SourceType.XTREAM ->
                sources.client(source)?.timeshiftUrl(
                    streamId = channel.streamKey,
                    startUtcMillis = startUtcMillis,
                    durationMinutes = durationMinutes,
                    serverTimezone = null,
                ) ?: return null

            else -> return null
        }

        return PlayableStream(
            url = url,
            title = programmeTitle,
            subtitle = channel.name,
            artwork = channel.logo,
            userAgent = source.userAgent,
            kind = MediaKind.LIVE,
            sourceId = source.id,
            streamKey = channel.streamKey,
            // Not live: catch-up is a fixed-length recording and should get a
            // seek bar, which is the whole point of the feature.
            isLive = false,
        )
    }

    /**
     * Expands an M3U `catchup-source` template.
     *
     * There is no standard for these. The placeholders below are the set that
     * appears across the players this format grew up around (Kodi's PVR
     * add-ons, Perfect Player, TiviMate); providers copy whichever one their
     * panel software emits. Unknown placeholders are left alone rather than
     * blanked, so a URL built by a template we do not fully understand still
     * has a chance of working.
     */
    private fun expandTemplate(
        template: String,
        startUtcMillis: Long,
        endUtcMillis: Long,
        durationMinutes: Int,
    ): String {
        val startSeconds = startUtcMillis / 1000
        val endSeconds = endUtcMillis / 1000
        val nowSeconds = System.currentTimeMillis() / 1000
        val utc = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(startUtcMillis))
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(startUtcMillis))

        return template
            .replace("\${start}", startSeconds.toString())
            .replace("{start}", startSeconds.toString())
            .replace("\${utc}", startSeconds.toString())
            .replace("{utc}", startSeconds.toString())
            .replace("\${timestamp}", startSeconds.toString())
            .replace("\${end}", endSeconds.toString())
            .replace("{end}", endSeconds.toString())
            .replace("\${utcend}", endSeconds.toString())
            .replace("{utcend}", endSeconds.toString())
            .replace("\${duration}", (durationMinutes * 60).toString())
            .replace("{duration}", (durationMinutes * 60).toString())
            .replace("\${durmin}", durationMinutes.toString())
            .replace("\${offset}", (nowSeconds - startSeconds).toString())
            .replace("{offset}", (nowSeconds - startSeconds).toString())
            .replace("\${Y}-\${m}-\${d}:\${H}-\${M}", utc)
            .replace("\${start-iso}", iso)
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
