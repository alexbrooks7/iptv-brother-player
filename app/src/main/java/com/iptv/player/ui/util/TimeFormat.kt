package com.iptv.player.ui.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guide and playback time formatting.
 *
 * Two things the brief asks for meet here. The 12/24-hour choice is a user
 * preference rather than a locale default because TV households do not always
 * match their region's convention and there is no system-wide TV setting worth
 * reading. The offset exists because IPTV providers publish XMLTV in whatever
 * timezone their panel is configured for — frequently not the viewer's, and
 * frequently not UTC either despite the `+0000` they stamp on it. Rather than
 * guess, the app lets the user nudge the whole guide until it lines up with
 * what is actually on screen, which is how every other player in this space
 * solves it.
 */
class TimeFormatter(
    private val use24Hour: Boolean,
    private val offsetMinutes: Int,
) {

    private val clock = SimpleDateFormat(if (use24Hour) "HH:mm" else "h:mm a", Locale.getDefault())
    private val dayAndClock = SimpleDateFormat(
        if (use24Hour) "EEE d MMM, HH:mm" else "EEE d MMM, h:mm a",
        Locale.getDefault(),
    )
    private val dayHeader = SimpleDateFormat("EEEE d MMMM", Locale.getDefault())

    /** Shifts a UTC instant by the user's guide offset. */
    fun adjust(utcMillis: Long): Long = utcMillis + offsetMinutes * 60_000L

    fun time(utcMillis: Long): String = clock.format(Date(adjust(utcMillis)))

    fun dateTime(utcMillis: Long): String = dayAndClock.format(Date(adjust(utcMillis)))

    fun day(utcMillis: Long): String = dayHeader.format(Date(adjust(utcMillis)))

    fun range(startUtc: Long, endUtc: Long): String = "${time(startUtc)} – ${time(endUtc)}"
}

/** `1:04:12` for anything over an hour, `4:12` below it. Used on the seek bar. */
fun formatDuration(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** "3 hours ago" / "yesterday" for the playlist list's last-updated line. */
fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val delta = now - millis
    val minutes = delta / 60_000
    val hours = delta / 3_600_000
    val days = delta / 86_400_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> if (hours == 1L) "1 hour ago" else "$hours hours ago"
        days < 2 -> "yesterday"
        days < 30 -> "$days days ago"
        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis))
    }
}
