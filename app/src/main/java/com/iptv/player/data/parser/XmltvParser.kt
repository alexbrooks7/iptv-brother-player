package com.iptv.player.data.parser

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/** A programme as it appears in the XMLTV file, before it is tied to a source. */
data class XmltvProgramme(
    val channelId: String,
    val title: String,
    val description: String?,
    val category: String?,
    val startUtc: Long,
    val endUtc: Long,
    val iconUrl: String?,
)

data class XmltvChannel(
    val id: String,
    val displayNames: List<String>,
    val icon: String?,
)

data class XmltvResult(
    val channels: List<XmltvChannel>,
    val programmeCount: Int,
    val malformed: Int,
)

/**
 * Streaming XMLTV reader.
 *
 * Streaming is not an optimisation here, it is the only workable design: a
 * week of listings for a 5,000-channel subscription is a 150–400 MB XML
 * document, and the target device is a Fire TV Stick Lite with 1 GB of RAM
 * shared with the system. Nothing is ever fully materialised — programmes are
 * handed to [onBatch] a few thousand at a time and the caller writes them
 * straight to Room.
 *
 * Channel definitions *are* collected in full, because there are only as many
 * of them as there are channels and the importer needs the whole map to fall
 * back to display-name matching for playlists that ship no `tvg-id`.
 */
object XmltvParser {

    private const val BATCH = 2_000

    /**
     * @param onBatch called on the parsing thread, in document order. Throwing
     *   from it aborts the parse, which is how cancellation is propagated.
     */
    fun parse(input: InputStream, onBatch: (List<XmltvProgramme>) -> Unit): XmltvResult {
        val channels = ArrayList<XmltvChannel>(512)
        val batch = ArrayList<XmltvProgramme>(BATCH)
        var total = 0
        var malformed = 0

        val parser = Xml.newPullParser().apply {
            // Providers are not consistent about declaring entities they then
            // use; being lenient about namespaces avoids a hard failure on
            // files that are otherwise perfectly readable.
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(maybeGunzip(input), null)
        }

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> readChannel(parser)?.let(channels::add)
                        "programme" -> {
                            val programme = readProgramme(parser)
                            if (programme == null) {
                                malformed++
                            } else {
                                batch += programme
                                total++
                                if (batch.size >= BATCH) {
                                    onBatch(batch.toList())
                                    batch.clear()
                                }
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            // A truncated download is the common cause. Everything parsed up
            // to the break is still valid guide data and worth keeping — a
            // partial guide beats no guide, and the next refresh will fill in.
            malformed++
        }

        if (batch.isNotEmpty()) onBatch(batch.toList())
        return XmltvResult(channels, total, malformed)
    }

    private fun readChannel(parser: XmlPullParser): XmltvChannel? {
        val id = parser.getAttributeValue(null, "id")?.trim().orEmpty()
        val names = ArrayList<String>(2)
        var icon: String? = null
        val depth = parser.depth

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "display-name" -> parser.nextText().trim().takeIf { it.isNotEmpty() }?.let(names::add)
                "icon" -> icon = parser.getAttributeValue(null, "src")?.trim()?.ifBlank { null }
            }
        }
        return if (id.isEmpty()) null else XmltvChannel(id, names, icon)
    }

    private fun readProgramme(parser: XmlPullParser): XmltvProgramme? {
        val channel = parser.getAttributeValue(null, "channel")?.trim().orEmpty()
        val start = parseXmltvTime(parser.getAttributeValue(null, "start"))
        var stop = parseXmltvTime(parser.getAttributeValue(null, "stop"))

        var title: String? = null
        var description: String? = null
        var category: String? = null
        var icon: String? = null
        val depth = parser.depth

        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                // First occurrence wins: multi-language files repeat these
                // tags with a `lang` attribute and the first is the provider's
                // primary language, which is the best default we have without
                // asking the user to configure a preference.
                "title" -> if (title == null) title = parser.nextTextSafe()
                "desc" -> if (description == null) description = parser.nextTextSafe()
                "category" -> if (category == null) category = parser.nextTextSafe()
                "icon" -> if (icon == null) icon = parser.getAttributeValue(null, "src")?.trim()?.ifBlank { null }
            }
        }

        if (channel.isEmpty() || start == null || title.isNullOrBlank()) return null

        // A missing or nonsensical stop time is common on the last entry of a
        // file. Half an hour is the modal slot length and keeps the block
        // visible in the grid instead of collapsing it to zero width.
        if (stop == null || stop <= start) stop = start + 30 * 60_000L

        return XmltvProgramme(channel, title.trim(), description?.trim(), category?.trim(), start, stop, icon)
    }

    private fun XmlPullParser.nextTextSafe(): String? =
        runCatching { nextText() }.getOrNull()?.trim()?.ifBlank { null }

    /**
     * XMLTV timestamps are `YYYYMMDDHHMMSS` optionally followed by a
     * ` +HHMM` offset. The offset is optional in the wild even though the
     * spec discourages that, and shorter forms (`YYYYMMDDHH`) turn up too, so
     * missing components default to zero and a missing offset is read as UTC.
     *
     * Built on Calendar rather than java.time so this stays allocation-cheap
     * when called a few hundred thousand times during one import.
     */
    fun parseXmltvTime(value: String?): Long? {
        val raw = value?.trim() ?: return null
        if (raw.length < 8) return null

        val digits = raw.takeWhile { it.isDigit() }
        if (digits.length < 8) return null

        fun part(from: Int, len: Int, fallback: Int) =
            if (digits.length >= from + len) digits.substring(from, from + len).toIntOrNull() ?: fallback else fallback

        val year = part(0, 4, 1970)
        val month = part(4, 2, 1)
        val day = part(6, 2, 1)
        val hour = part(8, 2, 0)
        val minute = part(10, 2, 0)
        val second = part(12, 2, 0)

        val offsetMillis = parseOffset(raw.substring(digits.length).trim())

        return runCatching {
            GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                isLenient = true // a "32nd of January" rolls over instead of throwing
                clear()
                set(year, month - 1, day, hour, minute, second)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis - offsetMillis
        }.getOrNull()
    }

    private fun parseOffset(text: String): Long {
        if (text.isEmpty()) return 0
        val sign = when (text[0]) {
            '+' -> 1
            '-' -> -1
            else -> return 0
        }
        val digits = text.drop(1).filter { it.isDigit() }
        if (digits.length < 4) return 0
        val hours = digits.substring(0, 2).toIntOrNull() ?: return 0
        val minutes = digits.substring(2, 4).toIntOrNull() ?: return 0
        return sign * (hours * 3_600_000L + minutes * 60_000L)
    }

    /** Same gzip sniffing as the M3U reader — see the note there. */
    private fun maybeGunzip(input: InputStream): InputStream {
        val pushback = PushbackInputStream(input.buffered(64 * 1024), 2)
        val b0 = pushback.read()
        val b1 = pushback.read()
        if (b1 != -1) pushback.unread(b1)
        if (b0 != -1) pushback.unread(b0)
        return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(pushback) else pushback
    }
}
