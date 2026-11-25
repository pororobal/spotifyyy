package com.spotilyric.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {
    @Test
    fun parsesCentisecondsAndMilliseconds() {
        val lines = LrcParser.parse(
            """
            [00:01.25]첫 줄
            [00:03.125]둘째 줄
            """.trimIndent(),
            totalDurationMs = 5_000L,
        )

        assertEquals(2, lines.size)
        assertEquals(1_250L, lines[0].startTimeMs)
        assertEquals(3_125L, lines[0].endTimeMs)
        assertEquals(5_000L, lines[1].endTimeMs)
    }

    @Test
    fun supportsMultipleTimestampsOnOneLine() {
        val lines = LrcParser.parse("[00:01.00][00:02.00]반복", 3_000L)

        assertEquals(listOf(1_000L, 2_000L), lines.map { it.startTimeMs })
        assertEquals(listOf("반복", "반복"), lines.map { it.text })
    }
}
