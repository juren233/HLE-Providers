/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderConstructorTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyHookInstallationStateTest {
    @Test
    fun `early fallback does not block a later exact profile`() {
        val state = SpotifyHookInstallationState()
        val fallback = SpotifyHookProfiles.fallbackProfile()
        val older = SpotifyHookProfiles.exactProfileFor(144_716_725L)!!
        val fallbackClient = fallback.lyricsClientConstructors.first()
        val olderClient = older.lyricsClientConstructors.first()

        state.recordClientConstructor(fallbackClient)
        assertFalse(state.needsClientConstructor(fallbackClient))
        assertTrue(state.needsClientConstructor(olderClient))

        assertTrue(state.shouldLogMissingEndpointSelector())
        assertFalse(state.shouldLogMissingEndpointSelector())
        val olderSelector = older.lyricsEndpointSelection!!
        assertTrue(state.needsEndpointSelector(olderSelector))
        state.recordEndpointSelector(olderSelector)
        assertFalse(state.needsEndpointSelector(olderSelector))
    }

    @Test
    fun `new wrapper and changed method descriptor remain installable`() {
        val state = SpotifyHookInstallationState()
        val fallback = SpotifyHookProfiles.fallbackProfile()
        val fallbackClient = fallback.lyricsClientConstructors.first()
        val nextClient = fallbackClient.copy(
            target = OfficialProviderConstructorTarget(
                className = "p.newwrapper",
                firstParameterTypeName = "p.newservice",
            ),
        )
        val fallbackRequest = fallback.lyricsRequests.first().target
        val changedRequest = fallbackRequest.copy(
            parameterTypeNames = listOf("java.lang.String"),
        )

        assertTrue(state.needsClientConstructor(fallbackClient))
        state.recordClientConstructor(fallbackClient)
        assertTrue(state.needsClientConstructor(nextClient))
        state.recordClientConstructor(nextClient)
        assertFalse(state.needsClientConstructor(nextClient))

        assertTrue(state.needsRequestTarget(fallbackRequest))
        state.recordRequestTarget(fallbackRequest)
        assertFalse(state.needsRequestTarget(fallbackRequest))
        assertTrue(state.needsRequestTarget(changedRequest))
    }
}
