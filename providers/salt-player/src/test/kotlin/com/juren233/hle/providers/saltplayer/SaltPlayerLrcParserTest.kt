/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaltPlayerLrcParserTest {
    @Test
    fun `plain lrc lines end at the following line`() {
        val document = SaltPlayerLrcParser.parse(
            """
            [00:01.00]First
            [00:03.50]Second
            """.trimIndent(),
            durationMs = 8_000L,
        )!!

        assertEquals(listOf(1_000L, 3_500L), document.lines.map { it.beginMs })
        assertEquals(listOf(3_500L, 8_000L), document.lines.map { it.endMs })
        assertEquals(listOf("First", "Second"), document.lines.map { it.mainText })
    }

    @Test
    fun `enhanced lrc becomes word timing within one active line`() {
        val document = SaltPlayerLrcParser.parse(
            """
            [00:01.00]<00:01.00>Hello <00:01.60>world
            [00:03.00]Next
            """.trimIndent(),
        )!!

        val line = document.lines.first()
        assertEquals("Hello world", line.mainText)
        assertEquals(listOf("Hello ", "world"), line.cells.map { it.text })
        assertEquals(listOf(1_000L, 1_600L), line.cells.map { it.beginMs })
        assertEquals(listOf(1_600L, 3_000L), line.cells.map { it.endMs })
        assertEquals(3_000L, line.endMs)
    }

    @Test
    fun `same timestamp plain line is kept as translation`() {
        val document = SaltPlayerLrcParser.parse(
            """
            [00:01.00]<00:01.00>Hello
            [00:01.00]你好
            [00:02.00]Next
            """.trimIndent(),
        )!!

        val line = document.lines.first()
        assertEquals("Hello", line.mainText)
        assertEquals("你好", line.translation)
        assertEquals(2_000L, line.endMs)
    }

    @Test
    fun `offset moves line and word timing together`() {
        val document = SaltPlayerLrcParser.parse(
            """
            [offset:250]
            [00:01.00]<00:01.00>A
            [00:02.00]B
            """.trimIndent(),
        )!!

        assertEquals(1_250L, document.lines.first().beginMs)
        assertEquals(1_250L, document.lines.first().cells.single().beginMs)
        assertEquals(2_250L, document.lines.first().endMs)
    }

    @Test
    fun `returns null when no timed lyric exists`() {
        assertNull(SaltPlayerLrcParser.parse("[ar:Artist]\nUntimed text"))
    }
}
