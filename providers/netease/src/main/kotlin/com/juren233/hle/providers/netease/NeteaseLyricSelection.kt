/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.netease

/**
 * Keeps the embedded-file, API and app lyric candidates separate until the active song is
 * selected. Lyrics embedded in the local audio file win over both online sources; while the
 * file lookup for a track is still running, nothing is selected so an online version does not
 * flash before the embedded one.
 */
internal class NeteaseLyricSelection<T>(private val maxTracks: Int = 8) {
    init {
        require(maxTracks > 0)
    }

    enum class Source { EMBEDDED, API, APP }

    data class Selected<T>(val source: Source, val value: T)

    private data class Candidates<T>(
        var embedded: T? = null,
        var embeddedLookupPending: Boolean = false,
        var api: T? = null,
        var app: T? = null,
    )

    private val byMusicId = LinkedHashMap<Long, Candidates<T>>(maxTracks, 0.75f, true)

    @Synchronized
    fun beginEmbeddedLookup(musicId: Long) {
        candidates(musicId).embeddedLookupPending = true
    }

    /** @param value 文件内嵌歌词；null 表示没有对应文件或文件没有内嵌歌词。 */
    @Synchronized
    fun finishEmbeddedLookup(musicId: Long, value: T?) {
        val candidates = candidates(musicId)
        candidates.embedded = value
        candidates.embeddedLookupPending = false
    }

    @Synchronized
    fun setApi(musicId: Long, value: T?) {
        // An empty refresh must not erase a previously valid API candidate.
        if (value == null) return
        candidates(musicId).api = value
    }

    @Synchronized
    fun setApp(musicId: Long, value: T?): Boolean {
        val candidates = candidates(musicId)
        if (candidates.app == value) return false
        candidates.app = value
        return true
    }

    @Synchronized
    fun select(musicId: Long): Selected<T>? {
        val candidates = byMusicId[musicId] ?: return null
        candidates.embedded?.let { return Selected(Source.EMBEDDED, it) }
        if (candidates.embeddedLookupPending) return null
        candidates.api?.let { return Selected(Source.API, it) }
        candidates.app?.let { return Selected(Source.APP, it) }
        return null
    }

    private fun candidates(musicId: Long): Candidates<T> {
        val candidates = byMusicId.getOrPut(musicId) { Candidates() }
        while (byMusicId.size > maxTracks) {
            byMusicId.remove(byMusicId.keys.first())
        }
        return candidates
    }
}

internal object NeteaseLyricContentPolicy {
    fun hasMeaningfulText(lines: Iterable<String>): Boolean = lines.any { line ->
        val text = line.trim()
        text.isNotEmpty() && text != "暂无歌词"
    }
}
