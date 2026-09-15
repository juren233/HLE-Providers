/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import org.junit.Assert.assertEquals
import org.junit.Test

class QQMusicCarLyricsMetadataPolicyTest {
    @Test
    fun `first sample of a song is kept untouched`() {
        val policy = QQMusicCarLyricsMetadataPolicy()
        val normalized = policy.normalize(
            id = "000Afpal3mCeFw",
            title = "You cut me open and I",
            artist = "Love Somebody-LAUV",
        )
        // 第一次采样无法区分污染与干净元数据，不做改写
        assertEquals("You cut me open and I", normalized.title)
        assertEquals("Love Somebody-LAUV", normalized.artist)
    }

    @Test
    fun `title drift under same id activates artist combo derivation`() {
        val policy = QQMusicCarLyricsMetadataPolicy()
        policy.normalize("000Afpal3mCeFw", "You cut me open and I", "Love Somebody-LAUV")
        val normalized = policy.normalize(
            id = "000Afpal3mCeFw",
            title = "vein that I keep",
            artist = "Love Somebody-LAUV",
        )
        assertEquals("Love Somebody", normalized.title)
        assertEquals("LAUV", normalized.artist)
    }

    @Test
    fun `stable title across samples never activates derivation`() {
        // 车载歌词关闭时元数据本来就干净；A-Lin 这类带连字符的歌手名不受影响
        val policy = QQMusicCarLyricsMetadataPolicy()
        policy.normalize("003Qui1q2u1Zho", "晃", "A-Lin")
        val normalized = policy.normalize("003Qui1q2u1Zho", "晃", "A-Lin")
        assertEquals("晃", normalized.title)
        assertEquals("A-Lin", normalized.artist)
    }

    @Test
    fun `polluted song without dash in artist falls back to first sampled title`() {
        val policy = QQMusicCarLyricsMetadataPolicy()
        policy.normalize("mid1", "真实歌名", "LAUV")
        val normalized = policy.normalize("mid1", "漂移的歌词行", "LAUV")
        // 歌手字段没有组合串可拆时，回退用本曲首个采样标题恢复真名
        assertEquals("真实歌名", normalized.title)
        assertEquals("LAUV", normalized.artist)
    }

    @Test
    fun `derivation splits at the last dash`() {
        val policy = QQMusicCarLyricsMetadataPolicy()
        policy.normalize("mid2", "line one", "真歌名-A-连字符歌手")
        val normalized = policy.normalize("mid2", "line two", "真歌名-A-连字符歌手")
        assertEquals("真歌名-A", normalized.title)
        assertEquals("连字符歌手", normalized.artist)
    }

    @Test
    fun `different ids are tracked independently`() {
        val policy = QQMusicCarLyricsMetadataPolicy()
        policy.normalize("mid-a", "line one", "Love Somebody-LAUV")
        // mid-b 第一次采样即使 artist 带连字符也不改写
        val first = policy.normalize("mid-b", "some line", "Other Song-Other Artist")
        assertEquals("some line", first.title)
        val secondA = policy.normalize("mid-a", "line two", "Love Somebody-LAUV")
        assertEquals("Love Somebody", secondA.title)
        assertEquals("LAUV", secondA.artist)
    }

    @Test
    fun `eviction drops polluted state with tracked ids`() {
        val policy = QQMusicCarLyricsMetadataPolicy(maxTrackedIds = 2)
        policy.normalize("mid-1", "a", "x-y")
        policy.normalize("mid-2", "a", "x-y")
        policy.normalize("mid-1", "b", "x-y") // mid-1 已污染（标题漂移）
        policy.normalize("mid-3", "a", "x-y") // 驱逐最旧的 mid-2
        // mid-2 的追踪与污染状态随驱逐消失，重新按首次采样处理
        val normalized = policy.normalize("mid-2", "b", "x-y")
        assertEquals("b", normalized.title)
    }

    @Test
    fun `combined title suffix is stripped with surrounding spaces`() {
        val policy = QQMusicCarLyricsMetadataPolicy()
        val normalized = policy.normalize(
            id = "mid-space",
            title = "菲律宾没有雪 - 一个小孩",
            artist = "一个小孩",
        )
        assertEquals("菲律宾没有雪", normalized.title)
        assertEquals("一个小孩", normalized.artist)
    }

    @Test
    fun `combined title suffix is stripped without spaces and case differences`() {
        val policy = QQMusicCarLyricsMetadataPolicy()
        val normalized = policy.normalize(
            id = "mid-case",
            title = "NIGHT DANCER-imase",
            artist = "IMASE",
        )
        assertEquals("NIGHT DANCER", normalized.title)
    }

    @Test
    fun `title suffix not matching artist is kept untouched`() {
        val policy = QQMusicCarLyricsMetadataPolicy()
        val normalized = policy.normalize(
            id = "mid-mismatch",
            title = "菲律宾没有雪-一个小孩",
            artist = "别的歌手",
        )
        assertEquals("菲律宾没有雪-一个小孩", normalized.title)
        assertEquals("别的歌手", normalized.artist)
    }

    @Test
    fun `real title ending with dash and artist text but different artist field is kept`() {
        // 歌手字段带连字符但标题后缀与歌手不一致（正常带副标题的歌名）
        val policy = QQMusicCarLyricsMetadataPolicy()
        val normalized = policy.normalize(
            id = "mid-real",
            title = "晃-Live Version",
            artist = "A-Lin",
        )
        assertEquals("晃-Live Version", normalized.title)
        assertEquals("A-Lin", normalized.artist)
    }

    @Test
    fun `blank id skips drift tracking but still strips combined suffix`() {
        val policy = QQMusicCarLyricsMetadataPolicy()
        val stripped = policy.normalize("", "歌名-歌手", "歌手")
        assertEquals("歌名", stripped.title)
        // 无稳定 mediaId 时不做漂移判定：标题变化不触发组合歌手拆分
        val next = policy.normalize("", "另一首歌名", "A-B")
        assertEquals("另一首歌名", next.title)
        assertEquals("A-B", next.artist)
    }
}
