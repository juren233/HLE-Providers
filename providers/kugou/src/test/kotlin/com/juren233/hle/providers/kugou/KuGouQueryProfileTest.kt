/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kugou

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KuGouQueryProfileTest {
    @Test
    fun `next track profiles preserve the verified semantics of each Kugou variant`() {
        val full = KuGouPluginEntry.nextTrackQueriesFor("com.kugou.android")
        val lite = KuGouPluginEntry.nextTrackQueriesFor("com.kugou.android.lite")

        assertEquals("K4", full[0].preferredTarget?.methodName)
        assertEquals("c4", lite[0].preferredTarget?.methodName)
        assertEquals("k", full[1].preferredTarget?.methodName)
        assertNull(lite[1].preferredTarget)
        assertEquals("kugou-lite-next-media-v2", lite[1].cacheKey)
        assertEquals(listOf("getNextMedia"), lite[1].requiredCallerMethodNames)
        assertEquals(
            "com.kugou.common.player.manager.IMedia",
            full[1].preferredTarget?.returnTypeName,
        )
    }
}
