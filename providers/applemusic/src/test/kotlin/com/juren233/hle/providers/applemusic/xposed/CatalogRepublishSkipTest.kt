/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import io.github.proify.lyricon.lyric.model.Song
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRepublishSkipTest {
    private fun inputs(
        generation: Long = 1L,
        displayTranslation: Boolean = false,
        songId: String = "1",
    ) = PlaybackManager.CatalogRepublishInputs(
        songId = songId,
        metadata = null,
        diskGeneration = generation,
        displayTranslation = displayTranslation,
    )

    @Test
    fun `identical inputs with the same published song are skipped`() {
        val published = Song(id = "1", name = "a")
        val previous = PlaybackManager.CatalogRepublish(inputs(), published)

        assertTrue(PlaybackManager.isCatalogRepublishUnchanged(previous, inputs(), published))
    }

    @Test
    fun `any changed input or replaced current song forces a republish`() {
        val published = Song(id = "1", name = "a")
        val previous = PlaybackManager.CatalogRepublish(inputs(), published)

        assertFalse(PlaybackManager.isCatalogRepublishUnchanged(null, inputs(), published))
        assertFalse(PlaybackManager.isCatalogRepublishUnchanged(previous, inputs(generation = 2L), published))
        assertFalse(PlaybackManager.isCatalogRepublishUnchanged(previous, inputs(displayTranslation = true), published))
        assertFalse(PlaybackManager.isCatalogRepublishUnchanged(previous, inputs(songId = "2"), published))
        // 内容相同但已被其他路径替换的歌曲也要重新合并。
        assertFalse(PlaybackManager.isCatalogRepublishUnchanged(previous, inputs(), Song(id = "1", name = "a")))
    }
}
