/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseLyricSelectionTest {
    @Test
    fun `keeps API ahead of app lyrics regardless of arrival order`() {
        val selection = NeteaseLyricSelection<String>()
        selection.setApp(1, "embedded")
        assertEquals(NeteaseLyricSelection.Selected(NeteaseLyricSelection.Source.APP, "embedded"), selection.select(1))

        selection.setApi(1, "official")
        assertEquals(NeteaseLyricSelection.Selected(NeteaseLyricSelection.Source.API, "official"), selection.select(1))

        selection.setApp(2, "embedded")
        selection.setApi(2, "official")
        assertEquals(NeteaseLyricSelection.Selected(NeteaseLyricSelection.Source.API, "official"), selection.select(2))
    }

    @Test
    fun `uses each available source alone and handles neither`() {
        val selection = NeteaseLyricSelection<String>()
        assertNull(selection.select(1))
        selection.setApi(1, "official")
        assertEquals(NeteaseLyricSelection.Source.API, selection.select(1)?.source)
        selection.setApp(2, "embedded")
        assertEquals(NeteaseLyricSelection.Source.APP, selection.select(2)?.source)
        assertNull(selection.select(3))
    }

    @Test
    fun `empty API refresh preserves a valid primary and isolates track switches`() {
        val selection = NeteaseLyricSelection<String>()
        selection.setApp(1, "first embedded")
        selection.setApi(1, null)
        assertEquals("first embedded", selection.select(1)?.value)
        selection.setApi(1, "first official")
        selection.setApi(1, null)
        assertEquals("first official", selection.select(1)?.value)

        selection.setApi(2, "second official")
        assertEquals("second official", selection.select(2)?.value)
        assertEquals("first official", selection.select(1)?.value)
        assertFalse(selection.setApp(1, "first embedded"))
        assertTrue(selection.setApp(1, "first updated"))
    }

    @Test
    fun `file embedded lyrics win over API and app lyrics`() {
        val selection = NeteaseLyricSelection<String>()
        selection.setApp(1, "app")
        selection.setApi(1, "official")
        selection.beginEmbeddedLookup(1)
        selection.finishEmbeddedLookup(1, "file")

        assertEquals(NeteaseLyricSelection.Selected(NeteaseLyricSelection.Source.EMBEDDED, "file"), selection.select(1))
    }

    @Test
    fun `holds online lyrics while the file lookup runs and releases them when the file has none`() {
        val selection = NeteaseLyricSelection<String>()
        selection.setApp(1, "app")
        selection.beginEmbeddedLookup(1)
        assertNull(selection.select(1))

        selection.finishEmbeddedLookup(1, null)
        assertEquals(NeteaseLyricSelection.Selected(NeteaseLyricSelection.Source.APP, "app"), selection.select(1))
        selection.setApi(1, "official")
        assertEquals(NeteaseLyricSelection.Source.API, selection.select(1)?.source)
    }

    @Test
    fun `a repeated lookup keeps showing the known embedded lyrics`() {
        val selection = NeteaseLyricSelection<String>()
        selection.beginEmbeddedLookup(1)
        selection.finishEmbeddedLookup(1, "file")
        selection.beginEmbeddedLookup(1)

        assertEquals("file", selection.select(1)?.value)
    }

    @Test
    fun `rejects NetEase no lyrics placeholder`() {
        assertFalse(NeteaseLyricContentPolicy.hasMeaningfulText(listOf("暂无歌词")))
        assertFalse(NeteaseLyricContentPolicy.hasMeaningfulText(listOf("", " 暂无歌词 ")))
        assertTrue(NeteaseLyricContentPolicy.hasMeaningfulText(listOf("暂无歌词", "实际歌词")))
    }
}
