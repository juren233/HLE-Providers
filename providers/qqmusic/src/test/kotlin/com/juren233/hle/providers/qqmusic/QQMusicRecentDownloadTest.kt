/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QQMusicRecentDownloadTest {
    @Test
    fun `same track and song ID reuses the last download`() {
        val recent = QQMusicRecentDownload<String>()
        recent.remember("4733575|失眠|Suki刘舒妤", "4733575", "lyrics")

        assertEquals("lyrics", recent.find("4733575|失眠|Suki刘舒妤", "4733575"))
    }

    @Test
    fun `different song ID or track does not reuse`() {
        val recent = QQMusicRecentDownload<String>()
        recent.remember("4733575|失眠|Suki刘舒妤", "4733575", "lyrics")

        assertNull(recent.find("4733575|失眠|Suki刘舒妤", "105345627"))
        assertNull(recent.find("272872413|Let's Fall in Love|FINNEAS", "4733575"))
    }

    @Test
    fun `only the latest download is kept`() {
        val recent = QQMusicRecentDownload<String>()
        recent.remember("a", "1", "first")
        recent.remember("b", "2", "second")

        assertNull(recent.find("a", "1"))
        assertEquals("second", recent.find("b", "2"))
    }
}
