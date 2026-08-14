package com.iptv.player.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.data.db.CategoryEntity
import com.iptv.player.data.db.EpisodeEntity
import com.iptv.player.data.db.MediaKind
import com.iptv.player.data.db.SeriesEntity
import com.iptv.player.data.db.VodEntity
import com.iptv.player.data.db.WatchHistoryEntity
import com.iptv.player.data.repo.CatalogRepository
import com.iptv.player.data.repo.SourceRepository
import com.iptv.player.data.repo.StreamUrlResolver
import com.iptv.player.player.PlaybackQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Detail state for one series, including its lazily fetched episodes. */
data class SeriesDetail(
    val series: SeriesEntity? = null,
    val episodes: List<EpisodeEntity> = emptyList(),
    val seasons: List<Int> = emptyList(),
    val selectedSeason: Int? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class VodViewModel(
    private val app: AppViewModel,
    private val catalog: CatalogRepository,
    private val sources: SourceRepository,
    private val resolver: StreamUrlResolver,
    private val queue: PlaybackQueue,
) : ViewModel() {

    // ---- Movies ---------------------------------------------------------

    private val _movieCategory = MutableStateFlow<CategoryEntity?>(null)
    val selectedMovieCategory: StateFlow<CategoryEntity?> = _movieCategory.asStateFlow()

    val movieCategories: StateFlow<List<CategoryEntity>> = app.activeSource
        .flatMapLatest { source ->
            if (source == null) flowOf(emptyList()) else catalog.observeCategories(source.id, MediaKind.MOVIE)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val movies: StateFlow<List<VodEntity>> = combine(app.activeSource, _movieCategory) { source, category ->
        source to category
    }.flatMapLatest { (source, category) ->
        when {
            source == null -> flowOf(emptyList())
            category == null -> catalog.observeMovies(source.id)
            else -> catalog.observeMovies(source.id, category.id)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectMovieCategory(category: CategoryEntity?) {
        _movieCategory.value = category
    }

    // ---- Series ---------------------------------------------------------

    private val _seriesCategory = MutableStateFlow<CategoryEntity?>(null)
    val selectedSeriesCategory: StateFlow<CategoryEntity?> = _seriesCategory.asStateFlow()

    val seriesCategories: StateFlow<List<CategoryEntity>> = app.activeSource
        .flatMapLatest { source ->
            if (source == null) flowOf(emptyList()) else catalog.observeCategories(source.id, MediaKind.SERIES)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val series: StateFlow<List<SeriesEntity>> = combine(app.activeSource, _seriesCategory) { source, category ->
        source to category
    }.flatMapLatest { (source, category) ->
        when {
            source == null -> flowOf(emptyList())
            category == null -> catalog.observeSeries(source.id)
            else -> catalog.observeSeries(source.id, category.id)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectSeriesCategory(category: CategoryEntity?) {
        _seriesCategory.value = category
    }

    // ---- Continue watching ----------------------------------------------

    val continueWatching: StateFlow<List<WatchHistoryEntity>> = catalog.observeContinueWatching()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Movie detail ---------------------------------------------------

    private val _movieDetail = MutableStateFlow<VodEntity?>(null)
    val movieDetail: StateFlow<VodEntity?> = _movieDetail.asStateFlow()

    private val _movieResumeMs = MutableStateFlow(0L)
    val movieResumeMs: StateFlow<Long> = _movieResumeMs.asStateFlow()

    fun loadMovie(id: Long) = viewModelScope.launch {
        val movie = catalog.movie(id) ?: return@launch
        _movieDetail.value = movie
        _movieResumeMs.value = catalog.resumePosition(movie.sourceId, MediaKind.MOVIE, movie.streamKey)
        // The list endpoint returns almost no metadata, so the plot, runtime
        // and rating are fetched when the detail screen opens — one request,
        // and only for something the user actually looked at.
        val source = sources.get(movie.sourceId) ?: return@launch
        _movieDetail.value = catalog.enrichMovie(source, movie)
    }

    fun playMovie(movie: VodEntity, fromStart: Boolean, onStarted: () -> Unit) = viewModelScope.launch {
        val source = sources.get(movie.sourceId) ?: return@launch
        val resume = if (fromStart) 0 else catalog.resumePosition(movie.sourceId, MediaKind.MOVIE, movie.streamKey)
        val stream = resolver.forMovie(source, movie, resume) ?: return@launch
        queue.play(stream)
        onStarted()
    }

    // ---- Series detail --------------------------------------------------

    private val _seriesDetail = MutableStateFlow(SeriesDetail())
    val seriesDetail: StateFlow<SeriesDetail> = _seriesDetail.asStateFlow()

    fun loadSeries(id: Long) = viewModelScope.launch {
        _seriesDetail.value = SeriesDetail(loading = true)
        val series = catalog.series(id)
        if (series == null) {
            _seriesDetail.value = SeriesDetail(loading = false, error = "Not found")
            return@launch
        }
        _seriesDetail.value = SeriesDetail(series = series, loading = true)

        val source = sources.get(series.sourceId)
        if (source != null) {
            catalog.ensureEpisodes(source, series).onFailure { error ->
                _seriesDetail.value = _seriesDetail.value.copy(loading = false, error = error.message)
            }
        }

        catalog.observeEpisodes(series.id).collect { episodes ->
            val seasons = episodes.map { it.season }.distinct().sorted()
            _seriesDetail.value = _seriesDetail.value.copy(
                episodes = episodes,
                seasons = seasons,
                selectedSeason = _seriesDetail.value.selectedSeason ?: seasons.firstOrNull(),
                loading = false,
            )
        }
    }

    fun selectSeason(season: Int) {
        _seriesDetail.value = _seriesDetail.value.copy(selectedSeason = season)
    }

    fun playEpisode(episode: EpisodeEntity, fromStart: Boolean, onStarted: () -> Unit) = viewModelScope.launch {
        val detail = _seriesDetail.value
        val series = detail.series ?: return@launch
        val source = sources.get(episode.sourceId) ?: return@launch
        val resume = if (fromStart) 0 else catalog.resumePosition(episode.sourceId, MediaKind.SERIES, episode.streamKey)
        val stream = resolver.forEpisode(source, episode, series.name, series.poster, resume) ?: return@launch
        queue.play(stream)
        catalog.recordOpened(
            stream = stream,
            parentTitle = series.name,
            parentKey = series.streamKey,
            season = episode.season,
            episode = episode.episode,
        )
        onStarted()
    }

    /** Reopens whatever a Continue Watching tile points at. */
    fun resume(entry: WatchHistoryEntity, onStarted: () -> Unit) = viewModelScope.launch {
        val source = sources.get(entry.sourceId) ?: return@launch
        // Resolved by key rather than by row id: a refresh between sessions can
        // renumber rows, but streamKey is stable by design.
        val resolved = when (entry.kind) {
            MediaKind.MOVIE -> {
                val movie = catalog.movieByKey(entry.sourceId, entry.streamKey) ?: return@launch
                resolver.forMovie(source, movie, entry.positionMs)
            }
            MediaKind.SERIES -> {
                val episode = catalog.episode(entry.sourceId, entry.streamKey) ?: return@launch
                resolver.forEpisode(
                    source = source,
                    episode = episode,
                    seriesName = entry.parentTitle ?: entry.title,
                    artwork = entry.poster,
                    resumeFromMs = entry.positionMs,
                )
            }
            MediaKind.LIVE -> {
                val channel = catalog.channel(entry.sourceId, entry.streamKey) ?: return@launch
                resolver.forChannel(source, channel)
            }
        } ?: return@launch
        queue.play(resolved)
        onStarted()
    }
}
