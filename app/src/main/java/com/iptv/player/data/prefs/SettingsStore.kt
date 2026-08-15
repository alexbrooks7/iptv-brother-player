package com.iptv.player.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/** How much the player buffers before it starts, and how deep it runs. */
enum class BufferProfile(val minBufferMs: Int, val maxBufferMs: Int, val playbackBufferMs: Int, val rebufferMs: Int) {
    /** Channel zapping feels instant; a lossy Wi-Fi link will stutter. */
    LOW(1_500, 8_000, 800, 1_500),
    BALANCED(4_000, 30_000, 2_000, 4_000),
    /** Rides out multi-second dropouts at the cost of a slower tune-in. */
    STABLE(10_000, 60_000, 4_000, 8_000),
}

enum class AspectMode { FIT, FILL, ZOOM, STRETCH, ORIGINAL }

data class Settings(
    val activeSourceId: Long = 0,
    val lightTheme: Boolean = false,
    val uiScale: Float = 1f,
    val bufferProfile: BufferProfile = BufferProfile.BALANCED,
    val hardwareDecoding: Boolean = true,
    /**
     * Renders video into a `TextureView` instead of a `SurfaceView`.
     *
     * Off by default because a SurfaceView is meaningfully cheaper: it gets its
     * own hardware overlay plane, so decoded frames go straight to the display
     * controller without the GPU ever touching them. A TextureView is an
     * ordinary texture in the view hierarchy, which means every frame is
     * composited by the GPU — measurable heat and power on a weak box, and the
     * kind of thing that turns 1080p60 into dropped frames.
     *
     * Kept as an escape hatch because the overlay path depends on the vendor's
     * display stack, and a minority of budget boxes composite it wrongly
     * underneath a Compose UI — the symptom being a black picture with working
     * sound. That is *also* what an unattached surface looks like, which is
     * what this app's own bug turned out to be; the setting exists for the
     * genuine firmware cases that remain after that fix.
     */
    val compatibilitySurface: Boolean = false,
    val reconnectAttempts: Int = 5,
    val resumeLastChannel: Boolean = false,
    val aspectMode: AspectMode = AspectMode.FIT,
    val use24HourTime: Boolean = true,
    val epgOffsetMinutes: Int = 0,
    val autoRefreshHours: Int = 24,
    val autoRefreshEpg: Boolean = true,
    val parentalEnabled: Boolean = false,
    val parentalPinHash: String? = null,
    val parentalPinSalt: String? = null,
    val hideLockedCategories: Boolean = false,
    val languageTag: String? = null,
    /**
     * Whether the bandwidth-sharing disclosure has been shown at least once.
     *
     * Tracked separately from the SDK's own consent flag because that flag is
     * only a binary "consent given" — it reads false whether the prompt was
     * declined or never shown. Prompting off it alone would re-ask on every
     * single launch forever after someone declines, which is exactly the
     * nagging pattern a consent flow must not have. Opting in later stays
     * available from Settings.
     */
    val sharingConsentAsked: Boolean = false,

    /**
     * Whether bandwidth sharing should be running.
     *
     * A third flag alongside the SDK's consent bit and [sharingConsentAsked],
     * because neither can answer "should this be on right now". The SDK's
     * `isConsentGiven()` persists across launches but only records that the
     * disclosure was once accepted — it stays true after the viewer switches
     * sharing off in Settings, since withdrawing consent entirely is a
     * different act from pausing the feature.
     *
     * Without this, the two obvious start-up rules are both wrong: resuming
     * on the consent bit alone silently re-enables sharing for someone who
     * deliberately turned it off, and not resuming at all means the service
     * only ever runs in the session where it was switched on. The first
     * overrides an explicit choice; the second is what shipped.
     */
    val sharingEnabled: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

/**
 * App-wide preferences.
 *
 * DataStore rather than SharedPreferences because the settings screen and the
 * player both read these on the main thread's critical path, and DataStore's
 * Flow means the player picks up a buffer-profile change without a restart.
 *
 * The parental PIN is stored as a salted SHA-256 digest. It is not protecting
 * anything of monetary value and there is no server to attack, so a memory-hard
 * KDF would be theatre — but storing four plaintext digits next to a setting
 * called "adult categories" would be indefensible, and the salt stops a
 * rainbow table over the 10,000 possible PINs.
 */
class SettingsStore(private val context: Context) {

    val flow: Flow<Settings> = context.dataStore.data
        .catch { e ->
            // A corrupt preferences file must not take the app down on launch;
            // defaults are always a valid state to start from.
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { p ->
            Settings(
                activeSourceId = p[Keys.ACTIVE_SOURCE] ?: 0,
                lightTheme = p[Keys.LIGHT_THEME] ?: false,
                uiScale = p[Keys.UI_SCALE] ?: 1f,
                bufferProfile = p[Keys.BUFFER_PROFILE]?.toEnum() ?: BufferProfile.BALANCED,
                hardwareDecoding = p[Keys.HW_DECODE] ?: true,
                compatibilitySurface = p[Keys.COMPAT_SURFACE] ?: false,
                reconnectAttempts = p[Keys.RECONNECT] ?: 5,
                resumeLastChannel = p[Keys.RESUME_LAST] ?: false,
                aspectMode = p[Keys.ASPECT]?.toAspect() ?: AspectMode.FIT,
                use24HourTime = p[Keys.TIME_24H] ?: true,
                epgOffsetMinutes = p[Keys.EPG_OFFSET] ?: 0,
                autoRefreshHours = p[Keys.REFRESH_HOURS] ?: 24,
                autoRefreshEpg = p[Keys.REFRESH_EPG] ?: true,
                parentalEnabled = p[Keys.PARENTAL_ON] ?: false,
                parentalPinHash = p[Keys.PIN_HASH],
                parentalPinSalt = p[Keys.PIN_SALT],
                hideLockedCategories = p[Keys.HIDE_LOCKED] ?: false,
                languageTag = p[Keys.LANGUAGE],
                sharingConsentAsked = p[Keys.SHARING_ASKED] ?: false,
                sharingEnabled = p[Keys.SHARING_ENABLED] ?: false,
            )
        }

    suspend fun setActiveSource(id: Long) = edit { it[Keys.ACTIVE_SOURCE] = id }
    suspend fun setLightTheme(value: Boolean) = edit { it[Keys.LIGHT_THEME] = value }
    suspend fun setUiScale(value: Float) = edit { it[Keys.UI_SCALE] = value.coerceIn(0.8f, 1.3f) }
    suspend fun setBufferProfile(value: BufferProfile) = edit { it[Keys.BUFFER_PROFILE] = value.name }
    suspend fun setHardwareDecoding(value: Boolean) = edit { it[Keys.HW_DECODE] = value }
    suspend fun setCompatibilitySurface(value: Boolean) = edit { it[Keys.COMPAT_SURFACE] = value }
    suspend fun setReconnectAttempts(value: Int) = edit { it[Keys.RECONNECT] = value.coerceIn(0, 20) }
    suspend fun setResumeLastChannel(value: Boolean) = edit { it[Keys.RESUME_LAST] = value }
    suspend fun setAspectMode(value: AspectMode) = edit { it[Keys.ASPECT] = value.name }
    suspend fun set24HourTime(value: Boolean) = edit { it[Keys.TIME_24H] = value }
    suspend fun setEpgOffsetMinutes(value: Int) = edit { it[Keys.EPG_OFFSET] = value.coerceIn(-720, 720) }
    suspend fun setAutoRefreshHours(value: Int) = edit { it[Keys.REFRESH_HOURS] = value.coerceIn(0, 168) }
    suspend fun setAutoRefreshEpg(value: Boolean) = edit { it[Keys.REFRESH_EPG] = value }
    suspend fun setHideLockedCategories(value: Boolean) = edit { it[Keys.HIDE_LOCKED] = value }
    suspend fun setSharingConsentAsked() = edit { it[Keys.SHARING_ASKED] = true }
    suspend fun setSharingEnabled(value: Boolean) = edit { it[Keys.SHARING_ENABLED] = value }
    suspend fun setLanguageTag(value: String?) = edit { p ->
        if (value == null) p.remove(Keys.LANGUAGE) else p[Keys.LANGUAGE] = value
    }

    suspend fun setParentalEnabled(value: Boolean) = edit { it[Keys.PARENTAL_ON] = value }

    suspend fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltHex = salt.toHex()
        edit {
            it[Keys.PIN_SALT] = saltHex
            it[Keys.PIN_HASH] = hashPin(pin, saltHex)
            it[Keys.PARENTAL_ON] = true
        }
    }

    suspend fun clearPin() = edit {
        it.remove(Keys.PIN_HASH)
        it.remove(Keys.PIN_SALT)
        it[Keys.PARENTAL_ON] = false
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private object Keys {
        val ACTIVE_SOURCE = longPreferencesKey("active_source")
        val LIGHT_THEME = booleanPreferencesKey("light_theme")
        val UI_SCALE = floatPreferencesKey("ui_scale")
        val BUFFER_PROFILE = stringPreferencesKey("buffer_profile")
        val HW_DECODE = booleanPreferencesKey("hw_decode")
        val COMPAT_SURFACE = booleanPreferencesKey("compat_surface")
        val RECONNECT = intPreferencesKey("reconnect_attempts")
        val RESUME_LAST = booleanPreferencesKey("resume_last_channel")
        val ASPECT = stringPreferencesKey("aspect_mode")
        val TIME_24H = booleanPreferencesKey("time_24h")
        val EPG_OFFSET = intPreferencesKey("epg_offset_minutes")
        val REFRESH_HOURS = intPreferencesKey("refresh_hours")
        val REFRESH_EPG = booleanPreferencesKey("refresh_epg")
        val PARENTAL_ON = booleanPreferencesKey("parental_enabled")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val HIDE_LOCKED = booleanPreferencesKey("hide_locked")
        val LANGUAGE = stringPreferencesKey("language_tag")
        val SHARING_ASKED = booleanPreferencesKey("sharing_consent_asked")
        val SHARING_ENABLED = booleanPreferencesKey("sharing_enabled")
    }

    companion object {
        fun hashPin(pin: String, saltHex: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(saltHex.toByteArray())
            return digest.digest(pin.toByteArray()).toHex()
        }
    }
}

/** Checks a PIN against the stored digest. Returns true when no PIN is set. */
fun Settings.pinMatches(pin: String): Boolean {
    val hash = parentalPinHash ?: return true
    val salt = parentalPinSalt ?: return true
    return SettingsStore.hashPin(pin, salt) == hash
}

private fun String.toEnum(): BufferProfile? = runCatching { BufferProfile.valueOf(this) }.getOrNull()
private fun String.toAspect(): AspectMode? = runCatching { AspectMode.valueOf(this) }.getOrNull()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
