/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import android.os.SystemClock
import android.util.Log
import com.juren233.hle.providers.qqmusic.QQMusicCatalogClient.DetailResult
import com.juren233.hle.providers.qqmusic.QQMusicCatalogClient.MetadataMatch
import com.juren233.hyperlyricsenhanced.common.lyric.SongMetadataQueryPolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 把会话里的歌曲标识换算成 QQ 歌词接口可用的在线数字歌曲 ID。
 *
 * 数字 ID 只有来源可信（HD/MIUI 播放队列快照）时才直接使用。QQ 手机版的 MEDIA_ID 是
 * `SongInfo.H2()` 原值（20.9.5.8 反编译 `MediaSessionUpdateController`），本地歌是本地库
 * 编号：issue #62 中《Broken》=1926 在在线库里是莫文蔚《那么爱你为什么》。因此不可信的数字
 * ID 先用歌曲详情核对「这个 ID 在在线库里是不是这首歌」，对不上再按歌名匹配；任何一步都
 * 确认不了时抛出，由调用方发布「歌词确认缺失」交给核心兜底，绝不按会话 ID 盲目透传。
 *
 * 1.0.30 的教训：旧实现靠搜索网关（music.search.SearchCgiService）校验，搜索在真机上大多
 * 被限流（code=2001），限流时又放行透传，本地歌照样取到别的歌的歌词。现在两条路径都只用
 * [QQMusicCatalogClient] 的详情与按歌名匹配接口，不再调用搜索网关。
 *
 * 小米音乐（com.miui.player）的会话标识是 songmid：先用单曲接口（songmid 精确主键）换算，
 * 单曲接口查不到时同样走按歌名匹配。4.44.0.9 真机证伪：MEDIA_ID 不是 songmid，系统媒体卡片
 * mediaFocusParam.shareData 携带的 songmid 才是真身（见 QQMusicPluginEntry）。
 *
 * 身份核对复用 [QQMusicLyricCachePolicy.belongsToSession]（歌名互相包含、歌手互相解释），
 * 与读取歌词缓存时的身份规则是同一份。
 */
internal object QQMusicSongMidResolver {
    private const val TAG = "HLEProvider/QQMusic"
    private const val SINGLE_SONG_URL =
        "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg"
    private const val REQUEST_TIMEOUT_MS = 15_000

    /** 确认「在线库没有这首歌」后的负缓存时长；网络异常不写负缓存。 */
    private const val FAILED_RETRY_MS = 10 * 60_000L

    private val numericPattern = Regex("""^\d+$""")

    data class SongMidResolution(
        val numericSongId: String,
        val songName: String?,
        val singerName: String?,
        /**
         * 按歌名匹配得到的其余同曲候选（尚未核对身份）。主条目没有歌词时，调用方用
         * [verifyAlternative] 逐个核对后再取词。
         */
        val alternatives: List<String> = emptyList(),
    )

    private val resolved = ConcurrentHashMap<String, SongMidResolution>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val rejectedAppLinked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    internal var elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime

    fun isNumericSongId(id: String): Boolean = numericPattern.matches(id)

    /**
     * 小米音乐 qqmusicsdk 的 SongInfomation.getId() 把真实歌曲 ID 与来源标志位合并成长整型
     * （4.44.0.9 真机实测：id = songId | 0x2000_0000_0000_0000，陈粒《虚拟》107762076 →
     * 2305843009321456028）。QQ 歌曲数字 ID 不超过 32 位，超出的高位只能是标志位；
     * 原样透传会让歌词接口返回空内容，反而覆盖已取得的歌词。
     */
    internal fun sanitizeNumericSongId(id: String): String {
        val value = id.toLongOrNull() ?: return id
        if (value <= 0xFFFF_FFFFL) return id
        return (value and 0xFFFF_FFFFL).toString()
    }

    fun resolve(
        id: String,
        title: String?,
        artist: String?,
        durationMs: Long = 0L,
        trustedId: Boolean = false,
        appLinkedSongId: String? = null,
    ): SongMidResolution {
        if (isNumericSongId(id)) {
            if (!trustedId && appLinkedSongId != null) {
                resolveAppLinked(id, appLinkedSongId, title, artist)?.let { return it }
            }
            return resolveNumericId(id, title, artist, durationMs, trustedId)
        }
        return resolveSongMid(id, title, artist, durationMs)
    }

    internal fun resolveSongMid(
        songMid: String,
        title: String?,
        artist: String?,
        durationMs: Long,
        singleSong: (String) -> SongMidResolution? = ::fetchSingleSong,
        detail: (String) -> DetailResult = QQMusicCatalogClient::fetchSongDetail,
        match: (String, String, Long) -> MetadataMatch = QQMusicCatalogClient::matchByMetadata,
    ): SongMidResolution {
        resolved[songMid]?.let { return it }
        val failureKey = "$songMid|$title|$artist"
        checkNegativeCache(failureKey, "mid=$songMid")
        val resolution = singleSong(songMid)
            ?: resolveByMetadata(null, title, artist, durationMs, failureKey, detail, match)
        resolved[songMid] = resolution
        Log.i(TAG, "QQ 歌曲 ID 已换算: mid=$songMid songId=${resolution.numericSongId}")
        return resolution
    }

    /**
     * 不可信数字 ID：详情核对通过即在线歌，照用会话 ID；否则按歌名匹配，取第一个经详情核对的
     * 候选。缓存键含标题/歌手：本地库数字 ID 可能被复用为另一首歌，不能只按 id 记住换算。
     */
    internal fun resolveNumericId(
        id: String,
        title: String?,
        artist: String?,
        durationMs: Long,
        trustedId: Boolean,
        detail: (String) -> DetailResult = QQMusicCatalogClient::fetchSongDetail,
        match: (String, String, Long) -> MetadataMatch = QQMusicCatalogClient::matchByMetadata,
    ): SongMidResolution {
        val numericId = sanitizeNumericSongId(id)
        if (trustedId) return SongMidResolution(numericId, null, null)
        val failureKey = "$id|$title|$artist"
        resolved[failureKey]?.let { return it }
        checkNegativeCache(failureKey, "id=$id")
        val sessionDetail = runCatching { detail(numericId) }
            .onFailure { Log.w(TAG, "QQ 会话 ID 详情查询失败: id=$numericId", it) }
            .getOrNull()
        val confirmed = (sessionDetail as? DetailResult.Found)?.detail
            ?.takeIf { identityMatches(it, title, artist) }
        val resolution = if (confirmed != null) {
            Log.i(TAG, "QQ 会话 ID 是在线歌曲: id=$numericId, name=${confirmed.name}")
            SongMidResolution(numericId, confirmed.name, confirmed.singer)
        } else {
            if (sessionDetail is DetailResult.Found) {
                Log.i(
                    TAG,
                    "QQ 会话 ID 在在线库中是另一首歌，按歌名匹配: id=$numericId, " +
                        "online=${sessionDetail.detail.name}, title=$title",
                )
            }
            resolveByMetadata(numericId, title, artist, durationMs, failureKey, detail, match)
        }
        resolved[failureKey] = resolution
        Log.i(TAG, "QQ 数字 ID 校验通过: id=$numericId -> songId=${resolution.numericSongId}")
        return resolution
    }

    /**
     * QQ 自己关联的在线 ID（播放队列快照）优先于按歌名匹配：它就是 QQ 播放这首歌时自己显示歌词
     * 所用的版本，按歌名匹配会在同名同歌手的 Remix/无专辑条目里选错（2026-11-09《Hello》
     * 200672361 vs QQ 关联的 105345627）。快照里也可能是本地库编号（真机出现过 next=36），
     * 所以同样要经详情核对歌名/歌手；不符或查不到返回 null，由调用方按原路径换算。
     * 网络异常不记为不符，下次仍会再核对。
     */
    internal fun resolveAppLinked(
        sessionId: String,
        linkedId: String,
        title: String?,
        artist: String?,
        detail: (String) -> DetailResult = QQMusicCatalogClient::fetchSongDetail,
    ): SongMidResolution? {
        val numericLinked = sanitizeNumericSongId(linkedId)
        if (!isNumericSongId(numericLinked)) return null
        val key = "linked|$numericLinked|$title|$artist"
        resolved[key]?.let { return it }
        if (key in rejectedAppLinked) return null
        val verified = runCatching { verifyAlternative(numericLinked, title, artist, detail) }
            .onFailure { Log.w(TAG, "QQ App 关联 ID 详情查询失败: linked=$numericLinked", it) }
            .getOrElse { return null }
        if (verified == null) {
            rejectedAppLinked += key
            Log.i(TAG, "QQ App 关联 ID 与会话不是同一首，不采用: id=$sessionId, linked=$numericLinked, title=$title")
            return null
        }
        resolved[key] = verified
        Log.i(
            TAG,
            "QQ 采用 App 关联的在线 ID: id=$sessionId -> songId=$numericLinked, name=${verified.songName}",
        )
        return verified
    }

    /** 核对一个按歌名匹配得到的候选；身份对不上或查不到时返回 null，网络异常抛出。 */
    fun verifyAlternative(
        candidateId: String,
        title: String?,
        artist: String?,
        detail: (String) -> DetailResult = QQMusicCatalogClient::fetchSongDetail,
    ): SongMidResolution? {
        val found = (detail(candidateId) as? DetailResult.Found)?.detail ?: return null
        if (!identityMatches(found, title, artist)) return null
        return SongMidResolution(candidateId, found.name, found.singer)
    }

    /**
     * 按会话歌名/歌手匹配在线歌曲，查询组合见与核心共用的
     * [SongMetadataQueryPolicy.metadataQueries]，逐组查到有候选通过
     * 核对为止。只有原歌名 + 整串歌手的查询结果里出现 [sessionId] 时，才视为会话 ID 已被服务端
     * 确认（详情查询失败时仍能识别在线歌）；放宽后的查询里会话 ID 与其他候选一样要过核对。
     * 候选一律按会话原始歌名/歌手核对。歌手为空不发请求：服务端会模糊匹配到别的歌
     * （2026-11-09 实测《Broken》配到《Broken Cities》）。网络异常直接抛出，不写负缓存。
     */
    private fun resolveByMetadata(
        sessionId: String?,
        title: String?,
        artist: String?,
        durationMs: Long,
        failureKey: String,
        detail: (String) -> DetailResult,
        match: (String, String, Long) -> MetadataMatch,
    ): SongMidResolution {
        if (title.isNullOrBlank() || artist.isNullOrBlank()) {
            throw IllegalStateException("QQ 歌曲 ID 无法核对(缺少歌名或歌手): id=$sessionId")
        }
        val seconds = durationMs.coerceAtLeast(0L) / 1_000L
        // 已核对过的候选不再重复查详情（不同查询组合常返回重叠的候选）
        val checked = mutableSetOf<String>()
        SongMetadataQueryPolicy.metadataQueries(title, artist).forEachIndexed { queryIndex, (queryTitle, queryArtist) ->
            val matched = match(queryTitle, queryArtist, seconds)
            if (matched !is MetadataMatch.Found) return@forEachIndexed
            val ordered = (listOfNotNull(matched.songId) + matched.candidates).distinct()
            if (queryIndex == 0 && sessionId != null && sessionId in ordered) {
                return SongMidResolution(sessionId, null, null)
            }
            ordered.forEachIndexed { index, candidate ->
                if (!checked.add(candidate)) return@forEachIndexed
                val verified = verifyAlternative(candidate, title, artist, detail) ?: return@forEachIndexed
                Log.i(
                    TAG,
                    "QQ 按歌名匹配到在线歌曲: id=$sessionId -> songId=$candidate, " +
                        "name=${verified.songName}, query=$queryTitle / $queryArtist",
                )
                return verified.copy(alternatives = ordered.drop(index + 1))
            }
        }
        failedAt[failureKey] = elapsedRealtimeMs()
        if (checked.isEmpty()) {
            Log.i(TAG, "QQ 按歌名匹配无结果: title=$title, artist=$artist")
            throw IllegalStateException("QQ 歌曲 ID 换算失败(按歌名匹配无结果): id=$sessionId")
        }
        Log.i(TAG, "QQ 按歌名匹配的候选均未通过核对: title=$title, candidates=${checked.size}")
        throw IllegalStateException("QQ 歌曲 ID 换算失败(候选身份不符): id=$sessionId")
    }

    private fun checkNegativeCache(failureKey: String, label: String) {
        failedAt[failureKey]?.let { failedAtMs ->
            if (elapsedRealtimeMs() - failedAtMs < FAILED_RETRY_MS) {
                throw IllegalStateException("QQ 歌曲 ID 换算失败(命中负缓存): $label")
            }
            failedAt.remove(failureKey)
        }
    }

    private fun identityMatches(
        detail: QQMusicCatalogClient.SongDetail,
        title: String?,
        artist: String?,
    ): Boolean = QQMusicLyricCachePolicy.belongsToSession(detail.name, detail.singer, title, artist)

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

    internal fun normalizeForMatch(value: String): String =
        value.trim().collapseWhitespace().lowercase()

    private fun String.collapseWhitespace(): String =
        replace(Regex("\\s+"), " ")
}
