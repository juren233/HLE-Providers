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
 * Xiaomi Music (com.miui.player) reports a QQ songmid in its MediaSession while the public
 * lyric endpoint only accepts numeric IDs; when the in-app SongInfomation belongs to the same
 * song — bound by exact songmid (immune to car-lyrics metadata pollution) or by title+artist
 * when the snapshot lacks it — replace the songmid with its numeric ID and adopt the
 * snapshot's authoritative name/singer so a polluted MediaSession title is not published.
 * A non-matching or invalid snapshot keeps the existing songmid conversion path.
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
        if (snapshot == null || playerPackage == QQMusicRuntimePlan.MOBILE_PACKAGE) {
            return QQMusicLyricTrackDecision.Unchanged
        }
        queueSnapshot = snapshot
        if (playerPackage == QQMusicRuntimePlan.MIUI_PACKAGE) {
            return mediaTrack?.let(::decide) ?: QQMusicLyricTrackDecision.Unchanged
        }
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
        val resolved = when (playerPackage) {
            QQMusicRuntimePlan.HD_PACKAGE -> queueSnapshot?.current
                ?.takeIf { sameIdentity(track, it) }
                ?.let { current -> track.copy(id = current.id) }
            QQMusicRuntimePlan.MIUI_PACKAGE -> queueSnapshot?.current
                ?.takeIf {
                    it.id.toLongOrNull()?.let { id -> id > 0L } == true && bindsCurrentSong(track, it)
                }
                ?.let { current ->
                    // 快照身份是 App 播放器的权威曲目信息：songmid 绑定成功但元数据标题
                    // 已被车载歌词改写成歌词行时，用快照真名/真歌手发布
                    track.copy(
                        id = QQMusicSongMidResolver.sanitizeNumericSongId(current.id),
                        title = current.title.takeIf { normalize(it).isNotEmpty() } ?: track.title,
                        artist = current.artist.takeIf { normalize(it).isNotEmpty() } ?: track.artist,
                    )
                }
                ?: track
            else -> track
        }
        return emit(resolved, track)
    }

    /**
     * 小米音乐绑定判定：优先 songmid 精确相等（车载歌词污染下元数据标题不可信），
     * 快照未提供 songmid 时退回标题+歌手精确相等。
     */
    private fun bindsCurrentSong(track: QQMusicLyricTrack, current: QQMusicTrackSnapshot): Boolean {
        if (!current.songMid.isNullOrBlank()) {
            return current.songMid.equals(track.id, ignoreCase = true)
        }
        return sameIdentity(track, current)
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
