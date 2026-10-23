/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.util

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderApplicationCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderConstructorCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderConstructorTarget
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodQuery
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodsCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHost
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMetadataCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodResultCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlaybackStateCallback
import com.juren233.hle.providers.applemusic.xposed.AppleProviderEnvironment
import com.juren233.hle.providers.applemusic.xposed.model.AppleSong
import com.juren233.hle.providers.applemusic.xposed.model.LyricAgent
import com.juren233.hle.providers.applemusic.xposed.model.LyricLine
import com.juren233.hle.providers.applemusic.xposed.model.LyricWord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppleSongMapperDiagnosticsTest {
    private val entries = mutableListOf<String>()

    private val host = object : OfficialProviderHost {
        override val packageName = "com.apple.android.music"
        override val processName = "com.apple.android.music"

        override fun isDiagnosticEnabled(): Boolean = true

        override fun reportDiagnostic(tag: String, message: String) {
            entries += message
        }

        override fun hookApplication(callback: OfficialProviderApplicationCallback) = Unit
        override fun hookMediaSession(
            playbackStateCallback: OfficialProviderPlaybackStateCallback,
            metadataCallback: OfficialProviderMetadataCallback,
        ) = Unit

        override fun hookAfterMethod(
            target: OfficialProviderMethodTarget,
            callback: OfficialProviderMethodCallback,
        ) = Unit

        override fun hookMethodResult(
            target: OfficialProviderMethodTarget,
            callback: OfficialProviderMethodResultCallback,
        ) = Unit

        override fun hookAfterConstructor(
            target: OfficialProviderConstructorTarget,
            callback: OfficialProviderConstructorCallback,
        ) = Unit

        override fun hookAfterDexMethod(
            application: android.app.Application,
            query: OfficialProviderDexMethodQuery,
            callback: OfficialProviderMethodCallback,
        ) = Unit

        override fun resolveDexMethods(
            application: android.app.Application,
            queries: List<OfficialProviderDexMethodQuery>,
            callback: OfficialProviderDexMethodsCallback,
        ) = Unit
    }

    @Before
    fun enableDiagnostics() {
        AppleProviderEnvironment.attach(host)
    }

    @After
    fun resetDiagnostics() {
        AppleProviderEnvironment.attach(object : OfficialProviderHost by host {
            override fun isDiagnosticEnabled(): Boolean = false
        })
    }

    @Test
    fun `caps diagnostic samples and omits lyric and agent payloads`() {
        val privateLyric = "private lyric payload"
        val privateAgent = "private agent identifier"
        val song = AppleSong(
            agents = mutableListOf(
                LyricAgent(id = privateAgent, type = 1, typeName = "private type name"),
            ),
            lyrics = MutableList(100) {
                LyricLine(agent = privateAgent, htmlLineText = privateLyric)
            },
        )

        val mapped = AppleSongMapper.map(song)

        assertEquals(100, mapped.lyrics.orEmpty().size)
        assertEquals(privateLyric, mapped.lyrics.orEmpty().last().text)
        assertEquals(7, entries.size)
        assertTrue(entries.first().contains("sampleCount=6, omittedLines=94"))
        assertTrue(entries.drop(1).all { it.contains("textLength=${privateLyric.length}") })
        assertFalse(entries.joinToString().contains(privateLyric))
        assertFalse(entries.joinToString().contains(privateAgent))
        assertFalse(entries.joinToString().contains("private type name"))
    }

    @Test
    fun `reports fallback word length without leaking word text`() {
        AppleSongMapper.map(
            AppleSong(lyrics = mutableListOf(
                LyricLine(words = mutableListOf(LyricWord(text = "hidden"), LyricWord(text = "words"))),
            )),
        )

        assertEquals(2, entries.size)
        assertTrue(entries.last().contains("textLength=11, wordCount=2"))
        assertFalse(entries.joinToString().contains("hidden"))
        assertFalse(entries.joinToString().contains("hiddenwords"))
    }

    @Test
    fun `empty lyrics produce only a summary`() {
        AppleSongMapper.map(AppleSong())

        assertEquals(1, entries.size)
        assertTrue(entries.single().contains("sampleCount=0, omittedLines=0"))
    }
}
