package com.iptv.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.iptv.player.ui.theme.ErrorRed

/**
 * A screen-filling state with a headline, an explanation and an optional
 * action.
 *
 * The brief asks for graceful empty and offline states rather than blank
 * screens, and this is the single component all of them go through — which is
 * also what stops one of them being forgotten. Note the action is a real
 * focusable button: a TV user cannot dismiss or work around a dead end without
 * something to press.
 */
@Composable
fun StateMessage(
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // Modest padding, not a generous 10-foot margin: this component is
            // also used inside narrow columns (the live list is one of three
            // panes), and a large fixed inset there leaves so little width
            // that the message wraps to one character per line.
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = if (isError) ErrorRed else MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            if (body != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(28.dp))
                TvButton(text = actionLabel, onClick = onAction, autoFocus = true)
            }
        }
    }
}

@Composable
fun LoadingState(label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(bottom = 12.dp),
    )
}

/**
 * Whether [Artwork] should actually fetch and decode images right now.
 *
 * Set to false by a list while it is being scrolled. Artwork is the most
 * expensive thing in a channel row by a wide margin — removing it outright took
 * the UI thread from 39.9 ms to 25.3 ms per frame on a 2 GB Android TV box —
 * and every image loaded during a scroll is one the user never sees, because
 * the row it belongs to has already left the screen by the time it decodes.
 *
 * This is only acceptable because the fallback is genuinely useful: rows still
 * show initials over a tinted block, so a fast scroll looks deliberate rather
 * than broken, and the artwork arrives the moment the list stops moving.
 */
val LocalArtworkLoading = compositionLocalOf { true }

/**
 * Channel logo, poster or still, with a text fallback.
 *
 * The fallback is not decoration. IPTV logo URLs are dead on arrival at a
 * remarkable rate — the provider's image host expires, or the playlist points
 * at a hotlink-protected CDN — and a grid of empty rectangles is unusable at
 * ten feet. Initials over a tinted block keeps every tile identifiable and
 * distinguishable even when nothing loads.
 */
@Composable
fun Artwork(
    url: String?,
    name: String,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
    /**
     * The size this artwork will be drawn at, when the caller knows it.
     *
     * Worth passing wherever it is known. Without it Coil has to wait for the
     * layout pass to discover how big the target is before it can even look in
     * the memory cache, so a logo the device already holds in memory still
     * arrives a frame or more late and drags a recomposition along behind it.
     * With it, a cache hit resolves during composition and the row is drawn
     * once, correctly, first time.
     */
    targetSize: DpSize? = null,
) {
    // Remembered because this sits in the channel row, which is drawn a dozen
    // times per visible screen and recomposed whenever the list scrolls.
    // `initials()` splits, maps and filters — three list allocations for two
    // characters that only change when the name does.
    val initials = remember(name) { name.initials() }
    val context = LocalContext.current
    val density = LocalDensity.current

    // Built once per URL instead of once per composition.
    //
    // Passing a bare String to AsyncImage makes Coil construct a fresh
    // ImageRequest on every composition — a large object with a full set of
    // defaults. Measuring this in isolation showed no improvement beyond run
    // to run noise, so it is kept on principle rather than on evidence: it
    // removes a per-row allocation and it is what lets [targetSize] be
    // specified at all, which is the part that does matter. The cost that
    // actually dominated was the image pipeline itself, and that is addressed
    // by [LocalArtworkLoading] rather than here.
    val request = remember(url, targetSize) {
        ImageRequest.Builder(context)
            .data(url)
            .apply {
                targetSize?.let {
                    with(density) { size(it.width.roundToPx(), it.height.roundToPx()) }
                }
            }
            // Logos are wildly inconsistent in aspect ratio and are drawn into
            // a fixed box; an inexact match is both fine and far more likely to
            // reuse a bitmap already in the cache.
            .precision(Precision.INEXACT)
            .build()
    }

    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text(
            initials,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (!url.isNullOrBlank() && LocalArtworkLoading.current) {
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Up to two initials, skipping the noise words playlists prefix names with. */
private fun String.initials(): String {
    val words = split(' ', '-', '_', '|', ':')
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.length > 1 && it.first().isLetterOrDigit() }
    return when {
        words.isEmpty() -> take(2).uppercase()
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].first().toString() + words[1].first()).uppercase()
    }
}

/** Small pill used for LIVE, catch-up availability and quality markers. */
@Composable
fun Badge(text: String, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.background,
            maxLines = 1,
        )
    }
}

/** Single-line label + value row, used throughout detail screens. */
@Composable
fun MetadataRow(items: List<String>, modifier: Modifier = Modifier) {
    val visible = items.filter { it.isNotBlank() }
    if (visible.isEmpty()) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        visible.forEachIndexed { index, item ->
            if (index > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 10.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }
            Text(
                item,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun FullWidthDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.border)
    )
}

/** Arrangement constant used by every list on the screen, kept in one place. */
val ListSpacing = Arrangement.spacedBy(8.dp)
