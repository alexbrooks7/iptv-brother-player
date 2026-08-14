package com.iptv.player.sharing

import android.content.Context
import android.content.Intent
import com.iptv.player.BuildConfig
import com.iptv.player.R
import com.iptv.player.util.Diagnostics
import com.pawns.sdk.common.dto.ServiceConfig
import com.pawns.sdk.common.dto.ServiceNotificationPriority
import com.pawns.sdk.common.dto.ServiceState
import com.pawns.sdk.common.dto.ServiceType
import com.pawns.sdk.common.sdk.Pawns
import kotlinx.coroutines.flow.Flow

/**
 * Thin wrapper around the Pawns.app bandwidth-sharing SDK.
 *
 * The feature routes other people's internet traffic through this device's
 * connection in exchange for revenue. That is worth stating plainly, because it
 * is the reason everything here is built to fail closed:
 *
 * - **No key, no feature.** [available] is false whenever `pawns.apiKey` is
 *   absent from `local.properties`, and every call below then does nothing. A
 *   fresh clone builds a player that never mentions sharing, never prompts, and
 *   never starts a service. Shipping a half-configured version of *this*
 *   feature is not the kind of mistake that should be one missing line away.
 * - **Nothing starts on its own.** [init] only configures the SDK. Traffic
 *   flows only after [setConsentGiven] and [startSharing], which are reached
 *   from the consent dialog and the Settings toggle — never from app start-up.
 *
 * Mirrors the primitives used by the official demo
 * (github.com/pawns-app/android-pawns-sdk-demo) so behaviour can be compared
 * against it directly.
 */
object PawnsManager {

    /**
     * Whether the feature exists in this build at all.
     *
     * Every screen checks this before showing sharing UI, so an unconfigured
     * build is not merely inert — it is invisible.
     */
    val available: Boolean = BuildConfig.PAWNS_API_KEY.isNotBlank()

    fun init(context: Context) {
        if (!available) return
        Pawns.Builder(context)
            .apiKey(BuildConfig.PAWNS_API_KEY)
            .serviceConfig(
                ServiceConfig(
                    title = R.string.pawns_service_name,
                    body = R.string.pawns_service_body,
                    smallIcon = R.drawable.ic_sharing,
                    // HIGH so the notification is not silently collapsed. While
                    // this is running the device is acting as someone else's
                    // gateway; that should never be quietly in the background.
                    notificationPriority = ServiceNotificationPriority.HIGH,
                )
            )
            .loggerEnabled(BuildConfig.DEBUG)
            .serviceType(ServiceType.FOREGROUND)
            .build()
        Diagnostics.info("sharing", "Bandwidth sharing available, awaiting consent")
    }

    /** Has the viewer already answered the prompt affirmatively? */
    fun hasConsent(): Boolean = available && Pawns.getInstance().isConsentGiven()

    /**
     * The SDK's own bundled consent Activity. Not used for the on-open prompt —
     * see ConsentDialog for why — but kept available for comparison testing.
     */
    fun consentIntent(): Intent? =
        if (available) Pawns.getInstance().getConsentIntent() else null

    fun setConsentGiven(given: Boolean) {
        if (!available) return
        Pawns.getInstance().setConsentGiven(given)
        Diagnostics.info("sharing", "Consent ${if (given) "granted" else "withdrawn"}")
    }

    /** Null when the feature is not configured — callers treat that as "off". */
    fun serviceState(): Flow<ServiceState>? =
        if (available) Pawns.getInstance().getServiceState() else null

    fun startSharing(context: Context) {
        if (!available) return
        Diagnostics.info("sharing", "Starting bandwidth sharing")
        Pawns.getInstance().startSharing(context)
    }

    fun stopSharing(context: Context) {
        if (!available) return
        Diagnostics.info("sharing", "Stopping bandwidth sharing")
        Pawns.getInstance().stopSharing(context)
    }
}
