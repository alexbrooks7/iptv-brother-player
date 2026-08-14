package com.iptv.player.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.analytics.IptvAnalytics
import com.iptv.player.data.db.CategoryEntity
import com.iptv.player.data.db.ChannelEntity
import com.iptv.player.data.db.MediaKind
import com.iptv.player.data.db.SourceEntity
import com.iptv.player.data.prefs.SettingsStore
import com.iptv.player.data.repo.CatalogRepository
import com.iptv.player.data.repo.EpgRepository
import com.iptv.player.data.repo.NowNext
import com.iptv.player.data.repo.SourceRepository
import com.iptv.player.data.repo.StreamUrlResolver
import com.iptv.player.player.PlaybackQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * An entry in the category column. The first three are computed rather than
 * stored, which is why this is a sealed type and not just [CategoryEntity].
 */
sealed interface ChannelFilter {
    val key: String

    data object Favorites : ChannelFilter { override val key = "favorites" }
    data object Recent : ChannelFilter { override val key = "recent" }
    data object All : ChannelFilter { override val key = "all" }
    data object Uncategorised : ChannelFilter { override val key = "uncategorised" }
    data class Category(val entity: CategoryEntity) : ChannelFilter {
        override val key = "cat:${entity.id}"
    }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LiveViewModel(
    private val app: AppViewModel,
    private val catalog: CatalogRepository,
    private val epg: EpgRepository,
    private val sources: SourceRepository,
    private val resolver: StreamUrlResolver,
    private val queue: PlaybackQueue,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _selectedFilter = MutableStateFlow<ChannelFilter>(ChannelFilter.All)
    val selectedFilter: StateFlow<ChannelFilter> = _selectedFilter.asStateFlow()

    private val _focused = MutableStateFlow<ChannelEntity?>(null)
    val focusedChannel: StateFlow<ChannelEntity?> = _focused.asStateFlow()

    /** Categories unlocked with the PIN for this app session only. */
    private val _unlocked = MutableStateFlow<Set<Long>>(emptySet())
    val unlockedCategories: StateFlow<Set<Long>> = _unlocked.asStateFlow()

    private val activeSource: StateFlow<SourceEntity?> = app.activeSource

    val filters: StateFlow<List<ChannelFilter>> = activeSource
        .flatMapLatest { source ->
            if (source == null) flowOf(emptyList())
            else catalog.observeCategories(source.id, MediaKind.LIVE)
        }
        .combine(app.settings) { categories, settings ->
            val visible = categories.filterNot { category ->
                // "Hide completely" removes locked categories from the list
                // rather than showing a PIN wall. Some households want the
                // adult section invisible, not merely gated — a locked door is
                // still a door a child can see.
                settings.parentalEnabled && settings.hideLockedCategories && category.adult
            }
            buildList {
                add(ChannelFilter.Favorites)
                add(ChannelFilter.Recent)
                add(ChannelFilter.All)
                visible.forEach { add(ChannelFilter.Category(it)) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Which category rows should draw a padlock, as ordinary observable state.
     *
     * Precomputed rather than asking [isLocked] per row while the list is being
     * composed. That call reads `StateFlow.value` directly, and a plain `.value`
     * read is invisible to Compose's snapshot system: the padlock would not
     * reappear when the PIN setting changed until something else happened to
     * recompose the row. Deriving the set here fixes the correctness bug and
     * removes the per-row work at the same time.
     */
    val lockedFilterKeys: StateFlow<Set<String>> =
        combine(filters, app.settings, _unlocked) { filters, settings, unlocked ->
            if (!settings.parentalEnabled || settings.parentalPinHash == null) return@combine emptySet()
            filters.asSequence()
                .mapNotNull { it as? ChannelFilter.Category }
                .filter { it.entity.adult && it.entity.id !in unlocked }
                .map { it.key }
                .toSet()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val channels: StateFlow<List<ChannelEntity>> = combine(activeSource, _selectedFilter) { source, filter ->
        source to filter
    }.flatMapLatest { (source, filter) ->
        if (source == null) flowOf(emptyList()) else when (filter) {
            ChannelFilter.Favorites -> catalog.observeFavoriteChannels(source.id)
            ChannelFilter.Recent -> catalog.observeRecentChannels(source.id)
            ChannelFilter.All -> catalog.observeChannels(source.id)
            ChannelFilter.Uncategorised -> catalog.observeUncategorisedChannels(source.id)
            is ChannelFilter.Category -> catalog.observeChannels(source.id, filter.entity.id)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Favourites regardless of which category is currently selected.
     *
     * Separate from [channels] on purpose: the home screen shows favourites
     * while the live list may be filtered to one category, and deriving one
     * from the other would silently hide every favourite outside that
     * category.
     */
    val favoriteChannels: StateFlow<List<ChannelEntity>> = activeSource
        .flatMapLatest { source ->
            if (source == null) flowOf(emptyList()) else catalog.observeFavoriteChannels(source.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteKeys: StateFlow<Set<String>> = activeSource
        .flatMapLatest { source ->
            if (source == null) flowOf(emptyList()) else catalog.observeFavoriteKeys(source.id, MediaKind.LIVE)
        }
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * Guide data for the channel the user is sitting on.
     *
     * Only the focused channel, never the whole visible list. Joining the
     * programme table against a 10,000-row channel list to label every row
     * would be the single most expensive query in the app, and it would run
     * again on every category change — on a Fire TV Stick that is a visible
     * stall. One indexed lookup per focus change is instant, and the
     * information ends up on screen in the panel next to the list either way.
     *
     * Debounced, because "per focus change" means *per D-pad press*. Holding
     * the down arrow to run through a few hundred channels otherwise fires a
     * database query for every row the focus passes over, none of which is on
     * screen long enough to be read. [flatMapLatest] cancels the previous
     * query but cannot un-start it, so the work still lands on the IO pool and
     * still competes with the list for CPU on a box that has very little to
     * spare. Waiting for the focus to settle means the query runs once, when
     * the user has actually stopped somewhere.
     */
    val focusedNowNext: StateFlow<NowNext> = combine(activeSource, _focused) { source, channel ->
        source to channel
    }
        .debounce { (_, channel) ->
            // No delay when focus is cleared, so the panel empties immediately
            // rather than lingering on the previous channel's listing.
            if (channel == null) 0L else FOCUS_SETTLE_MS
        }
        .flatMapLatest { (source, channel) ->
            flowOf(
                if (source == null || channel == null) NowNext(null, null)
                else epg.nowNext(source.id, channel.tvgId, System.currentTimeMillis())
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowNext(null, null))

    fun selectFilter(filter: ChannelFilter) {
        _selectedFilter.value = filter
    }

    fun onChannelFocused(channel: ChannelEntity) {
        _focused.value = channel
    }

    fun toggleFavorite(channel: ChannelEntity) = viewModelScope.launch {
        val nowFavorite = catalog.toggleFavorite(channel.sourceId, MediaKind.LIVE, channel.streamKey)
        IptvAnalytics.event("favorite_toggled", mapOf("kind" to "live", "now_favorite" to nowFavorite))
    }

    /** True when this category needs a PIN before its channels are shown. */
    fun isLocked(filter: ChannelFilter): Boolean {
        val settings = app.settings.value
        if (!settings.parentalEnabled || settings.parentalPinHash == null) return false
        val category = (filter as? ChannelFilter.Category)?.entity ?: return false
        return category.adult && category.id !in _unlocked.value
    }

    fun unlock(filter: ChannelFilter) {
        val category = (filter as? ChannelFilter.Category)?.entity ?: return
        _unlocked.value = _unlocked.value + category.id
    }

    /**
     * Opens a channel, handing the player the list it came from so that
     * channel up/down walks the same order the user was looking at.
     */
    fun play(channel: ChannelEntity, onStarted: () -> Unit) = viewModelScope.launch {
        val source = sources.get(channel.sourceId) ?: return@launch
        val stream = resolver.forChannel(source, channel) ?: return@launch
        val siblings = channels.value.map { it.id }
        queue.play(stream, siblings, siblings.indexOf(channel.id))
        onStarted()
    }

    /** Applies "open the last channel on start-up" once, on a cold launch. */
    fun resumeLastChannelIfEnabled(onStarted: () -> Unit) = viewModelScope.launch {
        if (!app.settings.value.resumeLastChannel) return@launch
        val last = catalog.lastLiveChannel() ?: return@launch
        val channel = catalog.channel(last.sourceId, last.streamKey) ?: return@launch
        play(channel, onStarted)
    }

    fun setHideLockedCategories(hide: Boolean) = viewModelScope.launch {
        settingsStore.setHideLockedCategories(hide)
    }

    private companion object {
        /**
         * How long focus has to rest on a channel before its guide data is
         * fetched. Long enough that scrolling past a row costs nothing, short
         * enough that stopping on one feels immediate — the panel is not the
         * thing the user is looking at while they are still moving.
         */
        const val FOCUS_SETTLE_MS = 250L
    }
}
