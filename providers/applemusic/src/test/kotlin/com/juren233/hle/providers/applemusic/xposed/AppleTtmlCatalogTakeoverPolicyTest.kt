/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hle.providers.applemusic.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleTtmlCatalogTakeoverPolicyTest {
    private val wordTimed = AppleNativeLyricsTimingStats(lineCount = 63, wordTimedLineCount = 63)
    private val lineTimed = AppleNativeLyricsTimingStats(lineCount = 63, wordTimedLineCount = 0)

    @Test
    fun `word timed native lyrics are not taken over automatically even when line counts differ`() {
        assertTrue(shouldUseTtmlCatalogOverAppleNativeLyrics(wordTimed, ttmlCatalogLineCount = 43))
        assertFalse(
            shouldAllowTtmlCatalogTakeover(
                eligible = true,
                nativeStats = wordTimed,
                userChoseCatalog = false,
            )
        )
    }

    @Test
    fun `word timed native lyrics yield to the catalog once the user chose it`() {
        assertTrue(
            shouldAllowTtmlCatalogTakeover(
                eligible = true,
                nativeStats = wordTimed,
                userChoseCatalog = true,
            )
        )
    }

    @Test
    fun `line timed or unknown native lyrics are still taken over automatically`() {
        assertTrue(shouldAllowTtmlCatalogTakeover(eligible = true, nativeStats = lineTimed, userChoseCatalog = false))
        assertTrue(shouldAllowTtmlCatalogTakeover(eligible = true, nativeStats = null, userChoseCatalog = false))
    }

    @Test
    fun `an ineligible catalog never takes over, even when the user chose it`() {
        assertFalse(shouldAllowTtmlCatalogTakeover(eligible = false, nativeStats = lineTimed, userChoseCatalog = true))
        assertFalse(shouldAllowTtmlCatalogTakeover(eligible = false, nativeStats = wordTimed, userChoseCatalog = true))
    }

    @Test
    fun `a menu request is remembered before the switch result arrives and reset on a new track`() {
        val selection = AppleLyricsSourceSelection()
        selection.beginPlayback("song")
        assertNull(selection.requested("song"))
        selection.noteRequest("song", "AMLL")
        assertEquals("AMLL", selection.requested("song"))
        assertNull(selection.selected("song"))
        selection.beginPlayback("other")
        assertNull(selection.requested("song"))
    }
}
