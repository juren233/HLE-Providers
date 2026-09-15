/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kugou

import org.junit.Assert.assertEquals
import org.junit.Test

class CarLyricsMetadataPolicyTest {
    @Test
    fun `clean stable metadata is kept untouched`() {
        val policy = CarLyricsMetadataPolicy()
        policy.normalize("1001", "晃", "A-Lin")
        val normalized = policy.normalize("1001", "晃", "A-Lin")
        assertEquals("晃", normalized.title)
        assertEquals("A-Lin", normalized.artist)
    }

    @Test
    fun `combined title suffix is stripped`() {
        val policy = CarLyricsMetadataPolicy()
        val withSpaces = policy.normalize("1002", "菲律宾没有雪 - 一个小孩", "一个小孩")
        assertEquals("菲律宾没有雪", withSpaces.title)
        val compact = policy.normalize("1003", "菲律宾没有雪-一个小孩", "一个小孩")
        assertEquals("菲律宾没有雪", compact.title)
    }

    @Test
    fun `title suffix not matching artist is kept untouched`() {
        val policy = CarLyricsMetadataPolicy()
        val normalized = policy.normalize("1004", "菲律宾没有雪-一个小孩", "别的歌手")
        assertEquals("菲律宾没有雪-一个小孩", normalized.title)
    }

    @Test
    fun `title drift activates artist combo derivation`() {
        val policy = CarLyricsMetadataPolicy()
        policy.normalize("1005", "You cut me open and I", "Love Somebody-LAUV")
        val normalized = policy.normalize("1005", "vein that I keep", "Love Somebody-LAUV")
        assertEquals("Love Somebody", normalized.title)
        assertEquals("LAUV", normalized.artist)
    }

    @Test
    fun `title drift without combo artist falls back to first sampled title`() {
        val policy = CarLyricsMetadataPolicy()
        policy.normalize("1006", "真实歌名", "LAUV")
        val normalized = policy.normalize("1006", "漂移的歌词行", "LAUV")
        assertEquals("真实歌名", normalized.title)
        assertEquals("LAUV", normalized.artist)
    }

    @Test
    fun `blank media id skips drift tracking but still strips suffix`() {
        val policy = CarLyricsMetadataPolicy()
        val stripped = policy.normalize("", "歌名-歌手", "歌手")
        assertEquals("歌名", stripped.title)
        val next = policy.normalize("", "另一首歌名", "A-B")
        assertEquals("另一首歌名", next.title)
        assertEquals("A-B", next.artist)
    }
}
