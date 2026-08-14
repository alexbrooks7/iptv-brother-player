package com.iptv.player.player

import com.iptv.player.analytics.IptvAnalytics
import com.iptv.player.data.db.MediaKind
import com.iptv.player.data.repo.CatalogRepository
import com.iptv.player.data.repo.PlayableStream
import com.iptv.player.data.repo.SourceRepository
import com.iptv.player.data.repo.StreamUrlResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the player is playing, and what is next to it.
 *
 * This exists because "play this" has to cross a navigation boundary carrying
 * more than a route can hold — a resolved URL, artwork, a resume position —
 * and because channel up/down needs to know the list the user came from. A
 * channel is not "the next row in the database": it is the next row *in the
 * category or favourites list they were browsing*, which is exactly the
 * expectation from a real set-top box and exactly what a route parameter
 * cannot express.
 *
 * Held as a single app-scoped instance rather than in a ViewModel so that it
 * survives the Activity being recreated mid-playback, which cheap TV boxes do
 * on HDMI resolution changes.
 */
class PlaybackQueue(
    private val sources: SourceRepository,
    private val catalog: CatalogRepository,
    private val resolver: StreamUrlResolver,
) {

    data class Request(
        val stream: PlayableStream,
        /** Channel row ids in the order the user was browsing them. */
        val siblings: List<Long> = emptyList(),
        val index: Int = -1,
    ) {
        val canZap: Boolean get() = siblings.size > 1 && index >= 0
    }

    private val _current = MutableStateFlow<Request?>(null)
    val current: StateFlow<Request?> = _current.asStateFlow()

    fun play(stream: PlayableStream, siblings: List<Long> = emptyList(), index: Int = -1) {
        _current.value = Request(stream, siblings, index)
        // The one place every kind of playback passes through — channels,
        // movies, series episodes and catch-up alike — so it is the one place
        // that needs an analytics call rather than four scattered ones.
        IptvAnalytics.event(
            "content_played",
            mapOf(
                "kind" to stream.kind.name.lowercase(),
                // Catch-up is the one case where `kind == LIVE` but `isLive`
                // is false — see StreamUrlResolver.forCatchup, which sets
                // exactly that combination because a catch-up recording has a
                // fixed length and needs a seek bar, unlike the live channel
                // it came from.
                "catchup" to (stream.kind == MediaKind.LIVE && !stream.isLive),
            ),
        )
    }

    /** Channel up. Wraps at the end, like every set-top box ever made. */
    suspend fun next(): Boolean = zap(+1)

    suspend fun previous(): Boolean = zap(-1)

    private suspend fun zap(delta: Int): Boolean {
        val request = _current.value ?: return false
        if (!request.canZap) return false

        // Walk past channels that fail to resolve rather than stopping on one.
        // A playlist with a few dead entries should still zap through them,
        // not strand the user on a channel that will not open.
        var index = request.index
        repeat(request.siblings.size) {
            index = (index + delta).mod(request.siblings.size)
            val channel = catalog.channelById(request.siblings[index]) ?: return@repeat
            val source = sources.get(channel.sourceId) ?: return@repeat
            val stream = resolver.forChannel(source, channel) ?: return@repeat
            _current.value = request.copy(stream = stream, index = index)
            return true
        }
        return false
    }

    /** Resume position lookup for anything that is not a live channel. */
    suspend fun resumePositionFor(stream: PlayableStream): Long =
        if (stream.kind == MediaKind.LIVE) 0
        else catalog.resumePosition(stream.sourceId, stream.kind, stream.streamKey)

    fun clear() {
        _current.value = null
    }
}
