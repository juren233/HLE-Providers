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
    fun `keeps semantic color lyrics cache query`() {
        val query = SpotifyHookProfiles.lyricsQuery
        val target = requireNotNull(query.preferredTarget)

        assertEquals("p.tix0", target.className)
        assertEquals("n", target.methodName)
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
