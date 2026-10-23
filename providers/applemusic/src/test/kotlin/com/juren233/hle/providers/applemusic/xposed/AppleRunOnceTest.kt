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

class AppleRunOnceTest {
    @Test
    fun `only the first of idle and timeout triggers runs the block`() {
        val once = AppleRunOnce()
        var runs = 0

        assertTrue(once.run { runs++ })
        assertFalse(once.run { runs++ })
        assertEquals(1, runs)
    }
}
