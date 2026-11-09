/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QQMusicLyricCachePolicyTest {

    @Test
    fun `legacy caches without authoritative name are untrusted`() {
        // 数字 ID 盲透传时代的缓存（含 issue #62 的错词缓存）不带权威 name
        assertFalse(
            QQMusicLyricCachePolicy.belongsToSession(null, null, "Without Me", "Eminem"),
        )
        assertFalse(
            QQMusicLyricCachePolicy.belongsToSession(" ", "Eminem", "Without Me", "Eminem"),
        )
    }

    @Test
    fun `matching name and singer belongs to the session`() {
        assertTrue(
            QQMusicLyricCachePolicy.belongsToSession("Without Me", "Eminem", "Without Me", "Eminem"),
        )
        // 会话歌手是多人斜杠串：缓存的单个歌手被会话解释
        assertTrue(
            QQMusicLyricCachePolicy.belongsToSession(
                "Love Somebody", "LAUV",
                "Love Somebody", "R3HAB/LAUV",
            ),
        )
    }

    @Test
    fun `wrong song cache under correct session title is rejected`() {
        // issue #62 场景：分手快乐的词顶着 Without Me 的标题缓存，必须被拒
        assertFalse(
            QQMusicLyricCachePolicy.belongsToSession("分手快乐", "梁静茹", "Without Me", "Eminem"),
        )
    }

    @Test
    fun `authoritative full title explains truncated session title`() {
        // 真机实证：端上截断长标题（会话 golden hour，QQ 真名 In your golden hour），
        // 缓存的权威全名反而更长，反向包含必须放行
        assertTrue(
            QQMusicLyricCachePolicy.belongsToSession(
                "In your golden hour", "JVKE", "golden hour", "JVKE",
            ),
        )
    }

    @Test
    fun `singer mismatch rejects the cache even when name matches`() {
        assertFalse(
            QQMusicLyricCachePolicy.belongsToSession("同名的歌", "张三", "同名的歌", "李四"),
        )
        // 缓存歌手为空（接口未返回）时只按歌名判定
        assertTrue(
            QQMusicLyricCachePolicy.belongsToSession("同名的歌", null, "同名的歌", "李四"),
        )
        // 缓存的合作版全串歌手被会话单歌手解释
        assertTrue(
            QQMusicLyricCachePolicy.belongsToSession(
                "同名的歌", "R3HAB/Noah Neiman", "同名的歌", "R3HAB",
            ),
        )
    }

    @Test
    fun `matching ignores case and whitespace`() {
        assertTrue(
            QQMusicLyricCachePolicy.belongsToSession(
                "Love  Somebody", "lauv", "love somebody", "LAUV",
            ),
        )
    }

    @Test
    fun `short cached names below two characters are rejected`() {
        // 过短的歌名子串匹配会大量误命中
        assertFalse(
            QQMusicLyricCachePolicy.belongsToSession("A", null, "A Little Love", "某人"),
        )
    }

    @Test
    fun `short containment below four characters does not explain a longer title`() {
        // 会话标题只是缓存歌名的过短前缀时不能算同曲（防「晴天」⊂「晴天笑了」类误配）
        assertFalse(
            QQMusicLyricCachePolicy.belongsToSession("晴天笑了", null, "晴天", "周杰伦"),
        )
    }

    // ---- v2 缓存文件名（2026-11-09：本地库编号与在线 ID 同号不再共用文件）----

    @Test
    fun `cache file name separates local library ID from online ID`() {
        // issue #62 场景：本地歌会话 46 与在线歌会话 46 都写成 46.json，互相顶掉对方
        val local = QQMusicLyricCachePolicy.cacheFileName("46", "勿听", "张紫宁/赵紫骅")
        val online = QQMusicLyricCachePolicy.cacheFileName("46", "爱上你只是我的错", "dMDM")
        assertTrue(local != online)
    }

    @Test
    fun `cache file name is stable and normalized`() {
        // 同一首歌大小写/空格差异不产生第二份缓存；文件名是 SHA-256 十六进制 + .json
        val first = QQMusicLyricCachePolicy.cacheFileName("46", "Hello", "Adele")
        val second = QQMusicLyricCachePolicy.cacheFileName("46", "hello ", "ADELE")
        assertTrue(first == second)
        assertTrue(first.endsWith(".json"))
        assertTrue(Regex("[0-9a-f]{64}\\.json").matches(first))
        // 不同会话（本地库编号递增）不命中同一份缓存
        assertTrue(first != QQMusicLyricCachePolicy.cacheFileName("47", "Hello", "Adele"))
    }
}
