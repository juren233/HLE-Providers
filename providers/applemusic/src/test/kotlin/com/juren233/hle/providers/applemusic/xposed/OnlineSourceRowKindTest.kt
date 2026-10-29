/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import com.juren233.hyperlyricsenhanced.common.lyric.OnlineSourceContentState
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineSourceContentStatesCodec
import org.junit.Test
import org.junit.Assert.assertEquals

class OnlineSourceRowKindTest {
    private fun kind(
        source: String,
        state: OnlineSourceContentState?,
        contentType: String = "translation",
        selected: Boolean = false,
        hasStat: Boolean = false,
    ) = onlineSourceRowKind(contentType, source, selected, state, hasStat)

    @Test
    fun `catalog sources are judged by their actual content like every other source`() {
        assertEquals(OnlineSourceRowKind.SEARCHING, kind("AMLL", null))
        assertEquals(OnlineSourceRowKind.AVAILABLE, kind("AMLL", OnlineSourceContentState(true, true)))
        assertEquals(
            OnlineSourceRowKind.AVAILABLE,
            kind("AMLL", OnlineSourceContentState(true, true), contentType = "pronunciation"),
        )
        assertEquals(
            OnlineSourceRowKind.NO_CONTENT,
            kind("LB", OnlineSourceContentState(true, false), contentType = "pronunciation"),
        )
        assertEquals(OnlineSourceRowKind.SEARCH_FAILED, kind("LB", OnlineSourceContentState(false, false, searchFailed = true)))
    }

    @Test
    fun `searched source without the content is dimmed`() {
        assertEquals(
            OnlineSourceRowKind.NO_CONTENT,
            kind("NE", OnlineSourceContentState(hasTranslation = false, hasPronunciation = true)),
        )
        assertEquals(
            OnlineSourceRowKind.AVAILABLE,
            kind("NE", OnlineSourceContentState(false, true), contentType = "pronunciation"),
        )
    }

    @Test
    fun `failed search stays clickable and unreported source is searching`() {
        assertEquals(
            OnlineSourceRowKind.SEARCH_FAILED,
            kind("QM", OnlineSourceContentState(false, false, searchFailed = true)),
        )
        assertEquals(OnlineSourceRowKind.SEARCHING, kind("QM", null))
        assertEquals(OnlineSourceRowKind.AVAILABLE, kind("QM", null, hasStat = true))
    }

    @Test
    fun `current source is always available`() {
        assertEquals(OnlineSourceRowKind.AVAILABLE, kind("NE", OnlineSourceContentState(false, false), selected = true))
    }

    @Test
    fun `states codec round trips`() {
        val states = mapOf(
            "NE" to OnlineSourceContentState(true, true),
            "QM" to OnlineSourceContentState(false, false),
            "KUWO" to OnlineSourceContentState(false, false, searchFailed = true),
        )
        assertEquals(states, OnlineSourceContentStatesCodec.decode(OnlineSourceContentStatesCodec.encode(states)))
    }
}
