package com.iptv.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.iptv.player.BuildConfig
import com.iptv.player.R
import com.iptv.player.analytics.IptvAnalytics
import com.iptv.player.data.prefs.AspectMode
import com.iptv.player.data.prefs.BufferProfile
import com.iptv.player.data.prefs.Settings
import com.iptv.player.ui.components.SectionHeader
import com.iptv.player.ui.components.SettingRow
import com.iptv.player.sharing.PawnsManager
import com.iptv.player.sharing.SharingState
import com.iptv.player.ui.util.labelRes
import com.iptv.player.ui.util.next
import com.iptv.player.ui.util.tvFocusGroup
import com.iptv.player.ui.vm.SettingsViewModel
import com.iptv.player.util.Diagnostics
import kotlinx.coroutines.flow.MutableStateFlow

private enum class SettingsPanel { NONE, PARENTAL, DIAGNOSTICS, SET_PIN }

/**
 * Settings.
 *
 * Every control is a row that cycles or toggles on centre-press. There are no
 * dropdowns, sliders or dialogs: on a D-pad, a slider is a nightmare to land
 * precisely and a dropdown adds a focus trap for no benefit, whereas pressing
 * the same row three times to walk Low → Balanced → Stable is immediately
 * understandable and impossible to get stuck in.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    settings: Settings,
    onReviewSharing: () -> Unit = {},
) {
    var panel by remember { mutableStateOf(SettingsPanel.NONE) }
    val message by viewModel.message.collectAsStateWithLifecycle()

    when (panel) {
        SettingsPanel.PARENTAL -> {
            ParentalPanel(
                viewModel = viewModel,
                settings = settings,
                onSetPin = { panel = SettingsPanel.SET_PIN },
                onClearPin = { viewModel.clearPin() },
                onBack = { panel = SettingsPanel.NONE },
            )
            return
        }
        SettingsPanel.DIAGNOSTICS -> {
            DiagnosticsPanel(onBack = { panel = SettingsPanel.NONE })
            return
        }
        SettingsPanel.SET_PIN -> {
            SetPinPanel(
                onSet = { pin ->
                    viewModel.setPin(pin)
                    panel = SettingsPanel.PARENTAL
                },
                onCancel = { panel = SettingsPanel.PARENTAL },
            )
            return
        }
        SettingsPanel.NONE -> Unit
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 56.dp)
            .tvFocusGroup(),
        contentPadding = PaddingValues(vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall) }

        message?.let { text ->
            item {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }

        // ---- Playback ---------------------------------------------------
        item { SectionHeader(stringResource(R.string.settings_section_playback)) }

        item {
            SettingRow(
                title = stringResource(R.string.settings_buffer),
                summary = stringResource(settings.bufferProfile.summaryRes()),
                value = settings.bufferProfile.name,
                onClick = { viewModel.setBufferProfile(settings.bufferProfile.next()) },
            )
        }
        item {
            SettingRow(
                title = stringResource(R.string.settings_hw_decoding),
                summary = stringResource(R.string.settings_hw_decoding_summary),
                value = settings.hardwareDecoding.onOff(),
                onClick = { viewModel.setHardwareDecoding(!settings.hardwareDecoding) },
            )
        }
        item {
            SettingRow(
                title = stringResource(R.string.settings_compat_surface),
                summary = stringResource(R.string.settings_compat_surface_summary),
                value = settings.compatibilitySurface.onOff(),
                onClick = { viewModel.setCompatibilitySurface(!settings.compatibilitySurface) },
            )
        }
        item {
            SettingRow(
                title = stringResource(R.string.settings_autoreconnect),
                value = settings.reconnectAttempts.toString(),
                // 0, 3, 5, 10, 20 rather than every integer: nobody needs the
                // difference between 6 and 7 attempts, and a short cycle keeps
                // the whole range two presses away.
                onClick = {
                    val steps = listOf(0, 3, 5, 10, 20)
                    val next = steps[(steps.indexOf(settings.reconnectAttempts).takeIf { it >= 0 }
                        ?.plus(1) ?: 0) % steps.size]
                    viewModel.setReconnectAttempts(next)
                },
            )
        }
        item {
            SettingRow(
                title = stringResource(R.string.settings_aspect_default),
                value = stringResource(settings.aspectMode.labelRes()),
                onClick = { viewModel.setAspectMode(settings.aspectMode.next()) },
            )
        }
        item {
            SettingRow(
                title = stringResource(R.string.settings_resume_last_channel),
                value = settings.resumeLastChannel.onOff(),
                onClick = { viewModel.setResumeLastChannel(!settings.resumeLastChannel) },
            )
        }

        // ---- Guide ------------------------------------------------------
        item { SectionHeader(stringResource(R.string.settings_section_guide)) }

        item {
            SettingRow(
                title = stringResource(R.string.settings_time_format),
                value = stringResource(
                    if (settings.use24HourTime) R.string.settings_time_24h else R.string.settings_time_12h
                ),
                onClick = { viewModel.set24HourTime(!settings.use24HourTime) },
            )
        }
        item {
            SettingRow(
                title = stringResource(R.string.settings_epg_offset),
                summary = stringResource(R.string.settings_epg_offset_summary, settings.epgOffsetMinutes.asOffset()),
                value = settings.epgOffsetMinutes.asOffset(),
                onClick = {
                    // Half-hour steps from -3h to +3h and back. Covers every
                    // real timezone mismatch, including the half-hour ones.
                    val next = if (settings.epgOffsetMinutes >= 180) -180 else settings.epgOffsetMinutes + 30
                    viewModel.setEpgOffsetMinutes(next)
                },
            )
        }
        item {
            SettingRow(
                title = stringResource(R.string.settings_refresh_interval),
                value = if (settings.autoRefreshHours == 0) {
                    stringResource(R.string.player_tracks_off)
                } else {
                    stringResource(R.string.settings_hours, settings.autoRefreshHours)
                },
                onClick = {
                    val steps = listOf(0, 6, 12, 24, 48)
                    val next = steps[(steps.indexOf(settings.autoRefreshHours).takeIf { it >= 0 }
                        ?.plus(1) ?: 0) % steps.size]
                    viewModel.setAutoRefreshHours(next)
                },
            )
        }

        // ---- Interface --------------------------------------------------
        item { SectionHeader(stringResource(R.string.settings_section_interface)) }

        item {
            SettingRow(
                title = stringResource(R.string.settings_theme),
                value = stringResource(
                    if (settings.lightTheme) R.string.settings_theme_light else R.string.settings_theme_dark
                ),
                onClick = { viewModel.setLightTheme(!settings.lightTheme) },
            )
        }
        item {
            SettingRow(
                title = stringResource(R.string.settings_ui_scale),
                summary = stringResource(R.string.settings_ui_scale_summary),
                value = "${(settings.uiScale * 100).toInt()}%",
                onClick = {
                    val steps = listOf(0.85f, 1.0f, 1.15f, 1.3f)
                    val current = steps.minByOrNull { kotlin.math.abs(it - settings.uiScale) } ?: 1f
                    viewModel.setUiScale(steps[(steps.indexOf(current) + 1) % steps.size])
                },
            )
        }

        // ---- Parental ---------------------------------------------------
        item { SectionHeader(stringResource(R.string.settings_section_parental)) }

        item {
            SettingRow(
                title = stringResource(R.string.settings_parental_enable),
                value = settings.parentalEnabled.onOff(),
                onClick = { panel = SettingsPanel.PARENTAL },
            )
        }

        // ---- Data -------------------------------------------------------
        item { SectionHeader(stringResource(R.string.settings_section_data)) }

        item {
            SettingRow(
                title = stringResource(R.string.settings_clear_cache),
                onClick = { viewModel.clearGuideCache() },
            )
        }
        item {
            SettingRow(
                title = stringResource(R.string.settings_clear_history),
                onClick = { viewModel.clearHistory() },
            )
        }
        item {
            SettingRow(
                title = stringResource(R.string.settings_logs),
                summary = stringResource(R.string.settings_logs_summary, Diagnostics.CAPACITY),
                onClick = { panel = SettingsPanel.DIAGNOSTICS },
            )
        }

        // ---- Internet sharing --------------------------------------------
        // Only present when the build is configured for it, so an unconfigured
        // build shows no trace of the feature rather than a dead toggle.
        if (PawnsManager.available) {
            item { SectionHeader(stringResource(R.string.settings_section_sharing)) }
            item {
                SharingRow(
                    onReviewSharing = onReviewSharing,
                    onSharingEnabledChange = { enabled ->
                        viewModel.setSharingEnabled(enabled)
                        // Tracked here as well as at the consent dialog: the
                        // dialog only ever records the first answer, so without
                        // this the funnel shows opt-ins and never opt-outs, and
                        // active sharers look permanently overstated.
                        IptvAnalytics.event("sharing_toggle", mapOf("enabled" to enabled))
                    },
                )
            }
            item {
                SettingRow(
                    title = stringResource(R.string.settings_sharing_review),
                    onClick = onReviewSharing,
                )
            }
        }

        // ---- About ------------------------------------------------------
        item { SectionHeader(stringResource(R.string.settings_section_about)) }
        item {
            Text(
                stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Text(
                stringResource(R.string.settings_legal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
            )
        }
    }
}

@Composable
private fun ParentalPanel(
    viewModel: SettingsViewModel,
    settings: Settings,
    onSetPin: () -> Unit,
    onClearPin: () -> Unit,
    onBack: () -> Unit,
) {
    val categories by viewModel.lockableCategories.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 56.dp).tvFocusGroup(),
        contentPadding = PaddingValues(vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                stringResource(R.string.settings_section_parental),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        item {
            SettingRow(
                title = if (settings.parentalPinHash == null) {
                    stringResource(R.string.settings_parental_set_pin)
                } else {
                    stringResource(R.string.settings_parental_change_pin)
                },
                onClick = onSetPin,
            )
        }
        if (settings.parentalPinHash != null) {
            item {
                SettingRow(
                    title = stringResource(R.string.settings_parental_remove_pin),
                    summary = stringResource(R.string.settings_parental_remove_pin_summary),
                    onClick = onClearPin,
                )
            }
        }
        item {
            SettingRow(
                title = stringResource(R.string.settings_parental_hide),
                summary = "Locked categories disappear from the channel list instead of asking for a PIN.",
                value = settings.hideLockedCategories.onOff(),
                onClick = { viewModel.setHideLockedCategories(!settings.hideLockedCategories) },
            )
        }
        item { SectionHeader(stringResource(R.string.settings_parental_locked_categories)) }

        items(categories, key = { it.id }) { category ->
            SettingRow(
                title = category.name,
                value = if (category.adult) "🔒" else "—",
                onClick = { viewModel.setCategoryLocked(category.id, !category.adult) },
            )
        }

        item {
            SettingRow(title = stringResource(R.string.action_back), onClick = onBack)
        }
    }
}

/**
 * Two-step PIN entry. The first pass is always accepted and remembered; the
 * second is only accepted if it matches, which turns "confirm your PIN" into
 * the same component rather than a second screen.
 */
@Composable
private fun SetPinPanel(onSet: (String) -> Unit, onCancel: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }

    PinPrompt(
        title = if (first == null) stringResource(R.string.pin_set) else stringResource(R.string.pin_confirm),
        verify = { pin -> first == null || pin == first },
        rejectedMessage = stringResource(R.string.pin_mismatch),
        onAccepted = { pin ->
            val entered = first
            if (entered == null) first = pin else onSet(pin)
        },
        onCancel = onCancel,
    )
}

@Composable
private fun DiagnosticsPanel(onBack: () -> Unit) {
    val events by Diagnostics.events.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 40.dp).tvFocusGroup(),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(stringResource(R.string.settings_logs), style = MaterialTheme.typography.headlineSmall)
        }
        item {
            SettingRow(title = stringResource(R.string.action_back), onClick = onBack)
        }
        items(events.asReversed(), key = { it.timestamp.toString() + it.message.hashCode() }) { event ->
            Text(
                Diagnostics.format(event),
                style = MaterialTheme.typography.bodySmall,
                color = when (event.level) {
                    Diagnostics.Level.ERROR -> MaterialTheme.colorScheme.error
                    Diagnostics.Level.WARN -> MaterialTheme.colorScheme.onSurfaceVariant
                    Diagnostics.Level.INFO -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

/**
 * The internet-sharing on/off row.
 *
 * Status comes from the SDK's own service-state flow rather than from the
 * consent flag. Consent being granted does not prove the service is running —
 * it can be paused on low battery or failing outright — and showing "On"
 * over a service that is erroring would be a claim the viewer has no way to
 * check. The row reports what is actually happening.
 */
@Composable
private fun SharingRow(onReviewSharing: () -> Unit, onSharingEnabledChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val stateFlow = remember { PawnsManager.serviceState() ?: MutableStateFlow(SharingState.Off) }
    val serviceState by stateFlow.collectAsStateWithLifecycle(SharingState.Off)

    val active = serviceState is SharingState.On || serviceState is SharingState.Running
    val summary = when (val s = serviceState) {
        is SharingState.Off -> stringResource(R.string.settings_sharing_off)
        is SharingState.On -> stringResource(R.string.settings_sharing_on)
        is SharingState.Running -> stringResource(R.string.settings_sharing_on)
        is SharingState.LowBattery -> stringResource(R.string.settings_sharing_low_battery)
        is SharingState.Error -> stringResource(R.string.settings_sharing_error, s.message)
    }

    SettingRow(
        title = stringResource(R.string.settings_sharing),
        summary = summary,
        value = active.onOff(),
        onClick = {
            when {
                active -> {
                    PawnsManager.stopSharing(context)
                    // Persisted, or the next launch would resume it and quietly
                    // undo this. Consent deliberately stays granted: switching
                    // the feature off is not the same act as withdrawing it.
                    onSharingEnabledChange(false)
                }
                // Already consented, so no need to ask again — just resume.
                PawnsManager.hasConsent() -> {
                    PawnsManager.startSharing(context)
                    onSharingEnabledChange(true)
                }
                // Never consented, or previously declined. The disclosure has
                // to come before any traffic flows, so this reopens it rather
                // than silently switching the feature on.
                else -> onReviewSharing()
            }
        },
    )
}

@Composable
private fun Boolean.onOff(): String =
    if (this) stringResource(R.string.state_on) else stringResource(R.string.state_off)

private fun Int.asOffset(): String = when {
    this == 0 -> "0"
    this > 0 -> "+${this / 60}h${if (this % 60 != 0) "30" else ""}"
    else -> "-${-this / 60}h${if (-this % 60 != 0) "30" else ""}"
}

private fun BufferProfile.next(): BufferProfile =
    BufferProfile.entries[(ordinal + 1) % BufferProfile.entries.size]

private fun BufferProfile.summaryRes(): Int = when (this) {
    BufferProfile.LOW -> R.string.settings_buffer_low
    BufferProfile.BALANCED -> R.string.settings_buffer_balanced
    BufferProfile.STABLE -> R.string.settings_buffer_stable
}
