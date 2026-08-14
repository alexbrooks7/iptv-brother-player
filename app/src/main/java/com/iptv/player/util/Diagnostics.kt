package com.iptv.player.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small in-memory ring buffer of network and playback events, shown under
 * Settings → Diagnostics log.
 *
 * This is the app's answer to the brief's "GMS-independent crash/analytics"
 * line, and it is a deliberate substitute for wiring in Sentry or similar
 * during v1. The reason is that the overwhelmingly common support case for an
 * IPTV player is not a crash — it is "channel 402 does not play and my
 * provider says it works". What resolves that is the HTTP status, the
 * redirect chain and the codec the device refused, on screen, readable off the
 * TV by the person holding the remote. A crash reporter would not have caught
 * any of it.
 *
 * Nothing here leaves the device and nothing is persisted. Credentials are
 * redacted by [redact] before anything is recorded, because Xtream stream URLs
 * carry the username and password in the path and users paste these logs into
 * forum threads.
 *
 * Adding Sentry later is a drop-in: implement the same three calls, keep the
 * redaction, and gate it behind an opt-in toggle. See README "Diagnostics".
 */
object Diagnostics {

    private const val TAG = "IPTV"
    const val CAPACITY = 300

    data class Event(
        val timestamp: Long,
        val level: Level,
        val area: String,
        val message: String,
    )

    enum class Level { INFO, WARN, ERROR }

    private val buffer = ArrayDeque<Event>(CAPACITY)
    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events

    fun info(area: String, message: String) = record(Level.INFO, area, message)
    fun warn(area: String, message: String) = record(Level.WARN, area, message)
    fun error(area: String, message: String, cause: Throwable? = null) =
        record(Level.ERROR, area, if (cause == null) message else "$message — ${cause.describe()}")

    @Synchronized
    private fun record(level: Level, area: String, message: String) {
        val event = Event(System.currentTimeMillis(), level, area, redact(message))
        if (buffer.size >= CAPACITY) buffer.removeFirst()
        buffer.addLast(event)
        _events.value = buffer.toList()
        when (level) {
            Level.INFO -> Log.i(TAG, "[$area] ${event.message}")
            Level.WARN -> Log.w(TAG, "[$area] ${event.message}")
            Level.ERROR -> Log.e(TAG, "[$area] ${event.message}")
        }
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _events.value = emptyList()
    }

    fun format(event: Event): String =
        "${TIME.format(Date(event.timestamp))}  ${event.level.name.padEnd(5)} ${event.area}: ${event.message}"

    /**
     * Strips credentials from anything that is about to be displayed. Covers
     * both shapes they appear in: `?username=x&password=y` query parameters,
     * and the `/live/<user>/<pass>/<id>.ts` path segments that Xtream stream
     * URLs use.
     */
    fun redact(text: String): String = text
        .replace(QUERY_CREDENTIAL, "$1=***")
        .replace(PATH_CREDENTIAL, "$1/***/***/")

    private val QUERY_CREDENTIAL = Regex("(?i)\\b(username|password|user|pass|token|auth)=[^&\\s\"]*")
    private val PATH_CREDENTIAL = Regex("(?i)/(live|movie|series|timeshift)/[^/\\s]+/[^/\\s]+/")

    private val TIME = SimpleDateFormat("HH:mm:ss", Locale.US)
}

/** Short, human-readable cause line — stack traces are useless on a TV screen. */
fun Throwable.describe(): String {
    val name = this::class.java.simpleName
    val detail = message?.takeIf { it.isNotBlank() }
    return if (detail == null) name else "$name: $detail"
}
