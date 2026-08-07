/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

internal data class KuwoTimedWord(
    val begin: Long,
    val end: Long,
    val text: String,
)

internal data class KuwoTimelineLine(
    val begin: Long,
    val end: Long,
    val text: String,
    val translation: String? = null,
    val roma: String? = null,
    val words: List<KuwoTimedWord> = emptyList(),
)

/** Parses both Kuwo LRCX word timing and ordinary timestamped LRC. */
internal object KuwoLyricsParser {
    private val timestamp = Regex("^\\[(\\d{1,3}):([0-5]\\d)(?:[.:]([0-9]{1,3}))?]")
    private val wordMarker = Regex("<(-?\\d+),(-?\\d+)>([^<]*)")

    fun parse(raw: String?): List<KuwoTimelineLine> {
        if (raw.isNullOrBlank()) return emptyList()
        val source = raw.lineSequence().mapNotNull(::parseRawLine).toList()
        if (source.isEmpty()) return emptyList()

        val lines = mutableListOf<MutableLine>()
        source.forEachIndexed { index, line ->
            when (line.kind) {
                LineKind.AUXILIARY -> {
                    if (line.text.isBlank() || lines.isEmpty()) return@forEachIndexed
                    val previous = lines.last()
                    if (previous.translation == null) {
                        previous.translation = line.text.trim()
                    } else if (previous.roma == null) {
                        previous.roma = line.text.trim()
                    }
                }

                LineKind.PLAIN -> {
                    if (line.text.isBlank()) return@forEachIndexed
                    val next = source.getOrNull(index + 1)
                    val isTranslationBeforeNextLine = lines.isNotEmpty() &&
                        next?.kind == LineKind.PLAIN &&
                        next.begin == line.begin &&
                        next.text.isNotBlank()
                    if (isTranslationBeforeNextLine) {
                        val previous = lines.last()
                        if (previous.translation == null) previous.translation = line.text.trim()
                    } else {
                        lines += line.toMutableLine()
                    }
                }

                LineKind.TIMED -> {
                    if (line.text.isNotBlank()) lines += line.toMutableLine()
                }
            }
        }

        return lines.mapIndexed { index, line ->
            val nextBegin = lines.getOrNull(index + 1)?.begin?.takeIf { it > line.begin }
            val wordEnd = line.words.maxOfOrNull(KuwoTimedWord::end)
            val end = maxOf(
                line.begin + 1L,
                wordEnd ?: nextBegin ?: line.begin + DEFAULT_LINE_DURATION_MS,
            )
            KuwoTimelineLine(
                begin = line.begin,
                end = end,
                text = line.text,
                translation = line.translation,
                roma = line.roma,
                words = line.words,
            )
        }
    }

    private fun parseRawLine(raw: String): RawLine? {
        val timestampMatch = timestamp.find(raw) ?: return null
        val begin = timestampMatch.toMillis()
        val payload = raw.substring(timestampMatch.range.last + 1)
        val markers = wordMarker.findAll(payload).toList()
        if (markers.isEmpty()) {
            return RawLine(begin, payload.trim(), emptyList(), LineKind.PLAIN)
        }

        val text = markers.joinToString("") { it.groupValues[3] }
        val hasTimedWords = markers.any {
            it.groupValues[1] != "0" || it.groupValues[2] != "0"
        }
        if (!hasTimedWords) {
            return RawLine(begin, text.trim(), emptyList(), LineKind.AUXILIARY)
        }

        val words = markers.mapNotNull { marker ->
            val encodedEnd = marker.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val encodedStartDelta = marker.groupValues[2].toLongOrNull()
                ?: return@mapNotNull null
            val wordText = marker.groupValues[3]
            if (wordText.isEmpty() || encodedEnd == 0L && encodedStartDelta == 0L) {
                return@mapNotNull null
            }
            val encodedStart = runCatching {
                Math.addExact(encodedEnd, encodedStartDelta)
            }.getOrNull() ?: return@mapNotNull null
            // Kuwo LRCX stores twice the word end in the first value. The
            // second value completes a four-times word start. This relation
            // is verified against the original 12.1.8.2 responses.
            val wordBegin = begin + Math.floorDiv(encodedStart, 4L)
            val wordEnd = begin + Math.floorDiv(encodedEnd, 2L)
            if (wordBegin < begin || wordEnd <= wordBegin) return@mapNotNull null
            KuwoTimedWord(wordBegin, wordEnd, wordText)
        }
        return RawLine(begin, text.trim(), words, LineKind.TIMED)
    }

    private fun MatchResult.toMillis(): Long {
        val minutes = groupValues[1].toLongOrNull() ?: 0L
        val seconds = groupValues[2].toLongOrNull() ?: 0L
        val fraction = groupValues.getOrNull(3).orEmpty()
        val millis = when (fraction.length) {
            1 -> fraction.toLong() * 100L
            2 -> fraction.toLong() * 10L
            3 -> fraction.toLong()
            else -> 0L
        }
        return minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun RawLine.toMutableLine() = MutableLine(
        begin = begin,
        text = text,
        words = words,
    )

    private data class RawLine(
        val begin: Long,
        val text: String,
        val words: List<KuwoTimedWord>,
        val kind: LineKind,
    )

    private data class MutableLine(
        val begin: Long,
        val text: String,
        val words: List<KuwoTimedWord>,
        var translation: String? = null,
        var roma: String? = null,
    )

    private enum class LineKind {
        TIMED,
        AUXILIARY,
        PLAIN,
    }

    private const val DEFAULT_LINE_DURATION_MS = 5_000L
}
