/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyHookProfilesTest {
    @Test
    fun `keeps exact raw DEX color lyrics cache target and semantic fallback`() {
        val query = SpotifyHookProfiles.lyricsFallbackQuery
        val target = SpotifyHookProfiles.lyricsExactTarget

        assertEquals("p.tix0", target.className)
        assertEquals("n", target.methodName)
        assertEquals(listOf("java.lang.Object", "java.lang.Object"), target.parameterTypeNames)
        assertEquals("java.lang.Object", target.returnTypeName)
        assertEquals(
            listOf("Ljava/util/AbstractMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
            query.requiredInvokedMethodDescriptors,
        )
        assertEquals(listOf("accept"), query.requiredCallerMethodNames)
    }

    @Test
    fun `keeps exact PlayerState nextTracks target and DexKit semantics`() {
        val query = SpotifyHookProfiles.queueStateQuery
        val target = requireNotNull(query.preferredTarget)

        assertEquals("p.zw21", target.className)
        assertEquals("g", target.methodName)
        assertEquals(
            listOf(SpotifyHookProfiles.PLAYER_STATE_CLASS, "boolean", "boolean"),
            target.parameterTypeNames,
        )
        assertTrue(query.requiredInvokedMethodNames.contains("nextTracks"))
        assertTrue(query.requiredInvokedMethodNames.contains("disallowSkippingNextReasons"))
        assertEquals(
            "com.spotify.player.model.AutoValue_PlayerState",
            SpotifyHookProfiles.nextTracksAccessorTarget.className,
        )
        assertEquals("nextTracks", SpotifyHookProfiles.nextTracksAccessorTarget.methodName)
        assertEquals("p.f320", SpotifyHookProfiles.nextTracksAccessorTarget.returnTypeName)
    }
}
