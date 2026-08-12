/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyHookProfilesTest {
    @Test
    fun `keeps exact v3 and v2 lyrics client constructors from original dex`() {
        val profiles = SpotifyHookProfiles.lyricsClientConstructors

        assertEquals(
            listOf(
                SpotifyLyricsEndpoint.V3 to "p.am80",
                SpotifyLyricsEndpoint.V2 to "p.lg80",
            ),
            profiles.map { it.endpoint to it.target.className },
        )
        assertEquals(
            listOf("p.xl80", "p.q2m", "p.xhe"),
            profiles.single { it.endpoint == SpotifyLyricsEndpoint.V3 }.target.parameterTypeNames,
        )
        assertEquals(
            listOf("p.g980", "p.q2m", "p.q2m", "p.xhe"),
            profiles.single { it.endpoint == SpotifyLyricsEndpoint.V2 }.target.parameterTypeNames,
        )
        assertTrue(profiles.none { it.target.className == "p.cla0" })
        assertTrue(profiles.none { it.target.className == "p.kf80" })
    }

    @Test
    fun `keeps exact endpoint selection method from original dex`() {
        val target = SpotifyHookProfiles.lyricsEndpointSelection

        assertEquals("p.hx3", target.className)
        assertEquals("b", target.methodName)
        assertEquals(emptyList<String>(), target.parameterTypeNames)
        assertEquals("boolean", target.returnTypeName)
        assertEquals(false, target.isStatic)
    }

    @Test
    fun `maps enable v3 flag to the same endpoint as Spotify dependency injection`() {
        assertEquals(SpotifyLyricsEndpoint.V3, SpotifyLyricsEndpoint.fromEnableV3(true))
        assertEquals(SpotifyLyricsEndpoint.V2, SpotifyLyricsEndpoint.fromEnableV3(false))
    }

    @Test
    fun `keeps both exact kg80 implementation request descriptors`() {
        val targets = SpotifyHookProfiles.lyricsRequests

        assertEquals(listOf("p.am80", "p.lg80"), targets.map { it.className })
        assertEquals(2, targets.size)
        targets.forEach { target ->
            assertEquals("b", target.methodName)
            assertEquals(
                listOf("java.lang.String", "java.lang.String"),
                target.parameterTypeNames,
            )
            assertEquals("io.reactivex.rxjava3.core.Single", target.returnTypeName)
            assertEquals(false, target.isStatic)
            assertNotEquals("p.v581", target.className)
        }
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
    }

    @Test
    fun `keeps exact AutoValue PlayerState nextTracks accessor from original dex`() {
        val target = SpotifyHookProfiles.nextTracksAccessorTarget

        assertEquals("com.spotify.player.model.AutoValue_PlayerState", target.className)
        assertEquals("nextTracks", target.methodName)
        assertEquals(emptyList<String>(), target.parameterTypeNames)
        assertEquals("p.f320", target.returnTypeName)
        assertEquals(false, target.isStatic)
    }

    @Test
    fun `debounces transient invalid hook callbacks`() {
        val tracker = SpotifyPluginEntry.SpotifyHookValidationTracker(invalidThreshold = 3)

        assertEquals(false, tracker.record(valid = false))
        assertEquals(false, tracker.record(valid = false))
        assertEquals(true, tracker.record(valid = false))
        assertEquals(false, tracker.record(valid = true))
        assertEquals(false, tracker.record(valid = false))
    }
}
