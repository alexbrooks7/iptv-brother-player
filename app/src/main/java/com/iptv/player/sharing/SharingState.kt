package com.iptv.player.sharing

/**
 * App-owned mirror of the Pawns SDK's `ServiceState`.
 *
 * Every screen in `main` that shows sharing status (`SettingsScreen`) reads
 * this type rather than the SDK's own, so that nothing outside the
 * `sideload` source set imports anything under `com.pawns.sdk`. That is not
 * defensive style — it is the actual mechanism that keeps the SDK's classes
 * out of the `store` flavour's APK. See `PawnsManager` in each source set
 * for why "unused" is not good enough there.
 */
sealed interface SharingState {
    data object Off : SharingState
    data object On : SharingState
    data object Running : SharingState
    data object LowBattery : SharingState
    data class Error(val message: String) : SharingState
}
