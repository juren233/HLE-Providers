/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeakReferenceBucketsTest {

    @Test
    fun `same object registers once per key and separately across keys`() {
        val buckets = WeakReferenceBuckets<String, Any>()
        val binding = Any()
        val other = Any()

        assertTrue(buckets.add("1", binding))
        assertFalse(buckets.add("1", binding))
        assertTrue(buckets.add("1", other))
        assertTrue(buckets.add("2", binding))

        assertEquals(2, buckets["1"]?.size)
        assertEquals(1, buckets["2"]?.size)
    }

    @Test
    fun `sweep drops cleared references and empty buckets but keeps live ones`() {
        val buckets = WeakReferenceBuckets<String, Any>()
        val live = Any()
        buckets.add("live", live)
        buckets.add("dead", Any())
        buckets["dead"]?.forEach { it.clear() }

        buckets.sweep()

        assertNull(buckets["dead"])
        assertEquals(1, buckets["live"]?.size)
        assertEquals(1, buckets.keyCount)
    }

    @Test
    fun `periodic sweep runs on the configured registration interval`() {
        val buckets = WeakReferenceBuckets<String, Any>(sweepInterval = 2)
        buckets.add("dead", Any())
        buckets["dead"]?.forEach { it.clear() }

        buckets.add("live", Any())

        assertNull(buckets["dead"])
        assertEquals(1, buckets.keyCount)
    }
}
