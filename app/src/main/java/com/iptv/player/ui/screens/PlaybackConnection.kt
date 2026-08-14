package com.iptv.player.ui.screens

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.iptv.player.player.PlaybackService

/**
 * Holds the bound service. `service` is Compose state, so the player screen
 * recomposes the moment the binder arrives rather than rendering against null
 * forever.
 */
class PlaybackConnection internal constructor() {
    var service: PlaybackService? by mutableStateOf(null)
        internal set
}

/**
 * Binds [PlaybackService] for as long as the calling composable is on screen.
 *
 * Bind only — no `startForegroundService`. Media3's `MediaSessionService`
 * promotes itself to the foreground when playback actually begins; starting it
 * explicitly while it has no player would arm the five-second
 * `startForeground` deadline with nothing to satisfy it, and Android kills the
 * process for that.
 *
 * The service is deliberately *not* stopped on unbind. Leaving the player
 * screen should not always end playback — the user may be opening the guide
 * over a running channel — so stopping is an explicit action (Back at the top
 * level of the player, or the service's own task-removed handling).
 */
@Composable
fun rememberPlaybackService(): PlaybackConnection {
    val context = LocalContext.current
    val connection = remember { PlaybackConnection() }

    DisposableEffect(context) {
        val intent = Intent(context, PlaybackService::class.java)

        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                connection.service = (binder as? PlaybackService.LocalBinder)?.service
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                connection.service = null
            }
        }

        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        onDispose {
            runCatching { context.unbindService(serviceConnection) }
            connection.service = null
        }
    }

    return connection
}
