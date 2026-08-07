/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import java.util.Locale

/**
 * Version-independent LRC/Enhanced-LRC parser used by the local-file lyric path.
 *
 * Duplicate timestamps are interpreted the same way local lyric editors commonly
 * encode translations: a word-timed line wins as the main line and the next distinct
 * plain line becomes its translation. Line ends are clamped to the next line so the
 * non-Apple-Music rendering path never reports multiple simultaneously active lines.
 */
internal object SaltPlayerLrcParser {
    private val timeTag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val wordTimeTag = Regex("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>")
    private val metadataTag = Regex("^\\[([A-Za-z][A-Za-z0-9_-]*):(.*)]$")

    fun parse(text: String, durationMs: Long = 0L): SaltPlayerLyricsDocument? {
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        var offsetMs = 0L
        val rawLines = ArrayList<RawLine>()

        normalized.lineSequence().forEach { sourceLine ->
            val line = sourceLine.trim()
            if (line.isEmpty()) return@forEach

            metadataTag.matchEntire(line)?.let { tag ->
                if (tag.groupValues[1].lowercase(Locale.ROOT) == "offset") {
                    offsetMs = tag.groupValues[2].trim().toLongOrNull() ?: offsetMs
                }
                return@forEach
            }

            val timestamps = timeTag.findAll(line).toList()
            if (timestamps.isEmpty()) return@forEach
            val contentStart = timestamps.last().range.last + 1
            val content = line.substring(contentStart).trim()
            if (content.isEmpty()) return@forEach
            val words = parseEnhancedWords(content)
            val mainText = if (words.isEmpty()) {
                content
            } else {
                words.joinToString(separator = "") { it.text }.trim()
            }
            if (mainText.isEmpty()) return@forEach

            timestamps.forEach { match ->
                val begin = parseTime(match.groupValues[1], match.groupValues[2], match.groupValues[3])
                rawLines += RawLine(begin, mainText, words)
            }
        }
        if (rawLines.isEmpty()) return null

        val grouped = rawLines
            .groupBy { it.beginMs }
            .toSortedMap()
            .mapNotNull { (beginMs, candidates) -> mergeSameTimestamp(beginMs, candidates) }
        if (grouped.isEmpty()) return null

        val adjustedBegins = grouped.map { (it.beginMs + offsetMs).coerceAtLeast(0L) }
        val lines = grouped.mapIndexed { index, line ->
            val begin = adjustedBegins[index]
            val nextBegin = adjustedBegins.getOrNull(index + 1)
            val fallbackEnd = when {
                nextBegin != null -> nextBegin
                durationMs > begin -> durationMs
                else -> begin + DEFAULT_LAST_LINE_DURATION_MS
            }
            val end = fallbackEnd.coerceAtLeast(begin)
            val cells = line.words.mapIndexedNotNull { wordIndex, word ->
                val wordBegin = (word.beginMs + offsetMs).coerceAtLeast(begin)
                val wordEnd = line.words.getOrNull(wordIndex + 1)
                    ?.let { (it.beginMs + offsetMs).coerceAtMost(end) }
                    ?: end
                if (wordEnd <= wordBegin || word.text.isEmpty()) return@mapIndexedNotNull null
                SaltPlayerLyricsCell(wordBegin, wordEnd, word.text)
            }
            SaltPlayerLyricsLine(
                beginMs = begin,
                endMs = end,
                mainText = line.text,
                translation = line.translation,
                cells = cells,
            )
        }
        return SaltPlayerLyricsDocument(lines)
    }

    private fun mergeSameTimestamp(beginMs: Long, candidates: List<RawLine>): MergedLine? {
        val distinct = candidates.distinctBy { it.text.trim() }
        val main = distinct.firstOrNull { it.words.isNotEmpty() } ?: distinct.firstOrNull() ?: return null
        val translation = distinct.firstOrNull {
            it !== main && it.text.trim() != main.text.trim()
        }?.text?.trim()?.takeIf(String::isNotEmpty)
        return MergedLine(beginMs, main.text.trim(), translation, main.words)
    }

    private fun parseEnhancedWords(content: String): List<RawWord> {
        val matches = wordTimeTag.findAll(content).toList()
        if (matches.isEmpty()) return emptyList()
        return matches.mapIndexedNotNull { index, match ->
            val textStart = match.range.last + 1
            val textEnd = matches.getOrNull(index + 1)?.range?.first ?: content.length
            val wordText = content.substring(textStart, textEnd)
            if (wordText.isEmpty()) return@mapIndexedNotNull null
            RawWord(
                beginMs = parseTime(
                    match.groupValues[1],
                    match.groupValues[2],
                    match.groupValues[3],
                ),
                text = wordText,
            )
        }
    }

    private fun parseTime(minutes: String, seconds: String, fraction: String): Long {
        val milliseconds = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100L
            2 -> fraction.toLong() * 10L
            else -> fraction.take(3).padEnd(3, '0').toLong()
        }
        return minutes.toLong() * 60_000L + seconds.toLong() * 1_000L + milliseconds
    }

    private data class RawLine(
        val beginMs: Long,
        val text: String,
        val words: List<RawWord>,
    )

    private data class RawWord(
        val beginMs: Long,
        val text: String,
    )

    private data class MergedLine(
        val beginMs: Long,
        val text: String,
        val translation: String?,
        val words: List<RawWord>,
    )

    private const val DEFAULT_LAST_LINE_DURATION_MS = 5_000L
}
