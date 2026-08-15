package com.iptv.player.sharing

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.Flow

/**
 * Store-flavour stand-in for the bandwidth-sharing manager.
 *
 * The Play Store and the Amazon Appstore both prohibit apps that route other
 * users' internet traffic through a device — Pawns.app is exactly that, so it
 * cannot ship in a build submitted to either. "Cannot ship" means the
 * library's own classes must not be in the APK at all: both stores' review
 * scans a submission for known SDK signatures, not merely for whether some
 * feature flag is off, so disabling the feature at runtime in a build that
 * still links the SDK would not satisfy either policy.
 *
 * This object exists so that call site (`MainScreen`, `SettingsScreen`)
 * compiles unchanged against either flavour. What actually keeps the library
 * out of this flavour's APK is that `build.gradle.kts` only adds the
 * `app.pawns:android-pawns-sdk` dependency to the `sideload` source set —
 * this file has nothing to import, because the dependency is not on this
 * flavour's classpath at all. Every method below is a fixed no-op rather than
 * reading a "disabled" flag, for the same reason: there is no SDK instance
 * here to ask.
 *
 * See `src/sideload/.../PawnsManager.kt` for the real implementation and
 * README "Store vs sideload builds".
 */
object PawnsManager {
    val available: Boolean = false
    fun init(context: Context) {}
    fun hasConsent(): Boolean = false
    fun consentIntent(): Intent? = null
    fun setConsentGiven(given: Boolean) {}
    fun serviceState(): Flow<SharingState>? = null
    fun startSharing(context: Context) {}
    fun stopSharing(context: Context) {}
}
