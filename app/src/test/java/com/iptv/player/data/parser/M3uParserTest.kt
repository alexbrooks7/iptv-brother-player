package com.iptv.player.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * The parser's contract is "never throw, never lose a good entry, never keep a
 * bad one", so these tests are mostly a catalogue of the malformed input real
 * playlists contain. Each case here is something observed in a playlist in the
 * wild, not a hypothetical.
 */
class M3uParserTest {

    private fun parse(text: String) = M3uParser.parse(text.byteInputStream())

    @Test
    fun `parses a well-formed entry with all attributes`() {
        val result = parse(
            """
            #EXTM3U url-tvg="http://example.com/xmltv.php"
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="http://img/1.png" tvg-chno="101" group-title="UK",BBC One HD
            http://server/live/u/p/1.ts
            """.trimIndent()
        )

        assertEquals(1, result.entries.size)
        val entry = result.entries.first()
        assertEquals("BBC One HD", entry.name)
        assertEquals("bbc1.uk", entry.tvgId)
        assertEquals("http://img/1.png", entry.logo)
        assertEquals("UK", entry.group)
        assertEquals(101, entry.number)
        assertEquals(listOf("http://example.com/xmltv.php"), result.epgUrls)
    }

    @Test
    fun `survives a playlist with no header`() {
        val result = parse(
            """
            #EXTINF:-1,Channel A
            http://server/a.ts
            """.trimIndent()
        )
        assertEquals(1, result.entries.size)
    }

    @Test
    fun `keeps the name after the last comma, not the first`() {
        // Durations and attribute values contain commas; only the final one
        // separates the display name.
        val result = parse(
            """
            #EXTINF:-1 tvg-name="Sports, News and More" group-title="A,B",Sky Sports Main Event
            http://server/b.ts
            """.trimIndent()
        )
        assertEquals("Sky Sports Main Event", result.entries.single().name)
    }

    @Test
    fun `handles single quotes and unquoted attribute values`() {
        val result = parse(
            """
            #EXTINF:-1 tvg-id='cnn.us' tvg-chno=205 group-title=News,CNN
            http://server/c.ts
            """.trimIndent()
        )
        val entry = result.entries.single()
        assertEquals("cnn.us", entry.tvgId)
        assertEquals(205, entry.number)
        assertEquals("News", entry.group)
    }

    @Test
    fun `an unbalanced quote does not swallow the rest of the line`() {
        val result = parse(
            """
            #EXTINF:-1 tvg-id="broken group-title="News",Channel D
            http://server/d.ts
            """.trimIndent()
        )
        // The entry survives with a usable name and group even though one
        // attribute is unparseable.
        assertEquals(1, result.entries.size)
        assertEquals("Channel D", result.entries.single().name)
    }

    @Test
    fun `EXTINF with no URL is skipped and counted`() {
        val result = parse(
            """
            #EXTINF:-1,Orphaned Channel
            #EXTINF:-1,Real Channel
            http://server/e.ts
            """.trimIndent()
        )
        assertEquals(1, result.entries.size)
        assertEquals("Real Channel", result.entries.single().name)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `a trailing EXTINF at end of file is counted as skipped`() {
        val result = parse(
            """
            #EXTINF:-1,Good
            http://server/f.ts
            #EXTINF:-1,Truncated
            """.trimIndent()
        )
        assertEquals(1, result.entries.size)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `entries with unusable URLs are skipped rather than stored`() {
        val result = parse(
            """
            #EXTINF:-1,Bad Scheme
            not-a-url
            #EXTINF:-1,Empty

            #EXTINF:-1,Fine
            https://server/g.m3u8
            """.trimIndent()
        )
        assertEquals(1, result.entries.size)
        assertEquals("Fine", result.entries.single().name)
        assertEquals(2, result.skipped)
    }

    @Test
    fun `exact duplicates are dropped and counted separately from skips`() {
        val result = parse(
            """
            #EXTINF:-1,Same
            http://server/h.ts
            #EXTINF:-1,Same
            http://server/h.ts
            """.trimIndent()
        )
        assertEquals(1, result.entries.size)
        assertEquals(1, result.duplicates)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `same URL under two names is kept - SD and HD aliases are legitimate`() {
        val result = parse(
            """
            #EXTINF:-1,Channel HD
            http://server/i.ts
            #EXTINF:-1,Channel SD
            http://server/i.ts
            """.trimIndent()
        )
        assertEquals(2, result.entries.size)
        assertEquals(0, result.duplicates)
    }

    @Test
    fun `EXTGRP supplies the group when group-title is absent`() {
        val result = parse(
            """
            #EXTINF:-1,Channel J
            #EXTGRP:Documentaries
            http://server/j.ts
            """.trimIndent()
        )
        assertEquals("Documentaries", result.entries.single().group)
    }

    @Test
    fun `EXTVLCOPT user agent and referrer are captured`() {
        val result = parse(
            """
            #EXTINF:-1,Channel K
            #EXTVLCOPT:http-user-agent=CustomAgent/2.0
            #EXTVLCOPT:http-referrer=http://ref.example
            http://server/k.ts
            """.trimIndent()
        )
        val entry = result.entries.single()
        assertEquals("CustomAgent/2.0", entry.userAgent)
        assertEquals("http://ref.example", entry.referrer)
    }

    @Test
    fun `unknown directives such as KODIPROP are ignored without losing the entry`() {
        val result = parse(
            """
            #EXTINF:-1,Channel L
            #KODIPROP:inputstream.adaptive.license_type=clearkey
            http://server/l.ts
            """.trimIndent()
        )
        assertEquals(1, result.entries.size)
    }

    @Test
    fun `catchup is read from any of its spellings`() {
        val days = parse(
            """
            #EXTINF:-1 catchup="default" catchup-days="14",A
            http://server/m.ts
            #EXTINF:-1 timeshift="3",B
            http://server/n.ts
            #EXTINF:-1 catchup="default",C
            http://server/o.ts
            #EXTINF:-1,D
            http://server/p.ts
            """.trimIndent()
        ).entries.map { it.catchupDays }

        // Explicit window, alternative spelling, flag-only (defaults to a
        // week), and none at all.
        assertEquals(listOf(14, 3, 7, 0), days)
    }

    @Test
    fun `a missing name falls back to tvg-name and then to the URL`() {
        val result = parse(
            """
            #EXTINF:-1 tvg-name="From Attribute",
            http://server/q.ts
            #EXTINF:-1,
            http://server/some-channel-name.ts
            """.trimIndent()
        )
        assertEquals("From Attribute", result.entries[0].name)
        assertEquals("some channel name", result.entries[1].name)
    }

    @Test
    fun `strips a UTF-8 BOM and tolerates CRLF line endings`() {
        val result = parse("﻿#EXTM3U\r\n#EXTINF:-1,Channel R\r\nhttp://server/r.ts\r\n")
        assertEquals(1, result.entries.size)
        assertEquals("Channel R", result.entries.single().name)
    }

    @Test
    fun `reads a gzipped playlist that was served without a content-encoding header`() {
        val plain = "#EXTM3U\n#EXTINF:-1,Gzipped\nhttp://server/s.ts\n"
        val gzipped = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(plain.toByteArray()) }
        }.toByteArray()

        val result = M3uParser.parse(gzipped.inputStream())
        assertEquals("Gzipped", result.entries.single().name)
    }

    @Test
    fun `a non-http logo URL is dropped rather than stored as a broken reference`() {
        val result = parse(
            """
            #EXTINF:-1 tvg-logo="",Channel T
            http://server/t.ts
            """.trimIndent()
        )
        assertNull(result.entries.single().logo)
    }

    @Test
    fun `handles a large playlist without pathological slowdown`() {
        val text = buildString {
            appendLine("#EXTM3U")
            repeat(10_000) { index ->
                appendLine("#EXTINF:-1 tvg-id=\"id$index\" group-title=\"Group ${index % 50}\",Channel $index")
                appendLine("http://server/live/u/p/$index.ts")
            }
        }
        val started = System.nanoTime()
        val result = parse(text)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(10_000, result.entries.size)
        assertEquals(50, result.entries.mapNotNull { it.group }.distinct().size)
        // Generous bound: this runs on CI machines of unknown speed. The point
        // is to catch accidental O(n^2) behaviour, not to benchmark.
        assertTrue("10k entries took ${elapsedMs}ms", elapsedMs < 5_000)
    }
}
