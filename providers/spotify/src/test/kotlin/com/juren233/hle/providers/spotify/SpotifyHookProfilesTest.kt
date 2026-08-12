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
    fun `keeps exact cla0 repository owner constructor from original dex`() {
        val target = SpotifyHookProfiles.lyricsRepositoryOwner

        assertEquals("p.cla0", target.className)
        assertEquals(
            listOf(
                "com.spotify.kodiak.dataloader.DataPool",
                "p.kg80",
            ),
            target.parameterTypeNames,
        )
        assertNotEquals("p.kf80", target.className)
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
    fun `debounces transient invalid hook callbacks`() {
        val tracker = SpotifyPluginEntry.SpotifyHookValidationTracker(invalidThreshold = 3)

        assertEquals(false, tracker.record(valid = false))
        assertEquals(false, tracker.record(valid = false))
        assertEquals(true, tracker.record(valid = false))
        assertEquals(false, tracker.record(valid = true))
        assertEquals(false, tracker.record(valid = false))
    }
}
