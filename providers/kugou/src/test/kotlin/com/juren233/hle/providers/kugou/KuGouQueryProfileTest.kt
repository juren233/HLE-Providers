/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kugou

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuGouQueryProfileTest {
    // Verified with build-tools/37.0.0/dexdump against the original APK DEX:
    // full 20.7.5 -> Lcom/kugou/framework/lyric/LyricManager;->k(Ljava/lang/String;)Lcom/kugou/framework/lyric/k;
    // lite 5.2.4 -> Lcom/kugou/framework/lyric/LyricManager;->k(Ljava/lang/String;Z)Lcom/kugou/framework/lyric/m;
    private val verifiedCurrentDescriptors = listOf(
        "Lcom/kugou/framework/lyric/LyricManager;->k(Ljava/lang/String;)Lcom/kugou/framework/lyric/k;",
        "Lcom/kugou/framework/lyric/LyricManager;->k(Ljava/lang/String;Z)Lcom/kugou/framework/lyric/m;",
    )

    private val rejectedFullOnlyShareDescriptor =
        "Lyv2/b;->a(Ljava/lang/String;)Lcom/kugou/framework/lyric/k;"

    @Test
    fun `full profile uses the verified LyricManager player path shape`() {
        val query = KuGouPluginEntry.queryFor("com.kugou.android")
        assertEquals("kugou-full-lyric-manager-v2", query.cacheKey)
        assertEquals("com.kugou.framework.lyric.LyricManager", query.declaringClassName)
        assertTrue(query.requiredStrings.isEmpty())
        assertEquals(listOf("java.lang.String"), query.parameterTypeNames)
        assertTrue(query.isStatic == false)
    }

    @Test
    fun `lite profile keeps the verified LyricManager descriptor constraints`() {
        val query = KuGouPluginEntry.queryFor("com.kugou.android.lite")
        assertEquals("com.kugou.framework.lyric.LyricManager", query.declaringClassName)
        assertEquals(listOf("java.lang.String", "boolean"), query.parameterTypeNames)
        assertTrue(query.isStatic == false)
    }

    @Test
    fun `keeps the original DEX evidence next to the resilient queries`() {
        assertEquals(2, verifiedCurrentDescriptors.size)
        assertTrue(verifiedCurrentDescriptors[0].contains("LyricManager;->k("))
        assertTrue(verifiedCurrentDescriptors[1].contains("LyricManager;->k("))
        assertFalse(verifiedCurrentDescriptors.contains(rejectedFullOnlyShareDescriptor))
    }
}
