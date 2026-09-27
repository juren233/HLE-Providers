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
        val resolution = QQMusicSongMidResolver.resolve("97773", null, null)
        assertEquals("97773", resolution.numericSongId)
        assertNull(resolution.songName)
        assertNull(resolution.singerName)
    }

    @Test
    fun `numeric passthrough strips SDK flag bits from implausible ids`() {
        // qqmusicsdk SongInfomation.getId() 携带高位标志位：songId | 0x2000000000000000
        assertEquals(
            "107762076",
            QQMusicSongMidResolver.sanitizeNumericSongId("2305843009321456028"),
        )
        assertEquals(
            "107762076",
            QQMusicSongMidResolver.resolve("2305843009321456028", null, null).numericSongId,
        )
        // 正常范围（≤32 位）的数字 ID 原样保留
        assertEquals("107762076", QQMusicSongMidResolver.sanitizeNumericSongId("107762076"))
        assertEquals("97773", QQMusicSongMidResolver.sanitizeNumericSongId("97773"))
        // 非数字输入原样返回，交给 songmid 换算路径
        assertEquals("0039MnYb0qxYhV", QQMusicSongMidResolver.sanitizeNumericSongId("0039MnYb0qxYhV"))
    }

    // musicu.fcg 网关真实响应样本（music.search.SearchCgiService / DoSearchForQQMusicDesktop）
    private val searchRaw = """
        {"code":0,"music.search.SearchCgiService":{"code":0,"data":{"body":{"song":{"list":[
          {"id":390478855,"mid":"004c8nzy40CLsL","name":"菲律宾没有雪",
           "singer":[{"name":"一个小孩"}]},
          {"id":717479938,"mid":"004BO87l2BofMf","name":"菲律宾没有雪(我想要的)",
           "singer":[{"name":"一个小孩"},{"name":"听风叙晚"}]}
        ]}}}}}
    """.trimIndent()

    @Test
    fun `search fallback accepts candidate with exact title and singer`() {
        val resolution = QQMusicSongMidResolver.parseSearchResponse(
            searchRaw, "菲律宾没有雪", "一个小孩",
        )!!
        assertEquals("390478855", resolution.numericSongId)
        assertEquals("菲律宾没有雪", resolution.songName)
        assertEquals("一个小孩", resolution.singerName)
    }

    @Test
    fun `search fallback rejects mismatched singer or title`() {
        // 标题相等但歌手不相等：精确轮拒绝；包含轮可命中「菲律宾没有雪(我想要的)」
        // 合作版条目（同曲不同版本，歌词一致，听风叙晚在候选歌手列表内）
        val collab = QQMusicSongMidResolver.parseSearchResponse(searchRaw, "菲律宾没有雪", "听风叙晚")
        assertEquals("717479938", collab?.numericSongId)
        // 标题不相等（即使歌手相等）：拒绝
        assertNull(QQMusicSongMidResolver.parseSearchResponse(searchRaw, "不存在的歌曲名", "一个小孩"))
        // 缺少服务层键：拒绝
        assertNull(QQMusicSongMidResolver.parseSearchResponse("""{"code":0}""", "x", "y"))
        // 顶层 code 非 0：拒绝
        assertNull(QQMusicSongMidResolver.parseSearchResponse("""{"code":2000}""", "x", "y"))
        // 服务层 code 非 0：拒绝
        assertNull(
            QQMusicSongMidResolver.parseSearchResponse(
                """{"code":0,"music.search.SearchCgiService":{"code":2000,"data":{}}}""", "x", "y",
            ),
        )
        // 非 JSON 响应（如网关返回错误页）：拒绝且不抛异常
        assertNull(QQMusicSongMidResolver.parseSearchResponse("<html></html>", "x", "y"))
    }

    @Test
    fun `search matches multi artist slash separated metadata strings`() {
        // 元数据歌手是多人斜杠串（小米音乐车载场景实测形态），按段匹配
        val raw = """
            {"code":0,"music.search.SearchCgiService":{"code":0,"data":{"body":{"song":{"list":[
              {"id":99,"mid":"x","name":"I Like Me Better","singer":[{"name":"Zach Herron"},{"name":"Lauv"}]}
            ]}}}}}
        """.trimIndent()
        val resolution = QQMusicSongMidResolver.parseSearchResponse(
            raw, "I Like Me Better", "Zach Herron/Lauv",
        )!!
        assertEquals("99", resolution.numericSongId)
    }

    @Test
    fun `search fallback accepts exact title when singer list has extra members`() {
        // 候选歌手列表含额外成员（合作版），但标题精确相等且目标歌手在列表内：采纳
        val resolution = QQMusicSongMidResolver.parseSearchResponse(
            searchRaw, "菲律宾没有雪(我想要的)", "一个小孩",
        )!!
        assertEquals("717479938", resolution.numericSongId)
    }

    @Test
    fun `match normalization ignores case and whitespace`() {
        val raw = """
            {"code":0,"music.search.SearchCgiService":{"code":0,"data":{"body":{"song":{"list":[
              {"id":42,"mid":"m","name":"Love  Somebody","singer":[{"name":"lauv"}]}
            ]}}}}}
        """.trimIndent()
        val resolution = QQMusicSongMidResolver.parseSearchResponse(raw, "love somebody", "LAUV")!!
        assertEquals("42", resolution.numericSongId)
    }

    @Test
    fun `search accepts truncated title via containment after exact pass misses`() {
        // 真机实测：App 标题 golden hour，QQ 真名 In your golden hour（精确轮不中，包含轮采纳）
        val raw = """
            {"code":0,"music.search.SearchCgiService":{"code":0,"data":{"body":{"song":{"list":[
              {"id":236084449,"mid":"002jsOVo03DMQG","name":"In your golden hour","singer":[{"name":"JVKE"}]}
            ]}}}}}
        """.trimIndent()
        val resolution = QQMusicSongMidResolver.parseSearchResponse(raw, "golden hour", "JVKE")!!
        assertEquals("236084449", resolution.numericSongId)
        assertEquals("In your golden hour", resolution.songName)
    }

    @Test
    fun `search prefers exact title over containment candidate listed earlier`() {
        val raw = """
            {"code":0,"music.search.SearchCgiService":{"code":0,"data":{"body":{"song":{"list":[
              {"id":1,"mid":"a","name":"golden hour (Live)","singer":[{"name":"JVKE"}]},
              {"id":2,"mid":"b","name":"golden hour","singer":[{"name":"JVKE"}]}
            ]}}}}}
        """.trimIndent()
        val resolution = QQMusicSongMidResolver.parseSearchResponse(raw, "golden hour", "JVKE")!!
        assertEquals("2", resolution.numericSongId)
    }

    @Test
    fun `search containment requires at least four normalized characters`() {
        val raw = """
            {"code":0,"music.search.SearchCgiService":{"code":0,"data":{"body":{"song":{"list":[
              {"id":3,"mid":"c","name":"晴天笑了","singer":[{"name":"周杰伦"}]}
            ]}}}}}
        """.trimIndent()
        assertNull(QQMusicSongMidResolver.parseSearchResponse(raw, "晴天", "周杰伦"))
    }

    @Test
    fun `search throttle detector flags service level rate limit code`() {
        val raw = """
            {"code":0,"ts":1790509010743,"music.search.SearchCgiService":{"code":2001,"data":{
              "body":{"song":{"list":[]}},"code":0}}}
        """.trimIndent()
        assertTrue(QQMusicSongMidResolver.parseSearchThrottled(raw))
    }

    @Test
    fun `search throttle detector flags top level gateway errors`() {
        val raw = """{"code":500001,"ts":1790508681101,"traceid":"x"}"""
        assertTrue(QQMusicSongMidResolver.parseSearchThrottled(raw))
    }

    @Test
    fun `search throttle detector passes valid empty result through`() {
        val raw = """
            {"code":0,"music.search.SearchCgiService":{"code":0,"data":{"body":{"song":{"list":[]}},"code":0}}}
        """.trimIndent()
        assertTrue(!QQMusicSongMidResolver.parseSearchThrottled(raw))
    }

    @Test
    fun `search throttle detector treats malformed payload as retryable`() {
        assertTrue(QQMusicSongMidResolver.parseSearchThrottled("<html>gateway</html>"))
    }
}
