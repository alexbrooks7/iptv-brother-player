package com.iptv.player.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class Credentials(val username: String, val password: String)

/**
 * Encrypts provider logins with an AES-256/GCM key held in the Android
 * Keystore, per the brief's "not plaintext SharedPreferences" requirement.
 *
 * Why Keystore rather than a key derived from something in the APK: on a
 * rooted box — and cheap Android TV boxes ship rooted more often than anyone
 * would like — `/data/data/<pkg>` is readable. A key baked into the app is
 * identical on every install and extracting it once breaks everyone. A
 * Keystore key is non-exportable and, on devices with a TEE (all current Fire
 * TV and Google TV hardware), never enters app memory at all.
 *
 * What this deliberately does *not* do is require user authentication to use
 * the key. A TV has no fingerprint reader, the app must be able to refresh
 * playlists from a background worker with nobody in the room, and prompting
 * for a PIN before every stream would be unusable. The threat model here is
 * "someone pulls the app's data directory", not "someone has the unlocked
 * device in their hands" — the latter is what the parental PIN covers, at a
 * different layer.
 *
 * GCM is used with a random 12-byte IV per encryption, stored as a prefix of
 * the ciphertext. Reusing an IV with GCM is catastrophic, so the IV is never
 * derived or cached.
 */
object CredentialCrypto {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "iptv_player_credentials_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val json = Json { ignoreUnknownKeys = true }

    fun encrypt(credentials: Credentials): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val body = cipher.doFinal(json.encodeToString(Credentials.serializer(), credentials).toByteArray())
        val packed = cipher.iv + body
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    /**
     * Returns null rather than throwing when the blob cannot be decrypted.
     * That happens for real, non-exceptional reasons: restoring an app backup
     * onto a different device, or a factory reset that regenerates the
     * Keystore. The caller's job is then to ask the user to sign in again,
     * which is a normal screen, not a crash.
     */
    fun decrypt(blob: String?): Credentials? {
        if (blob.isNullOrBlank()) return null
        return runCatching {
            val packed = Base64.decode(blob, Base64.NO_WRAP)
            require(packed.size > IV_BYTES) { "ciphertext too short" }
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES))
            }
            val plain = cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES)
            json.decodeFromString(Credentials.serializer(), String(plain))
        }.getOrNull()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }
}
