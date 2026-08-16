package com.iptv.player.ui.vm

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.iptv.player.di.ServiceLocator

/**
 * One factory for every ViewModel in the app.
 *
 * The screen-level ViewModels all depend on [AppViewModel] for the active
 * playlist, and they get the *same* instance because Compose resolves it
 * against the Activity's ViewModelStoreOwner. That matters: if each screen
 * built its own, switching playlists on the Settings screen would leave the
 * live list still pointed at the old one.
 */
val AppViewModelFactory: ViewModelProvider.Factory = viewModelFactory {

    // Returns the shared instance, not a new one — see [appViewModel] below.
    initializer { appViewModel() }

    initializer {
        SourcesViewModel(
            sources = ServiceLocator.sourceRepository,
            importer = ServiceLocator.importer,
            backup = ServiceLocator.configBackup,
        )
    }

    initializer {
        LiveViewModel(
            app = appViewModel(),
            catalog = ServiceLocator.catalogRepository,
            epg = ServiceLocator.epgRepository,
            sources = ServiceLocator.sourceRepository,
            resolver = ServiceLocator.streamResolver,
            queue = ServiceLocator.playbackQueue,
            settingsStore = ServiceLocator.settings,
        )
    }

    initializer {
        GuideViewModel(
            app = appViewModel(),
            catalog = ServiceLocator.catalogRepository,
            epg = ServiceLocator.epgRepository,
            sources = ServiceLocator.sourceRepository,
            resolver = ServiceLocator.streamResolver,
            queue = ServiceLocator.playbackQueue,
        )
    }

    initializer {
        VodViewModel(
            app = appViewModel(),
            catalog = ServiceLocator.catalogRepository,
            sources = ServiceLocator.sourceRepository,
            resolver = ServiceLocator.streamResolver,
            queue = ServiceLocator.playbackQueue,
        )
    }

    initializer {
        SearchViewModel(
            app = appViewModel(),
            catalog = ServiceLocator.catalogRepository,
            sources = ServiceLocator.sourceRepository,
            resolver = ServiceLocator.streamResolver,
            queue = ServiceLocator.playbackQueue,
        )
    }

    initializer {
        SettingsViewModel(
            app = appViewModel(),
            store = ServiceLocator.settings,
            catalog = ServiceLocator.catalogRepository,
            epg = ServiceLocator.epgRepository,
            db = ServiceLocator.database,
            scheduler = ServiceLocator.refreshScheduler,
            watchdog = ServiceLocator.sharingWatchdogScheduler,
        )
    }
}

/**
 * Shared [AppViewModel] for the initializers above.
 *
 * The dependent ViewModels need it at construction time, before Compose can
 * hand one over, so it is held here as a process-wide singleton. That is
 * correct for this app rather than a shortcut: its contents are settings and
 * the playlist list, both of which are process-scoped anyway, and it holds no
 * Activity reference to leak.
 */
private var sharedAppViewModel: AppViewModel? = null

private fun appViewModel(): AppViewModel = sharedAppViewModel ?: AppViewModel(
    ServiceLocator.settings,
    ServiceLocator.sourceRepository,
).also { sharedAppViewModel = it }
