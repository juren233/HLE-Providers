/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

/**
 * 多歌手写法比对。QQ 音乐简洁版的 OpenAPI getSingerName()、详情接口的 singer 只给第一位歌手，
 * 会话 ARTIST 却是全部歌手（「喻志博/李雅戈」）；SystemUI 用会话做曲目身份，
 * 歌手串不一致就把整首歌词当成别的歌丢掉（2026-11-24 真机日志）。
 *
 * 只按明确的多歌手分隔符拆分，不拆「-」和空格：车载/蓝牙歌词把歌手改写成「歌名-歌手」时，
 * 整串仍是一段，不会被当成同一组歌手。
 */
internal object QQMusicArtistNames {
    private val SEPARATORS = Regex("\\s*[/／&＆、,，;；]\\s*")

    fun parts(value: String?): Set<String> = value.orEmpty()
        .split(SEPARATORS)
        .map(QQMusicSongMidResolver::normalizeForMatch)
        .filter(String::isNotEmpty)
        .toSet()

    /** 两串歌手一方是另一方的子集（含相等），说明是同一组歌手的完整/简写形式。 */
    fun compatible(a: String?, b: String?): Boolean {
        val left = parts(a)
        val right = parts(b)
        if (left.isEmpty() || right.isEmpty()) return false
        return left.containsAll(right) || right.containsAll(left)
    }
}
