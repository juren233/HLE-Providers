/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NeteaseAppLyricsProfileTest {
    @Test
    fun `matches the original 9 5 81 DEX descriptor exactly`() {
        val target = NeteaseAppLyricsProfile.targetFor("com.netease.cloudmusic", 9_005_081L)!!

        assertEquals("com.netease.cloudmusic.module.lyric.e", target.className)
        assertEquals("v0", target.methodName)
        assertEquals(
            listOf(
                "com.netease.cloudmusic.meta.LyricData",
                "com.netease.cloudmusic.module.lyric.e\$d",
            ),
            target.parameterTypeNames,
        )
        assertEquals("com.netease.cloudmusic.meta.LyricInfo", target.returnTypeName)
        assertFalse(target.isStatic)
        assertFalse(target.parameterTypeNames.any { it.contains("C0100e") })
    }

    @Test
    fun `does not reuse unverified versions or Honor package`() {
        assertNull(NeteaseAppLyricsProfile.targetFor("com.netease.cloudmusic", 9_005_080L))
        assertNull(NeteaseAppLyricsProfile.targetFor("com.netease.cloudmusic", 9_005_082L))
        assertNull(NeteaseAppLyricsProfile.targetFor("com.hihonor.cloudmusic", 9_005_081L))
    }
}
