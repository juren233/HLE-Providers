/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BodianBufferHookResolverTest {
    @Test
    fun `verified bodian version pins exact targets`() {
        val queries = BodianBufferHookResolver.queries(
            packageName = KuwoHostPlan.BODIAN_PACKAGE,
            versionName = "5.8.7",
            versionCode = 472L,
        )

        assertNotNull(queries)
        assertEquals(2, queries!!.size)
        val start = queries.first { it.cacheKey == BodianBufferHookResolver.BUFFER_START_CACHE_KEY }
        assertEquals("PlayDelegate_WaitForBuffering", start.preferredTarget?.methodName)
        assertEquals("com.tme.push.p3.e", start.preferredTarget?.className)
        val end = queries.first { it.cacheKey == BodianBufferHookResolver.BUFFER_END_CACHE_KEY }
        assertEquals("PlayDelegate_WaitForBufferingFinish", end.preferredTarget?.methodName)
    }

    @Test
    fun `unverified bodian versions skip buffer hooks instead of guessing`() {
        assertNull(
            BodianBufferHookResolver.queries(
                packageName = KuwoHostPlan.BODIAN_PACKAGE,
                versionName = "5.9.0",
                versionCode = 480L,
            ),
        )
    }

    @Test
    fun `kuwo host never installs bodian buffer hooks`() {
        assertNull(
            BodianBufferHookResolver.queries(
                packageName = KuwoHostPlan.KUWO_PACKAGE,
                versionName = "12.2.2.0",
                versionCode = 12_220L,
            ),
        )
    }
}
