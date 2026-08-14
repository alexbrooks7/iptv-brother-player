package com.iptv.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.iptv.player.R
import com.iptv.player.data.db.MediaKind
import com.iptv.player.data.repo.SearchResult
import com.iptv.player.ui.components.SettingRow
import com.iptv.player.ui.components.StateMessage
import com.iptv.player.ui.components.TvTextField
import com.iptv.player.ui.util.tvFocusGroup
import com.iptv.player.ui.vm.SearchViewModel

/**
 * Search across channels, movies and series at once.
 *
 * Results are one flat list grouped by kind rather than three tabs. Typing on
 * a TV is expensive enough that making the user then choose *where* to look is
 * a poor trade — and in practice people search for a title without caring
 * whether their provider filed it under VOD or a channel.
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onPlay: () -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        Text(stringResource(R.string.search_title), style = MaterialTheme.typography.headlineSmall)

        TvTextField(
            value = query,
            onValueChange = viewModel::onQueryChanged,
            label = stringResource(R.string.search_hint),
            autoFocus = true,
            modifier = Modifier.width(640.dp).padding(top = 16.dp, bottom = 20.dp),
        )

        when {
            searching -> Text(
                stringResource(R.string.state_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            query.trim().length >= 2 && results.isEmpty() ->
                StateMessage(title = stringResource(R.string.search_no_results, query))

            results.isEmpty() -> Unit

            else -> ResultList(
                results = results,
                onOpen = { result ->
                    viewModel.open(result, onPlay, onOpenMovie, onOpenSeries)
                },
            )
        }
    }
}

@Composable
private fun ResultList(results: List<SearchResult>, onOpen: (SearchResult) -> Unit) {
    val grouped = results.groupBy { it.kind }

    LazyColumn(
        Modifier.fillMaxSize().tvFocusGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        listOf(MediaKind.LIVE, MediaKind.MOVIE, MediaKind.SERIES).forEach { kind ->
            val items = grouped[kind].orEmpty()
            if (items.isEmpty()) return@forEach

            item(key = "header-$kind") {
                Text(
                    stringResource(
                        when (kind) {
                            MediaKind.LIVE -> R.string.search_section_channels
                            MediaKind.MOVIE -> R.string.search_section_movies
                            MediaKind.SERIES -> R.string.search_section_series
                        }
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
                )
            }

            items(items, key = { "${it.kind}-${it.id}" }) { result ->
                SettingRow(
                    title = result.title,
                    summary = result.subtitle,
                    onClick = { onOpen(result) },
                )
            }
        }
    }
}
