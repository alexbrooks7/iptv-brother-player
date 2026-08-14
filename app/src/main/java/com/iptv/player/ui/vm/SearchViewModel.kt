package com.iptv.player.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.data.db.MediaKind
import com.iptv.player.data.repo.CatalogRepository
import com.iptv.player.data.repo.SearchResult
import com.iptv.player.data.repo.SourceRepository
import com.iptv.player.data.repo.StreamUrlResolver
import com.iptv.player.player.PlaybackQueue
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val app: AppViewModel,
    private val catalog: CatalogRepository,
    private val sources: SourceRepository,
    private val resolver: StreamUrlResolver,
    private val queue: PlaybackQueue,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private var searchJob: Job? = null

    /**
     * Debounced by 250 ms. Text arrives from the TV's on-screen keyboard a
     * character at a time and each keystroke would otherwise run three LIKE
     * queries against tables holding tens of thousands of rows.
     */
    fun onQueryChanged(value: String) {
        _query.value = value
        searchJob?.cancel()
        if (value.trim().length < 2) {
            _results.value = emptyList()
            _searching.value = false
            return
        }
        _searching.value = true
        searchJob = viewModelScope.launch {
            delay(250)
            val source = app.activeSource.value
            _results.value = if (source == null) emptyList() else catalog.search(source.id, value)
            _searching.value = false
        }
    }

    /** Channels play immediately; movies and series open their detail screen. */
    fun open(
        result: SearchResult,
        onPlay: () -> Unit,
        onOpenMovie: (Long) -> Unit,
        onOpenSeries: (Long) -> Unit,
    ) = viewModelScope.launch {
        when (result.kind) {
            MediaKind.LIVE -> {
                val channel = catalog.channelById(result.id) ?: return@launch
                val source = sources.get(channel.sourceId) ?: return@launch
                val stream = resolver.forChannel(source, channel) ?: return@launch
                queue.play(stream)
                onPlay()
            }
            MediaKind.MOVIE -> onOpenMovie(result.id)
            MediaKind.SERIES -> onOpenSeries(result.id)
        }
    }
}
