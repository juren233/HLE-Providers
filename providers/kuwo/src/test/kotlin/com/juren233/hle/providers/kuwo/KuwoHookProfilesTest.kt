/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KuwoHookProfilesTest {
    @Test
    fun `uses exact original DEX identifiers for Kuwo 12 1 8 2`() {
        val profile = KuwoHookProfiles.resolve("12.1.8.2", 12_182L)!!

        assertEquals("cn.kuwo.mod.playcontrol.n", profile.playback.managerClassName)
        assertEquals("cn.kuwo.base.bean.IContent", profile.playback.contentClassName)
        assertEquals("cn.kuwo.base.bean.Music", profile.playback.musicClassName)
        assertEquals("L", profile.playback.singletonMethodName)
        assertEquals("S", profile.playback.currentMusicMethodName)
        assertEquals("g0", profile.playback.nextContentMethodName)
        assertEquals("rid", profile.music.ridFieldName)
        assertEquals("name", profile.music.titleFieldName)
        assertEquals("artist", profile.music.artistFieldName)
        assertEquals("album", profile.music.albumFieldName)
        assertEquals("duration", profile.music.durationSecondsFieldName)
    }

    @Test
    fun `rejects unverified Kuwo versions`() {
        assertNull(KuwoHookProfiles.resolve("12.1.8.1", 12_181L))
        assertNull(KuwoHookProfiles.resolve("12.1.8.3", 12_183L))
    }
}
