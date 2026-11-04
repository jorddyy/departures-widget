package app.departures.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LineColorsTest {
    @Test
    fun `bundled table has official colours for major networks`() {
        assertEquals(LineColor(0xFFB82F89.toInt(), 0xFFFFFFFF.toInt()), LineColors.of("TPG", "18"))
        assertNotNull(LineColors.of("BVB", "8"))
        assertNotNull(LineColors.of("BLT", "11"))
    }

    @Test
    fun `operator codes are matched on their first word, ignoring case`() {
        assertEquals(LineColors.of("VBZ", "4"), LineColors.of("VBZ    F", "4"))
        assertNotNull(LineColors.of("vbz", "4"))
        assertEquals("SVB", LineColors.operatorKey(" SVB Auto "))
        assertNull(LineColors.operatorKey("  "))
    }

    @Test
    fun `unknown lines and operators have no colour`() {
        assertNull(LineColors.of("TPG", "9999"))
        assertNull(LineColors.of(null, "18"))
        assertNull(LineColors.of("XYZ", "18"))
    }

    @Test
    fun `parser skips comments, header and bad rows`() {
        val table = LineColors.parse(
            """
            # comment
            operator,line,background,text
            ABC,7,#112233,#ffffff
            ABC,8,notacolour,#ffffff
            ABC,9
            """.trimIndent(),
        )
        assertEquals(mapOf("ABC|7" to LineColor(0xFF112233.toInt(), 0xFFFFFFFF.toInt())), table)
    }

    @Test
    fun `every bundled badge is readable`() {
        fun lum(c: Int): Double {
            fun ch(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch(c shr 16 and 0xFF) + 0.7152 * ch(c shr 8 and 0xFF) + 0.0722 * ch(c and 0xFF)
        }
        val unreadable = LineColors.all.filter { (_, c) ->
            val (hi, lo) = listOf(lum(c.background), lum(c.text)).sortedDescending()
            (hi + 0.05) / (lo + 0.05) < 4.5
        }
        assertEquals(emptyMap<String, LineColor>(), unreadable)
        assertEquals(true, LineColors.all.size > 200)
    }
}
