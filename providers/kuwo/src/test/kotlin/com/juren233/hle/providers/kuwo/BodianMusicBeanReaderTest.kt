/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BodianMusicBeanReaderTest {
    /** 模拟波点 cn.kuwo.player.bean.Music 的真实形状：public rid 字段 + 私有属性 getter。 */
    @Suppress("unused")
    private class FakeMusic(private val name: String, private val artist: String) {
        @JvmField
        var rid: String? = null

        private var album: String = "测试专辑"

        private var dur: Int = 0

        fun getName(): String = name

        fun getArtist(): String = artist

        fun getAlbum(): String = album

        fun getDur(): Int = dur
    }

    @Test
    fun `reads rid field and getters`() {
        val music = FakeMusic("晴天", "周杰伦")
        music.rid = "123456"
        val bean = requireNotNull(BodianMusicBeanReader.read(music))
        assertEquals("123456", bean.rid)
        assertEquals("晴天", bean.title)
        assertEquals("周杰伦", bean.artist)
        assertEquals("测试专辑", bean.album)
        assertEquals(0L, bean.durationMs)
    }

    @Test
    fun `blank rid yields null bean`() {
        val music = FakeMusic("晴天", "周杰伦")
        music.rid = ""
        assertNull(BodianMusicBeanReader.read(music))
        assertNull(BodianMusicBeanReader.read(null))
    }
}
