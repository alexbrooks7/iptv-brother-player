package com.iptv.player.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.iptv.player.R
import com.iptv.player.ui.theme.AccentOrange
import com.iptv.player.ui.theme.LiveRed
import com.iptv.player.ui.theme.Scrim

// Rounder than the previous 10–12dp, matching the button/field radius in
// Controls.kt so cards and controls read as one shape language rather than
// two different rounding conventions competing on the same screen.
private val CardShape = RoundedCornerShape(18.dp)
private val PosterShape = RoundedCornerShape(16.dp)

/** Declared once so the layout box and the decode target cannot drift apart. */
private val ChannelLogoSize = DpSize(48.dp, 34.dp)

/**
 * A row in the live channel list: number, logo, name, and the current
 * programme when guide data exists for it.
 *
 * Deliberately a row and not a tile. A poster grid is right for VOD, where the
 * artwork *is* the information, but a live line-up is scanned by name and
 * number and can run to thousands of entries — a grid triples the number of
 * D-pad presses to reach anything and hides the now-playing text that makes
 * the list useful.
 */
@Composable
fun ChannelRow(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    number: Int? = null,
    logo: String? = null,
    nowPlaying: String? = null,
    progress: Float? = null,
    isFavorite: Boolean = false,
    hasCatchup: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val favouriteLabel = stringResource(
        if (isFavorite) R.string.cd_favorite_on else R.string.cd_favorite_off,
        name,
    )
    val catchupLabel = if (hasCatchup) stringResource(R.string.player_timeshift) else ""
    // Built once per row rather than on every recomposition. The concatenation
    // itself is cheap, but it happens inside the `semantics` lambda, which
    // Compose re-invokes whenever the modifier chain is rebuilt — and a
    // modifier chain that allocates a new String is a modifier chain that is
    // never equal to the previous one, so the node is invalidated every time.
    val description = remember(name, nowPlaying, favouriteLabel, catchupLabel) {
        buildString {
            append(name).append(". ")
            nowPlaying?.let { append(it).append(' ') }
            append(favouriteLabel)
            if (catchupLabel.isNotEmpty()) append(' ').append(catchupLabel)
        }
    }

    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .height(Sizes.channelRowHeight)
            .semantics { contentDescription = description },
        shape = ClickableSurfaceDefaults.shape(CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                BorderStroke(3.dp, MaterialTheme.colorScheme.secondary),
                shape = CardShape,
            )
        ),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (number != null) {
                Text(
                    number.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.width(40.dp),
                )
            }
            Artwork(
                url = logo,
                name = name,
                modifier = Modifier.size(ChannelLogoSize),
                shape = RoundedCornerShape(6.dp),
                targetSize = ChannelLogoSize,
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
            ) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (nowPlaying != null) {
                    Text(
                        nowPlaying,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (hasCatchup) {
                // A glyph, not the word: the row is the width-constrained part
                // of the screen and "Catch-up" spelled out steals space from
                // the channel name. The full label appears in the detail panel
                // alongside the retention window, and the row's spoken
                // description carries it for TalkBack.
                Badge("⟲", AccentOrange, Modifier.padding(end = 8.dp))
            }
            if (isFavorite) {
                // A star glyph rather than a vector asset: it is legible at
                // this size on every TV font, needs no drawable per density,
                // and the row already carries a spoken favourite state.
                Text("★", style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
            if (progress != null) {
                Box(
                    Modifier
                        .padding(start = 12.dp)
                        .width(56.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxSize()
                            .background(LiveRed)
                    )
                }
            }
        }
    }
}

/**
 * Poster tile for movies and series.
 *
 * The focused state scales the card up rather than only recolouring it. On a
 * dense poster grid this is the fastest cue to read from across a room — the
 * eye catches the size change before it resolves any border colour.
 */
@Composable
fun PosterCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    poster: String? = null,
    subtitle: String? = null,
    progress: Float? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "posterScale")
    val posterDescription = stringResource(R.string.cd_poster, title)

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(Sizes.posterWidth)
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = posterDescription },
        shape = ClickableSurfaceDefaults.shape(PosterShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                BorderStroke(3.dp, MaterialTheme.colorScheme.secondary),
                shape = PosterShape,
            )
        ),
    ) {
        Column {
            Box {
                Artwork(
                    url = poster,
                    name = title,
                    modifier = Modifier
                        .width(Sizes.posterWidth)
                        .height(Sizes.posterHeight),
                    shape = PosterShape,
                    targetSize = DpSize(Sizes.posterWidth, Sizes.posterHeight),
                )
                if (progress != null && progress > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(5.dp)
                            .background(Scrim)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Wide 16:9 card used on the home screen's Continue Watching row. */
@Composable
fun WideCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artwork: String? = null,
    subtitle: String? = null,
    progress: Float? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "wideScale")

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(304.dp)
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = "$title ${subtitle.orEmpty()}" },
        shape = ClickableSurfaceDefaults.shape(CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.secondary), shape = CardShape)
        ),
    ) {
        Box(Modifier.height(171.dp)) {
            Artwork(url = artwork, name = title, modifier = Modifier.fillMaxSize(), shape = CardShape)
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Scrim)))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                if (progress != null) {
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.25f))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
    }
}
