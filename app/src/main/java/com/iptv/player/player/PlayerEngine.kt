package com.iptv.player.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.iptv.player.BuildConfig
import com.iptv.player.analytics.IptvAnalytics
import com.iptv.player.data.prefs.BufferProfile
import com.iptv.player.data.repo.PlayableStream
import com.iptv.player.data.remote.Http
import com.iptv.player.util.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A selectable audio or subtitle track, flattened for the UI. */
data class TrackOption(
    val id: String,
    val label: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val selected: Boolean,
)

data class PlayerState(
    val stream: PlayableStream? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: PlaybackError? = null,
    /** Non-zero while the auto-reconnect loop is counting down. */
    val reconnectAttempt: Int = 0,
    /**
     * True once this stream has reached [Player.STATE_READY] at least once.
     * Distinguishes "this channel was playing and just dropped" (worth a
     * generous, quiet retry budget — see [scheduleReconnect]) from "this
     * channel has never worked this session" (not worth burning the user's
     * time on the same budget — see the UI text this drives in
     * `PlayerScreen.kt`).
     */
    val everReady: Boolean = false,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    val subtitlesEnabled: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
)

/**
 * Wraps one ExoPlayer with the behaviour an IPTV stream needs on top of stock
 * playback: reconnection, error translation and track selection.
 *
 * Owned by [PlaybackService]; the UI never constructs one.
 */
@OptIn(UnstableApi::class)
class PlayerEngine(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /**
     * The live player instance, as observable state.
     *
     * The video surface has to be attached to whatever `ExoPlayer` currently
     * exists, and that instance is created lazily on the first `open()` and
     * replaced whenever settings force a rebuild. Exposing it only through a
     * plain getter means a Compose `AndroidView` that reads it once — while it
     * is still null — never learns the player arrived and never attaches the
     * surface, so frames are decoded into nothing and the picture stays black
     * while audio plays perfectly. As a flow, the attach re-runs on every
     * change.
     */
    private val _playerInstance = MutableStateFlow<Player?>(null)
    val playerInstance: StateFlow<Player?> = _playerInstance.asStateFlow()

    private var player: ExoPlayer? = null
    private var reconnectJob: Job? = null
    private var progressJob: Job? = null

    private var bufferProfile: BufferProfile = BufferProfile.BALANCED
    private var hardwareDecoding: Boolean = true
    private var maxReconnectAttempts: Int = 5
    private var currentUserAgent: String? = null

    val exoPlayer: Player? get() = player

    /**
     * Applies settings. A change to the buffer profile, the decoder mode or
     * the User-Agent can only take effect by rebuilding the player — all three
     * are constructor-time on ExoPlayer — so the current stream is reopened at
     * its current position. Everything else is applied in place.
     */
    fun configure(
        bufferProfile: BufferProfile,
        hardwareDecoding: Boolean,
        maxReconnectAttempts: Int,
    ) {
        val needsRebuild = player != null &&
            (bufferProfile != this.bufferProfile || hardwareDecoding != this.hardwareDecoding)
        this.bufferProfile = bufferProfile
        this.hardwareDecoding = hardwareDecoding
        this.maxReconnectAttempts = maxReconnectAttempts
        if (needsRebuild) {
            val current = _state.value.stream
            val position = player?.currentPosition ?: 0
            release()
            current?.let { open(it.copy(resumeFromMs = position)) }
        }
    }

    fun open(stream: PlayableStream) {
        reconnectJob?.cancel()
        val instance = ensurePlayer(stream.userAgent)

        _state.value = PlayerState(stream = stream, isBuffering = true)
        Diagnostics.info("player", "Opening ${stream.title} — ${Diagnostics.redact(stream.url)}")

        instance.setMediaItem(stream.toMediaItem())
        instance.prepare()
        instance.playWhenReady = true
        if (stream.resumeFromMs > 0 && !stream.isLive) instance.seekTo(stream.resumeFromMs)
        startProgressTicker()
    }

    fun playPause() {
        val instance = player ?: return
        if (instance.isPlaying) instance.pause() else instance.play()
    }

    fun seekBy(deltaMs: Long) {
        val instance = player ?: return
        if (_state.value.stream?.isLive == true) return
        val target = (instance.currentPosition + deltaMs).coerceIn(0, instance.duration.coerceAtLeast(0))
        instance.seekTo(target)
    }

    fun seekTo(positionMs: Long) {
        if (_state.value.stream?.isLive == true) return
        player?.seekTo(positionMs.coerceAtLeast(0))
    }

    /** Manual retry from the error screen. Resets the attempt counter. */
    fun retry() {
        val stream = _state.value.stream ?: return
        reconnectJob?.cancel()
        _state.value = _state.value.copy(error = null, reconnectAttempt = 0, isBuffering = true)
        open(stream)
    }

    fun selectAudioTrack(option: TrackOption) = applyOverride(C.TRACK_TYPE_AUDIO, option)

    fun selectSubtitleTrack(option: TrackOption?) {
        val instance = player ?: return
        if (option == null) {
            instance.trackSelectionParameters = instance.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            _state.value = _state.value.copy(subtitlesEnabled = false)
        } else {
            instance.trackSelectionParameters = instance.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
            applyOverride(C.TRACK_TYPE_TEXT, option)
            _state.value = _state.value.copy(subtitlesEnabled = true)
        }
    }

    private fun applyOverride(trackType: Int, option: TrackOption) {
        val instance = player ?: return
        val group = instance.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        if (group.type != trackType) return
        instance.trackSelectionParameters = instance.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .build()
    }

    fun release() {
        reconnectJob?.cancel()
        progressJob?.cancel()
        player?.release()
        player = null
        _playerInstance.value = null
        _state.value = PlayerState()
    }

    // ---- Construction ---------------------------------------------------

    private fun ensurePlayer(userAgent: String?): ExoPlayer {
        player?.let {
            if (userAgent == currentUserAgent) return it
            // A different playlist can require a different User-Agent, and the
            // data source factory is fixed at build time.
            it.release()
            player = null
        }
        currentUserAgent = userAgent
        return build(userAgent).also {
            player = it
            _playerInstance.value = it
        }
    }

    private fun build(userAgent: String?): ExoPlayer {
        val httpFactory = OkHttpDataSource.Factory(Http.client())
            .setUserAgent(userAgent?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_USER_AGENT)

        // Logged because the decoder actually chosen is the single most useful
        // fact when a stream produces sound but no picture, and it is not
        // otherwise visible to anyone without a USB cable and logcat.
        Diagnostics.info("player", "Building player: hardwareDecoding=$hardwareDecoding buffer=${bufferProfile.name}")

        val renderers = DefaultRenderersFactory(context)
            // Decoder fallback is what turns "this box's HEVC decoder refused
            // this stream" from a dead channel into a software-decoded one.
            // Without it a single MediaCodec init failure ends playback.
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(if (hardwareDecoding) MediaCodecSelector.DEFAULT else softwareFirstSelector())
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferProfile.minBufferMs,
                bufferProfile.maxBufferMs,
                bufferProfile.playbackBufferMs,
                bufferProfile.rebufferMs,
            )
            // Prioritising time over bytes keeps behaviour predictable across
            // the huge bitrate spread of IPTV streams (a 1 Mbps SD channel and
            // a 25 Mbps 4K one otherwise buffer wildly different durations).
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                // TVs are fixed-resolution and mains-powered: there is no
                // reason to let the selector pick a lower rendition than the
                // panel can show.
                .setForceHighestSupportedBitrate(false)
                .setTunnelingEnabled(false)
                .build()
        }

        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory))
                    // Live streams with no manifest-declared target offset
                    // still need a sane default, or ExoPlayer chases the live
                    // edge and stutters on jittery provider feeds.
                    .setLiveTargetOffsetMs(bufferProfile.minBufferMs.toLong())
            )
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                addListener(listener)
                addAnalyticsListener(decoderLogger)
            }
    }

    /**
     * Reports the decoder MediaCodec actually handed us.
     *
     * Distinct from the [MediaCodecSelector] preference: the selector only
     * states an order, and `setEnableDecoderFallback(true)` means a decoder
     * that fails to initialise is silently replaced by the next candidate. So
     * the only trustworthy answer to "which decoder is running" is the one the
     * framework reports after the fact, which is exactly what a black-picture
     * report needs.
     */
    private val decoderLogger = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            Diagnostics.info("player", "Video decoder: $decoderName")
        }
    }

    /**
     * Puts software decoders ahead of hardware ones. Only used when the user
     * turns hardware decoding off, which the settings screen labels as a
     * debugging aid: on a Fire TV Stick, software-decoding a 1080p H.264
     * stream will not keep up.
     */
    private fun softwareFirstSelector() = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
        val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
        all.sortedBy { it.hardwareAccelerated }
    }

    // ---- Player callbacks -----------------------------------------------

    private val listener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            val instance = player ?: return
            _state.value = _state.value.copy(
                isBuffering = playbackState == Player.STATE_BUFFERING,
                durationMs = instance.duration.takeIf { it != C.TIME_UNSET } ?: 0,
            )
            if (playbackState == Player.STATE_READY) {
                // A successful READY means the stream is alive again, so the
                // backoff counter must reset — otherwise a channel that drops
                // once an hour eventually exhausts its attempts and gives up.
                // Also the one place `everReady` is set — see its doc comment
                // for why that flag exists.
                _state.value = _state.value.copy(reconnectAttempt = 0, error = null, everReady = true)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }

        override fun onTracksChanged(tracks: Tracks) {
            _state.value = _state.value.copy(
                audioTracks = tracks.options(C.TRACK_TYPE_AUDIO),
                subtitleTracks = tracks.options(C.TRACK_TYPE_TEXT),
            )
            logVideoTrackStatus(tracks)
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            Diagnostics.info("player", "Video size changed: ${videoSize.width}x${videoSize.height}")
            _state.value = _state.value.copy(videoWidth = videoSize.width, videoHeight = videoSize.height)
        }

        // The single most direct diagnostic for "audio plays, picture stays
        // black": this fires exactly when a decoded video frame is actually
        // handed to the surface. If it never appears in the log, the fault is
        // downstream of decoding — the surface itself, not the stream or the
        // codec — which is a different bug than a missing/rejected video
        // track and needs a different fix.
        override fun onRenderedFirstFrame() {
            Diagnostics.info("player", "First video frame rendered")
        }

        override fun onPlayerError(error: PlaybackException) {
            val mapped = PlaybackError.from(error)
            Diagnostics.error("player", "${_state.value.stream?.title}: ${mapped.technical}")
            if (mapped.retryable) {
                scheduleReconnect(mapped)
            } else {
                _state.value = _state.value.copy(error = mapped, isBuffering = false)
                // Non-retryable means this is already the message the viewer
                // sees, not an internal step on the way to a retry — see the
                // matching call in scheduleReconnect for the other path that
                // ends the same way.
                reportPlaybackFailed(mapped)
            }
        }
    }

    /**
     * Exponential backoff, capped — but only once the stream has proven it can
     * connect at all.
     *
     * IPTV streams drop constantly — an overloaded provider, a re-encoder
     * restarting, a CDN edge rotating — and almost all of it is transient. A
     * fixed short retry hammers a server that is already struggling; no retry
     * at all means the user has to pick up the remote every time a channel
     * blinks. Doubling from one second and capping at fifteen means a
     * momentary blip recovers in about a second, while a genuinely dead stream
     * settles into one quiet attempt every fifteen. That reasoning holds for a
     * channel that *was* playing.
     *
     * It does not hold for a channel that has never connected this session —
     * a dead CDN node, an ISP block, or a bad token is not the kind of fault
     * that clears itself between one 15-second connection attempt and the
     * next, and spending the user's whole [maxReconnectAttempts] budget on it
     * (which, at the default of 5, is over a minute of connect timeouts plus
     * backoff) means over a minute of "Buffering…" with nothing on screen to
     * say it is actually failing. So a stream with no prior [PlayerState.everReady]
     * gets exactly one extra attempt — enough to ride out a genuine one-off
     * blip like a DNS hiccup — and then surfaces the real error immediately.
     */
    private fun scheduleReconnect(error: PlaybackError) {
        val everReady = _state.value.everReady
        val attempt = _state.value.reconnectAttempt + 1
        val effectiveMax = if (everReady) maxReconnectAttempts else minOf(maxReconnectAttempts, 1)
        if (attempt > effectiveMax) {
            _state.value = _state.value.copy(error = error, isBuffering = false, reconnectAttempt = 0)
            Diagnostics.warn(
                "player",
                "Giving up after $attempt attempt(s) (everReady=$everReady, budget=$effectiveMax)",
            )
            reportPlaybackFailed(error, exhaustedRetries = attempt)
            return
        }

        _state.value = _state.value.copy(reconnectAttempt = attempt, error = null, isBuffering = true)
        val delayMs = (1_000L shl (attempt - 1).coerceAtMost(4)).coerceAtMost(15_000L)

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            val instance = player ?: return@launch
            Diagnostics.info("player", "Reconnect attempt $attempt")
            // seekToDefaultPosition first: for a live stream, resuming at the
            // stale position asks the server for a segment that has already
            // rolled out of the window, which fails again immediately.
            if (_state.value.stream?.isLive == true) instance.seekToDefaultPosition()
            instance.prepare()
            instance.playWhenReady = true
        }
    }

    /**
     * Records the error the viewer actually ended up looking at.
     *
     * Both call sites already decided the failure is final for this attempt —
     * either Media3 said outright it will not retry, or the reconnect budget
     * in [scheduleReconnect] ran out — so this fires once per thing a person
     * experienced, not once per network hiccup along the way. [exhaustedRetries]
     * is null for the immediate-failure path and the attempt count for the
     * gave-up-retrying path, which is the one property that tells apart "the
     * provider rejected this outright" from "this looked transient and still
     * did not come back".
     */
    private fun reportPlaybackFailed(error: PlaybackError, exhaustedRetries: Int? = null) {
        IptvAnalytics.event(
            "playback_failed",
            mapOf(
                "kind" to _state.value.stream?.kind?.name?.lowercase(),
                // The string resource *name* ("player_error_timeout") rather
                // than the resolved, localised message text: stable across
                // languages and human-readable in a PostHog dashboard, and it
                // can never drift out of sync with the string the viewer saw
                // because it is read off that same resource id.
                "reason" to context.resources.getResourceEntryName(error.messageRes),
                "exhausted_retries" to exhaustedRetries,
            ),
        )
    }

    /**
     * Logs what the player actually knows about the video track — present,
     * supported, selected — rather than only what came out of it. On a
     * "sound but no picture" report this narrows the fault immediately:
     * absent means the stream is audio-only or lost its video track;
     * unsupported means this box's decoder rejects the format; present +
     * selected but no [onRenderedFirstFrame] afterwards means the surface is
     * the problem, not the stream.
     */
    private fun logVideoTrackStatus(tracks: Tracks) {
        val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        if (videoGroups.isEmpty()) {
            Diagnostics.warn("player", "No video track in this stream")
            return
        }
        videoGroups.forEachIndexed { groupIndex, group ->
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                Diagnostics.info(
                    "player",
                    "Video track $groupIndex:$trackIndex ${format.sampleMimeType} " +
                        "${format.width}x${format.height} selected=${group.isTrackSelected(trackIndex)} " +
                        "supported=${group.isTrackSupported(trackIndex)}",
                )
            }
        }
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                val instance = player
                if (instance != null && instance.isPlaying) {
                    _state.value = _state.value.copy(
                        positionMs = instance.currentPosition,
                        durationMs = instance.duration.takeIf { it != C.TIME_UNSET } ?: 0,
                    )
                }
                // One second is enough for a progress bar at 10 feet and keeps
                // the main thread quiet on a low-end box.
                delay(1_000)
            }
        }
    }
}

@OptIn(UnstableApi::class)
private fun PlayableStream.toMediaItem(): MediaItem = MediaItem.Builder()
    .setUri(url)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(artwork?.let { android.net.Uri.parse(it) })
            .build()
    )
    // MPEG-TS over HTTP is the single most common IPTV delivery and carries no
    // file extension to sniff, so the container type is stated explicitly for
    // ".ts" URLs; everything else is inferred as usual.
    .apply { if (url.substringBefore('?').endsWith(".ts", ignoreCase = true)) setMimeType(androidx.media3.common.MimeTypes.VIDEO_MP2T) }
    .build()

/** Flattens a [Tracks] group of one type into UI-ready options. */
private fun Tracks.options(trackType: Int): List<TrackOption> = buildList {
    groups.forEachIndexed { groupIndex, group ->
        if (group.type != trackType) return@forEachIndexed
        for (trackIndex in 0 until group.length) {
            if (!group.isTrackSupported(trackIndex)) continue
            val format = group.getTrackFormat(trackIndex)
            add(
                TrackOption(
                    id = format.id ?: "$groupIndex:$trackIndex",
                    label = format.describe(trackType, size),
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    selected = group.isTrackSelected(trackIndex),
                )
            )
        }
    }
}

/**
 * A human label for a track. Streams label their tracks inconsistently — some
 * carry a language tag and nothing else, some a label and no language — so
 * this falls back through everything available before resorting to an index.
 */
private fun androidx.media3.common.Format.describe(trackType: Int, index: Int): String {
    val language = language
        ?.takeIf { it.isNotBlank() && it != "und" }
        ?.let { tag -> java.util.Locale.forLanguageTag(tag).displayLanguage.ifBlank { tag } }
    val explicit = label?.takeIf { it.isNotBlank() }

    val base = when {
        explicit != null && language != null && !explicit.contains(language, ignoreCase = true) -> "$language — $explicit"
        explicit != null -> explicit
        language != null -> language
        trackType == C.TRACK_TYPE_AUDIO -> "Audio ${index + 1}"
        else -> "Subtitle ${index + 1}"
    }

    // Channel count disambiguates the very common "English stereo / English
    // 5.1" pair, which would otherwise render as two identical rows.
    val channels = if (trackType == C.TRACK_TYPE_AUDIO && channelCount > 2) " · ${channelCount}ch" else ""
    return base + channels
}
