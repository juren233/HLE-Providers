/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import com.juren233.hle.providers.applemusic.xposed.sourceMenuLabel
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceInfo
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceStatus
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test

class AppleTtmlCatalogSourcesTest {
    @Before
    fun pinChinese() {
        AppleMenuText.chineseOverride = true
    }

    @After
    fun releaseLanguage() {
        AppleMenuText.chineseOverride = null
    }

    @Test
    fun `every TTML catalog source builds or presents its native model`() {
        listOf("LB", "AMLL").forEach { source ->
            assertEquals(
                AppleLyricsSourcePresentationAction.PRESENT_TTML_CATALOG,
                appleLyricsSourcePresentationAction(source, hasTtmlCatalogPointer = true, hasAppleNativePointer = false),
            )
            assertEquals(
                AppleLyricsSourcePresentationAction.BUILD_TTML_CATALOG,
                appleLyricsSourcePresentationAction(source, hasTtmlCatalogPointer = false, hasAppleNativePointer = true),
            )
        }
        assertEquals(
            AppleLyricsSourcePresentationAction.REFRESH_ONLY,
            appleLyricsSourcePresentationAction("NE", hasTtmlCatalogPointer = true, hasAppleNativePointer = true),
        )
    }

    @Test
    fun `a found word timed AMLL alternative is retained after native presentation`() {
        val info = AppleMissingLyricsSourceInfo(
            selectedSource = "AMLL",
            statuses = listOf(
                AppleMissingLyricsSourceStatus("AMLL", searched = true, found = true, wordTimed = true, lineCount = 10),
            ),
        )
        assertEquals(true, shouldRetainTtmlCatalogAlternativeAfterNativePresentation(true, info))
        assertEquals(false, shouldRetainTtmlCatalogAlternativeAfterNativePresentation(false, info))
    }

    @Test
    fun `AMLL has its own menu label instead of the NetEase default`() {
        assertEquals("AMLL歌词", sourceMenuLabel("AMLL", "lyrics"))
    }
}
