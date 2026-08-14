package com.iptv.player.data.remote

import com.iptv.player.data.prefs.Credentials
import com.iptv.player.util.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeToSequence
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.InputStream
import java.util.Locale

/**
 * Client for the Xtream Codes player API.
 *
 * Two decisions worth knowing about before changing anything here.
 *
 * **No Retrofit, no typed @Serializable models.** The API has no
 * specification and no consistency: `stream_id` comes back as `12345` from one
 * panel and `"12345"` from the next; `category_id` is a string, a number, or
 * absent; `rating` is `"7.5"`, `7.5`, `""` or `null`; empty result sets are
 * `[]`, `{}`, `""` or a 200 with an HTML error page. Strict deserialisation
 * against models turns each of those into a crash on somebody's provider. So
 * everything is read as [JsonElement] through the tolerant accessors at the
 * bottom of this file, which return null instead of throwing.
 *
 * **Streaming.** `get_live_streams` on a large subscription is 10–30 MB of
 * JSON. Materialising that as a `JsonArray` on a 1 GB Fire TV Stick is how you
 * get an OOM kill, so array endpoints are consumed element-by-element with
 * `decodeToSequence` and written straight through to the database.
 */
class XtreamClient(
    serverUrl: String,
    private val credentials: Credentials,
    private val userAgent: String? = null,
) {

    /**
     * Normalised server base with no trailing slash. Users type these off a
     * subscription email and get it wrong in predictable ways — a missing
     * scheme, a trailing slash, or the full `.../player_api.php` URL pasted in
     * whole — so all three are repaired rather than rejected.
     */
    val base: String = normaliseBase(serverUrl)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ---- Authentication -------------------------------------------------

    data class Account(
        val status: String?,
        val expiresAt: Long?,
        val maxConnections: Int?,
        val activeConnections: Int?,
        val serverTimezone: String?,
    )

    /** Throws [HttpFailure] on transport errors, returns null on bad credentials. */
    suspend fun authenticate(): Account? {
        val root = getObject(apiUrl {}) ?: throw HttpFailure(HttpFailure.Kind.BAD_RESPONSE)
        val info = root["user_info"]?.asObject() ?: return null
        // `auth` is 1/0, sometimes "1"/"0". Anything that is not truthy means
        // the panel rejected the login, which is not an exception — it is a
        // "check your username" message on the add-playlist screen.
        if (info["auth"]?.asInt() != 1) return null
        if (info["status"]?.asString()?.lowercase(Locale.US) == "banned") return null

        val server = root["server_info"]?.asObject()
        return Account(
            status = info["status"]?.asString(),
            expiresAt = info["exp_date"]?.asLong()?.times(1000),
            maxConnections = info["max_connections"]?.asInt(),
            activeConnections = info["active_cons"]?.asInt(),
            serverTimezone = server?.get("timezone")?.asString(),
        )
    }

    // ---- Catalogue ------------------------------------------------------

    data class RemoteCategory(val id: String, val name: String)

    suspend fun liveCategories(): List<RemoteCategory> = categories("get_live_categories")
    suspend fun vodCategories(): List<RemoteCategory> = categories("get_vod_categories")
    suspend fun seriesCategories(): List<RemoteCategory> = categories("get_series_categories")

    private suspend fun categories(action: String): List<RemoteCategory> {
        val array = getArray(apiUrl { it["action"] = action }) ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.asObject() ?: return@mapNotNull null
            val id = obj["category_id"]?.asString() ?: return@mapNotNull null
            val name = obj["category_name"]?.asString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RemoteCategory(id, name)
        }
    }

    /**
     * Streams live channels to [consume] without holding the whole catalogue
     * in memory. The callback receives raw objects; mapping happens in the
     * importer, which knows the source id it is writing against.
     */
    suspend fun liveStreams(consume: suspend (Sequence<JsonObject>) -> Unit) =
        streamArray(apiUrl { it["action"] = "get_live_streams" }, consume)

    suspend fun vodStreams(consume: suspend (Sequence<JsonObject>) -> Unit) =
        streamArray(apiUrl { it["action"] = "get_vod_streams" }, consume)

    suspend fun seriesList(consume: suspend (Sequence<JsonObject>) -> Unit) =
        streamArray(apiUrl { it["action"] = "get_series" }, consume)

    /** Per-series detail: seasons and episodes. One request per series, so this
     *  is called lazily when a series detail screen is opened. */
    suspend fun seriesInfo(seriesId: String): JsonObject? =
        getObject(apiUrl {
            it["action"] = "get_series_info"
            it["series_id"] = seriesId
        })

    suspend fun vodInfo(vodId: String): JsonObject? =
        getObject(apiUrl {
            it["action"] = "get_vod_info"
            it["vod_id"] = vodId
        })

    /** The panel's own XMLTV feed. Usually the fastest EPG a user can get. */
    fun epgUrl(): String = buildUrl("xmltv.php") {}

    fun openStream(url: String): InputStream = execute(url)

    // ---- Playable URLs --------------------------------------------------

    /**
     * Live stream URL.
     *
     * `.ts` rather than `.m3u8` by default: the raw MPEG-TS endpoint starts
     * playing roughly a segment-duration sooner than the HLS one (there is no
     * playlist round-trip and no need to buffer a full segment), and channel
     * change speed is what TV users judge an IPTV app on. HLS is available via
     * [liveStreamUrlHls] for providers whose TS endpoint is unreliable.
     */
    fun liveStreamUrl(streamId: String): String =
        "$base/live/${credentials.username.enc()}/${credentials.password.enc()}/$streamId.ts"

    fun liveStreamUrlHls(streamId: String): String =
        "$base/live/${credentials.username.enc()}/${credentials.password.enc()}/$streamId.m3u8"

    fun movieUrl(streamId: String, containerExtension: String?): String =
        "$base/movie/${credentials.username.enc()}/${credentials.password.enc()}/$streamId.${containerExtension.orMp4()}"

    fun episodeUrl(episodeId: String, containerExtension: String?): String =
        "$base/series/${credentials.username.enc()}/${credentials.password.enc()}/$episodeId.${containerExtension.orMp4()}"

    /**
     * Catch-up (timeshift) URL.
     *
     * @param startUtcMillis when the programme began
     * @param durationMinutes its length; the panel clamps this to what it
     *   actually retains, so passing the full programme length is correct even
     *   near the edge of the retention window.
     *
     * The `Y-m-d:H-i` timestamp is formatted in the *server's* timezone, not
     * the device's — panels interpret it locally, and getting this wrong is
     * the usual reason catch-up plays the wrong hour.
     */
    fun timeshiftUrl(streamId: String, startUtcMillis: Long, durationMinutes: Int, serverTimezone: String?): String {
        val zone = serverTimezone?.let { runCatching { java.util.TimeZone.getTimeZone(it) }.getOrNull() }
            ?: java.util.TimeZone.getTimeZone("UTC")
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply { timeZone = zone }
        val start = formatter.format(java.util.Date(startUtcMillis))
        return "$base/streaming/timeshift.php" +
            "?username=${credentials.username.enc()}&password=${credentials.password.enc()}" +
            "&stream=$streamId&start=$start&duration=${durationMinutes.coerceAtLeast(1)}"
    }

    // ---- Plumbing -------------------------------------------------------

    private fun apiUrl(build: (MutableMap<String, String>) -> Unit): String =
        buildUrl("player_api.php", build)

    private fun buildUrl(path: String, build: (MutableMap<String, String>) -> Unit): String {
        val params = linkedMapOf(
            "username" to credentials.username,
            "password" to credentials.password,
        )
        build(params)
        val query = params.entries.joinToString("&") { "${it.key.enc()}=${it.value.enc()}" }
        return "$base/$path?$query"
    }

    private fun execute(url: String): InputStream {
        val response = Http.client().newCall(Http.request(url, userAgent)).execute()
        if (!response.isSuccessful) {
            response.close()
            throw HttpFailure.fromStatus(response.code)
        }
        return response.body?.byteStream() ?: run {
            response.close()
            throw HttpFailure(HttpFailure.Kind.BAD_RESPONSE)
        }
    }

    private suspend fun getObject(url: String): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            execute(url).use { json.parseToJsonElement(it.readBytes().decodeToString()) }.asObject()
        }.onFailure { rethrowTransport(it) }.getOrNull()
    }

    private suspend fun getArray(url: String): JsonArray? = withContext(Dispatchers.IO) {
        runCatching {
            execute(url).use { json.parseToJsonElement(it.readBytes().decodeToString()) } as? JsonArray
        }.onFailure { rethrowTransport(it) }.getOrNull()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun streamArray(url: String, consume: suspend (Sequence<JsonObject>) -> Unit) {
        execute(url).use { stream ->
            try {
                consume(json.decodeToSequence(stream, JsonObject.serializer(), DecodeSequenceMode.ARRAY_WRAPPED))
            } catch (e: Exception) {
                rethrowTransport(e)
                // A panel that answers an empty catalogue with `{}` or an HTML
                // error page lands here. That is an empty section, not a
                // failure of the whole import — a subscription with live TV
                // and no VOD is completely normal.
                Diagnostics.warn("xtream", "Unreadable array response, treating as empty: ${e.message}")
                consume(emptySequence())
            }
        }
    }

    /** Transport problems are real errors; parse problems are handled locally. */
    private fun rethrowTransport(e: Throwable) {
        if (e is HttpFailure) throw e
        if (e is java.io.IOException && e !is kotlinx.serialization.SerializationException) {
            throw HttpFailure.fromException(e)
        }
    }

    private fun String.enc(): String = java.net.URLEncoder.encode(this, "UTF-8")
    private fun String?.orMp4(): String = this?.trim()?.trimStart('.')?.ifBlank { null } ?: "mp4"

    companion object {
        /**
         * Repairs the server URL. Accepts `example.com`, `example.com:8080`,
         * `http://example.com:8080/`, and a full
         * `http://example.com:8080/player_api.php?username=...` pasted from a
         * subscription email.
         */
        fun normaliseBase(input: String): String {
            var text = input.trim()
            if (text.isEmpty()) return text
            if (!text.startsWith("http://", true) && !text.startsWith("https://", true)) {
                text = "http://$text"
            }
            val url: HttpUrl = text.toHttpUrlOrNull() ?: return text.trimEnd('/')
            val builder = StringBuilder()
                .append(url.scheme).append("://").append(url.host)
            // Only keep an explicit port: appending the default one changes
            // nothing but shows up in the UI as noise.
            if (url.port != HttpUrl.defaultPort(url.scheme)) builder.append(':').append(url.port)
            // Preserve a real sub-path (some panels live under /iptv/) while
            // dropping the endpoint filename if one was pasted in.
            val segments = url.pathSegments.filter { it.isNotBlank() && !it.endsWith(".php") }
            if (segments.isNotEmpty()) builder.append('/').append(segments.joinToString("/"))
            return builder.toString()
        }
    }
}

// ---- Tolerant JSON accessors -------------------------------------------
//
// Every one of these returns null rather than throwing. That is the whole
// point: see the class doc for what the API actually sends.

fun JsonElement.asObject(): JsonObject? = this as? JsonObject

fun JsonElement.asString(): String? = (this as? JsonPrimitive)
    ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
    ?.content
    ?.takeIf { it.isNotBlank() && it != "null" }

fun JsonElement.asInt(): Int? = asString()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }

fun JsonElement.asLong(): Long? = asString()?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

fun JsonElement.asBool(): Boolean? = asString()?.lowercase(Locale.US)?.let {
    when (it) {
        "1", "true", "yes" -> true
        "0", "false", "no" -> false
        else -> null
    }
}

/** First non-null value among several spellings of the same field. */
fun JsonObject.firstOf(vararg keys: String): JsonElement? = keys.firstNotNullOfOrNull { this[it] }
