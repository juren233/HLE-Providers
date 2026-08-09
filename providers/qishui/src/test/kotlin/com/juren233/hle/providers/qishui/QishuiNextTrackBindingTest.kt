/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qishui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QishuiNextTrackBindingTest {
    @Test
    fun `aligns queue snapshot with media id embedded in a URI`() {
        val snapshot = snapshot(currentId = "7654321", currentTitle = "Current")
        val metadata = QishuiTrackMetadata(
            mediaId = "luna://track/7654321?scene=player",
            title = "Current",
            artist = "Artist",
            album = "Album",
            durationMs = 100_000L,
        )

        assertEquals(snapshot, QishuiNextTrackBinding.align(metadata, snapshot))
    }

    @Test
    fun `rejects a stale queue snapshot from a different song`() {
        val metadata = QishuiTrackMetadata(
            mediaId = "111111",
            title = "Current",
            artist = "Artist",
            album = "Album",
            durationMs = 100_000L,
        )

        assertNull(QishuiNextTrackBinding.align(metadata, snapshot("222222", "Previous")))
    }

    @Test
    fun `treats metadata completion without an initial id as the same song`() {
        val initial = QishuiTrackMetadata(
            mediaId = null,
            title = "Current",
            artist = "Artist",
            album = null,
            durationMs = 0L,
        )
        val completed = QishuiTrackMetadata(
            mediaId = "luna://track/7654321",
            title = "Current",
            artist = "Artist",
            album = "Album",
            durationMs = 100_000L,
        )

        assertEquals(true, QishuiTrackIdentity.sameTrack(initial, completed))
    }

    @Test
    fun `does not collapse different ids with the same title`() {
        val first = QishuiTrackMetadata("111111", "Current", "Artist", null, 100_000L)
        val second = QishuiTrackMetadata("222222", "Current", "Artist", null, 100_000L)

        assertEquals(false, QishuiTrackIdentity.sameTrack(first, second))
    }

    private fun snapshot(currentId: String, currentTitle: String) = QishuiQueueSnapshot(
        current = QishuiTrackSnapshot(
            id = currentId,
            title = currentTitle,
            artist = "Artist",
            album = "Album",
            durationMs = 100_000L,
        ),
        next = QishuiTrackSnapshot(
            id = "999999",
            title = "Next",
            artist = "Next Artist",
            album = "Next Album",
            durationMs = 120_000L,
        ),
    )
}
