/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QQMusicSongMidResolverTest {
    @Test
    fun `treats numeric media ids as numeric song ids`() {
        assertTrue(QQMusicSongMidResolver.isNumericSongId("97773"))
        assertTrue(QQMusicSongMidResolver.isNumericSongId("0"))
        assertTrue(QQMusicSongMidResolver.isNumericSongId("509076686"))
    }

    @Test
    fun `treats songmid shaped ids as non numeric`() {
        assertFalse(QQMusicSongMidResolver.isNumericSongId("0039MnYb0qxYhV"))
        assertFalse(QQMusicSongMidResolver.isNumericSongId("003Qui1q2u1Zho"))
        assertFalse(QQMusicSongMidResolver.isNumericSongId(""))
        assertFalse(QQMusicSongMidResolver.isNumericSongId("97773abc"))
        assertFalse(QQMusicSongMidResolver.isNumericSongId(" 97773"))
        assertFalse(QQMusicSongMidResolver.isNumericSongId("-1"))
    }

    @Test
    fun `parses authoritative song name and singer from single song response`() {
        val raw = """
            {"code":0,"data":[{"id":268716958,"name":"Love Somebody",
              "singer":[{"name":"LAUV","mid":"002MDGgE0VbTcV"}],"album":{"id":123}}]}
        """.trimIndent()
        val resolution = QQMusicSongMidResolver.parseSingleSongResponse(raw)!!
        assertEquals("268716958", resolution.numericSongId)
        assertEquals("Love Somebody", resolution.songName)
        assertEquals("LAUV", resolution.singerName)
    }

    @Test
    fun `gray songs return empty data and parse to null`() {
        // 无版权/灰色歌曲实测返回 code=0 且 data 为空数组
        val raw = """{"code":0,"data":[],"url":null,"url1":{},"extra_data":[]}"""
        assertNull(QQMusicSongMidResolver.parseSingleSongResponse(raw))
        assertNull(QQMusicSongMidResolver.parseSingleSongResponse("""{"code":0}"""))
        assertNull(QQMusicSongMidResolver.parseSingleSongResponse("""{"code":2000}"""))
        // 裸端点返回的 HTML 页必须解析为 null 而不是抛异常
        assertNull(QQMusicSongMidResolver.parseSingleSongResponse("<!DOCTYPE html><html></html>"))
    }

    @Test
    fun `numeric passthrough keeps song id without names`() {
        val resolution = QQMusicSongMidResolver.resolve("97773")
        assertEquals("97773", resolution.numericSongId)
        assertNull(resolution.songName)
        assertNull(resolution.singerName)
    }
}
