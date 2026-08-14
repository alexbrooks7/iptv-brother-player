package com.iptv.player.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.iptv.player.data.db.DatabaseMaintenance
import com.iptv.player.data.repo.SyncResult
import com.iptv.player.data.repo.reportSourceSynced
import com.iptv.player.di.ServiceLocator
import com.iptv.player.util.Diagnostics
import java.util.concurrent.TimeUnit

/**
 * Periodic playlist and guide refresh.
 *
 * WorkManager rather than an alarm or a foreground job: a TV box is mains
 * powered but is also frequently in standby with its network idle, and
 * WorkManager is the only API that will reliably run this across the range of
 * Fire OS and AOSP-derived firmwares this app has to work on — several of
 * which enforce aggressive app standby that silently drops other scheduling
 * mechanisms.
 *
 * It is GMS-free. WorkManager falls back to `AlarmManager` plus a boot
 * receiver on devices without Play Services, which is exactly the Fire TV
 * case, so the same code path ships to both stores.
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = ServiceLocator.settings
        val sources = ServiceLocator.sourceRepository
        val importer = ServiceLocator.importer

        val all = sources.all().filter { it.enabled }
        if (all.isEmpty()) return Result.success()

        var failures = 0
        all.forEach { source ->
            val result = importer.sync(source)
            reportSourceSynced(sourceType = source.type, trigger = "scheduled", result = result)
            when (result) {
                is SyncResult.Success -> Unit
                is SyncResult.Failure -> failures++
            }
        }

        Diagnostics.info("refresh", "Scheduled refresh finished: ${all.size - failures}/${all.size} playlists updated")

        // Housekeeping runs whether or not the refresh succeeded. A source that
        // keeps failing is precisely the one accumulating stale listings, so
        // tying the cleanup to a successful sync would skip it exactly when it
        // is needed most.
        runCatching {
            var purged = 0
            all.forEach { purged += ServiceLocator.epgRepository.purgeExpired(it.id) }
            if (purged > 0) {
                DatabaseMaintenance.reclaimSpaceIfWorthwhile(
                    ServiceLocator.database,
                    ServiceLocator.databaseFile(),
                )
            }
        }.onFailure { Diagnostics.error("refresh", "Housekeeping failed", it) }

        // Retry rather than fail: a refresh that missed because the box was
        // between DHCP leases should come back, and WorkManager's backoff is
        // exactly the right amount of patience. A permanent failure (expired
        // subscription) will surface in the UI via lastSyncError regardless.
        return if (failures == all.size) Result.retry() else Result.success()
    }

    companion object {
        const val WORK_NAME = "playlist-refresh"
    }
}

/** Schedules and reschedules [RefreshWorker]. */
class RefreshScheduler(private val context: Context) {

    fun ensureScheduled(intervalHours: Int) {
        if (intervalHours <= 0) {
            cancel()
            return
        }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            RefreshWorker.WORK_NAME,
            // KEEP, so that every app launch does not reset the interval and
            // push the next run further away — an app opened daily would
            // otherwise never actually refresh.
            ExistingPeriodicWorkPolicy.KEEP,
            request(intervalHours),
        )
    }

    fun reschedule(intervalHours: Int) {
        if (intervalHours <= 0) {
            cancel()
            return
        }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            RefreshWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request(intervalHours),
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(RefreshWorker.WORK_NAME)
    }

    private fun request(intervalHours: Int) =
        PeriodicWorkRequestBuilder<RefreshWorker>(intervalHours.toLong(), TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    // No battery or idle constraints: this is a mains-powered
                    // device, and requiring idle would mean the guide never
                    // refreshes in a household that leaves the TV on.
                    .build()
            )
            // A flex window lets the system batch this with other work rather
            // than waking the box on the hour, every hour.
            .setInitialDelay(15, TimeUnit.MINUTES)
            .build()
}
