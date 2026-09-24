/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QQMusicLyricTrackCoordinatorTest {
    @Test
    fun `HD replaces queue local MediaSession ID with verified SongInfo ID`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.HD_PACKAGE)

        assertTrue(
            coordinator.onMetadata(
                track("4137", "Spotlight (聚光灯)", "蔡徐坤"),
            ) is QQMusicLyricTrackDecision.AwaitingVerifiedId,
        )

        val decision = coordinator.onQueueSnapshot(
            snapshot("451887939", "Spotlight（聚光灯）", "蔡徐坤"),
        ) as QQMusicLyricTrackDecision.Load

        assertEquals("451887939", decision.track.id)
        assertEquals("Spotlight (聚光灯)", decision.track.title)
        assertEquals(202_000L, decision.track.duration)
    }

    @Test
    fun `HD refuses stale SongInfo identity while tracks are crossing`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.HD_PACKAGE)

        coordinator.onQueueSnapshot(snapshot("100", "Song A", "Artist A"))
        val first = coordinator.onMetadata(track("4081", "Song B", "Artist B"))

        assertTrue(first is QQMusicLyricTrackDecision.AwaitingVerifiedId)

        val second = coordinator.onQueueSnapshot(snapshot("200", "Song B", "Artist B"))
            as QQMusicLyricTrackDecision.Load
        assertEquals("200", second.track.id)
    }

    @Test
    fun `HD loads current SongInfo when queue changes before metadata callback`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.HD_PACKAGE)

        coordinator.onMetadata(track("4073", "Song A", "Artist A"))
        assertTrue(
            coordinator.onQueueSnapshot(
                snapshot("100", "Song A", "Artist A"),
            ) is QQMusicLyricTrackDecision.Load,
        )

        val next = coordinator.onQueueSnapshot(
            snapshot("200", "Song B", "Artist B"),
        ) as QQMusicLyricTrackDecision.Load
        assertEquals("200", next.track.id)
        assertEquals("Song B", next.track.title)
        assertEquals(0L, next.track.duration)
        assertEquals(
            QQMusicLyricTrackDecision.Unchanged,
            coordinator.onQueueSnapshot(snapshot("200", "Song B", "Artist B")),
        )
    }

    @Test
    fun `HD can load current SongInfo before any media metadata callback`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.HD_PACKAGE)

        val decision = coordinator.onQueueSnapshot(
            snapshot("261863461", "Dreamland", "Glass Animals"),
        ) as QQMusicLyricTrackDecision.Load

        assertEquals("261863461", decision.track.id)
        assertEquals("Dreamland", decision.track.title)
        assertEquals("Glass Animals", decision.track.artist)
    }

    @Test
    fun `HD rejects invalid current SongInfo instead of requesting unrelated lyrics`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.HD_PACKAGE)

        assertEquals(
            QQMusicLyricTrackDecision.Unchanged,
            coordinator.onQueueSnapshot(snapshot("0", "Dreamland", "Glass Animals")),
        )
        assertEquals(
            QQMusicLyricTrackDecision.Unchanged,
            coordinator.onQueueSnapshot(snapshot("261863461", "Dreamland", "")),
        )
    }

    @Test
    fun `repeated HD snapshot does not trigger another lyric download`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.HD_PACKAGE)
        val snapshot = snapshot("451887939", "Spotlight (聚光灯)", "蔡徐坤")

        coordinator.onMetadata(track("4137", "Spotlight (聚光灯)", "蔡徐坤"))
        assertTrue(coordinator.onQueueSnapshot(snapshot) is QQMusicLyricTrackDecision.Load)
        assertEquals(
            QQMusicLyricTrackDecision.Unchanged,
            coordinator.onQueueSnapshot(snapshot),
        )
    }

    @Test
    fun `changing HD queue local MediaSession ID does not reload the same song`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.HD_PACKAGE)
        val first = coordinator.onQueueSnapshot(
            snapshot("451887939", "Spotlight (聚光灯)", "蔡徐坤"),
        ) as QQMusicLyricTrackDecision.Load
        assertEquals("451887939", first.track.id)

        assertEquals(
            QQMusicLyricTrackDecision.Unchanged,
            coordinator.onMetadata(track("4137", "Spotlight (聚光灯)", "蔡徐坤")),
        )

        assertEquals(
            QQMusicLyricTrackDecision.Unchanged,
            coordinator.onMetadata(
                track("4209", "Spotlight (聚光灯)", "蔡徐坤"),
            ),
        )
    }

    @Test
    fun `mobile keeps using MediaSession song ID`() {
        val coordinator = QQMusicLyricTrackCoordinator(QQMusicRuntimePlan.MOBILE_PACKAGE)

        val decision = coordinator.onMetadata(
            track("368304013", "Hug me", "蔡徐坤"),
        ) as QQMusicLyricTrackDecision.Load

        assertEquals("368304013", decision.track.id)
    }

    private fun track(id: String, title: String, artist: String) = QQMusicLyricTrack(
        id = id,
        title = title,
        artist = artist,
        duration = 202_000L,
    )

    private fun snapshot(id: String, title: String, artist: String) = QQMusicQueueSnapshot(
        current = QQMusicTrackSnapshot(id, title, artist),
        next = null,
    )
}
