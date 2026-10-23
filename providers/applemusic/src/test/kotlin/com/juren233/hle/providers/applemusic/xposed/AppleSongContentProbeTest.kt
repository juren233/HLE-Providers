/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import com.juren233.hle.providers.applemusic.xposed.parser.AppleLyricsParserAccess
import com.juren233.hle.providers.applemusic.xposed.parser.AppleSongParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppleSongContentProbeTest {
    private val access = AppleLyricsParserAccess.from(
        AppleMusicHookResolver(
            version = AppleMusicVersion("6.5.1", 1583L),
            classLookup = { name ->
                if (name == "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel") {
                    FakePlayerLyricsViewModel::class.java
                } else {
                    throw ClassNotFoundException(name)
                }
            },
        )
    )

    private fun lines(count: Int = 12) = (0 until count).map { index ->
        FakeLine(text = "line $index", translation = "译 $index", start = index * 1000, stop = index * 1000 + 900)
    }

    private fun song(vararg sections: List<FakeLine>, duration: Int = 321) =
        FakeSong(duration, FakePointerVector(sections.map { FakePointer(FakeSection(FakePointerVector(it.map(::FakePointer)))) }))

    private fun probe(song: FakeSong) = AppleSongParser.contentProbe(song, access)

    @Test
    fun `distinct native objects with identical content share a probe`() {
        val first = probe(song(lines().take(6), lines().drop(6)))
        assertNotNull(first)
        assertEquals(first, probe(song(lines().take(6), lines().drop(6))))
    }

    @Test
    fun `translation change on the last line changes the probe`() {
        val changed = lines().toMutableList()
        changed[11] = changed[11].copy(translation = "新译文")
        assertNotEquals(probe(song(lines())), probe(song(changed)))
    }

    @Test
    fun `same text with a different timeline changes the probe`() {
        val shifted = lines().map { it.copy(start = it.start + 40, stop = it.stop + 40) }
        assertNotEquals(probe(song(lines())), probe(song(shifted)))
    }

    @Test
    fun `line count or duration change alters the probe`() {
        val base = probe(song(lines()))
        val inserted = lines().toMutableList().apply { add(6, FakeLine("extra", null, 6500, 6600)) }
        assertNotEquals(base, probe(song(inserted)))
        assertNotEquals(base, probe(song(lines(), duration = 322)))
    }

    @Test
    fun `lyrics without any text are not cacheable`() {
        assertNull(probe(song(lines().map { it.copy(text = " ") })))
        assertNull(probe(song()))
    }

    private class FakePlayerLyricsViewModel {
        @Suppress("UNUSED_PARAMETER")
        fun loadLyrics(song: Any) = Unit
    }

    private class FakeSong(private val duration: Int, private val sections: FakePointerVector) {
        fun getDuration(): Int = duration
        fun getSections(): FakePointerVector = sections
    }

    private class FakeSection(private val lines: FakePointerVector) {
        fun getLines(): FakePointerVector = lines
    }

    private data class FakeLine(val text: String, val translation: String?, val start: Int, val stop: Int) {
        fun getBegin(): Int = start
        fun getEnd(): Int = stop
        fun getHtmlLineText(): String = text
        fun getHtmlTranslationLineText(): String? = translation
        fun getHtmlPronunciationLineText(): String? = null
    }

    private class FakePointer(private val value: Any) {
        fun get(): Any = value
    }

    private class FakePointerVector(private val values: List<FakePointer>) {
        fun size(): Long = values.size.toLong()
        fun get(index: Long): FakePointer = values[index.toInt()]
    }
}
