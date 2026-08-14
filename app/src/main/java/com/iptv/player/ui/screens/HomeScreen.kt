package com.iptv.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import com.iptv.player.ui.components.ChannelRow
import com.iptv.player.ui.components.SectionHeader
import com.iptv.player.ui.components.StateMessage
import com.iptv.player.ui.components.WideCard
import com.iptv.player.ui.util.tvFocusGroup
import com.iptv.player.ui.vm.LiveViewModel
import com.iptv.player.ui.vm.VodViewModel

/**
 * The "pick up where you left off" screen: continue watching, favourites and
 * recently watched channels.
 *
 * It is deliberately thin. A home screen that tries to be a storefront —
 * recommended rows, trending, artwork carousels — is the wrong shape for this
 * app, because the catalogue belongs to the user's provider and there is no
 * editorial signal to rank anything by. What the app *does* know is what this
 * household actually watches, so that is all it shows.
 */
@Composable
fun HomeScreen(
    liveViewModel: LiveViewModel,
    vodViewModel: VodViewModel,
    hasSource: Boolean,
    onPlay: () -> Unit,
    onAddPlaylist: () -> Unit,
) {
    if (!hasSource) {
        StateMessage(
            title = stringResource(R.string.home_empty_title),
            body = stringResource(R.string.home_empty_body),
            actionLabel = stringResource(R.string.home_empty_action),
            onAction = onAddPlaylist,
        )
        return
    }

    val continueWatching by vodViewModel.continueWatching.collectAsStateWithLifecycle()
    val favorites by liveViewModel.favoriteChannels.collectAsStateWithLifecycle()

    val favoriteChannels = favorites.take(12)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (continueWatching.isNotEmpty()) {
            item(key = "continue") {
                Column {
                    SectionHeader(stringResource(R.string.home_continue_watching))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.tvFocusGroup(),
                    ) {
                        items(continueWatching, key = { "${it.kind}-${it.sourceId}-${it.streamKey}" }) { entry ->
                            WideCard(
                                title = entry.parentTitle ?: entry.title,
                                subtitle = when {
                                    entry.season != null && entry.episode != null ->
                                        "S${entry.season}E${entry.episode} · ${entry.title}"
                                    else -> null
                                },
                                artwork = entry.poster,
                                progress = if (entry.durationMs > 0) {
                                    entry.positionMs.toFloat() / entry.durationMs
                                } else {
                                    null
                                },
                                onClick = { vodViewModel.resume(entry) { onPlay() } },
                            )
                        }
                    }
                }
            }
        }

        if (favoriteChannels.isNotEmpty()) {
            item(key = "favorites") {
                Column {
                    SectionHeader(stringResource(R.string.home_favorites))
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.tvFocusGroup(),
                    ) {
                        favoriteChannels.forEach { channel ->
                            ChannelRow(
                                name = channel.name,
                                number = channel.number,
                                logo = channel.logo,
                                isFavorite = true,
                                hasCatchup = channel.catchupDays > 0,
                                onClick = { liveViewModel.play(channel) { onPlay() } },
                                onLongClick = { liveViewModel.toggleFavorite(channel) },
                            )
                        }
                    }
                }
            }
        }

        if (continueWatching.isEmpty() && favoriteChannels.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.state_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
