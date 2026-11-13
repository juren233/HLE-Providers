/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hle.providers.applemusic.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleUntimedNativeReplacementPolicyTest {
    private val untimed = AppleNativeLyricsTimingStats(
        lineCount = 38,
        wordTimedLineCount = 0,
        lineTimingPresent = false,
    )

    @Test
    fun `only native lyrics without any line or word timing are untimed`() {
        assertTrue(untimed.isUntimed)
        assertFalse(untimed.copy(lineTimingPresent = true).isUntimed)
        assertFalse(untimed.copy(wordTimedLineCount = 1).isUntimed)
        assertFalse(untimed.copy(lineCount = 0).isUntimed)
        // 未读取行时间（旧统计或读取失败）不能断定无时间轴。
        assertFalse(untimed.copy(lineTimingPresent = null).isUntimed)
    }

    @Test
    fun `untimed native lyrics are replaced while fill-missing lyrics is on`() {
        assertTrue(
            shouldReplaceUntimedAppleNativeLyrics(
                fillMissingLyricsEnabled = true,
                nativeStats = untimed,
                userChoseAppleNative = false,
            )
        )
    }

    @Test
    fun `untimed native lyrics stay when fill-missing lyrics is off`() {
        assertFalse(
            shouldReplaceUntimedAppleNativeLyrics(
                fillMissingLyricsEnabled = false,
                nativeStats = untimed,
                userChoseAppleNative = false,
            )
        )
    }

    @Test
    fun `timed or unknown native lyrics are never replaced by this rule`() {
        assertFalse(
            shouldReplaceUntimedAppleNativeLyrics(
                fillMissingLyricsEnabled = true,
                nativeStats = untimed.copy(lineTimingPresent = true),
                userChoseAppleNative = false,
            )
        )
        assertFalse(
            shouldReplaceUntimedAppleNativeLyrics(
                fillMissingLyricsEnabled = true,
                nativeStats = null,
                userChoseAppleNative = false,
            )
        )
    }

    @Test
    fun `choosing Apple Music native in the source menu keeps the native lyrics`() {
        assertFalse(
            shouldReplaceUntimedAppleNativeLyrics(
                fillMissingLyricsEnabled = true,
                nativeStats = untimed,
                userChoseAppleNative = true,
            )
        )
    }
}
