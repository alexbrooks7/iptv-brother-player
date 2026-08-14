package com.iptv.player.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.data.db.GuideChannel
import com.iptv.player.data.db.ProgrammeEntity
import com.iptv.player.data.repo.CatalogRepository
import com.iptv.player.data.repo.EpgRepository
import com.iptv.player.data.repo.SourceRepository
import com.iptv.player.data.repo.StreamUrlResolver
import com.iptv.player.player.PlaybackQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * State for the timeline guide.
 *
 * The grid is horizontally a time axis and vertically a channel list, which is
 * the layout every TV viewer already knows. Two things make it workable on a
 * large playlist:
 *
 * 1. **Programmes are loaded for the visible rows only.** A 5,000-channel
 *    playlist has upwards of a million programmes in a week; querying them all
 *    to draw twelve visible rows would be absurd. The screen reports which
 *    rows it is showing and this class fetches exactly those.
 * 2. **The loaded time window is wider than the visible one.** Scrolling
 *    sideways by one screen should not trigger a query, so [WINDOW_HOURS] of
 *    listings are held around the current position and only refetched when the
 *    view approaches the edge.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class GuideViewModel(
    private val app: AppViewModel,
    private val catalog: CatalogRepository,
    private val epg: EpgRepository,
    private val sources: SourceRepository,
    private val resolver: StreamUrlResolver,
    private val queue: PlaybackQueue,
) : ViewModel() {

    val channels: StateFlow<List<GuideChannel>> = app.activeSource
        .flatMapLatest { source ->
            if (source == null) flowOf(emptyList()) else catalog.observeGuideChannels(source.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Left edge of the timeline, always aligned to a half hour. */
    private val _windowStart = MutableStateFlow(alignToHalfHour(System.currentTimeMillis()))
    val windowStart: StateFlow<Long> = _windowStart.asStateFlow()

    private val _programmes = MutableStateFlow<Map<String, List<ProgrammeEntity>>>(emptyMap())
    val programmes: StateFlow<Map<String, List<ProgrammeEntity>>> = _programmes.asStateFlow()

    private val _selected = MutableStateFlow<ProgrammeEntity?>(null)
    val selectedProgramme: StateFlow<ProgrammeEntity?> = _selected.asStateFlow()

    private val _hasEpgData = MutableStateFlow(true)
    val hasEpgData: StateFlow<Boolean> = _hasEpgData.asStateFlow()

    private var loadJob: Job? = null
    private var loadedRange: LongRange = 0L..0L
    private var loadedChannels: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            app.activeSource.collect { source ->
                _hasEpgData.value = source == null || epg.hasData(source.id)
                loadedRange = 0L..0L
                loadedChannels = emptySet()
            }
        }
    }

    /**
     * Called by the grid as it scrolls. Cheap to call repeatedly — the work is
     * skipped unless the request has moved outside what is already loaded.
     */
    fun onVisibleRows(firstIndex: Int, lastIndex: Int) {
        val all = channels.value
        if (all.isEmpty()) return

        // Load a screen's worth either side so ordinary scrolling never waits.
        val from = (firstIndex - 10).coerceAtLeast(0)
        val to = (lastIndex + 10).coerceAtMost(all.lastIndex)
        val ids = all.subList(from, to + 1).mapNotNull { it.tvgId }.toSet()
        if (ids.isEmpty()) return

        val start = _windowStart.value - TimeUnit.HOURS.toMillis(1)
        val end = _windowStart.value + TimeUnit.HOURS.toMillis(WINDOW_HOURS)

        val alreadyCovered = loadedChannels.containsAll(ids) &&
            start >= loadedRange.first && end <= loadedRange.last
        if (alreadyCovered) return

        loadedChannels = loadedChannels + ids
        loadedRange = start..end
        reload(ids, start, end)
    }

    private fun reload(channelIds: Set<String>, from: Long, to: Long) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val source = app.activeSource.value ?: return@launch
            val loaded = epg.window(source.id, channelIds.toList(), from, to)
            _programmes.value = _programmes.value + loaded
        }
    }

    fun scrollTimeBy(millis: Long) {
        _windowStart.value = alignToHalfHour(_windowStart.value + millis)
        invalidateWindow()
    }

    fun jumpToNow() {
        _windowStart.value = alignToHalfHour(System.currentTimeMillis())
        invalidateWindow()
    }

    fun jumpToDay(dayOffset: Int) {
        val base = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(dayOffset.toLong())
        // Jumping to a day lands at the start of prime time rather than at
        // midnight: nobody opens tomorrow's guide to find out what is on at
        // 3 a.m., and starting at 18:00 puts the interesting listings on
        // screen without any further scrolling.
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = base
            set(java.util.Calendar.HOUR_OF_DAY, if (dayOffset == 0) get(java.util.Calendar.HOUR_OF_DAY) else 18)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
        }
        _windowStart.value = alignToHalfHour(calendar.timeInMillis)
        invalidateWindow()
    }

    private fun invalidateWindow() {
        loadedRange = 0L..0L
        _programmes.value = emptyMap()
    }

    fun selectProgramme(programme: ProgrammeEntity?) {
        _selected.value = programme
    }

    /** Watch live. Used when the selected programme is the one on air now. */
    fun playChannel(channelRowId: Long, onStarted: () -> Unit) = viewModelScope.launch {
        val channel = catalog.channelById(channelRowId) ?: return@launch
        val source = sources.get(channel.sourceId) ?: return@launch
        val stream = resolver.forChannel(source, channel) ?: return@launch
        val siblings = channels.value.map { it.id }
        queue.play(stream, siblings, siblings.indexOf(channelRowId))
        onStarted()
    }

    /**
     * Watch a past programme. Returns false when the provider does not offer
     * catch-up for this channel, which the screen reports rather than failing
     * silently — the brief is specific that catch-up must not be assumed.
     */
    suspend fun playCatchup(channelRowId: Long, programme: ProgrammeEntity): Boolean {
        val channel = catalog.channelById(channelRowId) ?: return false
        val source = sources.get(channel.sourceId) ?: return false
        val stream = resolver.forCatchup(
            source = source,
            channel = channel,
            programmeTitle = programme.title,
            startUtcMillis = programme.startUtc,
            endUtcMillis = programme.endUtc,
        ) ?: return false
        queue.play(stream)
        return true
    }

    companion object {
        const val WINDOW_HOURS = 6L

        fun alignToHalfHour(millis: Long): Long {
            val half = TimeUnit.MINUTES.toMillis(30)
            return millis / half * half
        }
    }
}
