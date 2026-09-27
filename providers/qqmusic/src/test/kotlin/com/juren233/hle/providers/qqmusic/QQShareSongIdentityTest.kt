/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QQShareSongIdentityTest {
    @Test
    fun `extracts songmid and clean identity from real miui focus param payload`() {
        // 2026-09-27 真机会话实测（Exhale）：MEDIA_ID=001Q5MKR47b4JH 查无此歌，
        // shareData 的 songmid 才是真实歌曲
        val payload = """
            {"param_v2":{"param_island":{"shareData":{"title":"Exhale","pic":"",
              "content":"Sabrina Carpenter",
              "shareContent":"https://i.y.qq.com/n2/m/musiclite/playsong/index.html?app_type=qmlite&&songmid=004OZHd728dJgm",
              "sharePic":""}}}}
        """.trimIndent()
        val identity = extractShareSongIdentity(listOf(payload))!!
        assertEquals("004OZHd728dJgm", identity.songMid)
        assertEquals("Exhale", identity.title)
        assertEquals("Sabrina Carpenter", identity.artist)
    }

    @Test
    fun `extracts chinese song identity`() {
        val payload = """
            {"param_v2":{"param_island":{"shareData":{"title":"爱在西元前","pic":"",
              "content":"周杰伦",
              "shareContent":"https://i.y.qq.com/n2/m/musiclite/playsong/index.html?app_type=qmlite&&songmid=003xxxxTestMid","sharePic":""}}}}
        """.trimIndent()
        val identity = extractShareSongIdentity(listOf(payload))!!
        assertEquals("003xxxxTestMid", identity.songMid)
        assertEquals("爱在西元前", identity.title)
        assertEquals("周杰伦", identity.artist)
    }

    @Test
    fun `unescapes json escapes in title and share url`() {
        val payload = """
            {"param_v2":{"param_island":{"shareData":{"title":"Stand \"Deluxe\" Wait",
              "content":"DJ Okawari",
              "shareContent":"https:\/\/i.y.qq.com\/n2\/m\/musiclite\/playsong\/index.html?app_type=qmlite&&songmid=002Zkt5w2Kc9Xy","sharePic":""}}}}
        """.trimIndent()
        val identity = extractShareSongIdentity(listOf(payload))!!
        assertEquals("002Zkt5w2Kc9Xy", identity.songMid)
        assertEquals("""Stand "Deluxe" Wait""", identity.title)
    }

    @Test
    fun `prefers shareData marked value over unrelated songmid urls`() {
        val unrelated = "https://y.qq.com/n/yqq/song/00otherMid.html?songmid=00WRONG"
        val focus = """{"shareData":{"title":"T","content":"A",
            "shareContent":"https://x/?songmid=00RIGHT"}}"""
        val identity = extractShareSongIdentity(listOf(unrelated, focus))!!
        assertEquals("00RIGHT", identity.songMid)
    }

    @Test
    fun `falls back to any string carrying songmid when no shareData marker`() {
        val identity = extractShareSongIdentity(listOf("some other extra", "songmid=003abcDEF123"))!!
        assertEquals("003abcDEF123", identity.songMid)
        assertNull(identity.title)
        assertNull(identity.artist)
    }

    @Test
    fun `returns null when no songmid anywhere`() {
        assertNull(
            extractShareSongIdentity(
                listOf("""{"shareData":{"title":"T","content":"A"}}""", null, ""),
            ),
        )
        assertNull(extractShareSongIdentity(emptyList()))
    }

    @Test
    fun `accepts null and non string entries in the collection`() {
        val payload = """{"param_island":{"shareData":{"title":"Song","content":"Singer",
            "shareContent":"https://x/?songmid=001aaBBccDD"}}}"""
        val identity = extractShareSongIdentity(listOf(null, payload))!!
        assertEquals("001aaBBccDD", identity.songMid)
    }
}
