/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeteaseNextTrackProfilesTest {
    @Test
    fun `uses exact original DEX identifiers for NetEase 9 5 61`() {
        val profile = NeteaseNextTrackProfiles.resolve("9.5.61", 9_005_061L)!!

        assertEquals("com.netease.cloudmusic.service.MainProcessPlayService", profile.serviceClassName)
        assertEquals("tr0.z", profile.playerManagerClassName)
        assertEquals("com.netease.cloudmusic.meta.MusicInfo", profile.musicInfoClassName)
        assertEquals(
            "com.netease.cloudmusic.meta.virtual.SimpleMusicInfo",
            profile.simpleMusicInfoClassName,
        )
        assertEquals("E1", profile.playerManagerAccessorName)
        assertEquals("g", profile.nextMusicMethodName)
        assertEquals("toSimpleMusicInfo", profile.toSimpleMusicInfoMethodName)
        assertEquals("getId", profile.idMethodName)
        assertEquals("getMusicName", profile.titleMethodName)
        assertEquals("getSingerName", profile.artistMethodName)
        assertEquals("getAlbumName", profile.albumMethodName)
        assertEquals("getDuration", profile.durationMethodName)
    }

    @Test
    fun `rejects stale 9 5 60 identifiers for the current profile`() {
        assertNull(NeteaseNextTrackProfiles.resolve("9.5.60", 9_005_060L))
    }
}
