/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.contract

import android.media.session.PlaybackState

/**
 * Defines whether a platform playback state still owns an active lyric surface.
 *
 * Buffering must keep the surface active while the position policy freezes its timeline.
 */
object PlaybackStateActivityPolicy {
    fun keepsSessionActive(state: Int?): Boolean =
        state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
}
