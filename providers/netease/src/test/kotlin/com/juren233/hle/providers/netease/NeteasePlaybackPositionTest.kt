/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.netease

import org.junit.Assert.assertEquals
import org.junit.Test

class NeteasePlaybackPositionTest {
    @Test
    fun playingStateAdvancesFromElapsedRealtimeAnchor() {
        assertEquals(
            15_000L,
            extrapolatePlaybackPosition(
                basePosition = 10_000L,
                lastUpdateTime = 1_000L,
                now = 6_000L,
                playbackSpeed = 1f,
                playing = true,
            ),
        )
    }

    @Test
    fun pausedStateKeepsAnchorPosition() {
        assertEquals(
            10_000L,
            extrapolatePlaybackPosition(10_000L, 1_000L, 6_000L, 1f, playing = false),
        )
    }

    @Test
    fun futureAnchorCannotMovePositionBackwards() {
        assertEquals(
            10_000L,
            extrapolatePlaybackPosition(10_000L, 7_000L, 6_000L, 1f, playing = true),
        )
    }

    @Test
    fun speedAndInvalidInputsAreBounded() {
        assertEquals(17_500L, extrapolatePlaybackPosition(10_000L, 1_000L, 6_000L, 1.5f, true))
        assertEquals(0L, extrapolatePlaybackPosition(-1L, 1_000L, 6_000L, -1f, true))
        assertEquals(10_000L, extrapolatePlaybackPosition(10_000L, 1_000L, 6_000L, Float.NaN, true))
    }
}
