package com.iptv.player.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import com.iptv.player.analytics.IptvAnalytics
import com.iptv.player.ui.nav.Section
import com.iptv.player.ui.nav.SideNavigation
import com.iptv.player.ui.screens.AddSourceScreen
import com.iptv.player.sharing.PawnsManager
import com.iptv.player.ui.screens.ConsentDialog
import com.iptv.player.ui.screens.GuideScreen
import com.iptv.player.ui.screens.HomeScreen
import com.iptv.player.ui.screens.LiveScreen
import com.iptv.player.ui.screens.MovieDetailScreen
import com.iptv.player.ui.screens.MoviesScreen
import com.iptv.player.ui.screens.PlayerScreen
import com.iptv.player.ui.screens.SearchScreen
import com.iptv.player.ui.screens.SeriesDetailScreen
import com.iptv.player.ui.screens.SeriesScreen
import com.iptv.player.ui.screens.SettingsScreen
import com.iptv.player.ui.screens.SourcesScreen
import com.iptv.player.ui.vm.AppViewModel
import com.iptv.player.ui.vm.AppViewModelFactory
import com.iptv.player.ui.vm.GuideViewModel
import com.iptv.player.ui.vm.LiveViewModel
import com.iptv.player.ui.vm.SearchViewModel
import com.iptv.player.ui.vm.SettingsViewModel
import com.iptv.player.ui.vm.SourcesViewModel
import com.iptv.player.ui.vm.VodViewModel

/**
 * Where the app is. Modelled as a small sealed hierarchy rather than a
 * Navigation-Compose graph.
 *
 * The reason is the player. Navigation-Compose would tear the player screen's
 * composition down and rebuild it on every navigation away and back, and with
 * a bound media service and a `PlayerView` holding a live surface, that means
 * a black frame and a re-buffer every time the user glances at the guide. The
 * screen set here is small, entirely known at compile time, and back behaviour
 * is one `when` — the cost of hand-rolling it is a few lines, and the benefit
 * is full control over what stays alive.
 */
private sealed interface Screen {
    data class Main(val section: Section) : Screen
    data object AddSource : Screen
    data class MovieDetail(val id: Long) : Screen
    data class SeriesDetail(val id: Long) : Screen
    data object Player : Screen
}

/** Stable name for [IptvAnalytics.screen] — see the `LaunchedEffect` below. */
private fun Screen.analyticsName(): String = when (this) {
    is Screen.Main -> section.name.lowercase()
    is Screen.AddSource -> "add_source"
    is Screen.MovieDetail -> "movie_detail"
    is Screen.SeriesDetail -> "series_detail"
    is Screen.Player -> "player"
}

@Composable
fun MainScreen() {
    val appViewModel: AppViewModel = viewModel(factory = AppViewModelFactory)
    val liveViewModel: LiveViewModel = viewModel(factory = AppViewModelFactory)
    val guideViewModel: GuideViewModel = viewModel(factory = AppViewModelFactory)
    val vodViewModel: VodViewModel = viewModel(factory = AppViewModelFactory)
    val searchViewModel: SearchViewModel = viewModel(factory = AppViewModelFactory)
    val sourcesViewModel: SourcesViewModel = viewModel(factory = AppViewModelFactory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = AppViewModelFactory)

    val settings by appViewModel.settings.collectAsStateWithLifecycle()
    val activeSource by appViewModel.activeSource.collectAsStateWithLifecycle()
    val sources by appViewModel.sourceList.collectAsStateWithLifecycle()
    val consentAsked by appViewModel.sharingConsentAsked.collectAsStateWithLifecycle()

    val activity = LocalContext.current as? Activity
    val context = LocalContext.current
    var screen: Screen by remember { mutableStateOf(Screen.Main(Section.Live)) }
    var previousSection by rememberSaveable { mutableStateOf(Section.Live.name) }
    var resumeAttempted by remember { mutableStateOf(false) }

    /**
     * The bandwidth-sharing disclosure, shown on app open rather than hidden
     * behind Settings.
     *
     * A feature that routes strangers' traffic through someone's home
     * connection has to be an active, informed choice, and a setting nobody
     * opens is not one. It is asked once: `settings.sharingConsentAsked`
     * records that an answer was given, so declining is respected permanently
     * instead of being re-asked every launch. Turning it on later lives in
     * Settings → Internet sharing.
     */
    var showConsent by remember { mutableStateOf(false) }
    LaunchedEffect(consentAsked) {
        if (!PawnsManager.available) return@LaunchedEffect
        // Null means DataStore has not answered yet. Prompting on that would
        // re-ask everyone who already declined, on every launch — see
        // AppViewModel.sharingConsentAsked.
        val asked = consentAsked ?: return@LaunchedEffect
        if (asked || PawnsManager.hasConsent()) return@LaunchedEffect
        showConsent = true
    }

    // "Open the last channel on start-up", applied once per process.
    LaunchedEffect(activeSource) {
        if (!resumeAttempted && activeSource != null && settings.resumeLastChannel) {
            resumeAttempted = true
            liveViewModel.resumeLastChannelIfEnabled { screen = Screen.Player }
        }
    }

    // With no playlists at all, drop straight into the add form rather than
    // showing an empty Live TV screen the user has to work out how to escape.
    // Guarded on a non-null list: null means the database has not answered yet,
    // and treating that as "no playlists" would hijack every cold start.
    LaunchedEffect(sources) {
        if (sources?.isEmpty() == true && screen is Screen.Main) screen = Screen.AddSource
    }

    val currentScreen = screen

    // Manual stand-in for screen tracking — see IptvAnalytics' class doc for
    // why this app cannot lean on PostHog's Activity-based auto capture.
    LaunchedEffect(currentScreen.analyticsName()) {
        IptvAnalytics.screen(currentScreen.analyticsName())
    }

    /**
     * Back walks detail screens up to their section, then any section back to
     * Live TV, and only leaves the app from Live TV itself. Letting Back exit
     * from wherever you happen to be is the classic TV-app mistake: on a
     * remote there is no other way out of a screen, so people press it
     * constantly and expect it to retreat, not quit.
     *
     * The player has its own handler — it must close overlays first — so it is
     * excluded here.
     */
    BackHandler(enabled = currentScreen !is Screen.Player) {
        screen = when (currentScreen) {
            is Screen.MovieDetail -> Screen.Main(Section.Movies)
            is Screen.SeriesDetail -> Screen.Main(Section.Series)
            is Screen.AddSource -> if (sources.isNullOrEmpty()) Screen.AddSource else Screen.Main(Section.Sources)
            is Screen.Main ->
                if (currentScreen.section == Section.Live) {
                    activity?.finish()
                    return@BackHandler
                } else {
                    Screen.Main(Section.Live)
                }
            is Screen.Player -> currentScreen
        }
    }

    if (currentScreen is Screen.Player) {
        PlayerScreen(
            settings = settings,
            onExit = { screen = Screen.Main(Section.valueOf(previousSection)) },
        )
        return
    }

    // Drawn over the app rather than as a separate screen, and returned from
    // early so nothing behind it is reachable while the question is open — the
    // answer has to be deliberate, and a half-focusable UI underneath a modal
    // is how a D-pad user ends up dismissing a consent prompt by accident.
    if (showConsent) {
        ConsentDialog(
            onAccept = {
                showConsent = false
                PawnsManager.setConsentGiven(true)
                PawnsManager.startSharing(context)
                settingsViewModel.markSharingConsentAsked()
                IptvAnalytics.event("sharing_consent", mapOf("granted" to true))
            },
            onDecline = {
                showConsent = false
                PawnsManager.setConsentGiven(false)
                // Also reachable from Settings while sharing is running, so
                // withdrawing consent must actually stop it rather than only
                // clearing the flag. A no-op when it is not running.
                PawnsManager.stopSharing(context)
                settingsViewModel.markSharingConsentAsked()
                IptvAnalytics.event("sharing_consent", mapOf("granted" to false))
            },
        )
        return
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (currentScreen) {
            is Screen.AddSource -> AddSourceScreen(
                viewModel = sourcesViewModel,
                onDone = { screen = Screen.Main(Section.Live) },
                onCancel = {
                    screen = if (sources.isNullOrEmpty()) Screen.AddSource else Screen.Main(Section.Sources)
                },
            )

            is Screen.MovieDetail -> MovieDetailScreen(
                viewModel = vodViewModel,
                movieId = currentScreen.id,
                onPlay = { screen = Screen.Player },
                onBack = { screen = Screen.Main(Section.Movies) },
            )

            is Screen.SeriesDetail -> SeriesDetailScreen(
                viewModel = vodViewModel,
                seriesId = currentScreen.id,
                onPlay = { screen = Screen.Player },
                onBack = { screen = Screen.Main(Section.Series) },
            )

            is Screen.Main -> Row(Modifier.fillMaxSize()) {
                SideNavigation(
                    current = currentScreen.section,
                    onSelect = { section ->
                        previousSection = section.name
                        screen = Screen.Main(section)
                    },
                )

                Box(Modifier.fillMaxSize()) {
                    SectionContent(
                        section = currentScreen.section,
                        settings = settings,
                        hasSource = activeSource != null,
                        activeSourceId = activeSource?.id ?: 0,
                        liveViewModel = liveViewModel,
                        guideViewModel = guideViewModel,
                        vodViewModel = vodViewModel,
                        searchViewModel = searchViewModel,
                        sourcesViewModel = sourcesViewModel,
                        settingsViewModel = settingsViewModel,
                        onSetActiveSource = appViewModel::setActiveSource,
                        onPlay = {
                            previousSection = currentScreen.section.name
                            screen = Screen.Player
                        },
                        onAddPlaylist = { screen = Screen.AddSource },
                        onOpenMovie = { screen = Screen.MovieDetail(it) },
                        onOpenSeries = { screen = Screen.SeriesDetail(it) },
                        onReviewSharing = { showConsent = true },
                    )
                }
            }

            is Screen.Player -> Unit // handled above
        }
    }
}

@Composable
private fun SectionContent(
    section: Section,
    settings: com.iptv.player.data.prefs.Settings,
    hasSource: Boolean,
    activeSourceId: Long,
    liveViewModel: LiveViewModel,
    guideViewModel: GuideViewModel,
    vodViewModel: VodViewModel,
    searchViewModel: SearchViewModel,
    sourcesViewModel: SourcesViewModel,
    settingsViewModel: SettingsViewModel,
    onSetActiveSource: (Long) -> Unit,
    onPlay: () -> Unit,
    onAddPlaylist: () -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    onReviewSharing: () -> Unit,
) {
    when (section) {
        Section.Live -> LiveScreen(
            viewModel = liveViewModel,
            settings = settings,
            onPlay = onPlay,
            onAddPlaylist = onAddPlaylist,
            hasSource = hasSource,
        )

        Section.Guide -> GuideScreen(
            viewModel = guideViewModel,
            settings = settings,
            onPlay = onPlay,
        )

        Section.Home -> HomeScreen(
            liveViewModel = liveViewModel,
            vodViewModel = vodViewModel,
            hasSource = hasSource,
            onPlay = onPlay,
            onAddPlaylist = onAddPlaylist,
        )

        Section.Movies -> MoviesScreen(viewModel = vodViewModel, onOpen = onOpenMovie)

        Section.Series -> SeriesScreen(viewModel = vodViewModel, onOpen = onOpenSeries)

        Section.Search -> SearchScreen(
            viewModel = searchViewModel,
            onPlay = onPlay,
            onOpenMovie = onOpenMovie,
            onOpenSeries = onOpenSeries,
        )

        Section.Sources -> SourcesScreen(
            viewModel = sourcesViewModel,
            activeSourceId = activeSourceId,
            onSetActive = onSetActiveSource,
            onAdd = onAddPlaylist,
        )

        Section.Settings -> SettingsScreen(
            viewModel = settingsViewModel,
            settings = settings,
            onReviewSharing = onReviewSharing,
        )
    }
}
