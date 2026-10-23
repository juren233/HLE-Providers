/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hle.providers.applemusic.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class AppleBudgetedResultQueueTest {
    @Test fun `tasks run in submission order and split across messages once the budget is used`() {
        val posted = ArrayDeque<Runnable>()
        var now = 0L
        val queue = AppleBudgetedResultQueue(
            post = { posted.addLast(it) },
            clock = { now },
            budgetNs = 10L,
        )
        val executed = mutableListOf<Int>()
        repeat(5) { index ->
            queue.enqueue {
                executed += index
                now += 6L
            }
        }
        assertEquals("only one drain is scheduled for a burst", 1, posted.size)

        posted.removeFirst().run()
        assertEquals(listOf(0, 1), executed)
        assertEquals("remaining tasks continue in the next message", 1, posted.size)

        posted.removeFirst().run()
        posted.removeFirst().run()
        assertEquals(listOf(0, 1, 2, 3, 4), executed)
        assertEquals(0, posted.size)
    }

    @Test fun `a single task larger than the budget still runs`() {
        val posted = ArrayDeque<Runnable>()
        var now = 0L
        val queue = AppleBudgetedResultQueue(post = { posted.addLast(it) }, clock = { now }, budgetNs = 1L)
        var ran = false
        queue.enqueue {
            ran = true
            now += 100L
        }
        posted.removeFirst().run()
        assertEquals(true, ran)
        assertEquals(0, posted.size)
    }
}
