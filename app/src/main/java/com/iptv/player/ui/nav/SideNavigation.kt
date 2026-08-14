package com.iptv.player.ui.nav

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.iptv.player.ui.components.Sizes

/**
 * Left-hand navigation rail.
 *
 * It expands when any of its items has focus and collapses to icons otherwise,
 * which is the Android TV convention and the reason it is a rail rather than a
 * top tab bar: on a 16:9 screen the vertical axis is the scarce one, and a
 * collapsed rail gives the content back ~150dp of width without hiding where
 * you are.
 *
 * It is not built on Leanback's `BrowseSupportFragment`. That class carries
 * the whole Leanback view stack, and Fire TV does not use Leanback at all —
 * the brief calls out needing one UI that satisfies both platforms, so this is
 * plain Compose with the Leanback *conventions* (rail, focus-expands, content
 * to the right) implemented directly.
 */
@Composable
fun SideNavigation(
    current: Section,
    onSelect: (Section) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxHeight()
            .width(if (expanded) Sizes.sideNavWidth else Sizes.sideNavCollapsedWidth)
            .animateContentSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 20.dp, horizontal = 12.dp)
            .onFocusChanged { expanded = it.hasFocus }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Section.entries.forEach { section ->
            NavItem(
                section = section,
                selected = section == current,
                expanded = expanded,
                onClick = { onSelect(section) },
            )
            // A small break above the housekeeping sections so the eye can
            // separate "watch something" from "configure something".
            if (section == Section.Search) Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NavItem(
    section: Section,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(section.labelRes)
    val shape = RoundedCornerShape(16.dp)

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            // Selected uses the brand green outright, not just a lighter
            // surface — the rail is the one place "where am I" and "what's
            // focused" could otherwise be confused, since both live in the
            // same narrow column.
            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, MaterialTheme.colorScheme.secondary), shape = shape)
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(section.glyph, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            if (expanded) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
        }
    }
}
