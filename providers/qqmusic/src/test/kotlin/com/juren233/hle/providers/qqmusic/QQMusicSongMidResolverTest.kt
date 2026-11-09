/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        val resolution = QQMusicSongMidResolver.resolve("97773", null, null, trustedId = true)
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
            QQMusicSongMidResolver.resolve("2305843009321456028", null, null, trustedId = true).numericSongId,
        )
        // 正常范围（≤32 位）的数字 ID 原样保留
        assertEquals("107762076", QQMusicSongMidResolver.sanitizeNumericSongId("107762076"))
        assertEquals("97773", QQMusicSongMidResolver.sanitizeNumericSongId("97773"))
        // 非数字输入原样返回，交给 songmid 换算路径
        assertEquals("0039MnYb0qxYhV", QQMusicSongMidResolver.sanitizeNumericSongId("0039MnYb0qxYhV"))
    }


    // ---- 数字 ID 核对（issue #62：QQ 手机版本地歌 MEDIA_ID 是本地库编号）----

    private fun detailOf(id: String, name: String, singer: String) =
        QQMusicCatalogClient.DetailResult.Found(QQMusicCatalogClient.SongDetail(id, name, singer, 200))

    private fun noMatch(title: String, artist: String, seconds: Long): QQMusicCatalogClient.MetadataMatch =
        throw AssertionError("不应按歌名匹配: $title / $artist / $seconds")

    @Test
    fun `trusted numeric id passes through without lookups`() {
        val resolution = QQMusicSongMidResolver.resolveNumericId(
            "97773", null, null, 0L, trustedId = true,
            detail = { throw AssertionError("不应查询详情") },
            match = ::noMatch,
        )
        assertEquals("97773", resolution.numericSongId)
        assertNull(resolution.songName)
    }

    @Test
    fun `online session id is kept when its detail is the same song`() {
        // 维护者手机实测：会话 Espresso (Explicit)，在线库名 Espresso
        val resolution = QQMusicSongMidResolver.resolveNumericId(
            "483138560", "Espresso (Explicit)", "Sabrina Carpenter", 175_000L, false,
            detail = { detailOf(it, "Espresso", "Sabrina Carpenter") },
            match = ::noMatch,
        )
        assertEquals("483138560", resolution.numericSongId)
        assertEquals("Espresso", resolution.songName)
    }

    @Test
    fun `local library id is replaced by verified metadata match`() {
        // issue #62 实测：1926 在线是莫文蔚《那么爱你为什么》，按歌名匹配到林峯《Broken》
        val details = mapOf(
            "1926" to detailOf("1926", "那么爱你为什么", "莫文蔚"),
            "605895210" to detailOf("605895210", "Broken", "林峯"),
        )
        var matchedSeconds = -1L
        val resolution = QQMusicSongMidResolver.resolveNumericId(
            "1926", "Broken", "林峯", 190_400L, false,
            detail = { details.getValue(it) },
            match = { _, _, seconds ->
                matchedSeconds = seconds
                QQMusicCatalogClient.MetadataMatch.Found("605895210", listOf("4907722", "107770907"))
            },
        )
        assertEquals(190L, matchedSeconds)
        assertEquals("605895210", resolution.numericSongId)
        assertEquals("Broken", resolution.songName)
        assertEquals("林峯", resolution.singerName)
        assertEquals(listOf("4907722", "107770907"), resolution.alternatives)
    }

    @Test
    fun `candidates failing identity check are skipped`() {
        val details = mapOf(
            "1921" to detailOf("1921", "没时间", "莫文蔚"),
            "500" to detailOf("500", "讲不出再见 (DJ版)", "某翻唱歌手"),
            "406532865" to detailOf("406532865", "讲不出再见", "谭咏麟"),
        )
        val resolution = QQMusicSongMidResolver.resolveNumericId(
            "1921", "讲不出再见", "谭咏麟", 292_000L, false,
            detail = { details[it] ?: QQMusicCatalogClient.DetailResult.NotFound },
            match = { _, _, _ ->
                QQMusicCatalogClient.MetadataMatch.Found("500", listOf("406532865", "102209"))
            },
        )
        assertEquals("406532865", resolution.numericSongId)
        assertEquals(listOf("102209"), resolution.alternatives)
    }

    @Test
    fun `session id confirmed by match result when detail lookup fails`() {
        val resolution = QQMusicSongMidResolver.resolveNumericId(
            "556300755", "日落", "孙燕姿", 249_000L, false,
            detail = { throw java.io.IOException("network down") },
            match = { _, _, _ ->
                QQMusicCatalogClient.MetadataMatch.Found("733033289", listOf("556300755", "648453000"))
            },
        )
        assertEquals("556300755", resolution.numericSongId)
        assertNull(resolution.songName)
    }

    @Test
    fun `network failure never passes the session id through and is not negatively cached`() {
        var matches = 0
        val failing: (String, String, Long) -> QQMusicCatalogClient.MetadataMatch = { _, _, _ ->
            matches++
            throw java.io.IOException("network down")
        }
        repeat(2) {
            assertThrows(java.io.IOException::class.java) {
                QQMusicSongMidResolver.resolveNumericId(
                    "1920", "Billie Jean", "Michael Jackson", 294_000L, false,
                    detail = { throw java.io.IOException("network down") },
                    match = failing,
                )
            }
        }
        assertEquals(2, matches)
    }

    @Test
    fun `no match throws and writes negative cache`() {
        var matches = 0
        val none: (String, String, Long) -> QQMusicCatalogClient.MetadataMatch = { _, _, _ ->
            matches++
            QQMusicCatalogClient.MetadataMatch.NoMatch
        }
        repeat(2) {
            assertThrows(IllegalStateException::class.java) {
                QQMusicSongMidResolver.resolveNumericId(
                    "987001", "懂了 (台版)", "周深", 0L, false,
                    detail = { detailOf(it, "另一首", "别人") },
                    match = none,
                )
            }
        }
        // 原歌名与去括号歌名各查一次；第二次调用命中负缓存，不再发请求
        assertEquals(2, matches)
    }

    @Test
    fun `multi artist local song retries with each split artist`() {
        // 2026-11-09 真机：本地《勿听》会话 ID 47 在线是《爱上你只是我的错》；
        // 接口对整串歌手返回 24001，只传「张紫宁」命中 509884965
        val artist = "张紫宁/赵紫骅/游戏科学/8082Audio"
        val details = mapOf(
            "47" to detailOf("47", "爱上你只是我的错", "dMDM"),
            "509884965" to detailOf("509884965", "勿听", "张紫宁"),
        )
        val queried = mutableListOf<Pair<String, String>>()
        val resolution = QQMusicSongMidResolver.resolveNumericId(
            "47", "勿听", artist, 255_000L, false,
            detail = { details[it] ?: QQMusicCatalogClient.DetailResult.NotFound },
            match = { title, singer, _ ->
                queried += title to singer
                if (singer == "张紫宁") {
                    QQMusicCatalogClient.MetadataMatch.Found(null, listOf("509884965", "509884987"))
                } else {
                    QQMusicCatalogClient.MetadataMatch.NoMatch
                }
            },
        )
        assertEquals(listOf("勿听" to artist, "勿听" to "张紫宁"), queried)
        assertEquals("509884965", resolution.numericSongId)
        assertEquals(listOf("509884987"), resolution.alternatives)
    }

    @Test
    fun `bracketed title retries without the annotation`() {
        // 2026-11-09 真机：本地《Question...? (Explicit)》会话 ID 34 在线是 BEYOND《太空》
        val details = mapOf(
            "34" to detailOf("34", "太空", "BEYOND"),
            "378883302" to detailOf("378883302", "Question...?", "Taylor Swift"),
        )
        val queried = mutableListOf<String>()
        val resolution = QQMusicSongMidResolver.resolveNumericId(
            "34", "Question...? (Explicit)", "Taylor Swift", 210_000L, false,
            detail = { details[it] ?: QQMusicCatalogClient.DetailResult.NotFound },
            match = { title, _, _ ->
                queried += title
                if (title == "Question...?") {
                    QQMusicCatalogClient.MetadataMatch.Found("378883302", listOf("413370134"))
                } else {
                    QQMusicCatalogClient.MetadataMatch.NoMatch
                }
            },
        )
        assertEquals(listOf("Question...? (Explicit)", "Question...?"), queried)
        assertEquals("378883302", resolution.numericSongId)
    }

    @Test
    fun `session id in a relaxed query result still needs identity check`() {
        // 放宽后的查询（单个歌手）不能直接确认会话 ID，会话 ID 与其他候选一样要过核对
        val details = mapOf(
            "987010" to detailOf("987010", "另一首", "别人"),
            "987011" to detailOf("987011", "勿听", "张紫宁"),
        )
        val resolution = QQMusicSongMidResolver.resolveNumericId(
            "987010", "勿听", "张紫宁/赵紫骅", 255_000L, false,
            detail = { details[it] ?: QQMusicCatalogClient.DetailResult.NotFound },
            match = { _, singer, _ ->
                if (singer == "张紫宁") {
                    QQMusicCatalogClient.MetadataMatch.Found("987010", listOf("987011"))
                } else {
                    QQMusicCatalogClient.MetadataMatch.NoMatch
                }
            },
        )
        assertEquals("987011", resolution.numericSongId)
    }

    @Test
    fun `blank artist is never sent to metadata matching`() {
        // 实测歌手为空时服务端会模糊匹配到别的歌（Broken → Broken Cities）
        assertThrows(IllegalStateException::class.java) {
            QQMusicSongMidResolver.resolveNumericId(
                "987005", "Broken", " ", 190_000L, false,
                detail = { detailOf(it, "那么爱你为什么", "莫文蔚") },
                match = ::noMatch,
            )
        }
    }

    @Test
    fun `verified resolution is cached per song identity`() {
        var details = 0
        val lookup: (String) -> QQMusicCatalogClient.DetailResult = {
            details++
            detailOf(it, "分手快乐", "梁静茹")
        }
        val first = QQMusicSongMidResolver.resolveNumericId("987004", "分手快乐", "梁静茹", 0L, false, lookup, ::noMatch)
        val second = QQMusicSongMidResolver.resolveNumericId("987004", "分手快乐", "梁静茹", 0L, false, lookup, ::noMatch)
        assertEquals(first, second)
        assertEquals(1, details)
        // 本地库数字 ID 可能被复用为另一首歌：不同标题/歌手组合不命中旧换算
        assertThrows(IllegalStateException::class.java) {
            QQMusicSongMidResolver.resolveNumericId("987004", "另一首", "别人", 0L, false, lookup) { _, _, _ ->
                QQMusicCatalogClient.MetadataMatch.NoMatch
            }
        }
    }

    @Test
    fun `untrusted numeric id strips flag bits before detail lookup`() {
        var looked: String? = null
        val resolution = QQMusicSongMidResolver.resolveNumericId(
            "2305843009321456028", "虚拟", "陈粒", 0L, false,
            detail = { looked = it; detailOf(it, "虚拟", "陈粒") },
            match = ::noMatch,
        )
        assertEquals("107762076", looked)
        assertEquals("107762076", resolution.numericSongId)
    }

    @Test
    fun `songmid uses single song interface before metadata matching`() {
        val resolution = QQMusicSongMidResolver.resolveSongMid(
            "002N4Yw52prWDN", "世界が終るまでは… (直到世界尽头)", "WANDS", 0L,
            singleSong = { QQMusicSongMidResolver.SongMidResolution("245587122", "世界が終るまでは…", "WANDS") },
            detail = { throw AssertionError("不应查询详情") },
            match = ::noMatch,
        )
        assertEquals("245587122", resolution.numericSongId)
    }

    @Test
    fun `songmid falls back to verified metadata match when single song misses`() {
        val resolution = QQMusicSongMidResolver.resolveSongMid(
            "000Afpal3mCeFw", "Stay Gold", "米津玄師", 0L,
            singleSong = { null },
            detail = { detailOf(it, "Stay Gold", "米津玄師") },
            match = { _, _, _ -> QQMusicCatalogClient.MetadataMatch.Found("777", emptyList()) },
        )
        assertEquals("777", resolution.numericSongId)
    }

    @Test
    fun `verify alternative rejects different songs`() {
        assertNull(
            QQMusicSongMidResolver.verifyAlternative("1", "Broken", "林峯") {
                detailOf(it, "Broken Cities", "Charlie Brennan")
            },
        )
        assertNull(
            QQMusicSongMidResolver.verifyAlternative("2", "Broken", "林峯") {
                QQMusicCatalogClient.DetailResult.NotFound
            },
        )
    }

    // ---- App 关联 ID（2026-11-09：主进程快照的 SongInfo ID 经广播转给播放进程）----

    @Test
    fun `app linked ID is adopted when its detail is the same song`() {
        // 2026-11-09 真机：QQ 给《Hello》关联的是 105345627，按歌名匹配会选错 200672361
        val resolution = QQMusicSongMidResolver.resolveAppLinked(
            "46", "105345627", "Hello", "Adele",
            detail = { detailOf(it, "Hello", "Adele") },
        )!!
        assertEquals("105345627", resolution.numericSongId)
        assertEquals("Hello", resolution.songName)
        assertEquals("Adele", resolution.singerName)
    }

    @Test
    fun `app linked ID mismatching identity is rejected and negatively cached`() {
        // 快照里也可能是本地库编号（真机出现过 next=36）：详情核对不过就不采用
        var details = 0
        repeat(2) {
            assertNull(
                QQMusicSongMidResolver.resolveAppLinked(
                    "46", "36", "Hello", "Adele",
                    detail = {
                        details++
                        detailOf(it, "爱上你只是我的错", "dMDM")
                    },
                ),
            )
        }
        // 第二次命中负缓存，不再查详情
        assertEquals(1, details)
    }

    @Test
    fun `app linked ID network failure returns null without negative cache`() {
        var details = 0
        repeat(2) {
            assertNull(
                QQMusicSongMidResolver.resolveAppLinked(
                    "987021", "987022", "Hello", "Adele",
                    detail = {
                        details++
                        throw java.io.IOException("network down")
                    },
                ),
            )
        }
        // 网络异常不记为「不是同一首」，下次仍会再核对
        assertEquals(2, details)
    }

    @Test
    fun `app linked ID is sanitized before verification`() {
        // 快照 ID 可能带 SDK 标志位（songId | 0x2000000000000000）
        var looked: String? = null
        val resolution = QQMusicSongMidResolver.resolveAppLinked(
            "46", "2305843009321456028", "虚拟", "陈粒",
            detail = {
                looked = it
                detailOf(it, "虚拟", "陈粒")
            },
        )!!
        assertEquals("107762076", looked)
        assertEquals("107762076", resolution.numericSongId)
    }

    @Test
    fun `non numeric app linked ID is not verified`() {
        assertNull(
            QQMusicSongMidResolver.resolveAppLinked(
                "46", "003iXtVK2B6Zk6", "Hello", "Adele",
                detail = { throw AssertionError("不应查询详情") },
            ),
        )
    }

    @Test
    fun `verified app linked resolution is cached per song identity`() {
        // resolve() 无法注入依赖：这里验证已核对过的关联 ID 命中换算缓存后，
        // 后续同曲换算直接复用、不再发请求
        val first = QQMusicSongMidResolver.resolveAppLinked(
            "987020", "105345627", "Hello", "Adele",
            detail = { detailOf(it, "Hello", "Adele") },
        )!!
        var details = 0
        val second = QQMusicSongMidResolver.resolveAppLinked(
            "987020", "105345627", "Hello", "Adele",
            detail = {
                details++
                detailOf(it, "Hello", "Adele")
            },
        )!!
        assertEquals(first, second)
        assertEquals(0, details)
    }

    @Test
    fun `trusted id passthrough ignores app linked ID`() {
        // HD/MIUI 的快照 ID 已经换算器信任：不因附带关联 ID 多打一次详情请求
        val resolution = QQMusicSongMidResolver.resolve(
            "463324123", "this is what winter feels like", "JVKE",
            trustedId = true,
            appLinkedSongId = "105345627",
        )
        assertEquals("463324123", resolution.numericSongId)
        assertNull(resolution.songName)
    }
}
