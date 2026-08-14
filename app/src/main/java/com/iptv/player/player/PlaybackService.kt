package com.iptv.player.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.iptv.player.MainActivity
import com.iptv.player.data.repo.PlayableStream
import com.iptv.player.di.ServiceLocator
import com.iptv.player.util.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground media service that owns the single [PlayerEngine].
 *
 * A service rather than a player inside the Activity, for three reasons that
 * all come from this being a TV app: the remote's play/pause/rewind keys are
 * routed through a MediaSession and would otherwise do nothing; playback has
 * to survive the Activity being recreated when the box changes HDMI resolution
 * mid-stream (which several Fire TV models do); and Android will happily kill a
 * backgrounded process that is not running a foreground service, which is what
 * makes a channel stop the moment the user opens the guide over it on some
 * OEM launchers.
 *
 * The UI binds directly through [LocalBinder] rather than through a
 * `MediaController`. `MediaController` models transport controls, and this app
 * needs to drive track selection, aspect ratio and reconnection state, none of
 * which fit that surface. The MediaSession still exists — it is what makes the
 * hardware keys and the notification work.
 */
class PlaybackService : MediaSessionService() {

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    private val localBinder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var mediaSession: MediaSession? = null
    private lateinit var engine: PlayerEngine
    private var positionSaveJob: Job? = null

    val state: StateFlow<PlayerState> get() = engine.state

    /** Observable player instance, for attaching the video surface. */
    val playerInstance: StateFlow<Player?> get() = engine.playerInstance

    override fun onCreate() {
        super.onCreate()
        engine = PlayerEngine(this, scope)

        scope.launch {
            ServiceLocator.settings.flow.collect { settings ->
                engine.configure(
                    bufferProfile = settings.bufferProfile,
                    hardwareDecoding = settings.hardwareDecoding,
                    maxReconnectAttempts = settings.reconnectAttempts,
                )
            }
        }
    }

    fun play(stream: PlayableStream) {
        // Apply the stored settings before the first open, not just whenever
        // the collector above happens to emit.
        //
        // The collector is asynchronous and DataStore's first read hits disk,
        // so on a cold start `play()` can win the race and build the player
        // with the *default* decoder/buffer settings. The user-visible symptom
        // is that "Hardware decoding: Off" appears to do nothing until
        // something else forces a rebuild — which is exactly the setting
        // someone reaches for when a stream plays sound but no picture, so it
        // failing silently is the worst possible time for it to fail.
        scope.launch {
            val settings = ServiceLocator.settings.flow.first()
            engine.configure(
                bufferProfile = settings.bufferProfile,
                hardwareDecoding = settings.hardwareDecoding,
                maxReconnectAttempts = settings.reconnectAttempts,
            )
            engine.open(stream)
            ensureSession()
            startPositionSaving()
            ServiceLocator.catalogRepository.recordOpened(stream)
        }
    }

    fun playPause() = engine.playPause()
    fun seekBy(deltaMs: Long) = engine.seekBy(deltaMs)
    fun seekTo(positionMs: Long) = engine.seekTo(positionMs)
    fun retry() = engine.retry()
    fun selectAudioTrack(option: TrackOption) = engine.selectAudioTrack(option)
    fun selectSubtitleTrack(option: TrackOption?) = engine.selectSubtitleTrack(option)

    /** Attaches the video output. Called by the Compose player surface. */
    fun player(): Player? = engine.exoPlayer

    fun stop() {
        savePositionNow()
        positionSaveJob?.cancel()
        engine.release()
        mediaSession?.release()
        mediaSession = null
        stopSelf()
    }

    private fun ensureSession() {
        if (mediaSession != null) return
        val exo = engine.exoPlayer ?: return
        mediaSession = MediaSession.Builder(this, exo)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()
    }

    /**
     * Persists the resume position while playing.
     *
     * Every 10 seconds rather than on stop only: a TV box loses power, gets
     * unplugged, or has its app killed by the system far more often than a
     * phone does, and "I watched 40 minutes and it forgot" is the failure this
     * prevents. Live channels are filtered out inside the repository, so this
     * writes nothing at all during ordinary TV watching.
     */
    private fun startPositionSaving() {
        positionSaveJob?.cancel()
        positionSaveJob = scope.launch {
            while (true) {
                delay(10_000)
                savePositionNow()
            }
        }
    }

    private fun savePositionNow() {
        val current = engine.state.value
        val stream = current.stream ?: return
        if (current.durationMs <= 0) return
        scope.launch {
            ServiceLocator.catalogRepository.savePosition(stream, current.positionMs, current.durationMs)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else localBinder

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Nothing is playing and the user swiped the app away — do not linger
        // as a foreground service, which both stores flag in review.
        if (engine.exoPlayer?.isPlaying != true) {
            savePositionNow()
            stopSelf()
        }
    }

    override fun onDestroy() {
        Diagnostics.info("player", "Service destroyed")
        savePositionNow()
        positionSaveJob?.cancel()
        mediaSession?.release()
        mediaSession = null
        engine.release()
        scope.cancel()
        super.onDestroy()
    }
}
