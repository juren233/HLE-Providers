/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodianNextTrackResolverTest {
    private var now = 0L

    private fun resolver() = BodianNextTrackResolver(nowMs = { now })

    private fun bean(
        rid: String,
        title: String = "歌$rid",
    ) = BodianTrackBean(
        rid = rid,
        title = title,
        artist = "歌手",
        album = "专辑",
        durationMs = 200_000L,
    )

    @Test
    fun `prefetch before any current track is ignored`() {
        val resolver = resolver()
        assertNull(resolver.onPrefetchMusic(bean("999")))
    }

    @Test
    fun `current then prefetch emits a full frame once and dedupes within heartbeat`() {
        now = 1_000L
        val resolver = resolver()
        assertNotNull(resolver.onCurrentMusic(bean("100")))

        val frame = resolver.onPrefetchMusic(bean("200"))
        val decoded = requireNotNull(OfficialProviderControlProtocol.decodeNextTrack(frame))
        assertTrue(!decoded.clear)
        assertEquals("100", decoded.currentId)
        assertEquals("200", decoded.nextId)
        assertEquals(200_000L, decoded.nextDurationMs)

        now = 3_000L
        assertNull(resolver.onPrefetchMusic(bean("200")))
    }

    @Test
    fun `track switch invalidates stale prefetch and emits a clear frame`() {
        now = 1_000L
        val resolver = resolver()
        resolver.onCurrentMusic(bean("100"))
        resolver.onPrefetchMusic(bean("200"))

        now = 20_000L
        val frame = resolver.onCurrentMusic(bean("200"))
        val decoded = requireNotNull(OfficialProviderControlProtocol.decodeNextTrack(frame))
        assertTrue(decoded.clear)
        assertEquals("200", decoded.currentId)
        assertTrue(decoded.nextTitle.isBlank())
    }

    @Test
    fun `heartbeat resends the same frame after the interval`() {
        now = 1_000L
        val resolver = resolver()
        resolver.onCurrentMusic(bean("100"))
        resolver.onPrefetchMusic(bean("200"))

        now = 3_000L
        assertNull(resolver.heartbeat())
        now = 6_100L
        assertNotNull(resolver.heartbeat())
    }

    @Test
    fun `blank rid and self prefetch are rejected`() {
        now = 1_000L
        val resolver = resolver()
        resolver.onCurrentMusic(bean("100"))
        assertNull(resolver.onPrefetchMusic(bean("")))
        assertNull(resolver.onPrefetchMusic(bean("100")))
        assertNull(resolver.onCurrentMusic(null))
    }
}
