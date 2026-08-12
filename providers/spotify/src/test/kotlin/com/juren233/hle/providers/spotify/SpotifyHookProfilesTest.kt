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
    fun `keeps exact track bound color lyrics success constructor`() {
        val target = SpotifyHookProfiles.lyricsSuccessConstructor

        assertEquals("p.v581", target.className)
        assertEquals(
            listOf("java.lang.String", "p.s2e"),
            target.parameterTypeNames,
        )
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
