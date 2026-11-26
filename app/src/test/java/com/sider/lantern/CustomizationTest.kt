package com.sider.lantern

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomizationTest {
    @Test fun shortenedRetentionAllowsRestorationDuringGracePeriod() {
        val now = 100 * DAY_MILLIS
        val removed = now - 8 * DAY_MILLIS
        val rule = TrashRetention.parse("7:${now + DAY_MILLIS}")
        assertFalse(rule.expires(removed, now))
        assertTrue(rule.expires(removed, now + DAY_MILLIS))
        assertFalse(rule.expires(now, now + DAY_MILLIS))
        assertFalse(TrashRetention.parse("0:0").expires(removed, now + 365 * DAY_MILLIS))
        assertEquals(30, TrashRetention.parse("invalid").days)
    }

    @Test fun imageDefaultsPreserveOldSerializedPresets() {
        val stored = Json.decodeFromString<ImageOptions>("{}")
        assertEquals(728, stored.width)
        assertEquals("default", stored.font)
        val recommended = ImageOptions.recommended(vertical = false).validated()
        assertEquals("3:4", aspectLabel(recommended.width, recommended.height))
        assertEquals("noto_serif", recommended.font)
        assertFalse(recommended.vertical)
        assertEquals(recommended, Json.decodeFromString<ImageOptions>(Json.encodeToString(recommended)))
    }

    @Test fun midnightInJapaneseIsTwentyFourHourTimeAndParagraphsCountBlocks() {
        val timestamp = Instant.parse("2026-11-25T15:30:00Z")
        val japan = ZoneId.of("Asia/Tokyo")
        assertEquals("00:30", compactDate(timestamp.toEpochMilli(), Locale.JAPAN, japan, timestamp.atZone(japan).toLocalDate()))
        assertEquals(5 to 2, documentStructure("一行目\r\n続き\r\n\r\n二段落\n"))
        assertEquals(0 to 0, documentStructure(""))
    }
}
