/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * QQ 音乐统一网关里两个不经过搜索服务的接口（2026-11-09 实测：两者连发数十次均无
 * 限流，同期 `music.search.SearchCgiService` 频繁返回 2001）。
 *
 * - 歌曲详情 `music.pf_song_detail_svr / get_song_detail_yqq`：按数字 ID 取在线库里
 *   的歌名/歌手/时长；不存在的 ID 返回 songinfo.code=500。用来判断会话 ID 是否就是
 *   当前这首歌的在线 ID（QQ 手机版本地歌的会话 ID 是本地库编号，查回来是另一首歌）。
 * - 按歌名匹配 `music.musichallSong.PlayLyricInfo / GetPlayLyricInfo`：songID 填 0、
 *   歌名/歌手按 Base64 提交，服务端返回匹配到的 songID 与同曲候选 vecSongID。这是
 *   QQ 音乐 App 给未关联在线歌的本地歌取词时发的请求形态（20.9.5.8 反编译
 *   `lyricnew/load/helper/a.java`）；歌手对不上返回 24001，歌手为空会模糊匹配到别的
 *   歌，所以调用方必须带歌手，并对返回的每个 ID 用详情接口核对身份。
 */
internal object QQMusicCatalogClient {
    private const val GATEWAY_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val TIMEOUT_MS = 10_000
    private const val NO_LYRIC_MATCH_CODE = 24001

    /** 与核心 QmSource 相同的轻量版客户端标识。 */
    private val comm = JSONObject()
        .put("ct", "11")
        .put("cv", "1003006")
        .put("v", "1003006")
        .put("tmeAppID", "qqmusiclight")
        .put("nettype", "NETWORK_WIFI")

    data class SongDetail(
        val songId: String,
        val name: String,
        val singer: String?,
        val durationSeconds: Int,
    )

    sealed interface DetailResult {
        data class Found(val detail: SongDetail) : DetailResult

        /** 在线库没有这个 ID。 */
        data object NotFound : DetailResult
    }

    sealed interface MetadataMatch {
        /** [songId] 为服务端选中的条目（时长对不上时可能为空），[candidates] 为同曲候选。 */
        data class Found(val songId: String?, val candidates: List<String>) : MetadataMatch

        /** 服务端明确表示没有匹配（24001，或既无选中条目也无候选）。 */
        data object NoMatch : MetadataMatch
    }

    /** 网络或网关异常时抛出，调用方不得把异常当作「查无此歌」。 */
    fun fetchSongDetail(songId: String): DetailResult {
        val data = JSONObject()
            .put(
                "songinfo",
                JSONObject()
                    .put("module", "music.pf_song_detail_svr")
                    .put("method", "get_song_detail_yqq")
                    .put("param", JSONObject().put("song_id", songId.toLong())),
            )
        val url = "$GATEWAY_URL?data=${URLEncoder.encode(data.toString(), "UTF-8")}"
        val raw = request(url, body = null)
        return parseSongDetail(raw) ?: error("QQ 歌曲详情响应异常: id=$songId")
    }

    /** 网络或网关异常时抛出；[artist] 必须非空。 */
    fun matchByMetadata(title: String, artist: String, durationSeconds: Long): MetadataMatch {
        require(title.isNotBlank() && artist.isNotBlank()) { "按歌名匹配需要歌名与歌手" }
        val param = JSONObject()
            .put("songID", 0)
            .put("type", 0)
            .put("songName", base64(title))
            .put("singerName", base64(artist))
            .put("albumName", "")
            .put("interval", durationSeconds.coerceAtLeast(0L))
            .put("crypt", 0)
            .put("qrc", 0)
            .put("trans", 0)
            .put("roma", 0)
        val body = JSONObject()
            .put("comm", comm)
            .put(
                "req_0",
                JSONObject()
                    .put("module", "music.musichallSong.PlayLyricInfo")
                    .put("method", "GetPlayLyricInfo")
                    .put("param", param),
            )
        val raw = request(GATEWAY_URL, body = body.toString())
        return parseMetadataMatch(raw) ?: error("QQ 按歌名匹配响应异常: title=$title")
    }

    /** 无法解析或网关层失败时返回 null。 */
    internal fun parseSongDetail(raw: String): DetailResult? = runCatching {
        val root = JSONObject(raw)
        if (root.optInt("code", -1) != 0) return@runCatching null
        val service = root.optJSONObject("songinfo") ?: return@runCatching null
        when (service.optInt("code", -1)) {
            0 -> Unit
            // 不存在的 ID（实测 99999999999）
            500 -> return@runCatching DetailResult.NotFound
            else -> return@runCatching null
        }
        val track = service.optJSONObject("data")?.optJSONObject("track_info")
            ?: return@runCatching DetailResult.NotFound
        val id = track.optLong("id", -1L).takeIf { it > 0L } ?: return@runCatching DetailResult.NotFound
        val name = track.optString("name").takeIf(String::isNotBlank)
            ?: return@runCatching DetailResult.NotFound
        DetailResult.Found(
            SongDetail(
                songId = id.toString(),
                name = name,
                singer = track.optJSONArray("singer")?.optJSONObject(0)
                    ?.optString("name")?.takeIf(String::isNotBlank),
                durationSeconds = track.optInt("interval", 0),
            ),
        )
    }.getOrNull()

    /** 无法解析或网关层失败时返回 null。 */
    internal fun parseMetadataMatch(raw: String): MetadataMatch? = runCatching {
        val root = JSONObject(raw)
        if (root.optInt("code", -1) != 0) return@runCatching null
        val module = root.optJSONObject("req_0") ?: return@runCatching null
        when (module.optInt("code", -1)) {
            0 -> Unit
            NO_LYRIC_MATCH_CODE -> return@runCatching MetadataMatch.NoMatch
            else -> return@runCatching null
        }
        val data = module.optJSONObject("data") ?: return@runCatching MetadataMatch.NoMatch
        val songId = data.optLong("songID", 0L).takeIf { it > 0L }?.toString()
        val candidates = buildList {
            val array = data.optJSONArray("vecSongID") ?: return@buildList
            for (index in 0 until array.length()) {
                array.optLong(index, 0L).takeIf { it > 0L }?.let { add(it.toString()) }
            }
        }.distinct()
        if (songId == null && candidates.isEmpty()) MetadataMatch.NoMatch
        else MetadataMatch.Found(songId, candidates)
    }.getOrNull()

    private fun base64(value: String): String =
        Base64.encodeToString(value.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

    private fun request(url: String, body: String?): String {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Referer", "https://y.qq.com/")
            if (body != null) {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "QQ 音乐网关 HTTP ${connection.responseCode}"
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
