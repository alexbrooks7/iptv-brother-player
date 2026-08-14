package com.iptv.player.analytics

import android.content.Context
import android.util.Log
import com.iptv.player.BuildConfig
import com.iptv.player.util.Diagnostics
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

/**
 * Thin wrapper around PostHog so the rest of the app never touches the SDK
 * directly — call sites just say what happened, not which vendor is
 * listening. If no API key is configured (see local.properties), every call
 * here is a silent no-op instead of a crash, so a fresh clone still builds and
 * runs with analytics simply off.
 *
 * What this buys you, concretely:
 * - DAU/WAU/MAU and retention: free from `captureApplicationLifecycleEvents`
 *   below — PostHog derives these from "Application Opened" events without any
 *   extra code.
 * - Everything else — which sections people actually use, how playlists get
 *   added and whether their first sync succeeds, what fraction of playback
 *   attempts end in a surfaced error, whether the sharing prompt converts —
 *   comes from the explicit `event()` calls, kept to a deliberately short list
 *   at the handful of places in the app where something product-meaningful
 *   happens. This is not a call logged on every button press: a settings
 *   toggle or a channel-zap does not get one, because a dashboard that fires
 *   on everything tells you nothing about what matters.
 *
 * Screens are tracked manually (`screen()`) rather than via PostHog's built-in
 * Activity-based auto capture. This app is a single Activity with the whole UI
 * living in Compose (see MainScreen's sealed `Screen`), so Activity-based
 * tracking would only ever emit one "screen" for the entire app — there is no
 * second Activity for it to distinguish.
 */
object IptvAnalytics {

    private const val TAG = "IptvAnalytics"
    private var enabled = false

    fun init(context: Context) {
        val apiKey = BuildConfig.POSTHOG_API_KEY
        if (apiKey.isBlank()) {
            Log.i(TAG, "No posthog.apiKey in local.properties — analytics disabled.")
            return
        }
        val config = PostHogAndroidConfig(
            apiKey = apiKey,
            host = BuildConfig.POSTHOG_HOST,
        ).apply {
            // Gives DAU/session data automatically (see class doc).
            captureApplicationLifecycleEvents = true
            // Single-Activity app — see class doc. Manual screen() calls stand
            // in for this instead.
            captureScreenViews = false
            // The screen this app spends most of its time on is a full-screen
            // video player. Recording that would mean uploading a proxy for
            // the user's actual viewing habits — which channels, for how
            // long — well beyond what playback analytics already captures in
            // aggregate, and IPTV content is exactly the category most likely
            // to carry copyright or privacy sensitivity. Off, unconditionally.
            sessionReplay = false
            // Verbose PostHog logcat output in debug builds only — this is
            // what makes "did that event actually fire" answerable without a
            // live dashboard, which matters on a device with no browser to
            // check one from.
            debug = BuildConfig.DEBUG
        }
        PostHogAndroid.setup(context, config)
        enabled = true
    }

    /** Manual stand-in for screen tracking — see class doc for why. */
    fun screen(name: String, properties: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        PostHog.screen(name, properties.filterValuesNotNull())
        Diagnostics.info("analytics", "screen: $name ${properties.filterValuesNotNull()}")
    }

    fun event(name: String, properties: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        PostHog.capture(name, properties = properties.filterValuesNotNull())
        // Mirrored into the in-app diagnostics log (Settings → Diagnostics
        // log) rather than only handed to the PostHog SDK. That screen exists
        // precisely so someone can confirm what the app just did on a device
        // with no browser to open a dashboard from — a TV box — and an event
        // that only a remote server can confirm was sent is not confirmable
        // at all on this hardware. PostHog's own SDK-level debug logging did
        // not surface anything readable over logcat on-device to verify
        // against, which is what prompted routing through here instead.
        Diagnostics.info("analytics", "event: $name ${properties.filterValuesNotNull()}")
    }

    private fun Map<String, Any?>.filterValuesNotNull(): Map<String, Any> =
        mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
}
