/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.netease

/**
 * 归一国内音乐 App 开启车载歌词后的 MediaSession 元数据污染，避免歌词行或
 * 「真歌名-真歌手」组合串被当作歌名/歌手发布。两类已知污染形态：
 *
 * 1. 标题被替换为当前歌词行并随播放进度逐句漂移，同时「真歌名-真歌手」
 *    被拼进歌手字段（小米音乐实测：artist="Love Somebody-LAUV"，
 *    title="You cut me open…"）。判定依据是「同一 mediaId 下标题发生变化」：
 *    真标题在单曲内是稳定的，只有歌词行才会逐句漂移；确认漂移后优先从
 *    歌手字段按最后一个连字符拆出真名/真歌手，拆不出时回退用本曲首个
 *    采样标题（正常切歌时首个标题就是真名；冷启动直接落到歌曲中段的
 *    场景首采样已是歌词行，无法恢复，保持原样）。
 * 2. 标题被拼成「真歌名-真歌手」组合串、歌手字段保持真歌手。只要标题以
 *    「-真歌手」结尾（忽略空白与大小写差异）即剥掉该后缀，不依赖漂移判定。
 *
 * 所有改写都要求正向证据：无漂移且后缀不匹配时原样返回，车载歌词关闭、
 * A-Lin 这类带连字符的正常歌手名均不受影响。
 */
internal class CarLyricsMetadataPolicy(
    private val maxTrackedIds: Int = 64,
) {
    private val lock = Any()
    private val firstTitleById = LinkedHashMap<String, String>(16, 0.75f, true)
    private val lastTitleById = LinkedHashMap<String, String?>(16, 0.75f, true)
    private val pollutedIds = HashSet<String>()

    data class Normalized(
        val title: String?,
        val artist: String?,
    )

    fun normalize(id: String, title: String?, artist: String?): Normalized = synchronized(lock) {
        val strippedTitle = stripCombinedTitleSuffix(title, artist)
        if (id.isNotEmpty()) {
            trackTitleDrift(id, title)
            if (id in pollutedIds) {
                deriveFromCombinedArtist(artist)?.let { return it }
                firstTitleById[id]?.takeIf(String::isNotBlank)?.let {
                    return Normalized(it, artist)
                }
            }
        }
        Normalized(strippedTitle ?: title, artist)
    }

    /** 形态一：同一 mediaId 下标题发生变化即判定车载歌词改写，该曲进入污染态。 */
    private fun trackTitleDrift(id: String, title: String?) {
        firstTitleById.putIfAbsent(id, title.orEmpty())
        lastTitleById.put(id, title)?.let { previous ->
            if (previous != title) pollutedIds += id
        }
        while (lastTitleById.size > maxTrackedIds) {
            val eldest = lastTitleById.keys.iterator().next()
            lastTitleById.remove(eldest)
            pollutedIds.remove(eldest)
            firstTitleById.remove(eldest)
        }
    }

    /** 形态一的恢复：歌手字段拼的是「真歌名-真歌手」，按最后一个连字符拆开。 */
    private fun deriveFromCombinedArtist(artist: String?): Normalized? {
        val dash = artist?.lastIndexOf('-') ?: -1
        if (artist == null || dash <= 0 || dash == artist.length - 1) return null
        val derivedTitle = artist.substring(0, dash).trim()
        val derivedArtist = artist.substring(dash + 1).trim()
        if (derivedTitle.isEmpty() || derivedArtist.isEmpty()) return null
        return Normalized(derivedTitle, derivedArtist)
    }

    /** 形态二：标题以「-真歌手」结尾时剥掉组合后缀（忽略空白与大小写差异）。 */
    private fun stripCombinedTitleSuffix(title: String?, artist: String?): String? {
        val rawTitle = title?.trim().orEmpty()
        val rawArtist = artist?.trim().orEmpty()
        if (rawTitle.isEmpty() || rawArtist.isEmpty()) return null
        val dash = rawTitle.lastIndexOf('-')
        if (dash <= 0 || dash == rawTitle.length - 1) return null
        if (collapseForMatch(rawTitle.substring(dash + 1)) != collapseForMatch(rawArtist)) {
            return null
        }
        return rawTitle.substring(0, dash).trim().takeIf(String::isNotEmpty)
    }

    private fun collapseForMatch(value: String): String =
        value.filterNot(Char::isWhitespace).lowercase()
}
