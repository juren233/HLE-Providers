/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qishui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QishuiLyricsParserTest {
    @Test
    fun `parses KRC word timing from the API payload`() {
        val lines = QishuiLyricsParser.parse(
            QishuiLyricPayload(
                trackId = "123456",
                type = "KRC",
                content = "[1000,2500]<0,500,0>Hello <500,750,0>Qishui",
                translations = emptyList(),
            ),
        )

        assertEquals(1, lines.size)
        assertEquals("Hello Qishui", lines.single().text)
        assertEquals(1_000L, lines.single().begin)
        assertEquals(3_500L, lines.single().end)
        assertEquals(listOf(1_000L, 1_500L), lines.single().words.map { it.begin })
        assertEquals(listOf(1_500L, 2_250L), lines.single().words.map { it.end })
    }

    @Test
    fun `aligns API translation LRC by exact timestamp`() {
        val lines = QishuiLyricsParser.parse(
            QishuiLyricPayload(
                trackId = "123456",
                type = "KRC",
                content = """
                    [1000,1200]<0,600,0>first
                    [2200,1300]<0,700,0>second
                """.trimIndent(),
                translations = listOf(
                    """
                    [00:01.00]第一句
                    [00:02.20]第二句
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals(listOf("第一句", "第二句"), lines.map { it.translation })
    }

    @Test
    fun `keeps ordinary LRC and infers line ends`() {
        val lines = QishuiLyricsParser.parse(
            QishuiLyricPayload(
                trackId = "123456",
                type = "LRC",
                content = "[00:01.25]first\n[00:03.500]second",
                translations = emptyList(),
            ),
            durationMs = 9_000L,
        )

        assertEquals(1_250L, lines[0].begin)
        assertEquals(3_500L, lines[0].end)
        assertEquals(9_000L, lines[1].end)
        assertNull(lines[0].translation)
    }
}
