/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * XIAOMI-MUSIC-NEXT-PREVIEW-IDENTITY-001：SystemUI 以 MediaSession MEDIA_ID 查询
 * 下一首缓存，帧的当前曲身份必须与其同源，SDK 队列内部 long id 只是回退。
 */
class QQMusicNextTrackFrameIdentityTest {
    private val sdkCurrent = QQMusicTrackSnapshot(
        id = "2305843009216247010",
        title = "还是会寂寞",
        artist = "陈绮贞",
    )

    @Test
    fun `session media id wins over sdk queue id`() {
        assertEquals(
            "003Iatz12frRWY",
            nextTrackFrameCurrentId("003Iatz12frRWY", sdkCurrent),
        )
    }

    @Test
    fun `blank session id falls back to sdk queue id`() {
        assertEquals(
            "2305843009216247010",
            nextTrackFrameCurrentId("  ", sdkCurrent),
        )
    }

    @Test
    fun `missing session id falls back to sdk queue id`() {
        assertEquals(
            "2305843009216247010",
            nextTrackFrameCurrentId(null, sdkCurrent),
        )
    }

    @Test
    fun `session id is trimmed before publishing`() {
        assertEquals(
            "003Iatz12frRWY",
            nextTrackFrameCurrentId(" 003Iatz12frRWY ", sdkCurrent),
        )
    }
}
