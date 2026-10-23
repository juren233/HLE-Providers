/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppleCatalogRequestGateTest {

    private val posted = ArrayDeque<Runnable>()
    private fun runPosted() {
        while (posted.isNotEmpty()) posted.removeFirst().run()
    }

    @Test
    fun `only one task holds the permit and waiters start in submit order`() {
        val gate = AppleCatalogRequestGate(dispatch = { posted.addLast(it) })
        val started = mutableListOf<String>()
        val releases = mutableMapOf<String, () -> Unit>()
        listOf("a", "b", "c").forEach { name ->
            gate.submit { release -> started += name; releases[name] = release }
        }
        runPosted()
        assertEquals(listOf("a"), started)
        assertEquals(2, gate.waitingCount)

        releases.getValue("a")()
        releases.getValue("a")()
        runPosted()
        assertEquals(listOf("a", "b"), started)
        assertEquals(1, gate.waitingCount)

        releases.getValue("b")()
        runPosted()
        releases.getValue("c")()
        gate.submit { release -> started += "d"; release() }
        runPosted()
        assertEquals(listOf("a", "b", "c", "d"), started)
        assertEquals(0, gate.waitingCount)
    }

    @Test
    fun `a task that throws while starting returns its permit`() {
        val gate = AppleCatalogRequestGate(dispatch = { posted.addLast(it) })
        val started = mutableListOf<String>()
        gate.submit { throw IllegalStateException("boom") }
        gate.submit { release -> started += "next"; release() }

        assertThrows(IllegalStateException::class.java) { runPosted() }
        runPosted()
        assertEquals(listOf("next"), started)
    }

    @Test
    fun `holder yields while native requests are busy but only up to the cap`() {
        var now = 0L
        var busy = true
        val delayed = ArrayDeque<Pair<Runnable, Long>>()
        val gate = AppleCatalogRequestGate(
            dispatch = { posted.addLast(it) },
            dispatchDelayed = { runnable, delayMs -> delayed.addLast(runnable to delayMs) },
            shouldYield = { busy },
            clock = { now },
            maxYieldMs = 1_000L,
            yieldPollMs = 50L,
        )
        val started = mutableListOf<Long>()
        gate.submit { release -> started += now; release() }

        runPosted()
        assertEquals(emptyList<Long>(), started)
        // 仍在让行：每 50 ms 复查，未到上限不启动。
        repeat(3) {
            val (runnable, delayMs) = delayed.removeFirst()
            assertEquals(50L, delayMs)
            now += delayMs
            runnable.run()
        }
        assertEquals(emptyList<Long>(), started)

        busy = false
        now += 50L
        delayed.removeFirst().first.run()
        assertEquals(listOf(200L), started)
    }

    @Test
    fun `yield stops at the cap even if native requests stay busy`() {
        var now = 0L
        val delayed = ArrayDeque<Runnable>()
        val gate = AppleCatalogRequestGate(
            dispatch = { posted.addLast(it) },
            dispatchDelayed = { runnable, _ -> delayed.addLast(runnable) },
            shouldYield = { true },
            clock = { now },
            maxYieldMs = 1_000L,
            yieldPollMs = 250L,
        )
        val started = mutableListOf<Long>()
        gate.submit { release -> started += now; release() }
        runPosted()
        while (started.isEmpty()) {
            now += 250L
            delayed.removeFirst().run()
        }
        assertEquals(listOf(1_000L), started)
    }
}
