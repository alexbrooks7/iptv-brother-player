package com.iptv.player.data

import com.iptv.player.data.parser.XmltvParser
import com.iptv.player.data.remote.XtreamClient
import com.iptv.player.data.repo.CategoryClassifier
import com.iptv.player.data.repo.normaliseChannelName
import com.iptv.player.data.repo.stableHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class XmltvTimeTest {

    private fun utc(text: String?) = XmltvParser.parseXmltvTime(text)

    @Test
    fun `parses a full timestamp with a positive offset`() {
        // 2024-01-15 12:00:00 +0100 == 11:00:00 UTC
        val expected = 1_705_316_400_000L
        assertEquals(expected, utc("20240115120000 +0100"))
    }

    @Test
    fun `parses a full timestamp with a negative offset`() {
        // 2024-01-15 12:00:00 -0500 == 17:00:00 UTC
        val expected = 1_705_338_000_000L
        assertEquals(expected, utc("20240115120000 -0500"))
    }

    @Test
    fun `a missing offset is read as UTC`() {
        assertEquals(1_705_320_000_000L, utc("20240115120000"))
    }

    @Test
    fun `handles a half-hour offset`() {
        // +0530 (India) — a whole-hour-only implementation gets this wrong.
        assertEquals(1_705_300_200_000L, utc("20240115120000 +0530"))
    }

    @Test
    fun `truncated timestamps default their missing components`() {
        // Date only: midnight UTC.
        assertEquals(utc("20240115000000"), utc("20240115"))
    }

    @Test
    fun `rejects input that is not a timestamp`() {
        assertNull(utc(null))
        assertNull(utc(""))
        assertNull(utc("not a date"))
        assertNull(utc("2024"))
    }

    @Test
    fun `is independent of the device timezone`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val pacific = utc("20240115120000 +0000")
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            val tokyo = utc("20240115120000 +0000")
            assertEquals(pacific, tokyo)
        } finally {
            TimeZone.setDefault(original)
        }
    }
}

class XtreamBaseUrlTest {

    @Test
    fun `adds a missing scheme`() {
        assertEquals("http://example.com", XtreamClient.normaliseBase("example.com"))
    }

    @Test
    fun `keeps an explicit port`() {
        assertEquals("http://example.com:8080", XtreamClient.normaliseBase("http://example.com:8080"))
    }

    @Test
    fun `drops a default port`() {
        assertEquals("https://example.com", XtreamClient.normaliseBase("https://example.com:443"))
    }

    @Test
    fun `strips a trailing slash`() {
        assertEquals("http://example.com:8080", XtreamClient.normaliseBase("http://example.com:8080/"))
    }

    @Test
    fun `strips a pasted player_api URL including its query string`() {
        assertEquals(
            "http://example.com:8080",
            XtreamClient.normaliseBase("http://example.com:8080/player_api.php?username=a&password=b"),
        )
    }

    @Test
    fun `preserves a real sub-path for panels not hosted at the root`() {
        assertEquals("http://example.com/iptv", XtreamClient.normaliseBase("http://example.com/iptv/"))
    }

    @Test
    fun `trims surrounding whitespace from a pasted value`() {
        assertEquals("http://example.com", XtreamClient.normaliseBase("  http://example.com  "))
    }
}

class CategoryClassifierTest {

    @Test
    fun `flags unambiguous adult categories`() {
        listOf("XXX", "Adult", "| ADULT |", "Adults Only", "18+", "For Adults", "XXX Movies")
            .forEach { assertTrue(it, CategoryClassifier.isAdult(it)) }
    }

    @Test
    fun `does not flag categories that merely contain those letters`() {
        // The classic false positives: place names containing "sex", and
        // broadcasters whose name is a flagged word.
        listOf("Sussex Local", "Essex News", "Middlesex TV", "Sports", "Kids", "Documentaries")
            .forEach { assertFalse(it, CategoryClassifier.isAdult(it)) }
    }

    @Test
    fun `does not flag mainstream names that a naive keyword list would catch`() {
        // HOT is an Israeli broadcaster; "Mature" appears in drama categories.
        listOf("HOT TV", "Hot Hits Music", "Mature Drama")
            .forEach { assertFalse(it, CategoryClassifier.isAdult(it)) }
    }
}

class ChannelNameMatchingTest {

    @Test
    fun `normalises case, punctuation and quality suffixes to one form`() {
        val expected = "sky sports main event"
        listOf(
            "Sky Sports Main Event",
            "SKY SPORTS MAIN EVENT HD",
            "Sky Sports: Main Event FHD",
            "UK| Sky Sports Main Event 4K",
            "sky-sports-main-event-uhd",
        ).forEach { assertEquals(it, expected, it.normaliseChannelName()) }
    }

    @Test
    fun `strips several trailing quality markers`() {
        assertEquals("channel one", "Channel One FHD RAW".normaliseChannelName())
    }

    @Test
    fun `keeps a name that is only a quality word`() {
        // Guard against reducing a name to nothing, which would match every
        // other empty-normalising name and attach the wrong guide data.
        assertEquals("hd", "HD".normaliseChannelName())
    }

    @Test
    fun `different channels do not collapse onto the same key`() {
        assertNotEquals(
            "Sky Sports Main Event".normaliseChannelName(),
            "Sky Sports Premier League".normaliseChannelName(),
        )
    }
}

class StableHashTest {

    @Test
    fun `is stable for the same input`() {
        assertEquals("BBC One|http://x".stableHash(), "BBC One|http://x".stableHash())
    }

    @Test
    fun `differs for different inputs`() {
        assertNotEquals("a".stableHash(), "b".stableHash())
    }

    @Test
    fun `matches a known value so a change to the algorithm is caught`() {
        // Favourites and watch history key off this hash, so changing the
        // algorithm silently orphans them. This test exists to make that
        // change impossible to do by accident. The constant is the published
        // FNV-1a 64-bit value for "foobar", which also confirms the
        // implementation is the real algorithm and not a lookalike.
        assertEquals("85944171f73967e8", "foobar".stableHash())
    }
}
