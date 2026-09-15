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
 * 本对象把非数字 ID 经 QQ 单曲信息接口（songmid 精确主键查询，非模糊搜索）
 * 换算为数字歌曲 ID；数字 ID（QQ 手机版/HD 的 MediaSession 上报值）原样透传。
 *
 * 单曲接口必须带 songmid 与 format=json 查询参数：裸端点返回 HTML 页而非 JSON
 * （1.0.14 及之前从未拼接查询串，导致换算全量失败）。接口对无版权/灰色歌曲
 * 返回 code=0 且 data 为空数组，属查不到而非解析失败，用负缓存避免逐行重试。
 * 响应同时携带权威的 name/singer，车载歌词污染场景下用它覆盖 MediaSession 标题。
 */
internal object QQMusicSongMidResolver {
    private const val TAG = "HLEProvider/QQMusic"
    private const val SINGLE_SONG_URL =
        "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg"
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

    fun resolve(id: String): SongMidResolution {
        if (isNumericSongId(id)) return SongMidResolution(id, null, null)
        resolved[id]?.let { return it }
        failedAt[id]?.let { failedAtMs ->
            if (elapsedRealtimeMs() - failedAtMs < FAILED_RETRY_MS) {
                throw IllegalStateException("QQ 歌曲 ID 换算失败(命中负缓存): mid=$id")
            }
            failedAt.remove(id)
        }
        val resolution = fetch(id)
            ?: run {
                failedAt[id] = elapsedRealtimeMs()
                throw IllegalStateException("QQ 歌曲 ID 换算失败: mid=$id")
            }
        resolved[id] = resolution
        Log.i(TAG, "QQ 歌曲 ID 已换算: mid=$id songId=${resolution.numericSongId}")
        return resolution
    }

    private fun fetch(songMid: String): SongMidResolution? {
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
}
