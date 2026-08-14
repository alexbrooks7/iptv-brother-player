package com.iptv.player.ui.screens

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.iptv.player.R
import com.iptv.player.data.prefs.AspectMode
import com.iptv.player.data.prefs.Settings
import com.iptv.player.player.PlaybackService
import com.iptv.player.player.PlayerState
import com.iptv.player.player.TrackOption
import com.iptv.player.ui.components.Badge
import com.iptv.player.ui.components.Chip
import com.iptv.player.ui.components.ChipSpacing
import com.iptv.player.ui.components.SettingRow
import com.iptv.player.ui.components.TvButton
import com.iptv.player.ui.theme.LiveRed
import com.iptv.player.ui.theme.Scrim
import com.iptv.player.di.ServiceLocator
import com.iptv.player.ui.util.formatDuration
import com.iptv.player.ui.util.next
import com.iptv.player.ui.util.rememberInitialFocus
import com.iptv.player.ui.util.tvFocusGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CONTROLS_TIMEOUT_MS = 6_000L

private enum class Overlay { NONE, CONTROLS, CHANNELS, TRACKS }

/**
 * Full-screen playback.
 *
 * Remote handling is the substance of this screen. A TV remote has no pointer,
 * so every control has to be reachable from a small set of keys with
 * conventional meanings, and the app has to honour the meanings people already
 * expect from a set-top box:
 *
 * - **Up/Down with no overlay showing changes channel.** This is the single
 *   most-used interaction on a live TV app and it must not require opening a
 *   menu first.
 * - **Centre/OK shows the controls**, and they fade after a few seconds so the
 *   picture is not permanently obscured.
 * - **Back closes an overlay if one is open, and only then leaves playback.**
 *   Back that quits from inside a menu is the most common complaint about
 *   D-pad apps.
 * - **Media keys** (play/pause, rewind, fast-forward) are wired even though
 *   many TV remotes lack them, because Fire TV remotes and most universal
 *   remotes do have them and their absence reads as broken.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    settings: Settings,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connection = rememberPlaybackService()
    val service = connection.service
    val queue = ServiceLocator.playbackQueue
    val request by queue.current.collectAsStateWithLifecycle()

    var overlay by remember { mutableStateOf(Overlay.CONTROLS) }
    var aspect by remember(settings.aspectMode) { mutableStateOf(settings.aspectMode) }
    val focusRequester = rememberInitialFocus()

    val state = service?.state?.collectAsStateWithLifecycle()?.value ?: PlayerState()

    // A visible way out while connecting/reconnecting, not just the physical
    // Back key — see the class doc on WaitingOverlay for why this exists and
    // why it waits before appearing. Keyed on `request` too so switching
    // channels always restarts the grace period rather than inheriting
    // however long the *previous* channel had already been waiting.
    val waiting = state.error == null && (state.isBuffering || state.reconnectAttempt > 0)
    var showWaitActions by remember { mutableStateOf(false) }
    LaunchedEffect(waiting, request) {
        showWaitActions = false
        if (waiting) {
            delay(4_000)
            showWaitActions = true
        }
    }

    // Start (or switch) playback whenever the queue's request changes.
    LaunchedEffect(service, request) {
        val current = request ?: return@LaunchedEffect
        service?.play(current.stream)
        overlay = Overlay.CONTROLS
    }

    // Auto-hide the controls. Reset by any interaction that changes `overlay`.
    LaunchedEffect(overlay, state.isPlaying) {
        if (overlay == Overlay.CONTROLS && state.isPlaying) {
            delay(CONTROLS_TIMEOUT_MS)
            overlay = Overlay.NONE
        }
    }

    /**
     * Back closes a *menu* if one is open, and otherwise leaves playback.
     *
     * The distinction that matters is between a menu and the transport bar.
     * Channels and Tracks are modal lists the user deliberately opened, so Back
     * has to dismiss them before it does anything else — Back that quits from
     * inside a menu is the most common complaint about D-pad apps.
     *
     * The transport bar is not one of those. It appears on its own whenever a
     * channel is opened, and it fades by itself after a few seconds. Treating
     * it as another layer to dismiss meant Back never exited on the first
     * press: opening a channel and immediately pressing Back only hid a bar
     * that was about to disappear anyway, and from the channel list it took
     * three presses to get out. Now it is at most two, and one in the ordinary
     * case of watching something and wanting to stop.
     */
    BackHandler(enabled = true) {
        when (overlay) {
            Overlay.CHANNELS, Overlay.TRACKS -> overlay = Overlay.CONTROLS
            Overlay.CONTROLS, Overlay.NONE -> {
                // Stop rather than leave it running in the background: the
                // brief's memory budget is 150 MB idle, and a live TS stream
                // decoding behind the UI blows straight through it.
                service?.stop()
                queue.clear()
                onExit()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (overlay == Overlay.NONE) {
                            overlay = Overlay.CONTROLS
                            true
                        } else {
                            false
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                        if (overlay == Overlay.NONE || event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
                            scope.launch { queue.previous() }
                            true
                        } else {
                            false
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        if (overlay == Overlay.NONE || event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                            scope.launch { queue.next() }
                            true
                        } else {
                            false
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (overlay == Overlay.NONE && !state.stream.isLiveOrNull()) {
                            service?.seekBy(-10_000)
                            overlay = Overlay.CONTROLS
                            true
                        } else {
                            false
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (overlay == Overlay.NONE && !state.stream.isLiveOrNull()) {
                            service?.seekBy(+30_000)
                            overlay = Overlay.CONTROLS
                            true
                        } else {
                            false
                        }
                    }

                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    -> {
                        service?.playPause()
                        overlay = Overlay.CONTROLS
                        true
                    }

                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        service?.seekBy(-30_000); overlay = Overlay.CONTROLS; true
                    }

                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        service?.seekBy(+30_000); overlay = Overlay.CONTROLS; true
                    }

                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                        overlay = if (overlay == Overlay.CHANNELS) Overlay.NONE else Overlay.CHANNELS
                        true
                    }

                    else -> false
                }
            },
    ) {
        // Collected rather than read through `service.player()` so the surface
        // is (re)attached whenever the engine builds or rebuilds its player —
        // see PlayerEngine.playerInstance.
        val activePlayer = service?.playerInstance?.collectAsStateWithLifecycle()?.value
        VideoSurface(
            player = activePlayer,
            aspect = aspect,
            compatibilitySurface = settings.compatibilitySurface,
            modifier = Modifier.fillMaxSize(),
        )

        when {
            state.error != null -> ErrorOverlay(
                state = state,
                onRetry = { service?.retry() },
                onNextChannel = { scope.launch { queue.next() } },
                onExit = {
                    service?.stop()
                    queue.clear()
                    onExit()
                },
            )

            waiting -> WaitingOverlay(
                state = state,
                showActions = showWaitActions,
                onCancel = {
                    service?.stop()
                    queue.clear()
                    onExit()
                },
                onNextChannel = { scope.launch { queue.next() } },
            )
        }

        AnimatedVisibility(
            visible = overlay == Overlay.CONTROLS && state.error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            ControlsOverlay(
                state = state,
                aspect = aspect,
                onPlayPause = { service?.playPause() },
                onAspect = { aspect = aspect.next() },
                onChannels = { overlay = Overlay.CHANNELS },
                onTracks = { overlay = Overlay.TRACKS },
            )
        }

        if (overlay == Overlay.TRACKS) {
            TracksOverlay(
                state = state,
                onAudio = { service?.selectAudioTrack(it) },
                onSubtitle = { service?.selectSubtitleTrack(it) },
                onClose = { overlay = Overlay.CONTROLS },
            )
        }

        if (overlay == Overlay.CHANNELS) {
            // The in-player channel list reuses the live list's data through
            // the queue's sibling ids, so zapping from here stays in the same
            // category the user was browsing.
            InPlayerChannelList(
                onSelect = { index ->
                    scope.launch {
                        val current = queue.current.value ?: return@launch
                        val delta = index - current.index
                        repeat(kotlin.math.abs(delta)) {
                            if (delta > 0) queue.next() else queue.previous()
                        }
                        overlay = Overlay.NONE
                    }
                },
                onClose = { overlay = Overlay.CONTROLS },
            )
        }
    }

    // Keep the key handler focused; without this, focus can drift into an
    // overlay's children and up/down stops changing channel.
    LaunchedEffect(overlay) {
        if (overlay == Overlay.NONE) runCatching { focusRequester.requestFocus() }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(
    player: androidx.media3.common.Player?,
    aspect: AspectMode,
    compatibilitySurface: Boolean,
    modifier: Modifier = Modifier,
) {
    // `key` so that flipping the surface setting throws the old PlayerView away
    // and inflates the other layout. `surface_type` is fixed at construction
    // time, so there is nothing to update in place — without this the setting
    // would appear to do nothing until the screen was left and re-entered.
    key(compatibilitySurface) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                // Inflated from XML rather than `PlayerView(ctx)` because
                // `surface_type` is an XML-only attribute; see the comments in
                // the two layout files for what the choice actually costs.
                //
                // Inflating with a null root drops the layout's width/height,
                // so the LayoutParams are restored explicitly — without them
                // the view measures zero and the surface has nowhere to put
                // frames, which looks exactly like a decoder failure.
                val layout =
                    if (compatibilitySurface) com.iptv.player.R.layout.player_view_texture
                    else com.iptv.player.R.layout.player_view
                (android.view.LayoutInflater.from(ctx).inflate(layout, null) as PlayerView).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // Media3's own controller is switched off: it is a
                    // touch-oriented, phone-shaped control bar. What PlayerView
                    // is used for here is its surface management and resize
                    // modes, which are genuinely hard to reimplement correctly
                    // (secure surfaces, aspect handling on resolution changes).
                    useController = false
                    setKeepContentOnPlayerReset(true)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                if (view.player !== player) view.player = player
                view.resizeMode = when (aspect) {
                    AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                    AspectMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    AspectMode.ORIGINAL -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            onRelease = { view -> view.player = null },
        )
    }
}

@Composable
private fun ControlsOverlay(
    state: PlayerState,
    aspect: AspectMode,
    onPlayPause: () -> Unit,
    onAspect: () -> Unit,
    onChannels: () -> Unit,
    onTracks: () -> Unit,
) {
    val stream = state.stream
    Column(
        Modifier
            .fillMaxWidth()
            .background(Scrim)
            .padding(horizontal = 48.dp, vertical = 28.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (stream?.isLive == true) {
                Badge(stringResource(R.string.player_live_edge), LiveRed, Modifier.padding(end = 12.dp))
            }
            Text(
                stream?.title.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        stream?.subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
        }

        if (stream?.isLive == false && state.durationMs > 0) {
            SeekBar(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        Row(
            Modifier.padding(top = 18.dp).tvFocusGroup(),
            horizontalArrangement = ChipSpacing,
        ) {
            TvButton(
                text = if (state.isPlaying) "❚❚" else "▶",
                onClick = onPlayPause,
                autoFocus = true,
            )
            Chip(label = stringResource(R.string.player_channels), selected = false, onClick = onChannels)
            Chip(
                label = stringResource(R.string.player_tracks_audio) + " / " +
                    stringResource(R.string.player_tracks_subtitles),
                selected = false,
                onClick = onTracks,
            )
            Chip(
                label = stringResource(R.string.player_aspect) + ": " + aspect.name,
                selected = false,
                onClick = onAspect,
            )
        }
    }
}

/**
 * Progress display, not a control.
 *
 * The bar is deliberately not focusable: scrubbing by moving focus onto a bar
 * and then holding a direction is slow and imprecise with a D-pad. Seeking is
 * left/right on the remote while the picture is unobscured, which is both
 * faster and what a set-top box does, so the bar's only job is to show where
 * you are.
 */
@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    val fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.25f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(positionMs), style = MaterialTheme.typography.labelSmall, color = Color.White)
            Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
        Text(
            "◀ 10s   ·   30s ▶",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun TracksOverlay(
    state: PlayerState,
    onAudio: (TrackOption) -> Unit,
    onSubtitle: (TrackOption?) -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Scrim), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .width(520.dp)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(28.dp)
                .tvFocusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.player_tracks_audio), style = MaterialTheme.typography.titleMedium)
            if (state.audioTracks.isEmpty()) {
                Text(
                    stringResource(R.string.player_tracks_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.audioTracks.forEach { track ->
                SettingRow(
                    title = track.label,
                    value = if (track.selected) "●" else null,
                    onClick = { onAudio(track) },
                )
            }

            Text(
                stringResource(R.string.player_tracks_subtitles),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            SettingRow(
                title = stringResource(R.string.player_tracks_off),
                value = if (!state.subtitlesEnabled) "●" else null,
                onClick = { onSubtitle(null) },
            )
            state.subtitleTracks.forEach { track ->
                SettingRow(
                    title = track.label,
                    value = if (track.selected && state.subtitlesEnabled) "●" else null,
                    onClick = { onSubtitle(track) },
                )
            }

            TvButton(
                text = stringResource(R.string.action_close),
                primary = false,
                onClick = onClose,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
    }
}

@Composable
private fun InPlayerChannelList(onSelect: (Int) -> Unit, onClose: () -> Unit) {
    val queue = ServiceLocator.playbackQueue
    val catalog = ServiceLocator.catalogRepository
    val request by queue.current.collectAsStateWithLifecycle()
    var names by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }

    LaunchedEffect(request?.siblings) {
        val siblings = request?.siblings.orEmpty()
        names = siblings.mapIndexedNotNull { index, id ->
            catalog.channelById(id)?.let { index to it.name }
        }
    }

    Box(Modifier.fillMaxSize().background(Scrim), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier
                .width(480.dp)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp)
        ) {
            Text(stringResource(R.string.player_channels), style = MaterialTheme.typography.titleMedium)
            LazyColumn(
                Modifier.weight(1f).padding(top = 12.dp).tvFocusGroup(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(names, key = { it.first }) { (index, name) ->
                    SettingRow(
                        title = name,
                        value = if (index == request?.index) "●" else null,
                        onClick = { onSelect(index) },
                    )
                }
            }
            TvButton(text = stringResource(R.string.action_close), primary = false, onClick = onClose)
        }
    }
}

/**
 * Shown while a stream is connecting, reconnecting, or re-buffering — i.e.
 * whenever the picture is not there and the user has nothing to look at but
 * this overlay.
 *
 * **Why it waits before offering a way out.** Most connects resolve in under
 * a second; a Cancel button that is already there and focused on every single
 * channel change is a button in the way of normal use, not a safety net. It
 * earns its place specifically when the wait has gone on long enough to feel
 * broken — four seconds, chosen to sit clearly above ordinary connect latency
 * and clearly below the point where a user reaches for the remote wondering
 * if anything is happening at all.
 *
 * **Why it takes focus itself once it appears**, rather than expecting the
 * user to navigate to it: at this point in the flow nothing is playing, so
 * there is no reason to make someone hunt for the one thing they can actually
 * do. `TvButton`'s `autoFocus` only fires once (on the enabled transition), so
 * this does not fight the bottom controls bar for focus on every recomposition
 * — only the moment the buttons actually appear.
 */
@Composable
private fun WaitingOverlay(
    state: PlayerState,
    showActions: Boolean,
    onCancel: () -> Unit,
    onNextChannel: () -> Unit,
) {
    // Same distinctions as before: "dropped" only after the stream has proven
    // it can play, "Buffering" only once bytes have actually arrived.
    val text = when {
        state.reconnectAttempt > 0 && state.everReady ->
            stringResource(R.string.player_reconnecting, state.reconnectAttempt)
        state.reconnectAttempt > 0 ->
            stringResource(R.string.player_connecting_retry, state.reconnectAttempt)
        state.everReady -> stringResource(R.string.player_buffering)
        else -> stringResource(R.string.player_connecting)
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Scrim)
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            )
            if (showActions) {
                Row(
                    Modifier.padding(top = 20.dp).tvFocusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TvButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = onCancel,
                        autoFocus = true,
                    )
                    TvButton(
                        text = stringResource(R.string.player_error_next),
                        primary = false,
                        onClick = onNextChannel,
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorOverlay(
    state: PlayerState,
    onRetry: () -> Unit,
    onNextChannel: () -> Unit,
    onExit: () -> Unit,
) {
    val error = state.error ?: return
    Box(Modifier.fillMaxSize().background(Scrim), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(48.dp)) {
            Text(
                stringResource(R.string.player_error_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Text(
                stringResource(error.messageRes, *error.formatArgs.toTypedArray()),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(
                Modifier.padding(top = 28.dp).tvFocusGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TvButton(text = stringResource(R.string.player_error_retry), onClick = onRetry, autoFocus = true)
                TvButton(text = stringResource(R.string.player_error_next), primary = false, onClick = onNextChannel)
                TvButton(text = stringResource(R.string.action_back), primary = false, onClick = onExit)
            }
        }
    }
}

private fun com.iptv.player.data.repo.PlayableStream?.isLiveOrNull(): Boolean = this?.isLive ?: true
