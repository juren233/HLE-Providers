/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import com.juren233.hle.providers.qqmusic.QQMusicCatalogClient.DetailResult
import com.juren233.hle.providers.qqmusic.QQMusicCatalogClient.MetadataMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QQMusicCatalogClientTest {
    @Test
    fun `parses song detail of an online id`() {
        // 2026-11-09 实测 song_id=1926 的响应（截取）
        val raw = """
            {"code":0,"songinfo":{"code":0,"data":{"track_info":{"id":1926,
              "name":"那么爱你为什么","singer":[{"name":"莫文蔚"},{"name":"黄品源"}],"interval":264}}}}
        """.trimIndent()
        val detail = (QQMusicCatalogClient.parseSongDetail(raw) as DetailResult.Found).detail
        assertEquals("1926", detail.songId)
        assertEquals("那么爱你为什么", detail.name)
        assertEquals("莫文蔚", detail.singer)
        assertEquals(264, detail.durationSeconds)
    }

    @Test
    fun `missing id is not found while gateway errors are failures`() {
        // 实测不存在的 ID：songinfo.code=500
        assertEquals(
            DetailResult.NotFound,
            QQMusicCatalogClient.parseSongDetail("""{"code":0,"songinfo":{"code":500,"data":{}}}"""),
        )
        assertNull(QQMusicCatalogClient.parseSongDetail("""{"code":500001}"""))
        assertNull(QQMusicCatalogClient.parseSongDetail("""{"code":0,"songinfo":{"code":2001}}"""))
        assertNull(QQMusicCatalogClient.parseSongDetail("<html></html>"))
    }

    @Test
    fun `parses metadata match with selected song and candidates`() {
        val raw = """
            {"code":0,"req_0":{"code":0,"data":{"songID":605895210,"lyric":"",
              "vecSongID":[4907722,107770907,0,4907722]}}}
        """.trimIndent()
        assertEquals(
            MetadataMatch.Found("605895210", listOf("4907722", "107770907")),
            QQMusicCatalogClient.parseMetadataMatch(raw),
        )
    }

    @Test
    fun `duration mismatch keeps candidates without a selected song`() {
        // 实测《Broken》带错误时长：songID=0，vecSongID 仍返回
        val raw = """{"code":0,"req_0":{"code":0,"data":{"songID":0,"vecSongID":[4907722]}}}"""
        assertEquals(
            MetadataMatch.Found(null, listOf("4907722")),
            QQMusicCatalogClient.parseMetadataMatch(raw),
        )
    }

    @Test
    fun `no lyric match and empty results are no match`() {
        assertEquals(
            MetadataMatch.NoMatch,
            QQMusicCatalogClient.parseMetadataMatch("""{"code":0,"req_0":{"code":24001,"data":{"songID":0}}}"""),
        )
        assertEquals(
            MetadataMatch.NoMatch,
            QQMusicCatalogClient.parseMetadataMatch("""{"code":0,"req_0":{"code":0,"data":{"songID":0,"vecSongID":[]}}}"""),
        )
    }

    @Test
    fun `gateway and module errors are failures`() {
        assertNull(QQMusicCatalogClient.parseMetadataMatch("""{"code":500001}"""))
        assertNull(QQMusicCatalogClient.parseMetadataMatch("""{"code":0,"req_0":{"code":10000}}"""))
        assertNull(QQMusicCatalogClient.parseMetadataMatch("not json"))
    }
}
