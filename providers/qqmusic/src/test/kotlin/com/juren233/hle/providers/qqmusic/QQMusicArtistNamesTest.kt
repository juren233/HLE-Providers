/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QQMusicArtistNamesTest {
    @Test
    fun `first singer is compatible with the full session artist list`() {
        assertTrue(QQMusicArtistNames.compatible("喻志博/李雅戈", "喻志博"))
        assertTrue(QQMusicArtistNames.compatible("喻志博", "喻志博 / 李雅戈"))
        assertTrue(QQMusicArtistNames.compatible("A&B、C", "c"))
        assertTrue(QQMusicArtistNames.compatible("周杰伦", "周杰伦"))
    }

    @Test
    fun `different singers or rewritten artist are not compatible`() {
        assertFalse(QQMusicArtistNames.compatible("喻志博/李雅戈", "林忆莲"))
        // 蓝牙歌词把歌手改成「歌名-歌手」：整串一段，不算同一组歌手
        assertFalse(QQMusicArtistNames.compatible("武设姑娘-喻志博", "喻志博"))
        assertFalse(QQMusicArtistNames.compatible("", "喻志博"))
        assertFalse(QQMusicArtistNames.compatible("喻志博", null))
    }
}
