/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QQMusicFreeMediaNotificationPatchTest {
    private val mediaStyle = "android.app.Notification\$MediaStyle"

    @Test
    fun `attaches session to the playback MediaStyle notification`() {
        assertTrue(
            QQMusicFreeMediaNotificationPatch.decide(
                channelId = "com.tencent.qqfreemusic.play",
                template = mediaStyle,
                hasSessionToken = false,
            ),
        )
    }

    @Test
    fun `leaves other notifications alone`() {
        // 前台服务刚启动时那条占位通知没有 MediaStyle
        assertFalse(
            QQMusicFreeMediaNotificationPatch.decide("com.tencent.qqfreemusic.play", null, false),
        )
        // 推送等其他渠道
        assertFalse(QQMusicFreeMediaNotificationPatch.decide("push", mediaStyle, false))
        // App 自己已经带了会话
        assertFalse(
            QQMusicFreeMediaNotificationPatch.decide("com.tencent.qqfreemusic.play", mediaStyle, true),
        )
    }
}
