/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QQMusicQueueHintChannelTest {

    @Test
    fun `encode decode round trip keeps current and next`() {
        val snapshot = QQMusicQueueSnapshot(
            current = QQMusicTrackSnapshot("105345627", "Hello", "Adele", songMid = null, duration = 295_000L),
            next = QQMusicTrackSnapshot("233201", "Lavender Haze", "Taylor Swift"),
        )

        val decoded = QQMusicQueueHintChannel.decode(QQMusicQueueHintChannel.encode(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `encode omits next when absent`() {
        val snapshot = QQMusicQueueSnapshot(
            current = QQMusicTrackSnapshot("46", "勿听", "张紫宁/赵紫骅", songMid = "001AzzRc42NBVo"),
            next = null,
        )

        val decoded = QQMusicQueueHintChannel.decode(QQMusicQueueHintChannel.encode(snapshot))

        assertEquals(snapshot, decoded)
        assertNull(decoded!!.next)
    }

    @Test
    fun `decode rejects malformed payloads`() {
        // 广播内容不可信：空串、乱码、缺 id 的条目一律丢弃，不抛异常
        assertNull(QQMusicQueueHintChannel.decode(null))
        assertNull(QQMusicQueueHintChannel.decode(""))
        assertNull(QQMusicQueueHintChannel.decode("not json"))
        assertNull(QQMusicQueueHintChannel.decode("""{"current":{"id":"","title":"Hello","artist":"Adele"}}"""))
        assertNull(QQMusicQueueHintChannel.decode("""{"next":{"id":"1","title":"x","artist":"y"}}"""))
    }
}
