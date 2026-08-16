package com.iptv.player.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.analytics.IptvAnalytics
import com.iptv.player.data.db.AppDatabase
import com.iptv.player.data.db.CategoryEntity
import com.iptv.player.data.db.MediaKind
import com.iptv.player.data.prefs.AspectMode
import com.iptv.player.data.prefs.BufferProfile
import com.iptv.player.data.prefs.SettingsStore
import com.iptv.player.data.prefs.pinMatches
import com.iptv.player.data.repo.CatalogRepository
import com.iptv.player.data.repo.EpgRepository
import com.iptv.player.work.RefreshScheduler
import com.iptv.player.work.SharingWatchdogScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val app: AppViewModel,
    private val store: SettingsStore,
    private val catalog: CatalogRepository,
    private val epg: EpgRepository,
    private val db: AppDatabase,
    private val scheduler: RefreshScheduler,
    private val watchdog: SharingWatchdogScheduler,
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Live categories of the active playlist, for the parental lock editor. */
    val lockableCategories: StateFlow<List<CategoryEntity>> = app.activeSource
        .flatMapLatest { source ->
            if (source == null) flowOf(emptyList()) else catalog.observeCategories(source.id, MediaKind.LIVE)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setLightTheme(value: Boolean) = viewModelScope.launch { store.setLightTheme(value) }
    fun setUiScale(value: Float) = viewModelScope.launch { store.setUiScale(value) }
    fun setBufferProfile(value: BufferProfile) = viewModelScope.launch { store.setBufferProfile(value) }
    fun setHardwareDecoding(value: Boolean) = viewModelScope.launch { store.setHardwareDecoding(value) }
    fun setCompatibilitySurface(value: Boolean) = viewModelScope.launch { store.setCompatibilitySurface(value) }

    /** Records that the sharing disclosure was answered, either way. */
    fun markSharingConsentAsked() = viewModelScope.launch { store.setSharingConsentAsked() }

    /**
     * Remembers the on/off choice so start-up can restore it, and brings the
     * recovery watchdog in line with it.
     *
     * The single point all three ways of changing this funnel through — the
     * consent dialog's two buttons and the Settings row — which is why the
     * watchdog is scheduled here rather than at each of those call sites.
     */
    fun setSharingEnabled(value: Boolean) = viewModelScope.launch {
        store.setSharingEnabled(value)
        watchdog.sync(value)
    }

    /**
     * Re-applies the watchdog schedule without touching the stored setting —
     * called once per start-up to recover a job that was dropped while the app
     * was not running.
     */
    fun syncSharingWatchdog(enabled: Boolean) = watchdog.sync(enabled)
    fun setReconnectAttempts(value: Int) = viewModelScope.launch { store.setReconnectAttempts(value) }
    fun setResumeLastChannel(value: Boolean) = viewModelScope.launch { store.setResumeLastChannel(value) }
    fun setAspectMode(value: AspectMode) = viewModelScope.launch { store.setAspectMode(value) }
    fun set24HourTime(value: Boolean) = viewModelScope.launch { store.set24HourTime(value) }
    fun setEpgOffsetMinutes(value: Int) = viewModelScope.launch { store.setEpgOffsetMinutes(value) }
    fun setHideLockedCategories(value: Boolean) = viewModelScope.launch { store.setHideLockedCategories(value) }

    /**
     * Changing the refresh interval reschedules the worker immediately rather
     * than at the next app launch — otherwise a user who turns auto-refresh
     * off still gets one more refresh, which looks like the setting is broken.
     */
    fun setAutoRefreshHours(value: Int) = viewModelScope.launch {
        store.setAutoRefreshHours(value)
        scheduler.reschedule(value)
    }

    fun setAutoRefreshEpg(value: Boolean) = viewModelScope.launch { store.setAutoRefreshEpg(value) }

    fun setCategoryLocked(categoryId: Long, locked: Boolean) = viewModelScope.launch {
        db.categories().setAdult(categoryId, locked)
    }

    // ---- Parental PIN ---------------------------------------------------

    fun setPin(pin: String) = viewModelScope.launch {
        store.setPin(pin)
        _message.value = "PIN set."
        // The digits never leave this function — only whether parental
        // controls are on, which is the only part that is anyone's business.
        IptvAnalytics.event("parental_pin_set", mapOf("enabled" to true))
    }

    fun clearPin() = viewModelScope.launch {
        store.clearPin()
        _message.value = "PIN removed."
        IptvAnalytics.event("parental_pin_set", mapOf("enabled" to false))
    }

    fun checkPin(pin: String): Boolean = app.settings.value.pinMatches(pin)

    // ---- Data -----------------------------------------------------------

    fun clearGuideCache() = viewModelScope.launch {
        app.activeSource.value?.let { epg.clear(it.id) }
        _message.value = "Guide cache cleared. It will reload on the next refresh."
    }

    fun clearHistory() = viewModelScope.launch {
        catalog.clearHistory()
        _message.value = "Watch history cleared."
    }

    fun consumeMessage() {
        _message.value = null
    }
}
