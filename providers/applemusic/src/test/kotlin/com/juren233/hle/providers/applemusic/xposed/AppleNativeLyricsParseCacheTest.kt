/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import com.juren233.hle.providers.applemusic.xposed.model.AppleSong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AppleNativeLyricsParseCacheTest {
    @Before
    fun setUp() = AppleNativeLyricsParseCache.resetForTest()

    @After
    fun tearDown() = AppleNativeLyricsParseCache.resetForTest()

    private fun key(
        songId: String? = "1",
        probe: Int? = 7,
        transform: Boolean = false,
    ) = AppleNativeLyricsParseCache.keyFor(
        songId = songId,
        contentProbe = probe,
        textTransformEnabled = transform,
    )

    @Test
    fun `probe failure disables caching`() {
        assertNull(key(probe = null))
        assertNotNull(key())
    }

    @Test
    fun `missing id is not cacheable`() {
        assertNull(key(songId = null))
        assertNull(key(songId = " "))
    }

    @Test
    fun `track selection change or transform toggle produces a different key`() {
        val before = key()!!
        AppleNativeLyricsParseCache.put(before, AppleSong(adamId = "1", name = "parsed"))
        assertEquals("parsed", AppleNativeLyricsParseCache.get(before)?.name)

        assertNotEquals(before, key(transform = true))
        val after = key(probe = 8)!!
        assertNotEquals(before, after)
        assertNull(AppleNativeLyricsParseCache.get(after))
    }

    @Test
    fun `cached song is returned as an independent copy`() {
        val key = key()!!
        AppleNativeLyricsParseCache.put(key, AppleSong(adamId = "1", name = "parsed"))

        AppleNativeLyricsParseCache.get(key)!!.name = "metadata applied"
        assertEquals("parsed", AppleNativeLyricsParseCache.get(key)?.name)
    }
}
