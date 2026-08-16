package com.iptv.player.di

import android.content.Context
import com.iptv.player.data.backup.ConfigBackup
import com.iptv.player.data.db.AppDatabase
import com.iptv.player.data.db.DatabaseMaintenance
import com.iptv.player.data.prefs.SettingsStore
import com.iptv.player.data.repo.CatalogImporter
import com.iptv.player.data.repo.CatalogRepository
import com.iptv.player.data.repo.EpgRepository
import com.iptv.player.data.repo.SourceRepository
import com.iptv.player.data.repo.StreamUrlResolver
import com.iptv.player.player.PlaybackQueue
import com.iptv.player.util.Diagnostics
import com.iptv.player.work.RefreshScheduler
import com.iptv.player.work.SharingWatchdogScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manual dependency wiring.
 *
 * No Hilt/Dagger on purpose. The graph is ten singletons with no scopes, no
 * variants and no test doubles to swap at the framework level; a DI framework
 * would add an annotation processor to every build — on top of the one Room
 * already needs — and a couple of hundred milliseconds to a cold start that
 * the brief budgets at three seconds. If the graph grows scopes (per-profile
 * repositories in the multi-user phase, say), revisit it then.
 *
 * Everything is created lazily so that nothing but the settings store is
 * touched during Application.onCreate.
 */
object ServiceLocator {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    val sourceRepository: SourceRepository by lazy { SourceRepository(database, settings) }

    val epgRepository: EpgRepository by lazy { EpgRepository(database) }

    val catalogRepository: CatalogRepository by lazy { CatalogRepository(database, sourceRepository) }

    val importer: CatalogImporter by lazy { CatalogImporter(appContext, database, epgRepository) }

    val streamResolver: StreamUrlResolver by lazy { StreamUrlResolver(sourceRepository) }

    val configBackup: ConfigBackup by lazy { ConfigBackup(appContext, database, sourceRepository) }

    val playbackQueue: PlaybackQueue by lazy {
        PlaybackQueue(sourceRepository, catalogRepository, streamResolver)
    }

    val refreshScheduler: RefreshScheduler by lazy { RefreshScheduler(appContext) }

    val sharingWatchdogScheduler: SharingWatchdogScheduler by lazy {
        SharingWatchdogScheduler(appContext)
    }

    /** Where Room put the database, for the maintenance pass. */
    fun databaseFile(): java.io.File = appContext.getDatabasePath(AppDatabase.NAME)

    /**
     * Fire-and-forget housekeeping, started from `Application.onCreate`.
     *
     * Deliberately on a background dispatcher with a delay in front of it, and
     * deliberately not awaited by anything. The whole point of this app's
     * start-up path is that `onCreate` touches nothing but the settings store;
     * opening the database here to tidy it would trade the bloat problem for a
     * slower cold start, which is the thing the user actually experiences.
     * Waiting until the UI has settled means the purge competes with nothing.
     */
    fun startupMaintenance() {
        maintenanceScope.launch {
            delay(STARTUP_MAINTENANCE_DELAY_MS)
            runCatching {
                var purged = 0
                sourceRepository.all().forEach { purged += epgRepository.purgeExpired(it.id) }
                // Only worth compacting when something was actually removed;
                // otherwise this runs a size check on every launch for nothing.
                if (purged > 0) {
                    DatabaseMaintenance.reclaimSpaceIfWorthwhile(database, databaseFile())
                }
            }.onFailure { Diagnostics.error("db", "Start-up maintenance failed", it) }
        }
    }

    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Long enough for the first screen to have drawn and settled. */
    private const val STARTUP_MAINTENANCE_DELAY_MS = 8_000L
}
