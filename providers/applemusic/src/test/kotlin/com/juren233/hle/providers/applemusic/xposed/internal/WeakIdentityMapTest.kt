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

class WeakIdentityMapTest {

    @Test
    fun `equal but distinct keys keep isolated values`() {
        val map = WeakIdentityMap<Any, String>()
        val first: Any = String(charArrayOf('i', 'd'))
        val second: Any = String(charArrayOf('i', 'd'))

        assertEquals(first, second)
        assertTrue(first !== second)

        map[first] = "first"
        map[second] = "second"

        assertEquals("first", map[first])
        assertEquals("second", map[second])
    }

    @Test
    fun `remove and clear affect only the requested identities`() {
        val map = WeakIdentityMap<Any, String>()
        val first = Any()
        val second = Any()

        map[first] = "first"
        map[second] = "second"
        map.remove(first)

        assertNull(map[first])
        assertEquals("second", map[second])

        map.clear()
        assertNull(map[second])
    }

    @Test
    fun `overwrite putIfAbsent and keys keep identity semantics`() {
        val map = WeakIdentityMap<Any, String>()
        val first: Any = String(charArrayOf('k'))
        val second: Any = String(charArrayOf('k'))

        assertTrue(map.putIfAbsent(first, "first"))
        assertFalse(map.putIfAbsent(first, "ignored"))
        assertTrue(map.putIfAbsent(second, "second"))
        map[first] = "updated"

        assertEquals("updated", map[first])
        assertEquals("second", map[second])
        assertEquals(2, map.keys().size)
        assertTrue(map.keys()[0] === first)
        assertTrue(map.keys()[1] === second)
        assertNull(map[Any()])
    }
}
