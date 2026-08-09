/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qishui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QishuiHookProfilesTest {
    @Test
    fun `keeps the exact raw DEX lyric conversion descriptor`() {
        val target = requireNotNull(QishuiHookProfiles.lyricConversionQuery.preferredTarget)

        assertEquals("com.luna.common.arch.db.entity.lyrics.NetTrackLyricKt", target.className)
        assertEquals("a", target.methodName)
        assertEquals(
            listOf(
                QishuiHookProfiles.NET_TRACK_LYRIC_CLASS,
                "java.lang.String",
                "java.lang.String",
            ),
            target.parameterTypeNames,
        )
        assertEquals(QishuiHookProfiles.TRACK_LYRIC_CLASS, target.returnTypeName)
        assertTrue(QishuiHookProfiles.lyricConversionQuery.requiredInvokedMethodNames.contains("getContent"))
        assertTrue(QishuiHookProfiles.lyricConversionQuery.requiredInvokedMethodNames.contains("getLangTranslations"))
    }

    @Test
    fun `keeps semantic DexKit fallbacks for current and both next methods`() {
        val queries = QishuiHookProfiles.queueQueries()

        assertEquals(4, queries.size)
        assertEquals("a", queries[0].preferredTarget?.methodName)
        assertEquals(listOf("getCurrentQueueItem"), queries[1].requiredInvokedMethodNames)
        assertEquals(listOf("getRealNextQueueItem"), queries[2].requiredInvokedMethodNames)
        assertEquals(listOf("getNextQueueItem"), queries[3].requiredInvokedMethodNames)
    }
}
