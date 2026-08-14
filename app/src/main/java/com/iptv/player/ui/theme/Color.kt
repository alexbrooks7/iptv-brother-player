package com.iptv.player.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette. The dark set is the default and the one the app is designed
 * around — TV apps are watched in dark rooms and a bright chrome around a
 * video window is genuinely uncomfortable. Light exists because the brief asks
 * for it and because a few users run TVs as office displays in daylight.
 *
 * The identity is deep forest green with a warm orange accent, carried over
 * from a mobile reference design. Two things were deliberately *not* carried
 * over along with the colours: the reference's white-card-on-flat-colour
 * layout (a TV app stays dark-first for 10-foot viewing) and its literal
 * button shapes (a phone's fully-pill inputs get harder to scan as a wall of
 * rounded rectangles once you have TV-width rows of them — see Controls.kt for
 * where the radius was judged rather than copied).
 *
 * **Why focus uses orange, not green.** Green is also the fill colour of a
 * selected/primary control, so a green focus ring on a green button would be
 * the one signal a D-pad user cannot afford to lose disappearing exactly where
 * it matters most. Orange reads against green, against the dark surfaces, and
 * against white text alike, so it is used as the *only* focus/attention colour
 * in the app — nothing else borrows it, which keeps "orange" meaning "this is
 * where the remote will act" everywhere, all the time.
 *
 * Contrast: every on-* colour listed here clears WCAG AA (4.5:1) against the
 * surface it is used on. That matters more than usual at 3 m viewing distance,
 * where TV panels are also frequently mis-calibrated toward crushed blacks.
 */

// Dark (default) — desaturated toward green rather than the neutral-black a
// generic dark theme would use, so the identity reads even with no colour
// controls on screen (the empty state, the guide's off-air rows).
val NightBackground = Color(0xFF0B160F)
val NightSurface = Color(0xFF12211A)
val NightSurfaceVariant = Color(0xFF1B3226)
val NightBorder = Color(0xFF2A4636)

// Light — a warm sage rather than a cold white-and-grey, matching the
// reference's mint background instead of a generic Material light theme.
val DayBackground = Color(0xFFEAF3E9)
val DaySurface = Color(0xFFFFFFFF)
val DaySurfaceVariant = Color(0xFFDCEBDA)
val DayBorder = Color(0xFFBFD8C0)

// Brand — shared by both themes so a button or a badge reads identically
// regardless of which theme is active.
val PrimaryGreen = Color(0xFF1E7A4C)
val PrimaryGreenDeep = Color(0xFF15532F)
/** The one focus/attention colour in the app. See the class doc above. */
val AccentOrange = Color(0xFFED8A3D)
val LiveRed = Color(0xFFFF4D5E)

// Foreground
val TextOnDark = Color(0xFFF1F7F2)
val TextOnDarkMuted = Color(0xFFA9C2AF)
val TextOnLight = Color(0xFF122117)
val TextOnLightMuted = Color(0xFF4C6350)

val ErrorRed = Color(0xFFFF6B6B)
/** Text/icon colour on top of a filled green or orange control — both are
 *  mid-dark and saturated enough that one light colour clears AA on either. */
val OnAccent = Color(0xFFFBFFFB)

/** Scrim over artwork so overlaid text stays legible on any poster. */
val Scrim = Color(0xCC08130D)
