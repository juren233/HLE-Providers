/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import android.os.SystemClock
import android.util.Log
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 小米音乐（com.miui.player）的 MediaSession MEDIA_ID 上报的是 QQ 音乐 songmid，而
 * lyric_download.fcg 只接受数字歌曲 ID。已对照原包 4.44.0.9 DEX 与在线接口验证：
 * musicid=songmid 返回空内容（musicid="0"），数字 ID 返回完整 QRC。
 * 数字 ID（QQ 手机版/HD 的 MediaSession 上报值）原样透传。
 *
 * songmid → 数字 ID 的主路径是单曲接口（songmid 精确主键查询，必须带
 * songmid 与 format=json 参数：裸端点返回 HTML 页而非 JSON；1.0.14 及之前
 * 从未拼接查询串导致换算全量失败）。接口对无版权/小众歌曲返回 code=0 且
 * data 为空数组（如 000Afpal3mCeFw、003ubw8F1sZAyy 实测），此时走搜索接口
 * 兜底：候选必须「标题精确相等且歌手列表精确包含」才采纳（搜索命中的条目
 * 可能与输入 songmid 不同——同一首歌的不同版本条目，歌词一致）。
 * 响应同时携带权威 name/singer，车载歌词污染场景下用它覆盖 MediaSession 标题。
 * 负缓存按 mid+标题+歌手 组合键：污染首行失败不会挡住归一化后的重试。
 */
internal object QQMusicSongMidResolver {
    private const val TAG = "HLEProvider/QQMusic"
    private const val SINGLE_SONG_URL =
        "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg"
    private const val SEARCH_URL =
        "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
    private const val REQUEST_TIMEOUT_MS = 15_000
    private const val FAILED_RETRY_MS = 10 * 60_000L

    private val numericPattern = Regex("""^\d+$""")

    data class SongMidResolution(
        val numericSongId: String,
        val songName: String?,
        val singerName: String?,
    )

    private val resolved = ConcurrentHashMap<String, SongMidResolution>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    internal var elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime

    fun isNumericSongId(id: String): Boolean = numericPattern.matches(id)

    fun resolve(id: String, title: String?, artist: String?): SongMidResolution {
        if (isNumericSongId(id)) return SongMidResolution(id, null, null)
        resolved[id]?.let { return it }
        val failureKey = "$id|$title|$artist"
        failedAt[failureKey]?.let { failedAtMs ->
            if (elapsedRealtimeMs() - failedAtMs < FAILED_RETRY_MS) {
                throw IllegalStateException("QQ 歌曲 ID 换算失败(命中负缓存): mid=$id")
            }
            failedAt.remove(failureKey)
        }
        val resolution = fetchSingleSong(id) ?: fetchBySearch(id, title, artist)
            ?: run {
                failedAt[failureKey] = elapsedRealtimeMs()
                throw IllegalStateException("QQ 歌曲 ID 换算失败: mid=$id")
            }
        resolved[id] = resolution
        Log.i(TAG, "QQ 歌曲 ID 已换算: mid=$id songId=${resolution.numericSongId}")
        return resolution
    }

    private fun fetchSingleSong(songMid: String): SongMidResolution? {
        val url = "$SINGLE_SONG_URL?songmid=${URLEncoder.encode(songMid, "UTF-8")}&format=json"
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = REQUEST_TIMEOUT_MS
            readTimeout = REQUEST_TIMEOUT_MS
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Referer", "https://y.qq.com/")
        }
        return try {
            connection.getInputStream().bufferedReader().use { reader ->
                parseSingleSongResponse(reader.readText())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchBySearch(id: String, title: String?, artist: String?): SongMidResolution? {
        if (title.isNullOrBlank() || artist.isNullOrBlank()) return null
        val query = URLEncoder.encode("$title $artist", "UTF-8")
        val url = "$SEARCH_URL?w=$query&format=json"
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = REQUEST_TIMEOUT_MS
            readTimeout = REQUEST_TIMEOUT_MS
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Referer", "https://y.qq.com/")
        }
        return try {
            connection.getInputStream().bufferedReader().use { reader ->
                parseSearchResponse(reader.readText(), title, artist)
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseSingleSongResponse(raw: String): SongMidResolution? = runCatching {
        val root = org.json.JSONObject(raw)
        if (root.optInt("code", -1) != 0) return null
        val data = root.optJSONArray("data") ?: return null
        if (data.length() == 0) return null
        // 响应里 album.id 等嵌套 id 出现在歌曲 id 之前，必须取 data[0] 的顶层 id 字段
        val song = data.getJSONObject(0)
        val songId = song.optLong("id", -1L).takeIf { it > 0L }?.toString() ?: return null
        SongMidResolution(
            numericSongId = songId,
            songName = song.optString("name").takeIf(String::isNotBlank),
            singerName = song.optJSONArray("singer")?.optJSONObject(0)
                ?.optString("name")?.takeIf(String::isNotBlank),
        )
    }.getOrNull()

    /**
     * 搜索兜底只采纳与本地标题、歌手都精确相等的候选（忽略大小写与空白差异），
     * 绝不放宽为模糊匹配；命中条目允许与输入 songmid 不同（同曲不同版本条目）。
     */
    internal fun parseSearchResponse(raw: String, title: String, artist: String): SongMidResolution? = runCatching {
        val root = org.json.JSONObject(raw)
        if (root.optInt("code", -1) != 0) return null
        val songs = root.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
            ?: return null
        val wantedTitle = normalizeForMatch(title)
        val wantedArtist = normalizeForMatch(artist)
        for (index in 0 until songs.length()) {
            val song = songs.getJSONObject(index)
            if (normalizeForMatch(song.optString("songname")) != wantedTitle) continue
            val singers = song.optJSONArray("singer") ?: continue
            var matchedSinger: String? = null
            for (singerIndex in 0 until singers.length()) {
                val singerName = singers.optJSONObject(singerIndex)?.optString("name")
                if (singerName != null && normalizeForMatch(singerName) == wantedArtist) {
                    matchedSinger = singerName
                    break
                }
            }
            matchedSinger ?: continue
            val songId = song.optLong("songid", -1L).takeIf { it > 0L }?.toString() ?: continue
            return SongMidResolution(
                numericSongId = songId,
                songName = song.optString("songname").takeIf(String::isNotBlank),
                singerName = matchedSinger,
            )
        }
        null
    }.getOrNull()

    internal fun normalizeForMatch(value: String): String =
        value.trim().collapseWhitespace().lowercase()

    private fun String.collapseWhitespace(): String =
        replace(Regex("\\s+"), " ")
}

