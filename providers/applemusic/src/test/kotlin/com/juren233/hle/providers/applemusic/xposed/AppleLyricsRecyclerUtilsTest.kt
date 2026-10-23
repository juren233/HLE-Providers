/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hle.providers.applemusic.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsRecyclerUtilsTest {
    private open class BaseHolder {
        var lineText: String? = "line"
        var translation: String? = "translation"
        var lineId: Int = 3
    }

    private class Holder : BaseHolder() {
        var pronunciation: String? = "roma"
        val payloads = LinkedHashSet<String>()
        var selected: Boolean = true
    }

    @Test
    fun `clears string caches across hierarchy but keeps other fields`() {
        val holder = Holder()

        assertEquals(3, clearAppleLyricsHolderTextCaches(holder))

        assertNull(holder.lineText)
        assertNull(holder.translation)
        assertNull(holder.pronunciation)
        assertEquals(3, holder.lineId)
        assertTrue(holder.selected)
        assertEquals(0, holder.payloads.size)
    }
}
