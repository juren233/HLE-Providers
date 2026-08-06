/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SaltPlayerHookProfilesTest {
    @Test
    fun `uses exact lyric getter descriptors from original dex`() {
        assertEquals("cn.lyric.getter.api.API", SaltPlayerHookProfiles.sendLyric.className)
        assertEquals("sendLyric", SaltPlayerHookProfiles.sendLyric.methodName)
        assertEquals(
            listOf("java.lang.String", "cn.lyric.getter.api.data.ExtraData"),
            SaltPlayerHookProfiles.sendLyric.parameterTypeNames,
        )
        assertEquals("void", SaltPlayerHookProfiles.sendLyric.returnTypeName)
        assertFalse(SaltPlayerHookProfiles.sendLyric.isStatic)
        assertEquals("clearLyric", SaltPlayerHookProfiles.clearLyric.methodName)
        assertEquals(emptyList<String>(), SaltPlayerHookProfiles.clearLyric.parameterTypeNames)
        assertEquals("void", SaltPlayerHookProfiles.clearLyric.returnTypeName)
        assertFalse(SaltPlayerHookProfiles.clearLyric.isStatic)
        assertFalse(SaltPlayerHookProfiles.sendLyric.className.startsWith("androidx.obf."))
    }

    @Test
    fun `records the apk version used for descriptor verification`() {
        assertEquals("12.1.1", SaltPlayerHookProfiles.VERIFIED_VERSION_NAME)
        assertEquals(2026070502L, SaltPlayerHookProfiles.VERIFIED_VERSION_CODE)
    }
}
