/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QQMusicNextTrackProfilesTest {
    @Test
    fun `uses exact original DEX identifiers for QQ Music 20 6 5 8`() {
        val profile = QQMusicNextTrackProfiles.resolve("20.6.5.8", 7228L)!!

        assertEquals("com.tencent.qqmusic.common.player.d", profile.managerClassName)
        assertEquals("z", profile.singletonMethodName)
        assertEquals("M", profile.currentSongMethodName)
        assertEquals("E", profile.nextSongMethodName)
        assertEquals("com.tencent.qqmusicplayerprocess.songinfo.SongInfo", profile.songInfoClassName)
        assertEquals("D2", profile.songIdMethodName)
        assertEquals("f3", profile.songTitleMethodName)
        assertEquals("R3", profile.songArtistMethodName)
    }

    @Test
    fun `rejects unverified QQ Music versions`() {
        assertNull(QQMusicNextTrackProfiles.resolve("20.6.5.9", 7229L))
    }
}
