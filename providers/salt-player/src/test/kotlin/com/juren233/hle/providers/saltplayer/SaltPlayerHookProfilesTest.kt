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
    fun `records 12 point 1 next track queue identifiers`() {
        val profile = SaltPlayerHookProfiles.V12_1_1

        assertEquals("com.salt.music.service.MusicController", profile.musicControllerClassName)
        assertEquals(0x078C, profile.queue.stateFlowFieldName.single().code)
        assertEquals("androidx.obf.r42", profile.queue.stateClassName)
        assertEquals("androidx.obf.o42", profile.queue.itemClassName)
        assertEquals("androidx.obf.a42", profile.queue.modeClassName)
        assertEquals("\u05ef", profile.queue.itemDataFieldName)
    }

    @Test
    fun `records independently verified 12 point 0 identifiers`() {
        val profile = SaltPlayerHookProfiles.V12_0_0

        assertEquals("\u0787", profile.queue.stateFlowFieldName)
        assertEquals("androidx.obf.h32", profile.queue.stateClassName)
        assertEquals("androidx.obf.e32", profile.queue.itemClassName)
        assertEquals("androidx.obf.q22", profile.queue.modeClassName)
        assertEquals("\u052e", profile.queue.itemDataFieldName)
    }

    @Test
    fun `records independently verified 11 point 1 identifiers`() {
        val profile = SaltPlayerHookProfiles.V11_1_0

        assertEquals("\u0787", profile.queue.stateFlowFieldName)
        assertEquals("androidx.core.ez1", profile.queue.stateClassName)
        assertEquals("androidx.core.bz1", profile.queue.itemClassName)
        assertEquals("androidx.core.oy1", profile.queue.modeClassName)
        assertEquals("\u058f", profile.queue.itemDataFieldName)
    }

    @Test
    fun `keeps shared queue contract sourced from original dex`() {
        listOf(
            SaltPlayerHookProfiles.V12_1_1,
            SaltPlayerHookProfiles.V12_1_0,
            SaltPlayerHookProfiles.V12_0_0,
            SaltPlayerHookProfiles.V11_1_0,
        ).forEach { profile ->
            assertEquals("getValue", profile.queue.stateFlowValueGetterName)
            assertEquals("Circle", profile.queue.circleModeName)
            assertEquals("CircleEnd", profile.queue.circleEndModeName)
            assertEquals("RepeatOne", profile.queue.repeatOneModeName)
            assertEquals("Random", profile.queue.randomModeName)
        }
    }
}
