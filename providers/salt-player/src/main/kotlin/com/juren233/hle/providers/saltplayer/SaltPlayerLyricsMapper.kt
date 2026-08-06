/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import java.lang.reflect.Field

internal data class SaltPlayerTrackMetadata(
    val id: String? = null,
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
}

internal data class SaltPlayerLyricsDocument(
    val lines: List<SaltPlayerLyricsLine>,
)

internal data class SaltPlayerLyricsLine(
    val beginMs: Long,
    val endMs: Long,
    val mainText: String,
    val translation: String?,
    val cells: List<SaltPlayerLyricsCell>,
)

internal data class SaltPlayerLyricsCell(
    val beginMs: Long,
    val endMs: Long,
    val text: String,
)

/** Reads only identifiers supplied by an exact original-DEX version profile. */
internal object SaltPlayerLyricsDecoder {
    fun decode(document: Any?, profile: SaltPlayerHookProfile): SaltPlayerLyricsDocument? {
        val lyrics = profile.lyrics
        if (document?.javaClass?.name != lyrics.documentClassName) return null
        val rawLines = readField(document, lyrics.documentLinesFieldName)
            as? Iterable<*> ?: return null
        return SaltPlayerLyricsDocument(rawLines.mapNotNull { decodeLine(it, lyrics) })
    }

    private fun decodeLine(
        value: Any?,
        profile: SaltPlayerLyricsHookProfile,
    ): SaltPlayerLyricsLine? {
        if (value?.javaClass?.name != profile.lineClassName) return null
        val begin = readLong(value, profile.lineBeginFieldName) ?: return null
        val rawEnd = readLong(value, profile.lineEndFieldName) ?: return null
        val end = rawEnd.coerceAtLeast(begin)
        val mainText = (readField(value, profile.lineMainTextFieldName)
            as? String)?.trim().orEmpty()
        if (mainText.isEmpty()) return null
        val translation = (readField(
            value,
            profile.lineTranslationFieldName,
        ) as? String)?.trim()?.takeIf { it.isNotEmpty() && it != mainText }
        val cells = (readField(value, profile.lineCellsFieldName)
            as? Iterable<*>)?.mapNotNull { decodeCell(it, profile) }.orEmpty()
        return SaltPlayerLyricsLine(
            beginMs = begin,
            endMs = end,
            mainText = mainText,
            translation = translation,
            cells = cells,
        )
    }

    private fun decodeCell(
        value: Any?,
        profile: SaltPlayerLyricsHookProfile,
    ): SaltPlayerLyricsCell? {
        if (value?.javaClass?.name != profile.cellClassName) return null
        val begin = readLong(value, profile.cellBeginFieldName) ?: return null
        val rawEnd = readLong(value, profile.cellEndFieldName) ?: return null
        val text = readField(value, profile.cellTextFieldName) as? String
            ?: return null
        if (text.isEmpty()) return null
        return SaltPlayerLyricsCell(
            beginMs = begin,
            endMs = rawEnd.coerceAtLeast(begin),
            text = text,
        )
    }

    private fun readLong(instance: Any, name: String): Long? =
        (readField(instance, name) as? Number)?.toLong()

    private fun readField(instance: Any, name: String): Any? = runCatching {
        field(instance.javaClass, name).get(instance)
    }.getOrNull()

    private fun field(type: Class<*>, name: String): Field = type.getDeclaredField(name).apply {
        isAccessible = true
    }
}

internal object SaltPlayerLyricsMapper {
    fun map(
        document: SaltPlayerLyricsDocument,
        metadata: SaltPlayerTrackMetadata,
    ): Song = Song().apply {
        id = metadata.id?.trim()?.takeIf(String::isNotEmpty)
        name = metadata.title?.trim()?.takeIf(String::isNotEmpty)
        artist = metadata.artist?.trim()?.takeIf(String::isNotEmpty)
        val mappedLines = document.lines
            .sortedWith(compareBy(SaltPlayerLyricsLine::beginMs, SaltPlayerLyricsLine::endMs))
            .map(::mapLine)
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

    private fun mapLine(line: SaltPlayerLyricsLine): RichLyricLine = RichLyricLine().apply {
        begin = line.beginMs
        end = line.endMs
        duration = (line.endMs - line.beginMs).coerceAtLeast(0L)
        text = line.mainText
        translation = line.translation?.takeUnless { it.trim() == line.mainText.trim() }
        words = mapWords(line)
    }

    private fun mapWords(line: SaltPlayerLyricsLine): List<LyricWord> {
        val lineEnd = line.endMs.coerceAtLeast(line.beginMs)
        val exactWords = line.cells.mapNotNull { cell ->
            val begin = cell.beginMs.coerceAtLeast(line.beginMs)
            val end = cell.endMs.coerceAtMost(lineEnd)
            if (end <= begin) return@mapNotNull null
            cell.text.takeIf(String::isNotEmpty)?.let { text ->
                LyricWord().apply {
                    this.begin = begin
                    this.end = end
                    duration = (end - begin).coerceAtLeast(0L)
                    this.text = text
                }
            }
        }
        if (exactWords.isNotEmpty()) return exactWords
        return listOf(
            LyricWord().apply {
                begin = line.beginMs
                end = line.endMs
                duration = (line.endMs - line.beginMs).coerceAtLeast(0L)
                text = line.mainText
            },
        )
    }
}
