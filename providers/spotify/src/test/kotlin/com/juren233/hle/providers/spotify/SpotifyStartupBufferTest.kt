/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyStartupBufferTest {
    @Test
    fun `replays metadata lyrics and playback state in separate ordered fields`() {
        val buffer = buffer(maxLyrics = 3)

        buffer.onPlaybackState("playing")
        buffer.onLyrics(Lyrics("track-a", "old"))
        buffer.onMetadata("metadata-a")
        buffer.onLyrics(Lyrics("track-a", "new"))
        buffer.onLyrics(Lyrics("track-b", "lyrics-b"))

        val snapshot = buffer.drain()

        assertTrue(snapshot.metadataReceived)
        assertEquals("metadata-a", snapshot.metadata)
        assertEquals(
            listOf(Lyrics("track-a", "new"), Lyrics("track-b", "lyrics-b")),
            snapshot.lyrics,
        )
        assertTrue(snapshot.playbackStateReceived)
        assertEquals("playing", snapshot.playbackState)
    }

    @Test
    fun `keeps only the bounded newest startup lyrics`() {
        val buffer = buffer(maxLyrics = 2)

        buffer.onLyrics(Lyrics("track-a", "lyrics-a"))
        buffer.onLyrics(Lyrics("track-b", "lyrics-b"))
        buffer.onLyrics(Lyrics("track-c", "lyrics-c"))

        assertEquals(
            listOf(Lyrics("track-b", "lyrics-b"), Lyrics("track-c", "lyrics-c")),
            buffer.drain().lyrics,
        )
    }

    @Test
    fun `drain clears all pending startup state including explicit nulls`() {
        val buffer = buffer(maxLyrics = 2)
        buffer.onMetadata(null)
        buffer.onPlaybackState(null)
        buffer.onLyrics(Lyrics("track-a", "lyrics-a"))

        val first = buffer.drain()
        val second = buffer.drain()

        assertTrue(first.metadataReceived)
        assertNull(first.metadata)
        assertTrue(first.playbackStateReceived)
        assertNull(first.playbackState)
        assertFalse(second.metadataReceived)
        assertNull(second.metadata)
        assertEquals(emptyList<Lyrics>(), second.lyrics)
        assertFalse(second.playbackStateReceived)
        assertNull(second.playbackState)
    }

    @Test
    fun `startup null cannot overwrite an already usable track`() {
        val buffer = buffer(maxLyrics = 2)

        buffer.onMetadata("track-a")
        buffer.onMetadata(null)

        val snapshot = buffer.drain()

        assertTrue(snapshot.metadataReceived)
        assertEquals("track-a", snapshot.metadata)
    }

    @Test
    fun `startup blank metadata cannot overwrite an already usable track`() {
        val buffer = buffer(maxLyrics = 2)

        buffer.onMetadata("track-a")
        buffer.onMetadata(" ")

        assertEquals("track-a", buffer.drain().metadata)
    }

    @Test
    fun `later usable track replaces startup noise`() {
        val buffer = buffer(maxLyrics = 2)

        buffer.onMetadata(null)
        buffer.onMetadata(" ")
        buffer.onMetadata("track-a")

        assertEquals("track-a", buffer.drain().metadata)
    }

    private fun buffer(maxLyrics: Int) =
        SpotifyPluginEntry.SpotifyStartupBuffer<String, Lyrics, String>(
            maxLyrics = maxLyrics,
            lyricsKey = Lyrics::trackUri,
            metadataIsUsable = { !it.isNullOrBlank() },
        )

    private data class Lyrics(
        val trackUri: String,
        val content: String,
    )
}
