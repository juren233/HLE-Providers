/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.locallyrics

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** 播放器会话给出的曲目信息，用来在系统媒体库里找对应的本地文件。 */
internal data class LocalAudioQuery(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
)

internal data class LocalAudioCandidate(
    val mediaUri: String,
    val mediaId: Long?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val displayName: String?,
    val relativePath: String?,
    val dataPath: String?,
)

/**
 * Conservatively identifies a local MediaStore item from standard MediaSession metadata.
 *
 * A title and duration match are mandatory because players such as Salt Player 12.1.1 and
 * QQ Music do not publish a MediaStore id or URI. If several local files remain plausible,
 * the selector refuses to guess unless artist/album/file-name evidence gives one candidate a
 * clear lead.
 */
internal object LocalAudioCandidateSelector {
    fun select(
        query: LocalAudioQuery,
        candidates: List<LocalAudioCandidate>,
    ): LocalAudioCandidate? {
        val scored = candidates.mapNotNull { candidate ->
            score(query, candidate)?.let { score -> ScoredCandidate(candidate, score) }
        }.sortedWith(
            compareByDescending<ScoredCandidate> { it.score }
                .thenBy { durationDelta(query, it.candidate) }
                .thenBy { it.candidate.mediaId ?: Long.MAX_VALUE },
        )
        val best = scored.firstOrNull() ?: return null
        val runnerUp = scored.getOrNull(1) ?: return best.candidate
        return best.candidate.takeIf { best.score - runnerUp.score >= MIN_SCORE_LEAD }
    }

    fun isHighConfidenceMatch(
        query: LocalAudioQuery,
        candidate: LocalAudioCandidate,
    ): Boolean = score(query, candidate) != null

    private fun score(
        query: LocalAudioQuery,
        candidate: LocalAudioCandidate,
    ): Int? {
        val expectedTitle = normalize(query.title)
        val candidateTitle = normalize(candidate.title)
        if (expectedTitle.isEmpty() || candidateTitle != expectedTitle) return null

        val expectedDuration = query.durationMs
        val candidateDuration = candidate.durationMs
        if (expectedDuration <= 0L || candidateDuration <= 0L) return null
        val durationDelta = abs(candidateDuration - expectedDuration)
        if (durationDelta > MAX_DURATION_DELTA_MS) return null

        var score = 100
        score += 40 - (durationDelta * 20L / MAX_DURATION_DELTA_MS).toInt()
        if (sameNonBlank(query.artist, candidate.artist)) score += 30
        if (sameNonBlank(query.album, candidate.album)) score += 20
        if (normalize(candidate.displayName?.substringBeforeLast('.')) == expectedTitle) score += 10
        return score
    }

    private fun durationDelta(
        query: LocalAudioQuery,
        candidate: LocalAudioCandidate,
    ): Long = if (query.durationMs > 0L && candidate.durationMs > 0L) {
        abs(query.durationMs - candidate.durationMs)
    } else {
        Long.MAX_VALUE
    }

    private fun sameNonBlank(left: String?, right: String?): Boolean {
        val normalizedLeft = normalize(left)
        return normalizedLeft.isNotEmpty() && normalizedLeft == normalize(right)
    }

    private fun normalize(value: String?): String = Normalizer
        .normalize(value.orEmpty(), Normalizer.Form.NFKC)
        .replace(ZERO_WIDTH, "")
        .trim()
        .replace(WHITESPACE, " ")
        .lowercase(Locale.ROOT)

    private data class ScoredCandidate(
        val candidate: LocalAudioCandidate,
        val score: Int,
    )

    private val ZERO_WIDTH = Regex("[\\u200B-\\u200D\\uFEFF]")
    private val WHITESPACE = Regex("\\s+")
    private const val MAX_DURATION_DELTA_MS = 2_000L
    private const val MIN_SCORE_LEAD = 15
}

/**
 * Queries the system MediaStore and reads lyric text from local audio files, without calling
 * any player class. Runs in the player's process, so it relies on the player's own audio
 * read permission.
 */
internal class LocalAudioLibrary(private val contentResolver: ContentResolver) {

    /** 按歌名 + 时长在媒体库里找唯一可信的本地文件；找不到或有多个难分的候选时返回 null。 */
    fun findByMetadata(query: LocalAudioQuery): LocalAudioCandidate? {
        query.title?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (query.durationMs <= 0L) return null
        val lowerDuration = (query.durationMs - QUERY_DURATION_WINDOW_MS).coerceAtLeast(0L)
        val upperDuration = query.durationMs + QUERY_DURATION_WINDOW_MS
        val candidates = queryCandidates(
            uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            selection = "${MediaStore.Audio.Media.DURATION} BETWEEN ? AND ?",
            selectionArgs = arrayOf(lowerDuration.toString(), upperDuration.toString()),
        )
        return LocalAudioCandidateSelector.select(query, candidates)
    }

    fun queryCandidates(
        uri: Uri,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
    ): List<LocalAudioCandidate> = runCatching {
        contentResolver.query(
            uri,
            AUDIO_PROJECTION,
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toMediaCandidate(uri))
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    /** 同目录同名 .lrc 文件。 */
    fun readSidecarLyrics(media: LocalAudioCandidate): String? {
        val displayName = media.displayName?.takeIf { it.contains('.') } ?: return null
        val sidecarName = displayName.substringBeforeLast('.') + ".lrc"

        runCatching {
            media.dataPath?.let(::File)?.parentFile?.listFiles()?.firstOrNull {
                it.isFile && it.name.equals(sidecarName, ignoreCase = true)
            }?.let { file -> readBounded(FileInputStream(file)) }
        }.getOrNull()?.let { return it }

        val relativePath = media.relativePath ?: return null
        val mediaUri = Uri.parse(media.mediaUri)
        val volumeName = runCatching { MediaStore.getVolumeName(mediaUri) }
            .getOrDefault(MediaStore.VOLUME_EXTERNAL)
        val filesUri = MediaStore.Files.getContentUri(volumeName)
        return runCatching {
            contentResolver.query(
                filesUri,
                arrayOf(BaseColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ? COLLATE NOCASE",
                arrayOf(relativePath, sidecarName),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(BaseColumns._ID))
                readTextUri(ContentUris.withAppendedId(filesUri, id))
            }
        }.getOrNull()
    }

    /** 音乐文件标签里内嵌的歌词原文。 */
    fun readEmbeddedLyrics(media: LocalAudioCandidate): String? {
        media.dataPath?.let(::File)?.takeIf(File::isFile)?.let { file ->
            runCatching {
                FileInputStream(file).use { input ->
                    EmbeddedLyricsReader.read(input.channel, file.name)
                }
            }.getOrNull()?.let { return it }
        }

        val uri = Uri.parse(media.mediaUri)
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        return runCatching {
            val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: return@runCatching null
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                EmbeddedLyricsReader.read(
                    channel = input.channel,
                    fileName = media.displayName.orEmpty(),
                )
            }
        }.getOrNull()
    }

    private fun Cursor.toMediaCandidate(queryUri: Uri): LocalAudioCandidate {
        val id = longOrNull(BaseColumns._ID)
        val queryAlreadyTargetsItem = queryUri.lastPathSegment?.toLongOrNull() != null
        val itemUri = if (id != null && !queryAlreadyTargetsItem) {
            ContentUris.withAppendedId(queryUri, id)
        } else {
            queryUri
        }
        return LocalAudioCandidate(
            mediaUri = itemUri.toString(),
            mediaId = id,
            title = stringOrNull(MediaStore.Audio.Media.TITLE),
            artist = stringOrNull(MediaStore.Audio.Media.ARTIST),
            album = stringOrNull(MediaStore.Audio.Media.ALBUM),
            durationMs = longOrNull(MediaStore.Audio.Media.DURATION) ?: 0L,
            displayName = stringOrNull(MediaStore.Audio.Media.DISPLAY_NAME),
            relativePath = stringOrNull(MediaStore.Audio.Media.RELATIVE_PATH),
            dataPath = stringOrNull(MediaStore.Audio.Media.DATA),
        )
    }

    private fun readTextUri(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.let(::readBounded)
    }.getOrNull()

    private fun readBounded(input: InputStream): String? = input.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_LRC_BYTES) return@use null
            output.write(buffer, 0, count)
        }
        EmbeddedLyricsReader.decodeBestEffort(output.toByteArray())
    }

    private fun Cursor.stringOrNull(columnName: String): String? =
        getColumnIndex(columnName).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun Cursor.longOrNull(columnName: String): Long? =
        getColumnIndex(columnName).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

    private companion object {
        val AUDIO_PROJECTION = arrayOf(
            BaseColumns._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATA,
        )

        const val QUERY_DURATION_WINDOW_MS = 2_000L
        const val MAX_LRC_BYTES = 4 * 1024 * 1024
    }
}
