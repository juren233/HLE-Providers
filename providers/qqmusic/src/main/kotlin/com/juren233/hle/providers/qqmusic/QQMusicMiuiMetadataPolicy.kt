/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

/**
 * 归一小米音乐（com.miui.player）车载歌词开启时的 MediaSession 元数据污染：
 * 标题字段被替换为当前歌词行并随播放进度逐句漂移，同时「真歌名-真歌手」
 * 被拼进歌手字段（如 artist="Love Somebody-LAUV"，title="You cut me open…"）。
 *
 * 判定依据是「同一 mediaId 下标题发生变化」：真标题在单曲内是稳定的，
 * 只有歌词行才会逐句漂移；因此第一次采样不做改写（车载歌词关闭时元数据
 * 本来就是干净的，含 A-Lin 这类带连字符的歌手名也不受影响），确认漂移后
 * 该 mediaId 的后续样本才从歌手字段按最后一个连字符拆出真名/真歌手。
 */
internal class QQMusicMiuiMetadataPolicy(
    private val maxTrackedIds: Int = 64,
) {
    private val lock = Any()
    private val lastTitleById = LinkedHashMap<String, String?>(16, 0.75f, true)
    private val pollutedIds = HashSet<String>()

    data class Normalized(
        val title: String?,
        val artist: String?,
    )

    fun normalize(id: String, title: String?, artist: String?): Normalized = synchronized(lock) {
        lastTitleById.put(id, title)?.let { previous ->
            if (previous != title) pollutedIds += id
        }
        while (lastTitleById.size > maxTrackedIds) {
            val eldest = lastTitleById.keys.iterator().next()
            lastTitleById.remove(eldest)
            pollutedIds.remove(eldest)
        }
        if (id !in pollutedIds) return Normalized(title, artist)

        val dash = artist?.lastIndexOf('-') ?: -1
        if (artist == null || dash <= 0 || dash == artist.length - 1) return Normalized(title, artist)
        val derivedTitle = artist.substring(0, dash).trim()
        val derivedArtist = artist.substring(dash + 1).trim()
        if (derivedTitle.isEmpty() || derivedArtist.isEmpty()) return Normalized(title, artist)
        Normalized(derivedTitle, derivedArtist)
    }
}
