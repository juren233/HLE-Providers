/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.locallyrics

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine

internal data class LocalLyricsDocument(
    val lines: List<LocalLyricsLine>,
)

internal data class LocalLyricsLine(
    val beginMs: Long,
    val endMs: Long,
    val mainText: String,
    val translation: String?,
    val cells: List<LocalLyricsCell>,
)

internal data class LocalLyricsCell(
    val beginMs: Long,
    val endMs: Long,
    val text: String,
)

/** 本地歌词文档转成发布用的歌词行；没有逐字时间时整行作为一个字。 */
internal object LocalLyricsLineMapper {
    fun map(document: LocalLyricsDocument): List<RichLyricLine> = document.lines
        .sortedWith(compareBy(LocalLyricsLine::beginMs, LocalLyricsLine::endMs))
        .map(::mapLine)

    private fun mapLine(line: LocalLyricsLine): RichLyricLine = RichLyricLine().apply {
        begin = line.beginMs
        end = line.endMs
        duration = (line.endMs - line.beginMs).coerceAtLeast(0L)
        text = line.mainText
        translation = line.translation?.takeUnless { it.trim() == line.mainText.trim() }
        words = mapWords(line)
    }

    private fun mapWords(line: LocalLyricsLine): List<LyricWord> {
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
