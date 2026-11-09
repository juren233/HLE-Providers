/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

/**
 * 歌词缓存读取前的身份校验：缓存的权威 name/singer 必须与当前会话的标题/歌手互相
 * 解释（归一化后相等或互相包含）。歌名包含要求较短侧 ≥4 字符——端上会截断长标题
 * （真机实证：会话 golden hour，QQ 真名 In your golden hour），单向包含会误删权威
 * 全名的正确缓存。换算器用歌曲详情核对会话 ID 与按歌名匹配的候选时复用同一份规则
 * （[QQMusicSongMidResolver]），两处身份判断不得分叉。标题/歌手主字段不解释时兜底用
 * 「标题+歌手」整串解释（车载歌词污染会把两个字段粘连互换）。
 * 不带 name 的缓存来自数字 ID 盲透传时代（含 issue #62 的错词缓存），一律不可信；
 * 校验失败时调用方应删除缓存文件并按 miss 处理，让下一轮搜索校验重建正确缓存。
 */
internal object QQMusicLyricCachePolicy {

    /**
     * 歌词缓存文件名：会话 ID + 归一化歌名/歌手的 SHA-256。QQ 手机版本地歌的会话 ID 是本地库编号，
     * 与在线歌曲 ID 处在同一数字空间，只按 ID 命名会让本地歌 46 与在线歌 46 共用一个文件。
     */
    fun cacheFileName(sessionId: String, title: String?, artist: String?): String {
        val key = listOf(
            sessionId,
            QQMusicSongMidResolver.normalizeForMatch(title.orEmpty()),
            QQMusicSongMidResolver.normalizeForMatch(artist.orEmpty()),
        ).joinToString("\u0000")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("", postfix = ".json") { "%02x".format(it) }
    }

    /** 缓存内容是否可确认属于该曲目；false 时调用方应删除缓存文件并按 miss 处理。 */
    fun belongsToSession(
        payloadName: String?,
        payloadSinger: String?,
        trackTitle: String?,
        trackArtist: String?,
    ): Boolean {
        val name = payloadName?.takeIf(String::isNotBlank) ?: return false
        val sessionTitle = QQMusicSongMidResolver.normalizeForMatch(trackTitle.orEmpty())
        val sessionArtist = QQMusicSongMidResolver.normalizeForMatch(trackArtist.orEmpty())
        val sessionHaystack = "$sessionTitle $sessionArtist"
        val normalizedName = QQMusicSongMidResolver.normalizeForMatch(name)
        if (normalizedName.length < 2 ||
            !explains(normalizedName, sessionTitle, minContained = 4) &&
            !explains(normalizedName, sessionHaystack, minContained = 4)
        ) {
            return false
        }
        val singer = payloadSinger?.let(QQMusicSongMidResolver::normalizeForMatch).orEmpty()
        return singer.isEmpty() ||
            explains(singer, sessionArtist, minContained = 2) ||
            explains(singer, sessionHaystack, minContained = 2)
    }

    /** [candidate] 与 [reference] 归一化后相等或互相包含（较短侧长度 ≥ [minContained]）。 */
    private fun explains(candidate: String, reference: String, minContained: Int): Boolean {
        if (candidate.isEmpty() || reference.isEmpty()) return false
        if (reference.contains(candidate)) return true
        val shorter = minOf(candidate.length, reference.length)
        return shorter >= minContained && candidate.contains(reference)
    }
}
