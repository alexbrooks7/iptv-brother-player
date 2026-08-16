package com.iptv.player.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.iptv.player.di.ServiceLocator
import com.iptv.player.sharing.PawnsManager
import com.iptv.player.sharing.SharingState
import com.iptv.player.util.Diagnostics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Last-resort recovery for bandwidth sharing. **Read the coverage notes before
 * relying on this — it is a backstop, not the primary mechanism.**
 *
 * The two things that actually keep sharing alive are neither of them this
 * class:
 *
 * 1. **Android restarts the service itself when the process is killed.** The
 *    peer service is `START_STICKY`, so a low-memory or vendor reclamation
 *    kill is repaired by the platform within about a second, with no app
 *    involvement and no permission needed. Measured on an API 34 emulator:
 *    `SIGKILL` on the process produced `Scheduling restart of crashed service
 *    ... in 1000ms`, a fresh process, `Action received null` (the null intent
 *    that marks a system-initiated sticky restart) and `event: running` about
 *    seven seconds later. This is also why the platform's own restart is not
 *    blocked by the background-start rule below — the system is resuming a
 *    service it already had, not the app starting one.
 * 2. **`SharingBootReceiver` handles reboots and app updates** (sideload
 *    source set), because `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are
 *    explicit exemptions to that rule.
 *
 * What is left for this worker is the residue: the service is not running,
 * the platform has stopped trying to bring it back (a repeatedly failing
 * service is eventually given up on), and no reboot has happened to trigger
 * the receiver. That is a narrow case, but it is the one where sharing would
 * otherwise stay off indefinitely while Settings honestly reported "Off" to
 * nobody looking.
 *
 * WorkManager rather than an alarm for the same reason [RefreshWorker] uses
 * it: it is the one scheduling mechanism that survives across this hardware
 * range, and it is GMS-free, which Fire TV requires.
 *
 * ### What this cannot do
 *
 * - **It cannot beat a Force Stop.** Force-stopping an app cancels its
 *   WorkManager jobs too, by design. Nothing an app can write changes that.
 *   Recovery there happens when the viewer next opens the app, via the resume
 *   in `MainScreen`.
 * - **It usually cannot restart the service while the app is in the
 *   background on API 31+.** Android forbids starting a foreground service
 *   from the background, and a worker woken by WorkManager *is* the
 *   background; the call throws `ForegroundServiceStartNotAllowedException`,
 *   caught below. It succeeds when the app is on screen — a viewer with the
 *   TV app open for hours whose service died — or when the user has exempted
 *   the app from battery optimisation, which lifts this restriction as a side
 *   effect.
 * - **It is not immediate.** WorkManager's periodic floor is 15 minutes,
 *   enforced by the OS, so this is "recovers within about a quarter of an
 *   hour", never "does not drop".
 */
class SharingWatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Store flavour has no SDK behind PawnsManager at all, and an
        // unconfigured build has no key — either way there is nothing to
        // watch. Cheap enough to re-check here rather than trusting that the
        // job was never scheduled.
        if (!PawnsManager.available) return Result.success()

        val enabled = withTimeoutOrNull(SETTINGS_READ_TIMEOUT_MS) {
            ServiceLocator.settings.flow.first().sharingEnabled
        }
        // Same rule the start-up resume follows: act on the stored preference,
        // never on `hasConsent()` alone, which stays true after someone
        // switches sharing off. A null read means DataStore did not answer in
        // time — treat that as "do not touch anything", because the failure
        // mode of guessing wrong here is starting a service the user turned
        // off.
        if (enabled != true) return Result.success()
        if (!PawnsManager.hasConsent()) return Result.success()

        val state = withTimeoutOrNull(STATE_READ_TIMEOUT_MS) {
            PawnsManager.serviceState()?.first()
        }
        val alive = state is SharingState.On || state is SharingState.Running
        if (alive) return Result.success()

        // Deliberately also covers the unreadable-state case (`state == null`).
        // Starting a service that is already running is a no-op the SDK
        // absorbs; not starting one that has died is the failure this whole
        // class exists to prevent, so ambiguity resolves towards acting.
        return try {
            Diagnostics.warn("sharing", "Sharing was enabled but not running — restarting")
            PawnsManager.startSharing(applicationContext)
            Result.success()
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) extends
            // IllegalStateException, so catching the supertype keeps this
            // compiling against minSdk 24 without a version gate.
            //
            // Success rather than retry: this is not a transient error, it is
            // the platform saying "not from the background". Retrying with
            // backoff would burn wakeups to be refused every time. The next
            // periodic run tries again anyway, and by then the app may be in
            // the foreground, where it is allowed.
            Diagnostics.warn(
                "sharing",
                "Could not restart sharing from the background: ${e.javaClass.simpleName}",
            )
            Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "sharing-watchdog"

        private const val SETTINGS_READ_TIMEOUT_MS = 5_000L
        private const val STATE_READ_TIMEOUT_MS = 2_000L
    }
}

/**
 * Schedules and cancels [SharingWatchdogWorker].
 *
 * Every caller goes through `SettingsViewModel.setSharingEnabled`, which is
 * the single point all three ways of changing the setting funnel through —
 * the consent dialog's two buttons and the Settings row.
 */
class SharingWatchdogScheduler(private val context: Context) {

    /**
     * Brings the schedule in line with [enabled].
     *
     * `KEEP` rather than `UPDATE` for the same reason [RefreshScheduler] uses
     * it: this is called on every app start to reconcile, and `UPDATE` would
     * reset the interval each time, pushing the next run further away on a
     * device opened regularly — the run would never actually happen.
     */
    fun sync(enabled: Boolean) {
        // Never schedule anything in a build that cannot share, so the store
        // flavour carries no periodic job at all rather than one that wakes
        // up only to return immediately.
        if (!PawnsManager.available || !enabled) {
            cancel()
            return
        }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SharingWatchdogWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request(),
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(SharingWatchdogWorker.WORK_NAME)
    }

    private fun request() =
        PeriodicWorkRequestBuilder<SharingWatchdogWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    // Sharing routes traffic, so without a network there is
                    // nothing for the service to do even if it started.
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    // No battery or idle constraints, matching RefreshWorker:
                    // this is mains-powered hardware, and requiring idle would
                    // mean the check never runs while someone is watching —
                    // which is precisely when the app is in the foreground and
                    // therefore the one time a restart is actually permitted.
                    .build()
            )
            .build()

    private companion object {
        /** WorkManager's own floor. Asking for less is silently clamped to it. */
        const val INTERVAL_MINUTES = 15L
    }
}
