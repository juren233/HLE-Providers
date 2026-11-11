/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleIdleQueueItemTest {

    @Test
    fun `remembered queue item is handed out once and then cleared`() {
        val holder = AppleIdleQueueItem()
        val item = Any()
        holder.remember("1455151646", item, null)
        assertEquals("1455151646", holder.mediaId())
        assertSame(item, holder.take()?.queueItem)
        assertNull(holder.mediaId())
        assertNull(holder.take())
    }

    @Test
    fun `a later queue item replaces the remembered one`() {
        val holder = AppleIdleQueueItem()
        holder.remember("old", Any(), null)
        val newer = Any()
        holder.remember("new", newer, null)
        assertEquals("new", holder.mediaId())
        assertSame(newer, holder.take()?.queueItem)
    }

    @Test
    fun `visible lyrics adopt the idle queue item only without an active player`() {
        assertTrue(
            shouldAdoptIdleQueueItem(
                activePlayerPresent = false,
                idleMediaId = "1455151646",
                visibleSongId = "1455151646",
                publishedMediaId = null,
            )
        )
        // 有活动播放器时仍只由活动播放器的当前队列项更新当前歌曲。
        assertFalse(
            shouldAdoptIdleQueueItem(
                activePlayerPresent = true,
                idleMediaId = "1455151646",
                visibleSongId = "1455151646",
                publishedMediaId = null,
            )
        )
    }

    @Test
    fun `a different visible song or a missing idle item is never adopted`() {
        assertFalse(
            shouldAdoptIdleQueueItem(false, idleMediaId = "a", visibleSongId = "b", publishedMediaId = null)
        )
        assertFalse(
            shouldAdoptIdleQueueItem(false, idleMediaId = null, visibleSongId = "b", publishedMediaId = null)
        )
        assertFalse(
            shouldAdoptIdleQueueItem(false, idleMediaId = "b", visibleSongId = null, publishedMediaId = null)
        )
        assertFalse(
            shouldAdoptIdleQueueItem(false, idleMediaId = "b", visibleSongId = " ", publishedMediaId = null)
        )
    }

    @Test
    fun `an already published song is not adopted again`() {
        assertFalse(
            shouldAdoptIdleQueueItem(
                activePlayerPresent = false,
                idleMediaId = "1455151646",
                visibleSongId = "1455151646",
                publishedMediaId = "1455151646",
            )
        )
    }
}
