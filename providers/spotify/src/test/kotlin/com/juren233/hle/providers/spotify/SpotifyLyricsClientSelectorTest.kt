/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SpotifyLyricsClientSelectorTest {
    @Test
    fun `true endpoint result waits for and selects v3 client`() {
        val selector = SpotifyLyricsClientSelector<Any>()
        val v3 = Any()

        assertNull(selector.onEndpointSelected(SpotifyLyricsEndpoint.V3))
        val selected = requireNotNull(selector.onClientAvailable(SpotifyLyricsEndpoint.V3, v3))

        assertEquals(SpotifyLyricsEndpoint.V3, selected.endpoint)
        assertSame(v3, selected.client)
    }

    @Test
    fun `false endpoint result selects existing v2 client`() {
        val selector = SpotifyLyricsClientSelector<Any>()
        val v2 = Any()

        assertNull(selector.onClientAvailable(SpotifyLyricsEndpoint.V2, v2))
        val selected = requireNotNull(selector.onEndpointSelected(SpotifyLyricsEndpoint.V2))

        assertEquals(SpotifyLyricsEndpoint.V2, selected.endpoint)
        assertSame(v2, selected.client)
    }

    @Test
    fun `unselected client and repeated selection do not emit`() {
        val selector = SpotifyLyricsClientSelector<Any>()
        val v2 = Any()
        val v3 = Any()

        selector.onClientAvailable(SpotifyLyricsEndpoint.V2, v2)
        selector.onClientAvailable(SpotifyLyricsEndpoint.V3, v3)
        val selected = requireNotNull(selector.onEndpointSelected(SpotifyLyricsEndpoint.V3))

        assertSame(v3, selected.client)
        assertNull(selector.onEndpointSelected(SpotifyLyricsEndpoint.V3))
        assertNull(selector.onClientAvailable(SpotifyLyricsEndpoint.V2, Any()))
    }

    @Test
    fun `endpoint switch emits the matching captured client`() {
        val selector = SpotifyLyricsClientSelector<Any>()
        val v2 = Any()
        val v3 = Any()
        selector.onClientAvailable(SpotifyLyricsEndpoint.V2, v2)
        selector.onClientAvailable(SpotifyLyricsEndpoint.V3, v3)

        assertSame(
            v3,
            requireNotNull(selector.onEndpointSelected(SpotifyLyricsEndpoint.V3)).client,
        )
        assertSame(
            v2,
            requireNotNull(selector.onEndpointSelected(SpotifyLyricsEndpoint.V2)).client,
        )
    }
}
