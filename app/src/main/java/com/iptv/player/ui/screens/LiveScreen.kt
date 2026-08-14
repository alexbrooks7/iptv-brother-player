package com.iptv.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.iptv.player.R
import com.iptv.player.data.db.ChannelEntity
import com.iptv.player.data.prefs.Settings
import com.iptv.player.data.prefs.pinMatches
import com.iptv.player.ui.components.Artwork
import com.iptv.player.ui.components.Badge
import com.iptv.player.ui.components.ChannelRow
import com.iptv.player.ui.components.LocalArtworkLoading
import com.iptv.player.ui.components.SettingRow
import com.iptv.player.ui.components.StateMessage
import com.iptv.player.ui.theme.LiveRed
import com.iptv.player.ui.util.TimeFormatter
import com.iptv.player.ui.util.requestFocusWhenReady
import com.iptv.player.ui.util.tvFocusGroup
import com.iptv.player.ui.vm.ChannelFilter
import com.iptv.player.ui.vm.LiveViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Live TV: categories on the left, channels in the middle, guide detail on the
 * right.
 *
 * The three-column split is what makes a 10,000-channel playlist navigable
 * with four arrow keys. Left/right moves between *kinds* of choice (which
 * group, which channel) and up/down moves within one, so the user never has to
 * think about where focus will land — which is the single biggest determinant
 * of whether a TV app feels fast.
 */
@Composable
fun LiveScreen(
    viewModel: LiveViewModel,
    settings: Settings,
    onPlay: () -> Unit,
    onAddPlaylist: () -> Unit,
    hasSource: Boolean,
) {
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val selected by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val favorites by viewModel.favoriteKeys.collectAsStateWithLifecycle()
    val lockedKeys by viewModel.lockedFilterKeys.collectAsStateWithLifecycle()

    // The focused channel and its guide data are deliberately *not* read here.
    //
    // Focus changes on every single D-pad press. Reading that state in this
    // scope makes this composable — and therefore the entire three-column
    // layout, including the LazyColumn holding thousands of channels — the
    // recomposition scope for a keypress. The reads live inside
    // ChannelDetailPanel instead, which is the only part that actually
    // changes, so a keypress recomposes one panel rather than a screen.
    //
    // Worth about 8% of UI-thread frame time when measured on a 2 GB Android
    // TV box scrolling a 4,091-channel list (43.6 ms to 39.9 ms): real, but
    // far smaller than it looks, because strong skipping was already stopping
    // the list itself from recomposing. The dominant cost on that screen was
    // per-row artwork — see LocalArtworkLoading.
    var pendingLockedFilter by remember { mutableStateOf<ChannelFilter?>(null) }

    if (!hasSource) {
        StateMessage(
            title = stringResource(R.string.home_empty_title),
            body = stringResource(R.string.home_empty_body),
            actionLabel = stringResource(R.string.home_empty_action),
            onAction = onAddPlaylist,
        )
        return
    }

    pendingLockedFilter?.let { filter ->
        PinPrompt(
            title = stringResource(R.string.pin_enter),
            verify = { pin -> settings.pinMatches(pin) },
            onAccepted = {
                viewModel.unlock(filter)
                viewModel.selectFilter(filter)
                pendingLockedFilter = null
            },
            onCancel = { pendingLockedFilter = null },
        )
        return
    }

    // Proportional rather than fixed widths. The three panes have to share
    // whatever the navigation rail leaves, and fixed dp values that look right
    // on a 1080p panel squeeze the middle column badly once the rail expands
    // or the user raises the interface scale — channel names then truncate to
    // two characters, which is the one thing this column exists to show. The
    // channel list gets the largest share because it is the pane being read.
    // Held across recompositions so the row callbacks stay referentially equal.
    // A fresh lambda every time would defeat the skipping that the split above
    // is there to enable — the list would recompose anyway, just for a
    // different reason.
    val onFocus = remember(viewModel) { viewModel::onChannelFocused }
    val onToggleFavorite: (ChannelEntity) -> Unit = remember(viewModel) {
        { channel -> viewModel.toggleFavorite(channel) }
    }
    val onPlayChannel: (ChannelEntity) -> Unit = remember(viewModel, onPlay) {
        { channel -> viewModel.play(channel) { onPlay() } }
    }
    val onSelectFilter: (ChannelFilter) -> Unit = remember(viewModel) {
        { filter ->
            if (viewModel.isLocked(filter)) pendingLockedFilter = filter else viewModel.selectFilter(filter)
        }
    }

    Row(Modifier.fillMaxSize()) {
        CategoryColumn(
            filters = filters,
            selectedKey = selected.key,
            lockedKeys = lockedKeys,
            onSelect = onSelectFilter,
            modifier = Modifier.weight(0.26f),
        )

        // Keyed on the category so switching one builds a *new* channel column
        // rather than re-pointing the old one at different data.
        //
        // Without this the scroll position carried across categories: going
        // from a 4,000-channel group to a 20-channel one left the list scrolled
        // somewhere past the end of the new content, so the first thing the
        // viewer saw was a blank column they had to scroll up out of. The
        // comment on the list state below used to claim this reset happened;
        // now it does. It also guarantees no stale per-row state survives into
        // a list that shares none of the same rows.
        key(selected.key) {
            ChannelColumn(
                channels = channels,
                favorites = favorites,
                selectedKey = selected.key,
                onFocus = onFocus,
                onPlay = onPlayChannel,
                onToggleFavorite = onToggleFavorite,
                modifier = Modifier.weight(0.42f),
            )
        }

        ChannelDetailPanel(
            viewModel = viewModel,
            formatter = TimeFormatter(settings.use24HourTime, settings.epgOffsetMinutes),
            modifier = Modifier.weight(0.32f),
        )
    }
}

@Composable
private fun CategoryColumn(
    filters: List<ChannelFilter>,
    selectedKey: String,
    lockedKeys: Set<String>,
    onSelect: (ChannelFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .tvFocusGroup(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(filters, key = { it.key }) { filter ->
            SettingRow(
                title = filter.label(),
                onClick = { onSelect(filter) },
                // The padlock is the only signal that a PIN is coming, so it
                // has to be on the row itself rather than in a tooltip.
                value = when {
                    filter.key in lockedKeys -> "🔒"
                    filter.key == selectedKey -> "●"
                    else -> null
                },
            )
        }
    }
}

@Composable
private fun ChannelFilter.label(): String = when (this) {
    ChannelFilter.Favorites -> stringResource(R.string.live_favorites)
    ChannelFilter.Recent -> stringResource(R.string.live_recent)
    ChannelFilter.All -> stringResource(R.string.live_all_channels)
    ChannelFilter.Uncategorised -> stringResource(R.string.live_uncategorised)
    is ChannelFilter.Category -> entity.name
}

@Composable
private fun ChannelColumn(
    channels: List<ChannelEntity>,
    favorites: Set<String>,
    selectedKey: String,
    onFocus: (ChannelEntity) -> Unit,
    onPlay: (ChannelEntity) -> Unit,
    onToggleFavorite: (ChannelEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on the selected category so the scroll position resets when the
    // list underneath it is replaced.
    //
    // This previously claimed to be keyed but was not, and the mismatch caused
    // the focus bug it was supposed to prevent: switching from a 4,000-channel
    // Scroll position resets when the category changes, because the caller
    // wraps this whole column in `key(selectedKey)` — so this is a new state
    // object per category rather than one shared across all of them.
    val listState = rememberLazyListState()

    if (channels.isEmpty()) {
        StateMessage(title = stringResource(R.string.live_no_channels), modifier = modifier)
        return
    }

    // Put the cursor somewhere as soon as this screen exists.
    //
    // Nothing else does. Opening a channel destroys this screen's composition
    // — the player deliberately replaces it rather than stacking on top, to
    // keep the surface alive — so coming back builds it fresh with no focus
    // anywhere. Measured with `uiautomator dump`: zero nodes reporting
    // `focused="true"` after returning from playback, recovering only on the
    // next keypress. The visible effect is that the remote ignores you once
    // after every channel you watch, which reads as the app being stuck.
    //
    // The requester sits on the list container rather than on a row: focusing
    // a focus group enters it and lands on its first focusable child, which
    // avoids attaching a FocusRequester to a lazy item that may not be
    // composed yet.
    val enterFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { enterFocus.requestFocusWhenReady() }

    // Artwork is suspended while the list is moving; see [LocalArtworkLoading].
    // The short delay before turning it back on is what stops a held-down
    // D-pad from re-enabling it in the gap between one keypress and the next,
    // which would reinstate exactly the cost this avoids.
    val artworkEnabled by produceState(true, listState) {
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling) {
                value = false
            } else {
                delay(SCROLL_SETTLE_MS)
                value = true
            }
        }
    }

    CompositionLocalProvider(LocalArtworkLoading provides artworkEnabled) {
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxHeight()
                .padding(horizontal = 16.dp)
                .focusRequester(enterFocus)
                .tvFocusGroup(),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "header-$selectedKey") {
                Text(
                    stringResource(R.string.live_channel_count, channels.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            itemsIndexed(channels, key = { _, channel -> channel.id }) { index, channel ->
                ChannelRow(
                    name = channel.name,
                    number = channel.number,
                    logo = channel.logo,
                    isFavorite = channel.streamKey in favorites,
                    hasCatchup = channel.catchupDays > 0,
                    onClick = { onPlay(channel) },
                    // Long-press is the only spare gesture on a TV remote, and
                    // favouriting is the one action worth binding to it — the
                    // alternative is a context menu that costs three more presses.
                    onLongClick = { onToggleFavorite(channel) },
                    modifier = Modifier.onFocusChanged { if (it.isFocused) onFocus(channel) },
                )
            }
        }
    }
}

/** How long the list must be still before artwork loading resumes. */
private const val SCROLL_SETTLE_MS = 150L

/**
 * The guide panel for whatever channel currently has focus.
 *
 * Takes the view model rather than the focused channel, so that the state read
 * happens *here*. That is the whole point: focus changes on every D-pad press,
 * and whichever composable reads it becomes the scope Compose re-runs. Reading
 * it in this leaf means a keypress recomposes this panel; reading it in
 * [LiveScreen] meant a keypress recomposed the channel list as well.
 */
@Composable
private fun ChannelDetailPanel(
    viewModel: LiveViewModel,
    formatter: TimeFormatter,
    modifier: Modifier = Modifier,
) {
    val focusedState by viewModel.focusedChannel.collectAsStateWithLifecycle()
    val nowNext by viewModel.focusedNowNext.collectAsStateWithLifecycle()
    val now = nowNext.now
    val next = nowNext.next

    val channel = focusedState
    if (channel == null) {
        Box(modifier.fillMaxHeight())
        return
    }

    Column(
        modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp)
            .focusGroup(),
    ) {
        Artwork(
            url = channel.logo,
            name = channel.name,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Text(
            channel.name,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 16.dp),
        )
        channel.groupTitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (channel.catchupDays > 0) {
            Badge(
                stringResource(R.string.player_timeshift) + " · ${channel.catchupDays}d",
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        if (now == null && next == null) {
            Text(
                stringResource(R.string.live_no_programme),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
            return@Column
        }

        now?.let { programme ->
            Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(stringResource(R.string.live_now), LiveRed)
                Text(
                    formatter.range(programme.startUtc, programme.endUtc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Text(
                programme.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            ProgressLine(
                fraction = programme.progressNow(),
                modifier = Modifier.padding(top = 10.dp),
            )
            programme.description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        next?.let { programme ->
            Text(
                stringResource(R.string.live_next),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                "${formatter.time(programme.startUtc)}  ${programme.title}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxSize()
                .background(LiveRed)
        )
    }
}

private fun com.iptv.player.data.db.ProgrammeEntity.progressNow(): Float {
    val span = (endUtc - startUtc).toFloat()
    if (span <= 0f) return 0f
    return ((System.currentTimeMillis() - startUtc) / span).coerceIn(0f, 1f)
}
