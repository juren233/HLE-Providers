/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import com.juren233.hle.providers.applemusic.xposed.model.AppleSong

/**
 * 原生歌词解析结果缓存。Apple 的歌词构建 Hook 与原生呈现 Hook 会对同一首歌反复触发
 * `onLyricsBuilt`，每次都在主线程逐行逐字反射读取原生对象（221015 实测中位 78 ms、最大 424 ms）。
 * 221016 实测同一歌曲 ID + 同一原生地址的 26 次重复解析内容全部相同。
 *
 * 解析在 raw read 下进行，结果只取决于原生对象内容、当前选中的翻译/发音轨道和繁转简开关。
 * Apple 每次呈现都会以相同语言重复调用 `setTranslation`，按调用次数作废缓存几乎不命中（221017）；
 * 键改为 [com.juren233.hle.providers.applemusic.xposed.parser.AppleSongParser.contentProbe]
 * 读出的内容指纹，轨道真正改变时指纹随之改变。221018 起不再以原生对象地址作键：
 * 构建与呈现 Hook 常拿到内容相同、地址不同的原生对象（16 次未命中）。
 * 翻译/发音显示偏好也不在键里：解析在 raw read 下进行，偏好只经由原生轨道选择影响结果，
 * 而轨道变化已反映在内容指纹中（221026：4 次只因开关显示翻译而未命中，内容哈希完全相同）。
 */
internal object AppleNativeLyricsParseCache {
    private const val MAX_ENTRIES = 8

    private val entries = object : LinkedHashMap<Key, AppleSong>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, AppleSong>?): Boolean =
            size > MAX_ENTRIES
    }

    data class Key(
        val songId: String,
        val contentProbe: Int,
        val textTransformEnabled: Boolean,
    )

    fun keyFor(
        songId: String?,
        contentProbe: Int?,
        textTransformEnabled: Boolean,
    ): Key? {
        val probe = contentProbe ?: return null
        val id = songId?.takeIf(String::isNotBlank) ?: return null
        return Key(
            songId = id,
            contentProbe = probe,
            textTransformEnabled = textTransformEnabled,
        )
    }

    /** 返回浅拷贝：调用方只改写顶层元数据字段，不改歌词列表。 */
    fun get(key: Key): AppleSong? = synchronized(entries) { entries[key] }?.copy()

    fun put(key: Key, song: AppleSong) {
        synchronized(entries) { entries[key] = song.copy() }
    }

    internal fun resetForTest() {
        synchronized(entries) { entries.clear() }
    }
}
