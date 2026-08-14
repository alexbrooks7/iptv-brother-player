package com.iptv.player.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.iptv.player.R
import com.iptv.player.data.db.GuideChannel
import com.iptv.player.data.db.ProgrammeEntity
import com.iptv.player.data.prefs.Settings
import com.iptv.player.ui.components.Artwork
import com.iptv.player.ui.components.Chip
import com.iptv.player.ui.components.StateMessage
import com.iptv.player.ui.components.TvButton
import com.iptv.player.ui.theme.LiveRed
import com.iptv.player.ui.util.TimeFormatter
import com.iptv.player.ui.util.tvFocusGroup
import com.iptv.player.ui.vm.GuideViewModel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Minutes of listings on screen at once. */
private const val VISIBLE_MINUTES = 150

private val ChannelLabelWidth = 250.dp
private val RowHeight = 74.dp

/**
 * Timeline programme guide: channels down, time across.
 *
 * **Why the timeline pages rather than scrolls.** The obvious implementation
 * is a horizontally scrollable grid with every row's scroll position tied
 * together. It is also the wrong one for a remote control: a D-pad has no
 * scroll gesture, so horizontal movement has to come from focus travel, and
 * keeping N lazy rows' scroll offsets synchronised with a focus target that
 * moves between them produces exactly the laggy, drifting focus the brief
 * warns about. Here, left/right moves focus between programme blocks that are
 * already laid out, and the window jumps a half hour at a time when you reach
 * its edge. Everything on screen is placed by simple arithmetic, so focus
 * movement stays at one frame regardless of playlist size.
 */
@Composable
fun GuideScreen(
    viewModel: GuideViewModel,
    settings: Settings,
    onPlay: () -> Unit,
) {
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val programmes by viewModel.programmes.collectAsStateWithLifecycle()
    val windowStart by viewModel.windowStart.collectAsStateWithLifecycle()
    val hasEpg by viewModel.hasEpgData.collectAsStateWithLifecycle()
    val selected by viewModel.selectedProgramme.collectAsStateWithLifecycle()

    val formatter = remember(settings) { TimeFormatter(settings.use24HourTime, settings.epgOffsetMinutes) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Tell the ViewModel which rows are on screen so it fetches listings for
    // those channels only — see GuideViewModel for why that matters.
    LaunchedEffect(listState, channels) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .collect { visible ->
                if (visible.isNotEmpty()) viewModel.onVisibleRows(visible.first(), visible.last())
            }
    }

    if (channels.isEmpty() || !hasEpg) {
        StateMessage(
            title = stringResource(R.string.guide_title),
            body = stringResource(R.string.guide_no_data),
        )
        return
    }

    selected?.let { programme ->
        val channel = channels.firstOrNull { it.tvgId == programme.channelId }
        ProgrammeDetail(
            programme = programme,
            channelName = channel?.name.orEmpty(),
            formatter = formatter,
            onWatch = {
                channel?.let { viewModel.playChannel(it.id) { onPlay() } }
                viewModel.selectProgramme(null)
            },
            onCatchup = {
                val row = channel ?: return@ProgrammeDetail
                scope.launch {
                    if (viewModel.playCatchup(row.id, programme)) onPlay()
                    viewModel.selectProgramme(null)
                }
            },
            onDismiss = { viewModel.selectProgramme(null) },
        )
        return
    }

    val windowEnd = windowStart + TimeUnit.MINUTES.toMillis(VISIBLE_MINUTES.toLong())

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        GuideHeader(
            windowStart = windowStart,
            formatter = formatter,
            onNow = viewModel::jumpToNow,
            onDay = viewModel::jumpToDay,
            onShift = { minutes -> viewModel.scrollTimeBy(TimeUnit.MINUTES.toMillis(minutes.toLong())) },
        )

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val timelineWidth = maxWidth - ChannelLabelWidth
            val dpPerMinute = timelineWidth / VISIBLE_MINUTES

            Column {
                TimeRuler(
                    windowStart = windowStart,
                    dpPerMinute = dpPerMinute,
                    formatter = formatter,
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().tvFocusGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(channels, key = { it.id }) { channel ->
                        GuideRow(
                            channel = channel,
                            programmes = channel.tvgId?.let { programmes[it] }.orEmpty(),
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            dpPerMinute = dpPerMinute,
                            formatter = formatter,
                            onProgramme = viewModel::selectProgramme,
                            onChannel = { viewModel.playChannel(channel.id) { onPlay() } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideHeader(
    windowStart: Long,
    formatter: TimeFormatter,
    onNow: () -> Unit,
    onDay: (Int) -> Unit,
    onShift: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            formatter.day(windowStart),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(ChannelLabelWidth - 10.dp),
        )
        Chip(label = "◀", selected = false, onClick = { onShift(-30) })
        Chip(label = stringResource(R.string.guide_now), selected = false, onClick = onNow)
        Chip(label = "▶", selected = false, onClick = { onShift(30) })

        Chip(label = stringResource(R.string.guide_yesterday), selected = false, onClick = { onDay(-1) })
        Chip(label = stringResource(R.string.guide_today), selected = false, onClick = { onDay(0) })
        Chip(label = stringResource(R.string.guide_tomorrow), selected = false, onClick = { onDay(1) })
        (2..6).forEach { offset ->
            Chip(label = "+$offset", selected = false, onClick = { onDay(offset) })
        }
    }
}

/** Half-hour tick labels across the top of the timeline. */
@Composable
private fun TimeRuler(windowStart: Long, dpPerMinute: Dp, formatter: TimeFormatter) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Box(Modifier.width(ChannelLabelWidth))
        repeat(VISIBLE_MINUTES / 30) { index ->
            Text(
                formatter.time(windowStart + TimeUnit.MINUTES.toMillis(index * 30L)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(dpPerMinute * 30),
            )
        }
    }
}

@Composable
private fun GuideRow(
    channel: GuideChannel,
    programmes: List<ProgrammeEntity>,
    windowStart: Long,
    windowEnd: Long,
    dpPerMinute: Dp,
    formatter: TimeFormatter,
    onProgramme: (ProgrammeEntity) -> Unit,
    onChannel: () -> Unit,
) {
    Row(Modifier.height(RowHeight)) {
        ChannelLabel(channel = channel, onClick = onChannel)

        val visible = programmes
            .filter { it.startUtc < windowEnd && it.endUtc > windowStart }
            .sortedBy { it.startUtc }

        if (visible.isEmpty()) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .padding(start = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    stringResource(R.string.live_no_programme),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
            return@Row
        }

        // Blocks are laid out sequentially with explicit gap spacers rather
        // than absolutely positioned, so that D-pad focus walks them in time
        // order for free.
        LazyRow(Modifier.fillMaxHeight()) {
            var cursor = windowStart
            visible.forEachIndexed { index, programme ->
                val gapMinutes = ((programme.startUtc - cursor) / 60_000L).toInt()
                if (gapMinutes > 0) {
                    item(key = "gap-${channel.id}-$index") {
                        Box(Modifier.width(dpPerMinute * gapMinutes))
                    }
                }
                val from = maxOf(programme.startUtc, windowStart)
                val to = minOf(programme.endUtc, windowEnd)
                val minutes = ((to - from) / 60_000L).toInt().coerceAtLeast(1)
                cursor = to

                item(key = "p-${programme.id}") {
                    ProgrammeBlock(
                        programme = programme,
                        width = dpPerMinute * minutes,
                        formatter = formatter,
                        onClick = { onProgramme(programme) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelLabel(channel: GuideChannel, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(ChannelLabelWidth).fillMaxHeight(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                BorderStroke(3.dp, MaterialTheme.colorScheme.secondary),
                shape = RoundedCornerShape(8.dp),
            )
        ),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            channel.number?.let {
                Text(it.toString(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(44.dp))
            }
            Artwork(
                url = channel.logo,
                name = channel.name,
                modifier = Modifier.width(46.dp).height(32.dp),
                shape = RoundedCornerShape(5.dp),
            )
            Text(
                channel.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun ProgrammeBlock(
    programme: ProgrammeEntity,
    width: Dp,
    formatter: TimeFormatter,
    onClick: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val onAir = now in programme.startUtc until programme.endUtc

    Surface(
        onClick = onClick,
        modifier = Modifier
            // A one-minute trailer would otherwise be a sliver too small to
            // focus with a remote; 64dp is the floor at which a block is still
            // hittable and its title still partially readable.
            .width(width.coerceAtLeast(64.dp))
            .fillMaxHeight()
            .padding(start = 4.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (onAir) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                BorderStroke(3.dp, MaterialTheme.colorScheme.secondary),
                shape = RoundedCornerShape(8.dp),
            )
        ),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                programme.title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatter.time(programme.startUtc),
                style = MaterialTheme.typography.labelSmall,
                color = if (onAir) LiveRed else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Detail sheet shown when a programme block is selected. */
@Composable
private fun ProgrammeDetail(
    programme: ProgrammeEntity,
    channelName: String,
    formatter: TimeFormatter,
    onWatch: () -> Unit,
    onCatchup: () -> Unit,
    onDismiss: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val isPast = programme.endUtc < now
    val isOnAir = now in programme.startUtc until programme.endUtc

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 64.dp, vertical = 48.dp)
    ) {
        Text(channelName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(programme.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))
        Text(
            formatter.range(programme.startUtc, programme.endUtc) +
                (programme.category?.let { "  ·  $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            programme.description ?: stringResource(R.string.detail_no_description),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 20.dp),
        )

        Row(Modifier.padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (isOnAir) {
                TvButton(text = stringResource(R.string.guide_watch), onClick = onWatch, autoFocus = true)
            }
            if (isPast) {
                TvButton(
                    text = stringResource(R.string.guide_watch_from_start),
                    onClick = onCatchup,
                    autoFocus = !isOnAir,
                )
            }
            TvButton(
                text = stringResource(R.string.action_close),
                primary = false,
                onClick = onDismiss,
                autoFocus = !isOnAir && !isPast,
            )
        }
    }
}
