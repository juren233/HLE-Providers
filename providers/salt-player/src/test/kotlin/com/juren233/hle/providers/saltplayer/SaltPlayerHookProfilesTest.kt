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
    fun `keeps 12 point 1 lyrics and queue identifiers distinct`() {
        val profile = SaltPlayerHookProfiles.V12_1_1

        assertEquals("com.salt.music.service.MusicController", profile.musicControllerClassName)
        assertEquals("androidx.obf.xv0", profile.lyrics.documentClassName)
        assertEquals("androidx.obf.hw0", profile.lyrics.lineClassName)
        assertEquals("androidx.obf.tv0", profile.lyrics.cellClassName)
        assertEquals(0x07A3, profile.lyrics.publishMethodName.single().code)
        assertEquals(0x078C, profile.queue.stateFlowFieldName.single().code)
        assertEquals("androidx.obf.r42", profile.queue.stateClassName)
        assertEquals("androidx.obf.o42", profile.queue.itemClassName)
        assertEquals("androidx.obf.a42", profile.queue.modeClassName)
        assertEquals("\u05ef", profile.queue.itemDataFieldName)

        val target = profile.publishLyricsDocument
        assertEquals(profile.musicControllerClassName, target.className)
        assertEquals("\u07a3", target.methodName)
        assertEquals(listOf(profile.lyrics.documentClassName), target.parameterTypeNames)
        assertEquals("void", target.returnTypeName)
        assertEquals(true, target.isStatic)
    }

    @Test
    fun `records independently verified 12 point 0 identifiers`() {
        val profile = SaltPlayerHookProfiles.V12_0_0

        assertEquals("androidx.obf.jv0", profile.lyrics.documentClassName)
        assertEquals("androidx.obf.tv0", profile.lyrics.lineClassName)
        assertEquals("androidx.obf.fv0", profile.lyrics.cellClassName)
        assertEquals("\u0795", profile.lyrics.publishMethodName)
        assertEquals("\u0787", profile.queue.stateFlowFieldName)
        assertEquals("androidx.obf.h32", profile.queue.stateClassName)
        assertEquals("androidx.obf.e32", profile.queue.itemClassName)
        assertEquals("androidx.obf.q22", profile.queue.modeClassName)
        assertEquals("\u052e", profile.queue.itemDataFieldName)
    }

    @Test
    fun `records independently verified 11 point 1 identifiers`() {
        val profile = SaltPlayerHookProfiles.V11_1_0

        assertEquals("androidx.core.cs0", profile.lyrics.documentClassName)
        assertEquals("androidx.core.ks0", profile.lyrics.lineClassName)
        assertEquals("androidx.core.ur0", profile.lyrics.cellClassName)
        assertEquals("\u0794", profile.lyrics.publishMethodName)
        assertEquals("\u0787", profile.queue.stateFlowFieldName)
        assertEquals("androidx.core.ez1", profile.queue.stateClassName)
        assertEquals("androidx.core.bz1", profile.queue.itemClassName)
        assertEquals("androidx.core.oy1", profile.queue.modeClassName)
        assertEquals("\u058f", profile.queue.itemDataFieldName)
    }

    @Test
    fun `keeps shared lyrics field layout sourced from original dex`() {
        listOf(
            SaltPlayerHookProfiles.V12_1_1,
            SaltPlayerHookProfiles.V12_1_0,
            SaltPlayerHookProfiles.V12_0_0,
            SaltPlayerHookProfiles.V11_1_0,
        ).forEach { profile ->
            assertEquals("\u0528", profile.lyrics.documentLinesFieldName)
            assertEquals("\u037f", profile.lyrics.lineBeginFieldName)
            assertEquals("\u0528", profile.lyrics.lineEndFieldName)
            assertEquals("\u0529", profile.lyrics.lineCellsFieldName)
            assertEquals("\u052a", profile.lyrics.lineTranslationFieldName)
            assertEquals("\u052b", profile.lyrics.lineMainTextFieldName)
            assertEquals("\u037f", profile.lyrics.cellBeginFieldName)
            assertEquals("\u0528", profile.lyrics.cellEndFieldName)
            assertEquals("\u0529", profile.lyrics.cellTextFieldName)
            assertEquals("getValue", profile.queue.stateFlowValueGetterName)
            assertEquals("Circle", profile.queue.circleModeName)
            assertEquals("CircleEnd", profile.queue.circleEndModeName)
            assertEquals("RepeatOne", profile.queue.repeatOneModeName)
            assertEquals("Random", profile.queue.randomModeName)
        }
    }
}
