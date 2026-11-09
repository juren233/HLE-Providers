/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import com.juren233.hle.providers.locallyrics.LocalAudioQuery
import com.juren233.hle.providers.locallyrics.LocalLyricsDocument
import com.juren233.hle.providers.locallyrics.LocalLyricsLineMapper
import io.github.proify.lyricon.lyric.model.Song

internal data class SaltPlayerTrackMetadata(
    val id: String? = null,
    val mediaUri: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
) {
    val identity: String?
        get() = id?.trim()?.takeIf(String::isNotEmpty)
            ?: listOf(title, artist)
                .map { it?.trim().orEmpty() }
                .takeIf { values -> values.any(String::isNotEmpty) }
                ?.joinToString("|")

    val localLyricsRequestKey: String?
        get() = identity?.let {
            listOf(
                it,
                mediaUri.orEmpty(),
                album.orEmpty(),
                durationMs.coerceAtLeast(0L).toString(),
            ).joinToString("|")
        }

    fun toLocalAudioQuery(): LocalAudioQuery = LocalAudioQuery(
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
    )
}

internal object SaltPlayerLyricsMapper {
    fun map(
        document: LocalLyricsDocument,
        metadata: SaltPlayerTrackMetadata,
    ): Song = Song().apply {
        id = metadata.id?.trim()?.takeIf(String::isNotEmpty)
        name = metadata.title?.trim()?.takeIf(String::isNotEmpty)
        artist = metadata.artist?.trim()?.takeIf(String::isNotEmpty)
        val mappedLines = LocalLyricsLineMapper.map(document)
        duration = metadata.durationMs.takeIf { it > 0L }
            ?: mappedLines.maxOfOrNull { it.end }
            ?: 0L
        lyrics = mappedLines.takeIf { it.isNotEmpty() }
    }

    fun placeholder(metadata: SaltPlayerTrackMetadata): Song = Song().apply {
        id = metadata.id?.trim()?.takeIf(String::isNotEmpty)
        name = metadata.title?.trim()?.takeIf(String::isNotEmpty)
        artist = metadata.artist?.trim()?.takeIf(String::isNotEmpty)
        duration = metadata.durationMs.coerceAtLeast(0L)
    }
}
