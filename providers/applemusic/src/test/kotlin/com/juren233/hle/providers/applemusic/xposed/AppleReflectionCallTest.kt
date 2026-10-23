/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppleReflectionCallTest {

    private open class Base {
        @Suppress("unused")
        private fun secret(): String = "base"
    }

    private class Item(private val title: String) : Base() {
        @Suppress("unused")
        private fun title(): String = title

        @Suppress("unused")
        private fun title(suffix: String): String = title + suffix
    }

    @Test
    fun `zero arg call picks the parameterless overload per instance`() {
        assertEquals("a", AppleReflection.call(Item("a"), "title"))
        assertEquals("b", AppleReflection.call(Item("b"), "title"))
        assertEquals("a!", AppleReflection.call(Item("a"), "title", "!"))
        assertEquals("base", AppleReflection.call(Item("a"), "secret"))
    }

    @Test
    fun `missing zero arg method still throws on every call`() {
        repeat(2) {
            assertThrows(NoSuchMethodException::class.java) {
                AppleReflection.call(Item("a"), "missing")
            }
        }
    }
}
