/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QQMusicOpenApiQueueReaderTest {
    @Test
    fun `reads current and next SongInfo with play time in seconds`() {
        val api = FakePlayerManager(
            current = FakeSongInfo(97773L, "晴天", "周杰伦", "0039MnYb0qxYhV", 269),
            next = FakeSongInfo(102065756L, "稻香", "周杰伦", null, null),
        )

        val snapshot = QQMusicOpenApiQueueReader { api }.resolve()!!

        assertEquals(
            QQMusicTrackSnapshot("97773", "晴天", "周杰伦", "0039MnYb0qxYhV", 269_000L),
            snapshot.current,
        )
        assertEquals(
            QQMusicTrackSnapshot("102065756", "稻香", "周杰伦", null, 0L),
            snapshot.next,
        )
    }

    @Test
    fun `next song is optional on player implementations without it`() {
        val api = FakeInterfaceOnlyPlayer(FakeSongInfo(97773L, "晴天", "周杰伦", null, 269))

        val snapshot = QQMusicOpenApiQueueReader { api }.resolve()!!

        assertEquals("97773", snapshot.current.id)
        assertNull(snapshot.next)
    }

    @Test
    fun `no snapshot while SDK is not ready or nothing is playing`() {
        assertNull(QQMusicOpenApiQueueReader { null }.resolve())
        assertNull(QQMusicOpenApiQueueReader { FakePlayerManager(null, null) }.resolve())
    }

    @Test(expected = ClassNotFoundException::class)
    fun `create fails when OpenAPI SDK is absent`() {
        QQMusicOpenApiQueueReader.create(javaClass.classLoader!!)
    }

    class FakeSongInfo(
        private val songId: Long,
        private val songName: String,
        private val singerName: String,
        private val songMid: String?,
        private val playTime: Int?,
    ) {
        fun getSongId(): Long = songId
        fun getSongName(): String = songName
        fun getSingerName(): String = singerName
        fun getSongMid(): String? = songMid
        fun getSongPlayTime(): Int? = playTime
    }

    class FakePlayerManager(
        private val current: FakeSongInfo?,
        private val next: FakeSongInfo?,
    ) {
        fun getCurrentSongInfo(): FakeSongInfo? = current
        fun getNextSongInfo(): FakeSongInfo? = next
    }

    class FakeInterfaceOnlyPlayer(private val current: FakeSongInfo?) {
        fun getCurrentSongInfo(): FakeSongInfo? = current
    }
}
