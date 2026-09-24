/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import java.util.Locale

internal data class QQMusicLyricTrack(
    val id: String,
    val title: String?,
    val artist: String?,
    val duration: Long,
)

internal sealed interface QQMusicLyricTrackDecision {
    data class AwaitingVerifiedId(val track: QQMusicLyricTrack) : QQMusicLyricTrackDecision
    data class Load(val track: QQMusicLyricTrack) : QQMusicLyricTrackDecision
    data object Unchanged : QQMusicLyricTrackDecision
}

/**
 * QQ Music HD exposes a queue-local MediaSession ID instead of the real QQ song ID. Keep the
 * MediaSession metadata for display. The current in-app SongInfo can also start a load when
 * MediaSession callbacks lag behind automatic track changes; SystemUI verifies the published
 * title and artist against its current MediaSession before displaying those lyrics.
 * QQ Music mobile retains its existing MediaSession ID behavior.
 */
internal class QQMusicLyricTrackCoordinator(
    private val playerPackage: String,
) {
    private var mediaTrack: QQMusicLyricTrack? = null
    private var queueSnapshot: QQMusicQueueSnapshot? = null
    private var lastDecisionKey: String? = null

    fun onMetadata(track: QQMusicLyricTrack): QQMusicLyricTrackDecision {
        mediaTrack = track
        return decide(track)
    }

    fun onQueueSnapshot(snapshot: QQMusicQueueSnapshot?): QQMusicLyricTrackDecision {
        if (playerPackage != QQMusicRuntimePlan.HD_PACKAGE || snapshot == null) {
            return QQMusicLyricTrackDecision.Unchanged
        }
        queueSnapshot = snapshot
        val current = snapshot.current
        if (current.id.toLongOrNull()?.let { it > 0L } != true ||
            normalize(current.title).isEmpty() || normalize(current.artist).isEmpty()
        ) {
            return mediaTrack?.let(::decide) ?: QQMusicLyricTrackDecision.Unchanged
        }
        val track = mediaTrack?.takeIf { sameIdentity(it, current) }
            ?.copy(id = current.id)
            ?: QQMusicLyricTrack(
                id = current.id,
                title = current.title,
                artist = current.artist,
                duration = 0L,
            )
        return emit(track, track)
    }

    private fun decide(track: QQMusicLyricTrack): QQMusicLyricTrackDecision {
        val resolved = if (playerPackage == QQMusicRuntimePlan.HD_PACKAGE) {
            queueSnapshot?.current
                ?.takeIf { sameIdentity(track, it) }
                ?.let { current -> track.copy(id = current.id) }
        } else {
            track
        }
        return emit(resolved, track)
    }

    private fun emit(
        resolved: QQMusicLyricTrack?,
        awaiting: QQMusicLyricTrack,
    ): QQMusicLyricTrackDecision {
        val decisionKey = if (resolved == null) {
            "awaiting:${awaiting.identityKey()}"
        } else {
            "load:${resolved.identityKey()}"
        }
        if (decisionKey == lastDecisionKey) return QQMusicLyricTrackDecision.Unchanged
        lastDecisionKey = decisionKey
        return if (resolved == null) {
            QQMusicLyricTrackDecision.AwaitingVerifiedId(awaiting)
        } else {
            QQMusicLyricTrackDecision.Load(resolved)
        }
    }

    private fun sameIdentity(
        metadata: QQMusicLyricTrack,
        snapshot: QQMusicTrackSnapshot,
    ): Boolean {
        val metadataTitle = normalize(metadata.title)
        val metadataArtist = normalize(metadata.artist)
        return metadataTitle.isNotEmpty() &&
            metadataArtist.isNotEmpty() &&
            metadataTitle == normalize(snapshot.title) &&
            metadataArtist == normalize(snapshot.artist)
    }

    private fun QQMusicLyricTrack.identityKey(): String = buildString {
        append(id)
        append('|')
        append(normalize(title))
        append('|')
        append(normalize(artist))
    }

    private fun normalize(value: String?): String = value.orEmpty()
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
}
