package com.iptv.player.data.remote

import com.iptv.player.BuildConfig
import com.iptv.player.util.Diagnostics
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The one OkHttp client the app uses — for playlists, EPG downloads, the
 * Xtream API and (via media3-datasource-okhttp) the video streams themselves.
 *
 * Sharing a single client is what lets connection pooling actually help: IPTV
 * providers commonly run one host for everything, and re-using a warm TLS
 * connection removes roughly a handshake's worth of latency from every channel
 * change.
 */
object Http {

    /**
     * Generous, because these are not phone-style API calls. A playlist can be
     * 40 MB of text served from an overloaded box, and a read timeout that
     * fires mid-download turns "slow provider" into "import failed". The
     * *connect* timeout stays short so a dead host is reported quickly.
     */
    private const val CONNECT_TIMEOUT_S = 15L
    private const val READ_TIMEOUT_S = 60L
    private const val CALL_TIMEOUT_S = 0L // no overall cap: see above

    @Volatile
    private var client: OkHttpClient? = null

    fun client(): OkHttpClient = client ?: synchronized(this) {
        client ?: build().also { client = it }
    }

    private fun build(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // Many provider panels answer on http and 302 to https (or the other
        // way round) — following protocol switches is required, not optional.
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(LoggingInterceptor)
        .build()

    /**
     * The client used for artwork — channel logos and posters.
     *
     * Derived from [client] with `newBuilder`, which shares the connection
     * pool and the dispatcher rather than standing up a second set of sockets
     * and thread pools. That matters here: Coil creates its own OkHttpClient by
     * default, and on a 2 GB TV box a duplicate pool is real memory spent on
     * nothing, given that the logos usually come from the same host as the
     * playlist and can reuse an already-warm TLS connection.
     *
     * Two deliberate differences from the main client. The logging interceptor
     * is dropped, because a single screen of channels is a dozen image
     * requests and they would push the playback and playlist lines — the ones
     * that actually resolve support cases — straight out of the 300-entry
     * diagnostics ring buffer. And the timeouts are short: artwork is
     * decoration, so a slow logo host should give up quickly and let the text
     * fallback stand rather than occupy a connection for a minute.
     */
    fun imageClient(): OkHttpClient = imageClient ?: synchronized(this) {
        imageClient ?: buildImageClient().also { imageClient = it }
    }

    private fun buildImageClient(): OkHttpClient = client().newBuilder()
        .apply { interceptors().remove(LoggingInterceptor) }
        .connectTimeout(IMAGE_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(IMAGE_READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private const val IMAGE_CONNECT_TIMEOUT_S = 10L
    private const val IMAGE_READ_TIMEOUT_S = 15L

    @Volatile
    private var imageClient: OkHttpClient? = null

    /**
     * Applies the per-source User-Agent. Providers routinely filter on it,
     * either to block generic clients or because their CDN keys off it, so
     * this is user-configurable per playlist rather than a constant.
     */
    fun request(url: String, userAgent: String?, referrer: String? = null): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_USER_AGENT)
            .apply { referrer?.takeIf { it.isNotBlank() }?.let { header("Referer", it) } }
            // Explicitly accept gzip; the parsers also sniff for it because
            // some servers gzip regardless of what was negotiated.
            .header("Accept-Encoding", "gzip")
            .build()

    private object LoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val started = System.nanoTime()
            return try {
                val response = chain.proceed(request)
                val ms = (System.nanoTime() - started) / 1_000_000
                val line = "${request.method} ${request.url} -> ${response.code} in ${ms}ms"
                if (response.isSuccessful) Diagnostics.info("http", line) else Diagnostics.warn("http", line)
                response
            } catch (e: IOException) {
                Diagnostics.error("http", "${request.method} ${request.url} failed", e)
                throw e
            }
        }
    }
}

/**
 * A network failure with a message written for the person holding the remote
 * rather than for a stack trace. [userMessageRes] is resolved by the UI so the
 * text stays translatable.
 */
class HttpFailure(
    val kind: Kind,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : IOException(buildString {
    append(kind.name.lowercase())
    statusCode?.let { append(" (HTTP ").append(it).append(')') }
}, cause) {
    enum class Kind {
        OFFLINE, TIMEOUT, NOT_FOUND, FORBIDDEN, SERVER_ERROR, BAD_RESPONSE, UNKNOWN,
        /**
         * An Xtream source with no stored login — e.g. imported from a backup,
         * which never carries credentials (see ConfigBackup). Kept distinct
         * from [FORBIDDEN] because the fix is different: there is nothing to
         * retry here, the user has to enter a username and password before
         * this source can do anything.
         */
        CREDENTIALS_MISSING,
    }

    companion object {
        fun fromStatus(code: Int): HttpFailure = HttpFailure(
            when (code) {
                401, 403 -> Kind.FORBIDDEN
                404, 410 -> Kind.NOT_FOUND
                in 500..599 -> Kind.SERVER_ERROR
                else -> Kind.BAD_RESPONSE
            },
            code,
        )

        fun fromException(e: Throwable): HttpFailure = when (e) {
            is HttpFailure -> e
            is java.net.SocketTimeoutException -> HttpFailure(Kind.TIMEOUT, cause = e)
            is java.net.UnknownHostException -> HttpFailure(Kind.OFFLINE, cause = e)
            is java.net.ConnectException -> HttpFailure(Kind.OFFLINE, cause = e)
            else -> HttpFailure(Kind.UNKNOWN, cause = e)
        }
    }
}
