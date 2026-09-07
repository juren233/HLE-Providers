/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyHookProfilesTest {
    @Test
    fun `selects the verified profile matching the host version code`() {
        assertEquals(144716725L, SpotifyHookProfiles.profileFor(144716725L).versionCode)
        assertEquals(145767611L, SpotifyHookProfiles.profileFor(145767611L).versionCode)
    }

    @Test
    fun `unknown host versions fall back to the newest verified profile`() {
        val fallback = SpotifyHookProfiles.profileFor(999999999L)

        assertEquals(145767611L, fallback.versionCode)
        assertEquals("9.1.80.2221", fallback.versionName)
    }

    @Test
    fun `keeps exact 9172 lyrics targets from original dex`() {
        val profile = SpotifyHookProfiles.profileFor(144716725L)

        assertEquals(
            listOf(
                SpotifyLyricsEndpoint.V3 to "p.am80",
                SpotifyLyricsEndpoint.V2 to "p.lg80",
            ),
            profile.lyricsClientConstructors.map { it.endpoint to it.target.className },
        )
        assertEquals(
            listOf("p.xl80", "p.q2m", "p.xhe"),
            profile.lyricsClientConstructors
                .single { it.endpoint == SpotifyLyricsEndpoint.V3 }
                .target.parameterTypeNames,
        )
        assertEquals(
            listOf("p.g980", "p.q2m", "p.q2m", "p.xhe"),
            profile.lyricsClientConstructors
                .single { it.endpoint == SpotifyLyricsEndpoint.V2 }
                .target.parameterTypeNames,
        )

        val selection = requireNotNull(profile.lyricsEndpointSelection)
        assertEquals("p.hx3", selection.className)
        assertEquals("b", selection.methodName)
        assertEquals(emptyList<String>(), selection.parameterTypeNames)
        assertEquals("boolean", selection.returnTypeName)
        assertEquals(false, selection.isStatic)

        assertEquals(
            listOf(
                SpotifyLyricsEndpoint.V3 to "p.am80",
                SpotifyLyricsEndpoint.V2 to "p.lg80",
            ),
            profile.lyricsRequests.map { it.endpoint to it.target.className },
        )
        assertTrue(profile.lyricsRequests.none { it.target.className == "p.cla0" })
        assertTrue(profile.lyricsRequests.none { it.target.className == "p.kf80" })
    }

    @Test
    fun `keeps exact 9180 lyrics targets from original dex`() {
        val profile = SpotifyHookProfiles.profileFor(145767611L)

        assertEquals(
            listOf(
                SpotifyLyricsEndpoint.V3 to "p.vja0",
                SpotifyLyricsEndpoint.V2 to "p.gea0",
            ),
            profile.lyricsClientConstructors.map { it.endpoint to it.target.className },
        )
        assertEquals(
            listOf("p.sja0", "p.p4n", "p.qbf"),
            profile.lyricsClientConstructors
                .single { it.endpoint == SpotifyLyricsEndpoint.V3 }
                .target.parameterTypeNames,
        )
        assertEquals(
            listOf("p.h7a0", "p.p4n", "p.p4n", "p.qbf"),
            profile.lyricsClientConstructors
                .single { it.endpoint == SpotifyLyricsEndpoint.V2 }
                .target.parameterTypeNames,
        )

        assertNull(profile.lyricsEndpointSelection)

        assertEquals(
            listOf(
                SpotifyLyricsEndpoint.V3 to "p.vja0",
                SpotifyLyricsEndpoint.V2 to "p.gea0",
            ),
            profile.lyricsRequests.map { it.endpoint to it.target.className },
        )
    }

    @Test
    fun `every profile request keeps the cross-version b descriptor`() {
        listOf(144716725L, 145767611L).forEach { versionCode ->
            SpotifyHookProfiles.profileFor(versionCode).lyricsRequests.forEach { request ->
                assertEquals("b", request.target.methodName)
                assertEquals(
                    listOf("java.lang.String", "java.lang.String"),
                    request.target.parameterTypeNames,
                )
                assertEquals("io.reactivex.rxjava3.core.Single", request.target.returnTypeName)
                assertEquals(false, request.target.isStatic)
                assertNotEquals("p.v581", request.target.className)
            }
        }
    }

    @Test
    fun `maps enable v3 flag to the same endpoint as Spotify dependency injection`() {
        assertEquals(SpotifyLyricsEndpoint.V3, SpotifyLyricsEndpoint.fromEnableV3(true))
        assertEquals(SpotifyLyricsEndpoint.V2, SpotifyLyricsEndpoint.fromEnableV3(false))
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
