/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaltPlayerHookProfilesTest {
    @Test
    fun `resolves only exact original apk versions`() {
        assertEquals(
            SaltPlayerHookProfiles.V12_1_1,
            SaltPlayerHookProfiles.resolve("12.1.1", 2_026_070_502L),
        )
        assertEquals(
            SaltPlayerHookProfiles.V12_1_0,
            SaltPlayerHookProfiles.resolve("12.1.0", 2_026_070_208L),
        )
        assertEquals(
            SaltPlayerHookProfiles.V12_0_0,
            SaltPlayerHookProfiles.resolve("12.0.0", 2_026_061_801L),
        )
        assertEquals(
            SaltPlayerHookProfiles.V11_1_0,
            SaltPlayerHookProfiles.resolve("11.1.0", 2_026_031_101L),
        )
        assertNull(SaltPlayerHookProfiles.resolve("12.1.2", 2_026_070_600L))
        assertNull(SaltPlayerHookProfiles.resolve("12.1.1", 2_026_070_208L))
    }

    @Test
    fun `keeps only stable queue contracts`() {
        val profile = SaltPlayerHookProfiles.V12_1_1

        assertEquals("com.salt.music.service.MusicController", profile.musicControllerClassName)
        assertEquals("kotlinx.coroutines.flow.StateFlow", profile.stateFlowClassName)
        listOf(
            SaltPlayerHookProfiles.V12_1_1,
            SaltPlayerHookProfiles.V12_1_0,
            SaltPlayerHookProfiles.V12_0_0,
            SaltPlayerHookProfiles.V11_1_0,
        ).forEach { profile ->
            assertEquals("Circle", profile.queue.circleModeName)
            assertEquals("CircleEnd", profile.queue.circleEndModeName)
            assertEquals("RepeatOne", profile.queue.repeatOneModeName)
            assertEquals("Random", profile.queue.randomModeName)
            assertEquals("getId", profile.song.idGetterName)
            assertEquals("getTitle", profile.song.titleGetterName)
            assertEquals("getArtist", profile.song.artistGetterName)
            assertEquals("getAlbum", profile.song.albumGetterName)
            assertEquals("getDuration", profile.song.durationGetterName)
        }
    }
}
