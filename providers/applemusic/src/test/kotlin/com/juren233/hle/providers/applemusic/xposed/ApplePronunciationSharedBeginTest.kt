/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplePronunciationSharedBeginTest {
    @Test
    fun `two words with the same begin are detected`() {
        // 11:26 crash line: 2@14085-14090 and 3@14085-14725
        assertTrue(ApplePronunciationPolicy.hasSharedWordBegin(listOf(13425, 13855, 14085, 14085, 14725)))
    }

    @Test
    fun `distinct begins and repeated placeholders are not shared`() {
        assertFalse(ApplePronunciationPolicy.hasSharedWordBegin(listOf(34235, 35295, -1, 36725, -1)))
        assertFalse(ApplePronunciationPolicy.hasSharedWordBegin(emptyList()))
    }
}
