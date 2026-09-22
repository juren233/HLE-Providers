/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.netease

import org.junit.Assert.assertEquals
import org.junit.Test

class NeteaseInlineTimestampSanitizerTest {
    @Test
    fun `removes embedded word timestamps without changing line timing`() {
        val raw = "[00:13.43]<00:13.871>跟<00:13.991>佢<00:14.191>做<00:14.381>个<00:14.631>Friend<00:15.321>"

        assertEquals("[00:13.43]跟佢做个Friend", NeteaseInlineTimestampSanitizer.strip(raw))
    }

    @Test
    fun `removes timestamp only text and keeps ordinary angle text`() {
        assertEquals("", NeteaseInlineTimestampSanitizer.strip("<00:13.431>"))
        assertEquals("I <3 U <verse>", NeteaseInlineTimestampSanitizer.strip("I <3 U <verse>"))
        assertEquals("lyric", NeteaseInlineTimestampSanitizer.strip("<01:02>lyric"))
    }
}
