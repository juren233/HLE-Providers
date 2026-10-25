/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kugou

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouQueryProfileTest {
    private val full = "com.kugou.android"
    private val lite = "com.kugou.android.lite"

    @Test
    fun `20_7_5 profile keeps the verified names of the original release`() {
        val queries = KuGouPluginEntry.nextTrackQueriesFor(full, versionCode = 20759L)

        assertEquals("K4", queries[0].preferredTarget?.methodName)
        assertEquals("kugou-full-player-singleton-v3", queries[0].cacheKey)
        assertEquals("com.kugou.framework.service.KGPlayerManager", queries[0].declaringClassName)
        assertEquals("k", queries[1].preferredTarget?.methodName)
        assertEquals("kugou-full-next-media-v2", queries[1].cacheKey)
        assertEquals(listOf("w", "v"), queries[1].requiredInvokedMethodNames)
        assertEquals(emptyList<String>(), queries[1].requiredCallerMethodNames)
        assertEquals("com.kugou.common.player.manager.IMedia", queries[1].returnTypeName)
    }

    @Test
    fun `20_8_2 profile follows the renamed queue methods`() {
        val queries = KuGouPluginEntry.nextTrackQueriesFor(full, versionCode = 20829L)

        assertEquals("I4", queries[0].preferredTarget?.methodName)
        assertEquals("kugou-full-player-singleton-v3", queries[0].cacheKey)
        assertEquals("j", queries[1].preferredTarget?.methodName)
        assertEquals("kugou-full-next-media-v3", queries[1].cacheKey)
        assertEquals(listOf("t", "s"), queries[1].requiredInvokedMethodNames)
        assertEquals(emptyList<String>(), queries[1].requiredCallerMethodNames)
        assertEquals("com.kugou.common.player.manager.QueuePlayerManager", queries[1].declaringClassName)
        assertEquals("com.kugou.common.player.manager.IMedia", queries[1].returnTypeName)
        assertEquals(emptyList<String>(), queries[1].parameterTypeNames)
        assertEquals(false, queries[1].isStatic)
    }

    @Test
    fun `profile selection follows the newest profile not above the installed version`() {
        fun methodAt(versionCode: Long?) = KuGouNextTrackProfiles.fullProfileFor(versionCode).nextMediaMethodName

        assertEquals("k", methodAt(0L))
        assertEquals("k", methodAt(20759L))
        assertEquals("k", methodAt(20760L))
        assertEquals("k", methodAt(20828L))
        assertEquals("j", methodAt(20829L))
        assertEquals("j", methodAt(20830L))
        assertEquals("j", methodAt(99999L))
    }

    @Test
    fun `unknown installed version falls back to the newest profile`() {
        assertEquals(
            "j",
            KuGouPluginEntry.nextTrackQueriesFor(full, versionCode = null)[1].preferredTarget?.methodName,
        )
    }

    @Test
    fun `every profile uses its own cache key and distinct queue method names`() {
        val profiles = listOf(20759L, 20829L).map(KuGouNextTrackProfiles::fullProfileFor)

        assertEquals(profiles.size, profiles.map { it.nextMediaCacheKey }.toSet().size)
        profiles.forEach { profile ->
            val names = listOf(
                profile.nextMediaMethodName,
                profile.nextIndexMethodName,
                profile.mediaAtIndexMethodName,
            )
            assertEquals("a profile must not reuse one name for two roles: $profile", names.size, names.toSet().size)
            assertTrue(profile.verifiedAgainst.isNotBlank())
        }
    }

    @Test
    fun `lite keeps the caller anchored query and ignores the full version profiles`() {
        val atOld = KuGouPluginEntry.nextTrackQueriesFor(lite, versionCode = 20759L)
        val atNew = KuGouPluginEntry.nextTrackQueriesFor(lite, versionCode = 20829L)

        assertEquals(atOld, atNew)
        assertEquals("c4", atOld[0].preferredTarget?.methodName)
        assertEquals("kugou-lite-player-singleton-v3", atOld[0].cacheKey)
        assertNull(atOld[1].preferredTarget)
        assertEquals("kugou-lite-next-media-v2", atOld[1].cacheKey)
        assertEquals(listOf("getNextMedia"), atOld[1].requiredCallerMethodNames)
        assertEquals(emptyList<String>(), atOld[1].requiredInvokedMethodNames)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects packages that are not kugou`() {
        KuGouPluginEntry.nextTrackQueriesFor("com.example.player", versionCode = 20829L)
    }
}
