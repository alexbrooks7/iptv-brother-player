package com.iptv.player.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.data.db.SourceEntity
import com.iptv.player.data.prefs.Settings
import com.iptv.player.data.prefs.SettingsStore
import com.iptv.player.data.repo.SourceRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * App-wide state: preferences, the configured playlists, and which one is
 * active. Every screen reads the active playlist from here rather than
 * carrying an id through navigation, because switching playlists has to take
 * effect everywhere at once.
 */
class AppViewModel(
    private val settingsStore: SettingsStore,
    private val sources: SourceRepository,
) : ViewModel() {

    val settings: StateFlow<Settings> = settingsStore.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    /**
     * Whether the sharing disclosure has been answered — null until DataStore
     * has actually said.
     *
     * Nullable for the same load-bearing reason as [sourceList] below. [settings]
     * is seeded eagerly with `Settings()`, whose `sharingConsentAsked` is
     * `false`, and DataStore's first read hits disk. Deciding whether to prompt
     * from that seed means every cold start briefly looks like "never asked",
     * so someone who declined once would be asked again on every single launch
     * — the exact nagging pattern a consent flow must not have. Null means
     * "don't decide yet".
     */
    val sharingConsentAsked: StateFlow<Boolean?> = settingsStore.flow
        .map { it.sharingConsentAsked }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Whether sharing should be resumed on start-up — null until DataStore has
     * said, for the same reason as [sharingConsentAsked].
     *
     * Here the seeded default is the *safe* direction (`false` reads as "do not
     * start"), so a wrong guess would never route traffic without permission.
     * It stays nullable anyway so the resume decision is made once, against a
     * real value, rather than being skipped on every cold start because the
     * disk read had not landed yet.
     */
    val sharingEnabled: StateFlow<Boolean?> = settingsStore.flow
        .map { it.sharingEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Null until the database has answered, then the real list.
     *
     * The nullable initial value is load-bearing, not defensive style. A plain
     * `emptyList()` initial is indistinguishable from "this user has no
     * playlists", so any UI that reacts to emptiness — here, jumping to the
     * add-playlist form — fires on every cold start before Room emits, and the
     * user is thrown into a setup screen they already completed.
     */
    val sourceList: StateFlow<List<SourceEntity>?> = sources.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The active playlist, falling back to the first configured one.
     *
     * The fallback is what makes deleting a playlist, or restoring a backup
     * onto a device whose stored id no longer exists, resolve to something
     * usable instead of an empty screen with no explanation.
     */
    val activeSource: StateFlow<SourceEntity?> = combine(settings, sourceList) { prefs, list ->
        val loaded = list ?: return@combine null
        loaded.firstOrNull { it.id == prefs.activeSourceId } ?: loaded.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setActiveSource(id: Long) = viewModelScope.launch { settingsStore.setActiveSource(id) }
}
