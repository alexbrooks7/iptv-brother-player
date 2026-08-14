package com.iptv.player.player

import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.iptv.player.R

/**
 * A playback failure translated into something worth putting on a TV screen.
 *
 * The brief calls out "Timeout and error messaging that's actually useful
 * ('Stream unavailable' vs generic crash)", and this is where that is
 * delivered. It matters more here than in most apps: with IPTV, the *cause*
 * tells the user which of three completely different things to do — wait and
 * retry (server hiccup), check their subscription (403 / connection limit), or
 * accept that this device cannot play this channel at all (no HEVC decoder).
 * A single "playback error" string makes all three look identical.
 *
 * [retryable] additionally drives the auto-reconnect loop: re-requesting a
 * stream the provider says does not exist just burns battery and hides the
 * real message behind a spinner.
 */
data class PlaybackError(
    @StringRes val messageRes: Int,
    val formatArgs: List<Any> = emptyList(),
    val retryable: Boolean = true,
    /** Kept for the diagnostics log, never shown as the primary message. */
    val technical: String = "",
) {
    @OptIn(UnstableApi::class)
    companion object {

        fun from(error: PlaybackException): PlaybackError {
            val http = error.findHttpCause()
            if (http != null) return fromHttpStatus(http.responseCode, error.technicalLine())

            return when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                -> PlaybackError(R.string.player_error_timeout, retryable = true, technical = error.technicalLine())

                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                    PlaybackError(R.string.player_error_not_found, retryable = false, technical = error.technicalLine())

                PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                    PlaybackError(
                        R.string.player_error_forbidden,
                        listOf(403),
                        retryable = false,
                        technical = error.technicalLine(),
                    )

                // Malformed container/manifest. Retryable once or twice
                // because a live TS stream joined mid-corruption often
                // recovers on a fresh connection, but it is not worth
                // hammering.
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                -> PlaybackError(R.string.player_error_parse, retryable = true, technical = error.technicalLine())

                // A decoder problem will not fix itself by reconnecting: the
                // device simply lacks the codec, which on cheap boxes means
                // HEVC or 10-bit almost every time.
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                -> PlaybackError(
                    R.string.player_error_codec,
                    listOf(error.codecName()),
                    retryable = false,
                    technical = error.technicalLine(),
                )

                PlaybackException.ERROR_CODE_DECODING_FAILED ->
                    PlaybackError(
                        R.string.player_error_codec,
                        listOf(error.codecName()),
                        retryable = true,
                        technical = error.technicalLine(),
                    )

                else -> PlaybackError(
                    R.string.player_error_generic,
                    listOf(error.errorCodeName),
                    retryable = true,
                    technical = error.technicalLine(),
                )
            }
        }

        fun fromHttpStatus(code: Int, technical: String = ""): PlaybackError = when (code) {
            404, 410 -> PlaybackError(R.string.player_error_not_found, retryable = false, technical = technical)
            // 401/403 is usually an expired subscription — but it is *also*
            // what most panels return when the account's simultaneous
            // connection limit is in use, which clears on its own. Retryable,
            // and the message names both possibilities.
            401, 403 -> PlaybackError(R.string.player_error_forbidden, listOf(code), retryable = true, technical = technical)
            in 500..599 -> PlaybackError(R.string.player_error_generic, listOf("HTTP $code"), retryable = true, technical = technical)
            else -> PlaybackError(R.string.player_error_generic, listOf("HTTP $code"), retryable = true, technical = technical)
        }

        val offline = PlaybackError(R.string.player_error_offline, retryable = true)

        private fun PlaybackException.findHttpCause(): HttpDataSource.InvalidResponseCodeException? {
            var current: Throwable? = cause
            var depth = 0
            while (current != null && depth < 8) {
                if (current is HttpDataSource.InvalidResponseCodeException) return current
                current = current.cause
                depth++
            }
            return null
        }

        private fun PlaybackException.technicalLine(): String =
            "$errorCodeName (${errorCode})" + (message?.let { ": $it" } ?: "")

        /** Best-effort codec name for the "this device cannot decode X" message. */
        private fun PlaybackException.codecName(): String {
            val decoderCause = generateSequence(cause as Throwable?) { it.cause }
                .take(8)
                .filterIsInstance<androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException>()
                .firstOrNull()
            return decoderCause?.mimeType ?: decoderCause?.codecInfo?.name ?: "unknown codec"
        }
    }
}
