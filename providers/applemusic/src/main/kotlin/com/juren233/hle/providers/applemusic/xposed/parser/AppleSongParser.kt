/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.parser

import com.juren233.hle.providers.applemusic.xposed.AppleMusicRuntimeMember
import com.juren233.hle.providers.applemusic.xposed.MediaMetadataCache
import com.juren233.hle.providers.applemusic.xposed.model.AppleSong
import com.juren233.hle.providers.applemusic.xposed.parser.LyricsSectionParser.mergeLyrics

object AppleSongParser {

    internal fun parser(songNative: Any, access: AppleLyricsParserAccess): AppleSong =
        AppleSong().apply {
            adamId = access.call(songNative, AppleMusicRuntimeMember.LYRICS_SONG_ADAM_ID_METHOD)
                .toString()

            access.call(songNative, AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_AGENTS_METHOD)?.let {
                agents = LyricsAgentParser.parserAgentVector(it, access)
            }

            duration = access.call(
                songNative,
                AppleMusicRuntimeMember.LYRICS_NATIVE_DURATION_METHOD,
            ) as? Int ?: 0

            runCatching {
                access.call(
                    songNative,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_PRONUNCIATION_LANGUAGES_METHOD,
                )?.let { StringVectorParser.parserStringVectorNative(it, access) }
            }.getOrNull()?.let { pronunciationLanguages = it }

            val sections = access.call(
                songNative,
                AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_SECTIONS_METHOD,
            )
            if (sections != null) {
                lyrics = LyricsSectionParser.parserSectionVector(sections, access).mergeLyrics()
            }
            adamId?.let {
                MediaMetadataCache.getMetadataById(it)
                    ?.let { metadata ->
                        name = metadata.title
                        artist = metadata.artist
                        genre = metadata.genre
                        originalTitle = metadata.originalTitle
                        originalArtist = metadata.originalArtist
                        originalAlbum = metadata.originalAlbum
                    }
            }
        }

    /**
     * 原生歌词内容的轻量指纹，用作解析缓存键（不再依赖原生对象地址）。
     *
     * 221018：同一首歌的构建 Hook 与呈现 Hook 常拿到内容相同、`address()` 不同的原生对象，
     * 按地址作键有 16 次未命中（合计约 1.3 s）。指纹包含歌曲时长、总行数，以及首尾各
     * [CONTENT_PROBE_LINES] 条有正文行的正文、译文、发音与起止时间：
     * - 切换翻译/发音轨道、译文异步加载或模块补充译文会改变各行译文/发音，首尾行随之改变；
     * - 同名不同来源的歌词（文字相同、时间轴不同）会因起止时间而不同。
     * 只读这些字段，不逐字解析。须在 raw read 下调用，与 [parser] 读取同一份原生文本。
     */
    internal fun contentProbe(songNative: Any, access: AppleLyricsParserAccess): Int? {
        val sections = access.call(songNative, AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_SECTIONS_METHOD)
            ?: return null
        val sectionCount = access.call(sections, AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_SIZE_METHOD)
            as? Long ?: return null
        val sectionLines = buildList {
            for (sectionIndex in 0 until sectionCount) {
                val lines = access.call(sections, AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD, sectionIndex)
                    ?.let { access.call(it, AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD) }
                    ?.let { access.call(it, AppleMusicRuntimeMember.LYRICS_NATIVE_SECTION_LINES_METHOD) }
                    ?: continue
                val count = access.call(lines, AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_SIZE_METHOD)
                    as? Long ?: continue
                if (count > 0) add(lines to count)
            }
        }
        val totalLines = sectionLines.sumOf { it.second }
        var hash = 31 * 1 + (access.call(songNative, AppleMusicRuntimeMember.LYRICS_NATIVE_DURATION_METHOD) as? Int ?: 0)
        hash = 31 * hash + totalLines.hashCode()

        fun lineAt(position: Pair<Int, Long>): Any? {
            val (sectionIndex, lineIndex) = position
            return access.call(sectionLines[sectionIndex].first, AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD, lineIndex)
                ?.let { access.call(it, AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD) }
        }

        /** 依次读取 [positions] 中的行，混入前 [CONTENT_PROBE_LINES] 条有正文的行；返回采样数。 */
        fun sample(positions: Sequence<Pair<Int, Long>>): Int {
            var sampled = 0
            for (position in positions) {
                if (sampled >= CONTENT_PROBE_LINES) break
                val line = lineAt(position) ?: continue
                val text = access.call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD) as? String
                if (text.isNullOrBlank()) continue
                hash = 31 * hash + text.hashCode()
                hash = 31 * hash + (access.call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_TRANSLATION_TEXT_METHOD) as? String).hashCode()
                hash = 31 * hash + (access.call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD) as? String).hashCode()
                hash = 31 * hash + (access.call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD) as? Int ?: 0)
                hash = 31 * hash + (access.call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD) as? Int ?: 0)
                sampled++
            }
            return sampled
        }

        val forward = sequence {
            for (sectionIndex in sectionLines.indices) {
                for (lineIndex in 0 until sectionLines[sectionIndex].second) yield(sectionIndex to lineIndex)
            }
        }
        val backward = sequence {
            for (sectionIndex in sectionLines.indices.reversed()) {
                for (lineIndex in sectionLines[sectionIndex].second - 1 downTo 0) yield(sectionIndex to lineIndex)
            }
        }
        val sampled = sample(forward)
        if (sampled == 0) return null
        sample(backward)
        return hash
    }

    private const val CONTENT_PROBE_LINES = 4
}
