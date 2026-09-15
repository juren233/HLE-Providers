/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import org.junit.Assert.assertEquals
import org.junit.Test

class QQMusicMiuiMetadataPolicyTest {
    @Test
    fun `first sample of a song is kept untouched`() {
        val policy = QQMusicMiuiMetadataPolicy()
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
        val policy = QQMusicMiuiMetadataPolicy()
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
        val policy = QQMusicMiuiMetadataPolicy()
        policy.normalize("003Qui1q2u1Zho", "晃", "A-Lin")
        val normalized = policy.normalize("003Qui1q2u1Zho", "晃", "A-Lin")
        assertEquals("晃", normalized.title)
        assertEquals("A-Lin", normalized.artist)
    }

    @Test
    fun `polluted song without dash in artist keeps original fields`() {
        val policy = QQMusicMiuiMetadataPolicy()
        policy.normalize("mid1", "line one", "LAUV")
        val normalized = policy.normalize("mid1", "line two", "LAUV")
        assertEquals("line two", normalized.title)
        assertEquals("LAUV", normalized.artist)
    }

    @Test
    fun `derivation splits at the last dash`() {
        val policy = QQMusicMiuiMetadataPolicy()
        policy.normalize("mid2", "line one", "真歌名-A-连字符歌手")
        val normalized = policy.normalize("mid2", "line two", "真歌名-A-连字符歌手")
        assertEquals("真歌名-A", normalized.title)
        assertEquals("连字符歌手", normalized.artist)
    }

    @Test
    fun `different ids are tracked independently`() {
        val policy = QQMusicMiuiMetadataPolicy()
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
        val policy = QQMusicMiuiMetadataPolicy(maxTrackedIds = 2)
        policy.normalize("mid-1", "a", "x-y")
        policy.normalize("mid-2", "a", "x-y")
        policy.normalize("mid-1", "b", "x-y") // mid-1 已污染（标题漂移）
        policy.normalize("mid-3", "a", "x-y") // 驱逐最旧的 mid-2
        // mid-2 的追踪与污染状态随驱逐消失，重新按首次采样处理
        val normalized = policy.normalize("mid-2", "b", "x-y")
        assertEquals("b", normalized.title)
    }
}
