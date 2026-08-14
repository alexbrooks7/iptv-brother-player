package com.iptv.player.ui.util

import androidx.annotation.StringRes
import com.iptv.player.R
import com.iptv.player.data.prefs.AspectMode

/**
 * Aspect-ratio helpers shared by Settings (which sets the default) and the
 * player (which overrides it for one stream).
 *
 * Both need to cycle through the modes on a single button, because there is no
 * room on a remote for five dedicated controls and no pointer to pick from a
 * menu quickly.
 */
fun AspectMode.next(): AspectMode = AspectMode.entries[(ordinal + 1) % AspectMode.entries.size]

@StringRes
fun AspectMode.labelRes(): Int = when (this) {
    AspectMode.FIT -> R.string.player_aspect_fit
    AspectMode.FILL -> R.string.player_aspect_fill
    AspectMode.ZOOM -> R.string.player_aspect_zoom
    AspectMode.STRETCH -> R.string.player_aspect_stretch
    AspectMode.ORIGINAL -> R.string.player_aspect_original
}
