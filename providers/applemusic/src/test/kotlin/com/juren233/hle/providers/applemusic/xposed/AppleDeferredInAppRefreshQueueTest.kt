/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleDeferredInAppRefreshQueueTest {
    @Test
    fun repeatedOffersForSameIdScheduleOnceAndMergeForce() {
        val queue = AppleDeferredInAppRefreshQueue()

        assertTrue(queue.offer("1", forceRebind = false))
        assertFalse(queue.offer("1", forceRebind = true))
        assertFalse(queue.offer("1", forceRebind = false))
        assertFalse(queue.offer("2", forceRebind = false))

        val drained = queue.drain()
        assertEquals(mapOf("1" to true, "2" to false), drained.pending)
        assertEquals(4, drained.offers)
    }

    @Test
    fun drainResetsSchedulingAndCancelRemovesPendingId() {
        val queue = AppleDeferredInAppRefreshQueue()
        queue.offer("1", forceRebind = true)
        queue.offer("2", forceRebind = true)
        queue.cancel("1")

        assertEquals(mapOf("2" to true), queue.drain().pending)
        assertTrue(queue.offer("3", forceRebind = false))
        assertEquals(listOf("3"), queue.drain().pending.keys.toList())
    }

    @Test
    fun restorePutsLeftoversFirstMergesForceAndDoesNotCountOffers() {
        val queue = AppleDeferredInAppRefreshQueue()
        queue.offer("1", forceRebind = false)
        queue.drain()

        assertTrue(queue.offer("2", forceRebind = true))
        assertFalse(queue.restore(mapOf("3" to false, "2" to false)))

        val drained = queue.drain()
        assertEquals(listOf("3", "2"), drained.pending.keys.toList())
        assertEquals(true, drained.pending["2"])
        assertEquals(1, drained.offers)
        assertFalse(queue.restore(emptyMap()))
        assertTrue(queue.restore(mapOf("4" to true)))
    }
}
