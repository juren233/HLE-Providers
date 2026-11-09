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
    /**
     * MediaSession 原始 MEDIA_ID（未被 songmid/数字 ID 替换前的会话标识）。
     * 小米音乐车载歌词持续污染会话标题/歌手，mediaId 是唯一逐曲稳定身份；
     * 随歌曲元数据透传给 Core 后，SystemUI 侧 TrackIdentity 按 包名+mediaId
     * 严格匹配，不再依赖被污染的标题/歌手。
     */
    val sessionMediaId: String? = null,
    /**
     * id 是否已由应用内数据（HD/MIUI 播放队列快照的数字 ID）验证为在线歌曲 ID。
     * false 时（QQ 手机版的 MEDIA_ID 原值）本地歌曲的 id 是本地库数字 ID，
     * 换算器必须先经标题/歌手搜索校验才能用于取词（issue #62）。
     */
    val idTrusted: Boolean = false,
    /**
     * QQ 音乐手机版队列快照里的候选在线歌曲 ID（主进程 SongInfo ID，当前曲在前、下一首在后，
     * 经应用内广播转给播放进程）。快照的歌名/歌手不参与判断：20.9.5.8 的快照歌手为空
     * （2026-11-09 真机调试日志），而切歌瞬间快照可能还停在上一首。值也可能是本地库编号
     * （真机出现过 next=36），换算器逐个用详情接口核对歌名/歌手与会话一致才采用。
     */
    val appLinkedSongIds: List<String> = emptyList(),
)

/** Keep songmid/numeric-ID upgrades on one lyric, but distinguish MIUI playback sessions. */
internal fun QQMusicLyricTrack.publishedIdentityKey(playerPackage: String): String = buildString {
    append(QQMusicSongMidResolver.normalizeForMatch(title.orEmpty()))
    append('|')
    append(QQMusicSongMidResolver.normalizeForMatch(artist.orEmpty()))
    if (playerPackage == QQMusicRuntimePlan.MIUI_PACKAGE) {
        append('|')
        append(sessionMediaId.orEmpty())
    }
}

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
 * lyric endpoint only accepts numeric IDs, and its car-lyrics feature rewrites the session
 * title into the current lyric line (also truncating long titles). The in-app
 * SongInfomation from MusicPlayer.getCurSong() is the player's authoritative current song,
 * so whenever it carries a usable numeric ID it replaces the songmid and provides the
 * published name/artist. When metadata switches first and the snapshot still holds the song
 * already confirmed for the previous MediaSession ID, the snapshot is stale: publish a
 * placeholder and wait for the next snapshot instead of reloading the previous song's lyrics
 * (2026-11-09 device log). An invalid snapshot (id<=0)
 * keeps the existing songmid conversion path as fallback.
 * QQ Music mobile keeps the MediaSession ID as the track ID (for local songs it is a local
 * library number); the main-process queue snapshot only adds candidate online IDs
 * ([QQMusicLyricTrack.appLinkedSongIds]), which the resolver verifies one by one before use.
 */
internal class QQMusicLyricTrackCoordinator(
    private val playerPackage: String,
) {
    private var mediaTrack: QQMusicLyricTrack? = null
    private var queueSnapshot: QQMusicQueueSnapshot? = null
    private var lastDecisionKey: String? = null

    /** 小米音乐：最近一次与元数据确认同曲的快照当前曲 ID，及当时的会话 MEDIA_ID。 */
    private var miuiConfirmedSnapshotId: String? = null
    private var miuiConfirmedSessionId: String? = null

    /** 仅供 Debug 诊断：最近一次会话元数据（歌名/歌手）。 */
    internal val diagnosticMediaTrack: QQMusicLyricTrack? get() = mediaTrack

    fun onMetadata(track: QQMusicLyricTrack): QQMusicLyricTrackDecision {
        mediaTrack = track
        return decide(track)
    }

    fun onQueueSnapshot(snapshot: QQMusicQueueSnapshot?): QQMusicLyricTrackDecision {
        if (snapshot == null) return QQMusicLyricTrackDecision.Unchanged
        queueSnapshot = snapshot
        if (playerPackage == QQMusicRuntimePlan.MOBILE_PACKAGE) {
            return mediaTrack?.let(::decide) ?: QQMusicLyricTrackDecision.Unchanged
        }
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
            ?.copy(id = current.id, idTrusted = true)
            ?: QQMusicLyricTrack(
                id = current.id,
                title = current.title,
                artist = current.artist,
                duration = 0L,
                idTrusted = true,
            )
        return emit(track, track)
    }

    private fun decide(track: QQMusicLyricTrack): QQMusicLyricTrackDecision {
        val resolved = when (playerPackage) {
            QQMusicRuntimePlan.HD_PACKAGE -> queueSnapshot?.current
                ?.takeIf { sameIdentity(track, it) }
                ?.let { current -> track.copy(id = current.id, idTrusted = true) }
            QQMusicRuntimePlan.MIUI_PACKAGE -> queueSnapshot?.current
                ?.takeIf { it.id.toLongOrNull()?.let { id -> id > 0L } == true }
                ?.let { current ->
                    // 快照 getCurSong() 是播放器当前曲：信任其数字 ID 与权威真名/真歌手。
                    // 切歌时快照可能先于 MediaSession 更新；旧曲的 sessionMediaId
                    // 不能随新曲歌词一起发布，否则 SystemUI 会持续拒收。
                    val metadataMatchesCurrent = sameMiuiMediaTrack(track, current) ||
                        current.songMid?.equals(track.id, ignoreCase = true) == true
                    if (metadataMatchesCurrent && !track.sessionMediaId.isNullOrBlank()) {
                        miuiConfirmedSnapshotId = current.id
                        miuiConfirmedSessionId = track.sessionMediaId
                    } else if (isStaleMiuiSnapshot(current, track)) {
                        // 元数据先于快照切到新歌：快照仍是上一首，不能按它取词（否则会把上一首
                        // 的歌词重新下载、发布一遍）。先发新歌占位，等下一份快照
                        return emit(null, track)
                    }
                    track.copy(
                        id = QQMusicSongMidResolver.sanitizeNumericSongId(current.id),
                        title = current.title.takeIf { normalize(it).isNotEmpty() } ?: track.title,
                        artist = current.artist.takeIf { normalize(it).isNotEmpty() } ?: track.artist,
                        sessionMediaId = track.sessionMediaId.takeIf { metadataMatchesCurrent },
                        idTrusted = true,
                    )
                }
                ?: track
            QQMusicRuntimePlan.MOBILE_PACKAGE -> track.copy(appLinkedSongIds = appLinkedSongIds())
            else -> track
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

    /**
     * 手机版：快照当前曲与下一首的正数 ID 依次作为候选。不按快照歌名/歌手预筛：
     * 身份由换算器的详情核对决定（切歌瞬间快照滞后时，新歌往往是上一份快照的「下一首」）。
     */
    private fun appLinkedSongIds(): List<String> {
        val snapshot = queueSnapshot ?: return emptyList()
        return listOfNotNull(snapshot.current, snapshot.next)
            .map { it.id }
            .filter { id -> id.toLongOrNull()?.let { it > 0L } == true }
            .distinct()
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

    /**
     * 小米音乐快照过期的正向证据：快照当前曲正是此前已与另一个会话 MEDIA_ID 确认同曲的那首，
     * 而新元数据换了 MEDIA_ID 且与快照对不上。不按标题判断（车载歌词会把标题改成歌词行）；
     * 快照从未与元数据确认过时没有证据，照旧采用快照。
     */
    private fun isStaleMiuiSnapshot(
        current: QQMusicTrackSnapshot,
        metadata: QQMusicLyricTrack,
    ): Boolean {
        val session = metadata.sessionMediaId?.takeIf(String::isNotBlank) ?: return false
        return current.id == miuiConfirmedSnapshotId && session != miuiConfirmedSessionId
    }

    private fun sameMiuiMediaTrack(
        metadata: QQMusicLyricTrack,
        snapshot: QQMusicTrackSnapshot,
    ): Boolean {
        val metadataTitle = normalize(metadata.title)
        val snapshotTitle = normalize(snapshot.title)
        val metadataArtist = normalize(metadata.artist)
        val sameArtist = metadataArtist.isNotEmpty() &&
            metadataArtist == normalize(snapshot.artist)
        if (!sameArtist) return false
        if (metadataTitle.isNotEmpty() && metadataTitle == snapshotTitle) return true
        return metadataTitle.length >= 5 && snapshotTitle.length >= 5 &&
            (snapshotTitle.contains(metadataTitle) || metadataTitle.contains(snapshotTitle))
    }

    private fun QQMusicLyricTrack.identityKey(): String = buildString {
        append(id)
        append('|')
        append(normalize(title))
        append('|')
        append(normalize(artist))
        if (playerPackage == QQMusicRuntimePlan.MIUI_PACKAGE) {
            append('|')
            append(sessionMediaId.orEmpty())
        }
        if (playerPackage == QQMusicRuntimePlan.MOBILE_PACKAGE) {
            append('|')
            append(appLinkedSongIds.joinToString(","))
        }
    }

    private fun normalize(value: String?): String = value.orEmpty()
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
}
