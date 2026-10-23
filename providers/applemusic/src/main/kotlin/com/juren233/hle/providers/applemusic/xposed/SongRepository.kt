/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hle.providers.applemusic.xposed.parser.AppleSongParser
import com.juren233.hle.providers.applemusic.xposed.parser.AppleLyricsParserAccess
import com.juren233.hle.providers.applemusic.xposed.model.AppleSong
import com.juren233.hle.providers.applemusic.xposed.util.toSong
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.lyricMetadataOf

object SongRepository {

    /**
     * 根据 ID 获取歌曲
     * 策略：内存/磁盘缓存 -> 占位符
     */
    fun getSong(id: String): Song {
        // 1. 尝试从磁盘缓存读取
        val cache = DiskSongManager.load(id)
        if (cache != null) {
            val mapped = normalizeRenderableLyrics(cache.applyCurrentMetadata().toSong())
            if (cache.lyrics.isNotEmpty() && mapped.lyrics.isNullOrEmpty()) {
                DiskSongManager.delete(id)
                ProviderLogger.info(
                    "SongRepository: Discarded textless lyrics cache for $id, " +
                        "cachedLines=${cache.lyrics.size}"
                )
            }
            return mapped
        }

        // 2. 缓存未命中，从 Metadata 生成占位符（只有标题/歌手，无歌词）
        val metadata = MediaMetadataCache.getMetadataById(id)
        return Song(
            id = id,
            name = metadata?.title,
            artist = metadata?.artist,
            duration = metadata?.duration ?: 0L,
            metadata = metadata?.toLyricMetadata()
        )
    }

    /**
     * 保存解析好的歌曲到磁盘
     */
    internal fun saveSong(
        nativeSongObj: Any,
        parserAccess: AppleLyricsParserAccess,
        source: String,
        trigger: String = "",
    ): Song? {
        val parseStartNs = if (AppleProviderEnvironment.diagnosticsEnabled) System.nanoTime() else 0L
        // 仅用于 Debug 诊断；缓存键已改用内容指纹。
        val nativeAddress = if (AppleProviderEnvironment.diagnosticsEnabled) {
            parserAccess.call(
                nativeSongObj,
                AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_ADDRESS_METHOD,
            ) as? Long
        } else {
            null
        }
        val parseCacheKey = AppleNativeLyricsParseCache.keyFor(
            songId = parserAccess.call(
                nativeSongObj,
                AppleMusicRuntimeMember.LYRICS_SONG_ADAM_ID_METHOD,
            )?.toString(),
            contentProbe = AppleLyricTextTransform.withRawReads {
                AppleSongParser.contentProbe(nativeSongObj, parserAccess)
            },
            textTransformEnabled = AppleLyricTextTransform.isTransformEnabled(),
        )
        val cachedSong = parseCacheKey?.let(AppleNativeLyricsParseCache::get)
        val song = cachedSong ?: AppleLyricTextTransform.withRawReads {
            AppleSongParser.parser(nativeSongObj, parserAccess)
        }.also { parsed ->
            if (parseCacheKey != null && !parsed.adamId.isNullOrBlank()) {
                AppleNativeLyricsParseCache.put(parseCacheKey, parsed)
            }
        }
        if (AppleProviderEnvironment.diagnosticsEnabled) {
            ProviderLogger.diagnostic(
                "[LyricsParseCost] id=${song.adamId}, source=$source, trigger=$trigger, " +
                    "cacheHit=${cachedSong != null}, cacheable=${parseCacheKey != null}, " +
                    "contentHash=${listOf(song.lyrics, song.agents, song.pronunciationLanguages, song.duration).hashCode()}, " +
                    "parseMs=${(System.nanoTime() - parseStartNs) / 1_000_000.0}, " +
                    "lines=${song.lyrics.size}, address=$nativeAddress, " +
                    "thread=${Thread.currentThread().name}"
            )
        }
        if (song.adamId.isNullOrBlank()) {
            return null
        }
        if (AppleProviderEnvironment.diagnosticsEnabled) {
            ProviderLogger.debug(
                "SongRepository: Parsed Apple lyrics for ${song.adamId}, " +
                    "mainWordLines=${song.lyrics.count { it.words.isNotEmpty() }}, " +
                    "nativePronunciationLines=${song.lyrics.count { !it.htmlPronunciationLineText.isNullOrBlank() }}, " +
                    "backgroundTextLines=${song.lyrics.count { !it.htmlBackgroundVocalsLineText.isNullOrBlank() }}, " +
                    "backgroundWordLines=${song.lyrics.count { it.backgroundWords.isNotEmpty() }}, " +
                    "translatedBackgroundLines=${song.lyrics.count { !it.htmlTranslatedBackgroundVocalsLineText.isNullOrBlank() }}"
            )
        }
        song.lyricsSource = source
        val songWithMetadata = song.applyCurrentMetadata()
        val mapped = normalizeRenderableLyrics(songWithMetadata.toSong())
        if (mapped.lyrics.isNullOrEmpty()) {
            DiskSongManager.delete(song.adamId.orEmpty())
            ProviderLogger.info(
                "SongRepository: Rejected textless parsed lyrics for ${song.adamId}, " +
                    "source=$source, parsedLines=${song.lyrics.size}"
            )
            return mapped
        }
        DiskSongManager.saveIfChanged(songWithMetadata)
        return mapped
    }

    /**
     * Native timing vectors without any main text are not lyrics that a consumer can render.
     * Old caches may contain exactly that shape, so repair recoverable word-only lines and drop
     * the remaining empty lines before availability/fallback decisions are made.
     */
    internal fun normalizeRenderableLyrics(song: Song): Song {
        val normalizedLines = song.lyrics.orEmpty().mapNotNull { line ->
            val text = line.text?.takeIf(String::isNotBlank)
                ?: line.words.orEmpty()
                    .joinToString("") { it.text.orEmpty() }
                    .takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val secondary = line.secondary?.takeIf(String::isNotBlank)
                ?: line.secondaryWords.orEmpty()
                    .joinToString("") { it.text.orEmpty() }
                    .takeIf(String::isNotBlank)
            line.copy(text = text, secondary = secondary)
        }
        return song.copy(lyrics = normalizedLines)
    }

    private fun AppleSong.applyCurrentMetadata(): AppleSong = apply {
        val metadata = adamId?.let(MediaMetadataCache::getMetadataById) ?: return@apply
        name = metadata.title
        artist = metadata.artist
        genre = metadata.genre
        originalTitle = metadata.originalTitle
        originalArtist = metadata.originalArtist
        originalAlbum = metadata.originalAlbum
        originalMetadataResolved = metadata.originalMetadataResolved
    }

    private fun MediaMetadataCache.Metadata.toLyricMetadata() = lyricMetadataOf(
        LyricMetadataKeys.APPLE_CATALOG_GENRE to genre,
        LyricMetadataKeys.APPLE_ORIGINAL_TITLE to originalTitle,
        LyricMetadataKeys.APPLE_ORIGINAL_ARTIST to originalArtist,
        LyricMetadataKeys.APPLE_ORIGINAL_ALBUM to originalAlbum,
        LyricMetadataKeys.APPLE_ORIGINAL_METADATA_RESOLVED to
            originalMetadataResolved.toString()
    )
}
