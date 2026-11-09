/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import com.juren233.hle.providers.locallyrics.LocalAudioCandidate
import com.juren233.hle.providers.locallyrics.LocalAudioCandidateSelector
import com.juren233.hle.providers.locallyrics.LocalAudioLibrary
import com.juren233.hle.providers.locallyrics.LocalLrcParser
import com.juren233.hle.providers.locallyrics.LocalLyricsDocument
import java.io.File
import java.util.Locale

internal enum class SaltPlayerLocalLyricsSource {
    SIDECAR_LRC,
    EMBEDDED,
}

internal data class SaltPlayerLocalLyricsResult(
    val document: LocalLyricsDocument,
    val source: SaltPlayerLocalLyricsSource,
    val mediaUri: String,
)

/** Reads sidecar or embedded lyrics without loading any Salt Player implementation class. */
internal class SaltPlayerMediaStoreResolver(application: Application) {
    private val library = LocalAudioLibrary(application.contentResolver)

    fun load(metadata: SaltPlayerTrackMetadata): SaltPlayerLocalLyricsResult? {
        val media = resolveMedia(metadata) ?: return null

        library.readSidecarLyrics(media)?.let { text ->
            LocalLrcParser.parse(text, metadata.durationMs)?.let { document ->
                return SaltPlayerLocalLyricsResult(
                    document = document,
                    source = SaltPlayerLocalLyricsSource.SIDECAR_LRC,
                    mediaUri = media.mediaUri,
                )
            }
        }

        library.readEmbeddedLyrics(media)?.let { text ->
            LocalLrcParser.parse(text, metadata.durationMs)?.let { document ->
                return SaltPlayerLocalLyricsResult(
                    document = document,
                    source = SaltPlayerLocalLyricsSource.EMBEDDED,
                    mediaUri = media.mediaUri,
                )
            }
        }
        return null
    }

    private fun resolveMedia(metadata: SaltPlayerTrackMetadata): LocalAudioCandidate? {
        resolvePublishedUri(metadata)?.let { return it }
        resolvePublishedMediaId(metadata)?.let { return it }

        return library.findByMetadata(metadata.toLocalAudioQuery())
    }

    private fun resolvePublishedUri(metadata: SaltPlayerTrackMetadata): LocalAudioCandidate? {
        val uri = metadata.mediaUri?.trim()?.takeIf(String::isNotEmpty)?.let(Uri::parse)
            ?: return null
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            ContentResolver.SCHEME_CONTENT -> library.queryCandidates(uri).singleOrNull()
                ?: LocalAudioCandidate(
                    mediaUri = uri.toString(),
                    mediaId = null,
                    title = metadata.title,
                    artist = metadata.artist,
                    album = metadata.album,
                    durationMs = metadata.durationMs,
                    displayName = uri.lastPathSegment,
                    relativePath = null,
                    dataPath = null,
                )
            ContentResolver.SCHEME_FILE -> uri.path?.let(::File)?.takeIf(File::isFile)?.let { file ->
                LocalAudioCandidate(
                    mediaUri = uri.toString(),
                    mediaId = null,
                    title = metadata.title,
                    artist = metadata.artist,
                    album = metadata.album,
                    durationMs = metadata.durationMs,
                    displayName = file.name,
                    relativePath = null,
                    dataPath = file.absolutePath,
                )
            }
            else -> null
        }
    }

    private fun resolvePublishedMediaId(metadata: SaltPlayerTrackMetadata): LocalAudioCandidate? {
        val mediaId = metadata.id?.trim()?.toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
        return library.queryCandidates(uri).singleOrNull()?.takeIf {
            LocalAudioCandidateSelector.isHighConfidenceMatch(metadata.toLocalAudioQuery(), it)
        }
    }
}
