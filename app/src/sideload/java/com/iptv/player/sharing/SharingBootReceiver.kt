package com.iptv.player.sharing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.iptv.player.di.ServiceLocator
import com.iptv.player.util.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resumes bandwidth sharing after a reboot or an app update.
 *
 * Without this the feature simply stopped at every reboot and stayed stopped
 * until someone next opened the app — a TV box that reboots overnight and sits
 * on the home screen for a day would share nothing for that whole day, while
 * Settings honestly reported "Off" to anyone who went looking.
 *
 * This lives in `src/sideload` and is declared only in that flavour's
 * manifest, so the store build — which has no Pawns SDK at all — never
 * registers a boot receiver for a service it does not contain.
 *
 * ### Why a receiver rather than leaving it to the watchdog
 *
 * `SharingWatchdogWorker` cannot do this job. Android 12 forbids starting a
 * foreground service from the background, and a WorkManager job is the
 * background, so its restart attempt is refused in exactly this situation.
 * `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are two of the explicit
 * exemptions to that rule — the app is permitted to start a foreground
 * service while handling them, which makes this the one path that reliably
 * works with no user-granted permission behind it.
 *
 * `RECEIVE_BOOT_COMPLETED` costs nothing extra here: WorkManager already
 * declares it for [com.iptv.player.work.RefreshWorker]'s scheduling to
 * survive a reboot, in both flavours.
 */
class SharingBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            // Receivers are addressable by other apps; ignoring anything we
            // did not ask for means a stray broadcast cannot start sharing.
            else -> return
        }
        if (!PawnsManager.available) return

        // Android delivers no component until Application.onCreate has run, so
        // ServiceLocator is initialised and PawnsManager.init has configured
        // the SDK by the time we get here.
        val app = context.applicationContext
        // goAsync keeps the broadcast — and with it the permission to start a
        // foreground service — alive across the DataStore read, which is disk
        // I/O and cannot be done on the main thread.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val enabled = withTimeoutOrNull(SETTINGS_READ_TIMEOUT_MS) {
                    ServiceLocator.settings.flow.first().sharingEnabled
                }
                // Gated on the stored preference, never on hasConsent() alone:
                // consent stays granted after someone switches sharing off, so
                // resuming from that bit would quietly overturn a deliberate
                // opt-out on every reboot.
                if (enabled == true && PawnsManager.hasConsent()) {
                    Diagnostics.info("sharing", "Resuming sharing after ${intent.action}")
                    PawnsManager.startSharing(app)
                }
            } catch (e: Exception) {
                // A receiver that throws takes the whole process down with it,
                // at boot, on every boot. Whatever went wrong here, failing to
                // resume sharing is not worth that.
                Diagnostics.error("sharing", "Failed to resume sharing at boot", e)
            } finally {
                // Must run on every path or the system holds the broadcast
                // open until it times out and reports the app as slow.
                pending.finish()
            }
        }
    }

    private companion object {
        const val SETTINGS_READ_TIMEOUT_MS = 5_000L
    }
}
