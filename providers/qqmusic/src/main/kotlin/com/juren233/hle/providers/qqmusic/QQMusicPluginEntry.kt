/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * QQ Music exposes playback metadata from the QQPlayerService process. The
 * official Pack keeps the same process split as the upstream LyricProvider,
 * but relies only on the static host callbacks.
 */

package com.juren233.hle.providers.qqmusic

import android.app.Application
import android.content.Context
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.util.Log
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHost
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMetadataCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlaybackStateCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlugin
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object QQMusicPluginEntry : OfficialProviderPlugin {
    private const val TAG = "HLEProvider/QQMusic"
    private const val PROVIDER_PACKAGE = "com.juren233.hyperlyricsenhanced.provider.qqmusic"
    private const val PLAYER_PROCESS_SUFFIX = ":QQPlayerService"
    private val installed = AtomicBoolean(false)

    @Volatile
    private var runtime: QQRuntime? = null

    override fun install(host: OfficialProviderHost) {
        require(host.packageName == "com.tencent.qqmusic") {
            "Unsupported QQ Music package: ${host.packageName}"
        }
        host.hookApplication { application ->
            if (Application.getProcessName() != host.packageName + PLAYER_PROCESS_SUFFIX) return@hookApplication
            if (!installed.compareAndSet(false, true)) return@hookApplication
            QQRuntime(application, host.packageName).start()
        }
        host.hookMediaSession(
            playbackStateCallback = OfficialProviderPlaybackStateCallback { state ->
                runtime?.provider?.player?.setPlaybackState(state)
            },
            metadataCallback = OfficialProviderMetadataCallback { metadata ->
                runtime?.onMetadata(metadata)
            },
        )
        Log.i(TAG, "QQ 音乐 Provider Hook 已安装: package=${host.packageName}")
    }

    private class QQRuntime(
        private val application: Application,
        private val playerPackage: String,
    ) {
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HLE-QQMusic-Lyrics").apply { isDaemon = true }
        }
        private val metadata = ConcurrentHashMap<String, TrackMetadata>()
        private val cacheDir = File(application.filesDir, "hle-provider/qqmusic")
        private var currentId: String? = null
        private var lastSong: Song? = null

        @Volatile
        var provider: LyriconProvider? = null
            private set

        fun start() {
            cacheDir.mkdirs()
            provider = LyriconFactory.createProvider(
                context = application,
                providerPackageName = PROVIDER_PACKAGE,
                playerPackageName = playerPackage,
            ).also {
                it.register()
                refreshDisplayPreference(it)
            }
            runtime = this
            Log.i(TAG, "QQ 音乐 Lyricon Provider 已注册: process=${Application.getProcessName()}")
        }

        fun onMetadata(value: MediaMetadata?) {
            val id = value?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.trim()
                ?.takeIf(String::isNotEmpty) ?: return
            refreshDisplayPreference(provider)
            val track = TrackMetadata(
                id = id,
                title = value.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = value.getString(MediaMetadata.METADATA_KEY_ARTIST),
                duration = value.getLong(MediaMetadata.METADATA_KEY_DURATION),
            )
            metadata[id] = track
            if (currentId == id) return
            currentId = id
            publish(loadCached(track) ?: placeholder(track))
            executor.execute {
                runCatching { QQClient.fetch(id) }
                    .onSuccess { payload ->
                        writeCache(id, payload)
                        if (currentId == id) publish(toSong(track, payload))
                    }
                    .onFailure { error -> Log.w(TAG, "QQ 歌词下载失败: id=$id", error) }
            }
        }

        private fun refreshDisplayPreference(provider: LyriconProvider?) {
            val target = provider ?: return
            val prefs = application.getSharedPreferences("qqmusicplayer", Context.MODE_PRIVATE)
            target.player.setDisplayTranslation(prefs.getBoolean("showtranslyric", false))
            target.player.setDisplayRoma(prefs.getBoolean("showromalyric", false))
        }

        private fun publish(song: Song) {
            if (lastSong == song) return
            lastSong = song
            provider?.player?.setSong(song)
        }

        private fun placeholder(track: TrackMetadata): Song = Song().apply {
            id = track.id
            name = track.title
            artist = track.artist
            duration = track.duration
        }

        private fun loadCached(track: TrackMetadata): Song? {
            val file = File(cacheDir, "${track.id}.json")
            if (!file.isFile) return null
            return runCatching { toSong(track, QQPayload.fromJson(JSONObject(file.readText()))) }.getOrNull()
        }

        private fun writeCache(id: String, payload: QQPayload) {
            runCatching { File(cacheDir, "$id.json").writeText(payload.toJson().toString()) }
                .onFailure { Log.w(TAG, "QQ 歌词缓存写入失败: id=$id", it) }
        }
    }

    private data class TrackMetadata(
        val id: String,
        val title: String?,
        val artist: String?,
        val duration: Long,
    )

    private data class QQPayload(
        val lyric: String?,
        val translation: String?,
        val roma: String?,
    ) {
        fun toJson() = JSONObject().apply {
            putOpt("lyric", lyric)
            putOpt("translation", translation)
            putOpt("roma", roma)
        }

        companion object {
            fun fromJson(json: JSONObject) = QQPayload(
                lyric = json.optString("lyric").takeIf(String::isNotBlank),
                translation = json.optString("translation").takeIf(String::isNotBlank),
                roma = json.optString("roma").takeIf(String::isNotBlank),
            )
        }
    }

    private object QQClient {
        private const val URL = "https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg"

        fun fetch(id: String): QQPayload {
            val connection = (URI(URL).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Referer", "https://y.qq.com/")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            return try {
                val body = listOf(
                    "version" to "15",
                    "miniversion" to "100",
                    // lrctype=1 deliberately asks QQ for ordinary LRC. It is
                    // supported by the same endpoint and avoids a native or
                    // third-party crypto dependency in the Pack.
                    "lrctype" to "1",
                    "musicid" to id,
                ).joinToString("&") { (key, value) ->
                    "$key=${URLEncoder.encode(value, "UTF-8")}"
                }
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "QQ 音乐 HTTP ${connection.responseCode}"
                }
                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                QQPayload(
                    lyric = cdata(raw, "content"),
                    translation = cdata(raw, "contentts"),
                    roma = cdata(raw, "contentroma"),
                )
            } finally {
                connection.disconnect()
            }
        }

        private fun cdata(raw: String, name: String): String? {
            val pattern = Regex("<$name(?:\\s|>)[^>]*>.*?<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)
            return pattern.find(raw)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
        }
    }

    private data class TimelineLine(
        val begin: Long,
        val end: Long,
        val text: String,
    )

    private object LrcParser {
        private val timestamp = Regex("\\[(\\d{1,3})[:.]([0-5]\\d)(?:[:.]([0-9]{1,3}))?]")

        fun parse(raw: String?): List<TimelineLine> {
            if (raw.isNullOrBlank()) return emptyList()
            val parsed = mutableListOf<TimelineLine>()
            raw.lineSequence().forEach { line ->
                val matches = timestamp.findAll(line).toList()
                if (matches.isEmpty() || matches.first().range.first != 0) return@forEach
                val content = line.substring(matches.last().range.last + 1).trim()
                matches.forEach { match ->
                    val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                    val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                    val fraction = match.groupValues.getOrNull(3).orEmpty()
                    val millis = when (fraction.length) {
                        1 -> fraction.toLong() * 100
                        2 -> fraction.toLong() * 10
                        3 -> fraction.toLong()
                        else -> 0L
                    }
                    parsed += TimelineLine(minutes * 60_000 + seconds * 1_000 + millis, 0L, content)
                }
            }
            val sorted = parsed.sortedBy(TimelineLine::begin)
            return sorted.mapIndexed { index, line ->
                line.copy(end = sorted.getOrNull(index + 1)?.begin ?: line.begin + 5_000L)
            }
        }
    }

    private fun toSong(track: TrackMetadata, payload: QQPayload): Song {
        val source = LrcParser.parse(payload.lyric)
        val translations = LrcParser.parse(payload.translation)
        val romas = LrcParser.parse(payload.roma)
        val rich = source.map { line ->
            RichLyricLine().apply {
                begin = line.begin
                end = line.end
                duration = (line.end - line.begin).coerceAtLeast(0L)
                text = line.text
                translation = closest(translations, line.begin)?.text
                roma = closest(romas, line.begin)?.text
            }
        }
        return Song().apply {
            id = track.id
            name = track.title
            artist = track.artist
            duration = track.duration.takeIf { it > 0 } ?: rich.lastOrNull()?.end ?: 0L
            lyrics = rich.takeIf { it.isNotEmpty() }
        }
    }

    private fun closest(lines: List<TimelineLine>, position: Long): TimelineLine? = lines
        .minByOrNull { kotlin.math.abs(it.begin - position) }
        ?.takeIf { kotlin.math.abs(it.begin - position) <= 1_000L }
}
