package com.iptv.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.iptv.player.R
import com.iptv.player.data.db.CategoryEntity
import com.iptv.player.ui.components.Artwork
import com.iptv.player.ui.components.Chip
import com.iptv.player.ui.components.ChipSpacing
import com.iptv.player.ui.components.MetadataRow
import com.iptv.player.ui.components.PosterCard
import com.iptv.player.ui.components.SettingRow
import com.iptv.player.ui.components.StateMessage
import com.iptv.player.ui.components.TvButton
import com.iptv.player.ui.util.formatDuration
import com.iptv.player.ui.util.tvFocusGroup
import com.iptv.player.ui.vm.VodViewModel

@Composable
fun MoviesScreen(viewModel: VodViewModel, onOpen: (Long) -> Unit) {
    val categories by viewModel.movieCategories.collectAsStateWithLifecycle()
    val selected by viewModel.selectedMovieCategory.collectAsStateWithLifecycle()
    val movies by viewModel.movies.collectAsStateWithLifecycle()

    CatalogBrowser(
        title = stringResource(R.string.vod_title),
        emptyMessage = stringResource(R.string.vod_empty),
        categories = categories,
        selected = selected,
        onSelect = viewModel::selectMovieCategory,
        itemCount = movies.size,
    ) { modifier ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(190.dp),
            modifier = modifier.tvFocusGroup(),
            contentPadding = PaddingValues(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(movies, key = { it.id }) { movie ->
                PosterCard(
                    title = movie.name,
                    poster = movie.poster,
                    subtitle = movie.year,
                    onClick = { onOpen(movie.id) },
                )
            }
        }
    }
}

@Composable
fun SeriesScreen(viewModel: VodViewModel, onOpen: (Long) -> Unit) {
    val categories by viewModel.seriesCategories.collectAsStateWithLifecycle()
    val selected by viewModel.selectedSeriesCategory.collectAsStateWithLifecycle()
    val series by viewModel.series.collectAsStateWithLifecycle()

    CatalogBrowser(
        title = stringResource(R.string.series_title),
        emptyMessage = stringResource(R.string.series_empty),
        categories = categories,
        selected = selected,
        onSelect = viewModel::selectSeriesCategory,
        itemCount = series.size,
    ) { modifier ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(190.dp),
            modifier = modifier.tvFocusGroup(),
            contentPadding = PaddingValues(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(series, key = { it.id }) { show ->
                PosterCard(
                    title = show.name,
                    poster = show.poster,
                    subtitle = show.year,
                    onClick = { onOpen(show.id) },
                )
            }
        }
    }
}

/**
 * Shared chrome for the two poster catalogues: a title, a horizontal category
 * filter, and a grid.
 *
 * The category filter is a chip row rather than the left-hand column Live TV
 * uses. VOD categories are browsed occasionally and are far fewer, while a
 * poster grid needs every pixel of width it can get — the opposite trade-off
 * to a channel list, where the category column is in constant use.
 */
@Composable
private fun CatalogBrowser(
    title: String,
    emptyMessage: String,
    categories: List<CategoryEntity>,
    selected: CategoryEntity?,
    onSelect: (CategoryEntity?) -> Unit,
    itemCount: Int,
    content: @Composable (Modifier) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)

        if (categories.isNotEmpty()) {
            LazyRow(
                Modifier.padding(top = 14.dp, bottom = 18.dp).tvFocusGroup(),
                horizontalArrangement = ChipSpacing,
            ) {
                item(key = "all") {
                    Chip(
                        label = stringResource(R.string.live_all_channels),
                        selected = selected == null,
                        onClick = { onSelect(null) },
                    )
                }
                items(categories, key = { it.id }) { category ->
                    Chip(
                        label = category.name,
                        selected = selected?.id == category.id,
                        onClick = { onSelect(category) },
                    )
                }
            }
        }

        if (itemCount == 0) {
            StateMessage(title = emptyMessage)
        } else {
            content(Modifier.fillMaxSize())
        }
    }
}

@Composable
fun MovieDetailScreen(viewModel: VodViewModel, movieId: Long, onPlay: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(movieId) { viewModel.loadMovie(movieId) }
    val movie by viewModel.movieDetail.collectAsStateWithLifecycle()
    val resumeMs by viewModel.movieResumeMs.collectAsStateWithLifecycle()

    val current = movie
    if (current == null || current.id != movieId) {
        StateMessage(title = stringResource(R.string.state_loading))
        return
    }

    Row(Modifier.fillMaxSize().padding(48.dp)) {
        Artwork(
            url = current.poster,
            name = current.name,
            modifier = Modifier.width(300.dp).height(450.dp),
            shape = RoundedCornerShape(12.dp),
        )
        Column(Modifier.padding(start = 40.dp).fillMaxHeight()) {
            Text(current.name, style = MaterialTheme.typography.headlineMedium)
            MetadataRow(
                items = listOfNotNull(
                    current.year,
                    current.genre,
                    current.rating?.let { stringResource(R.string.detail_rating, it) },
                    current.durationSecs?.let { formatDuration(it * 1000L) },
                ),
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                current.plot ?: stringResource(R.string.detail_no_description),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 20.dp),
            )

            Row(Modifier.padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (resumeMs > 0) {
                    TvButton(
                        text = stringResource(R.string.detail_resume, formatDuration(resumeMs)),
                        onClick = { viewModel.playMovie(current, fromStart = false) { onPlay() } },
                        autoFocus = true,
                    )
                    TvButton(
                        text = stringResource(R.string.detail_start_over),
                        primary = false,
                        onClick = { viewModel.playMovie(current, fromStart = true) { onPlay() } },
                    )
                } else {
                    TvButton(
                        text = stringResource(R.string.detail_play),
                        onClick = { viewModel.playMovie(current, fromStart = true) { onPlay() } },
                        autoFocus = true,
                    )
                }
                TvButton(text = stringResource(R.string.action_back), primary = false, onClick = onBack)
            }
        }
    }
}

@Composable
fun SeriesDetailScreen(viewModel: VodViewModel, seriesId: Long, onPlay: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(seriesId) { viewModel.loadSeries(seriesId) }
    val detail by viewModel.seriesDetail.collectAsStateWithLifecycle()

    val series = detail.series
    if (series == null) {
        StateMessage(
            title = if (detail.loading) stringResource(R.string.state_loading) else stringResource(R.string.state_empty),
        )
        return
    }

    Row(Modifier.fillMaxSize().padding(40.dp)) {
        Column(Modifier.width(300.dp)) {
            Artwork(
                url = series.poster,
                name = series.name,
                modifier = Modifier.fillMaxWidth().height(430.dp),
                shape = RoundedCornerShape(12.dp),
            )
            Text(
                series.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 14.dp),
            )
            MetadataRow(
                items = listOfNotNull(series.year, series.genre),
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                series.plot ?: stringResource(R.string.detail_no_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
            TvButton(
                text = stringResource(R.string.action_back),
                primary = false,
                onClick = onBack,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        Column(Modifier.padding(start = 36.dp).fillMaxSize()) {
            if (detail.loading) {
                StateMessage(title = stringResource(R.string.state_loading))
                return@Column
            }
            if (detail.episodes.isEmpty()) {
                StateMessage(
                    title = stringResource(R.string.state_empty),
                    body = detail.error,
                )
                return@Column
            }

            LazyRow(
                Modifier.padding(bottom = 16.dp).tvFocusGroup(),
                horizontalArrangement = ChipSpacing,
            ) {
                items(detail.seasons, key = { it }) { season ->
                    Chip(
                        label = stringResource(R.string.series_season, season),
                        selected = season == detail.selectedSeason,
                        onClick = { viewModel.selectSeason(season) },
                    )
                }
            }

            val episodes = detail.episodes.filter { it.season == detail.selectedSeason }
            Text(
                stringResource(R.string.series_episodes_count, episodes.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
            )

            LazyColumn(
                Modifier.fillMaxSize().tvFocusGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                items(episodes, key = { it.id }) { episode ->
                    SettingRow(
                        title = stringResource(R.string.series_episode, episode.episode, episode.title),
                        summary = episode.plot,
                        value = episode.durationSecs?.let { formatDuration(it * 1000L) },
                        onClick = { viewModel.playEpisode(episode, fromStart = false) { onPlay() } },
                    )
                }
            }
        }
    }
}

/** Small helper used by the home screen's rows. */
@Composable
fun RowTitle(text: String) {
    Box(
        Modifier
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.background)
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
