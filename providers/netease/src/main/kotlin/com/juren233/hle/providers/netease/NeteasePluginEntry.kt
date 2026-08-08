/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * The MediaSession boundary follows the original LyricProvider implementation,
 * while the host-side Xposed calls remain in HyperLyrics Enhanced.
 */

package com.juren233.hle.providers.netease

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.juren233.hle.providers.netease.BuildConfig
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodsCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHost
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMetadataCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlaybackStateCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlugin
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object NeteasePluginEntry : OfficialProviderPlugin {
    private const val TAG = "HLEProvider/Netease"
    private const val PROVIDER_PACKAGE = "com.juren233.hyperlyricsenhanced.provider.netease"
    private val installed = AtomicBoolean(false)

    override fun install(host: OfficialProviderHost) {
        require(host.packageName == "com.netease.cloudmusic" ||
            host.packageName == "com.hihonor.cloudmusic") {
            "Unsupported Netease package: ${host.packageName}"
        }

        host.hookApplication { application ->
            val process = Application.getProcessName()
            if (process != host.packageName && process != "${host.packageName}:play") return@hookApplication
            if (!installed.compareAndSet(false, true)) return@hookApplication
            NeteaseRuntime(
                application = application,
                playerPackage = host.packageName,
                enableNextTrack = process == host.packageName,
                host = host,
            ).start()
        }

        host.hookMediaSession(
            playbackStateCallback = OfficialProviderPlaybackStateCallback { state ->
                runtime?.provider?.player?.setPlaybackState(state)
            },
            metadataCallback = OfficialProviderMetadataCallback { metadata ->
                runtime?.onMetadata(metadata)
            },
        )
        Log.i(TAG, "网易云音乐 Provider Hook 已安装: package=${host.packageName}")
    }

    @Volatile
    private var runtime: NeteaseRuntime? = null

    private class NeteaseRuntime(
        private val application: Application,
        private val playerPackage: String,
        private val enableNextTrack: Boolean,
        private val host: OfficialProviderHost,
    ) {
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HLE-Netease-Lyrics").apply { isDaemon = true }
        }
        private val metadata = ConcurrentHashMap<Long, TrackMetadata>()
        private val cacheDir = File(application.filesDir, "hle-provider/netease")
        private val nextTrackScheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "HLE-Netease-NextTrack").apply { isDaemon = true }
            }
        private var currentId: Long? = null
        private var lastSong: Song? = null
        private var lastNextTrackFrame: String? = null
        private var lastNextTrackFrameSentAtMs = 0L
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var currentTrack: TrackMetadata? = null

        private var nextTrackResolver: NeteaseNextTrackResolver? = null

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
                applyDisplayPreference(it, application)
                it.register()
            }
            NeteasePluginEntry.runtime = this
            if (enableNextTrack) startNextTrackCapture()
            Log.i(TAG, "网易云音乐 Lyricon Provider 已注册: process=${Application.getProcessName()}")
        }

        fun onMetadata(value: MediaMetadata?) {
            val id = value?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.toLongOrNull()
            if (id == null) {
                currentTrack = null
                requestNextTrackCapture()
                return
            }
            val track = TrackMetadata(
                id = id,
                title = value.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = value.getString(MediaMetadata.METADATA_KEY_ARTIST),
                duration = value.getLong(MediaMetadata.METADATA_KEY_DURATION),
            )
            currentTrack = track
            requestNextTrackCapture()
            metadata[id] = track
            if (currentId == id) return
            currentId = id
            publish(loadCached(track) ?: placeholder(track))
            executor.execute {
                runCatching { NeteaseClient.fetch(id) }
                    .onSuccess { payload ->
                        writeCache(id, payload)
                        if (currentId == id) publish(toSong(track, payload))
                    }
                    .onFailure { error -> Log.w(TAG, "网易云歌词下载失败: id=$id", error) }
            }
        }

        private fun applyDisplayPreference(provider: LyriconProvider, context: Context) {
            val prefs = context.getSharedPreferences("com.netease.cloudmusic.preferences", Context.MODE_PRIVATE)
            applyDisplayPreference(provider, prefs)
            prefs.registerOnSharedPreferenceChangeListener { changed, key ->
                if (key == "showLyricSetting") applyDisplayPreference(provider, changed)
            }
        }

        private fun applyDisplayPreference(provider: LyriconProvider, prefs: SharedPreferences) {
            when (prefs.getInt("showLyricSetting", -1)) {
                0 -> {
                    provider.player.setDisplayTranslation(true)
                    provider.player.setDisplayRoma(false)
                }
                1 -> {
                    provider.player.setDisplayTranslation(false)
                    provider.player.setDisplayRoma(true)
                }
                else -> {
                    provider.player.setDisplayTranslation(false)
                    provider.player.setDisplayRoma(false)
                }
            }
        }

        private fun publish(song: Song) {
            if (lastSong == song) return
            lastSong = song
            provider?.player?.setSong(song)
        }

        private fun startNextTrackCapture() {
            val queries = NeteaseNextTrackResolver.queries(application)
            if (queries == null) {
                Log.w(TAG, "网易云版本未匹配，跳过下一首适配")
                return
            }
            host.resolveDexMethods(
                application = application,
                queries = queries,
                callback = OfficialProviderDexMethodsCallback { targets ->
                    mainHandler.post { finishNextTrackSetup(targets) }
                },
            )
        }

        private fun finishNextTrackSetup(targets: List<OfficialProviderMethodTarget>) {
            nextTrackResolver = runCatching {
                NeteaseNextTrackResolver.create(application, targets)
            }.onFailure { error ->
                Log.w(TAG, "网易云下一首解析器校验失败", error)
            }.getOrNull() ?: return
            nextTrackScheduler.scheduleWithFixedDelay(
                ::captureNextTrack,
                0L,
                NEXT_TRACK_POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
        }

        private fun requestNextTrackCapture() {
            if (nextTrackResolver != null) nextTrackScheduler.execute(::captureNextTrack)
        }

        private fun captureNextTrack() {
            val resolver = nextTrackResolver ?: return
            val current = currentTrack
            runCatching { resolver.resolve() }
                .onSuccess { next -> publishNextTrack(current, next) }
                .onFailure { error ->
                    if (BuildConfig.DEBUG) Log.w(TAG, "网易云下一首采集失败", error)
                }
        }

        private fun publishNextTrack(current: TrackMetadata?, next: NeteaseNextTrackSnapshot?) {
            val frame = when {
                current == null -> OfficialProviderControlProtocol.encodeNextTrackClear()
                next == null || next.title.isBlank() ->
                    OfficialProviderControlProtocol.encodeNextTrackClear(
                        currentId = current.id.toString(),
                        currentTitle = current.title.orEmpty(),
                        currentArtist = current.artist.orEmpty(),
                    )
                else -> OfficialProviderControlProtocol.encodeNextTrack(
                    currentId = current.id.toString(),
                    currentTitle = current.title.orEmpty(),
                    currentArtist = current.artist.orEmpty(),
                    nextId = next.id,
                    nextTitle = next.title,
                    nextArtist = next.artist,
                    nextAlbum = next.album,
                    nextDurationMs = next.durationMs,
                )
            }
            val now = SystemClock.elapsedRealtime()
            if (
                frame == lastNextTrackFrame &&
                now - lastNextTrackFrameSentAtMs < NEXT_TRACK_HEARTBEAT_MS
            ) {
                return
            }
            if (provider?.player?.sendText(frame) == true) {
                lastNextTrackFrame = frame
                lastNextTrackFrameSentAtMs = now
                if (BuildConfig.DEBUG) {
                    Log.i(
                        TAG,
                        "网易云下一首控制帧已发送: current=${current?.id}, next=${next?.id}",
                    )
                }
            }
        }

        private fun placeholder(track: TrackMetadata): Song = Song().apply {
            id = track.id.toString()
            name = track.title
            artist = track.artist
            duration = track.duration
        }

        private fun loadCached(track: TrackMetadata): Song? {
            val file = File(cacheDir, "${track.id}.json")
            if (!file.isFile) return null
            return runCatching { toSong(track, NeteasePayload.fromJson(JSONObject(file.readText()))) }.getOrNull()
        }

        private fun writeCache(id: Long, payload: NeteasePayload) {
            runCatching {
                File(cacheDir, "$id.json").writeText(payload.toJson().toString())
            }.onFailure { Log.w(TAG, "网易云歌词缓存写入失败: id=$id", it) }
        }
    }

    private data class TrackMetadata(
        val id: Long,
        val title: String?,
        val artist: String?,
        val duration: Long,
    )

    private const val NEXT_TRACK_POLL_INTERVAL_MS = 1_500L
    private const val NEXT_TRACK_HEARTBEAT_MS = 5_000L

    private data class NeteasePayload(
        val lrc: String?,
        val translated: String?,
        val yrc: String?,
        val yrcTranslated: String?,
        val roma: String?,
        val pureMusic: Boolean,
    ) {
        fun toJson() = JSONObject().apply {
            putOpt("lrc", lrc)
            putOpt("translated", translated)
            putOpt("yrc", yrc)
            putOpt("yrcTranslated", yrcTranslated)
            putOpt("roma", roma)
            put("pureMusic", pureMusic)
        }

        companion object {
            fun fromJson(json: JSONObject) = NeteasePayload(
                lrc = json.optString("lrc").takeIf(String::isNotBlank),
                translated = json.optString("translated").takeIf(String::isNotBlank),
                yrc = json.optString("yrc").takeIf(String::isNotBlank),
                yrcTranslated = json.optString("yrcTranslated").takeIf(String::isNotBlank),
                roma = json.optString("roma").takeIf(String::isNotBlank),
                pureMusic = json.optBoolean("pureMusic", false),
            )
        }
    }

    private object NeteaseClient {
        private const val URL = "https://interface.music.163.com/eapi/song/lyric/v1"
        private const val KEY = "e82ckenh8dichen8"
        private const val SALT = "nobody%suse%smd5forencrypt"

        fun fetch(id: Long): NeteasePayload {
            val params = JSONObject().apply {
                put("id", id.toString())
                put("cp", false)
                put("lv", 0)
                put("tv", 0)
                put("rv", 0)
                put("yv", 0)
                put("ytv", 0)
                put("yrv", 0)
            }.toString()
            val path = "/eapi/song/lyric/v1"
            val apiPath = path.replace("eapi", "api")
            val digest = md5(String.format(SALT, apiPath, params))
            val text = "$apiPath-36cd479b6b5-$params-36cd479b6b5-$digest"
            val encrypted = aes(text, KEY).uppercase()
            val connection = (java.net.URI(URL).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Referer", "https://music.163.com/")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            return try {
                val body = "params=${URLEncoder.encode(encrypted, "UTF-8")}"
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "网易云 HTTP ${connection.responseCode}"
                }
                fromJson(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
            } finally {
                connection.disconnect()
            }
        }

        private fun fromJson(json: JSONObject): NeteasePayload {
            fun lyric(name: String): String? = json.optJSONObject(name)?.optString("lyric")
                ?.takeIf(String::isNotBlank)
            return NeteasePayload(
                lrc = lyric("lrc"),
                translated = lyric("tlyric"),
                yrc = lyric("yrc"),
                yrcTranslated = lyric("ytlrc"),
                roma = lyric("romalrc"),
                pureMusic = json.optBoolean("pureMusic", false),
            )
        }

        private fun md5(value: String): String = MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        private fun aes(value: String, key: String): String {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES"))
            return cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }

    private data class TimelineLine(
        val begin: Long,
        val end: Long,
        val text: String,
        val words: List<LyricWord> = emptyList(),
    )

    private object TimelineParser {
        private val yrcHeader = Regex("\\[(\\d+),(\\d+)]")
        private val lrcTime = Regex("\\[(\\d{1,3})[:.]([0-5]\\d)(?:[:.]([0-9]{1,3}))?]")

        fun parseYrc(raw: String?): List<TimelineLine> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.lineSequence().mapNotNull { line ->
                val header = yrcHeader.find(line) ?: return@mapNotNull null
                val start = header.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val duration = header.groupValues[2].toLongOrNull() ?: 0L
                val words = NeteaseYrcWordParser.parse(
                    line.substring(header.range.last + 1),
                ).map { segment ->
                    LyricWord().apply {
                        this.begin = segment.begin
                        this.end = segment.begin + segment.duration
                        this.duration = segment.duration
                        this.text = segment.text
                    }
                }
                TimelineLine(start, start + duration, words.joinToString("") { it.text.orEmpty() }, words)
            }.sortedBy(TimelineLine::begin).toList()
        }

        fun parseLrc(raw: String?): List<TimelineLine> {
            if (raw.isNullOrBlank()) return emptyList()
            val result = mutableListOf<TimelineLine>()
            raw.lineSequence().forEach { line ->
                val matches = lrcTime.findAll(line).toList()
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
                    result += TimelineLine(minutes * 60_000 + seconds * 1_000 + millis, 0L, content)
                }
            }
            return result.sortedBy(TimelineLine::begin).mapIndexed { index, line ->
                val next = result.sortedBy(TimelineLine::begin).getOrNull(index + 1)?.begin
                line.copy(end = next ?: line.begin + 5_000L)
            }
        }
    }

    private fun toSong(track: TrackMetadata, payload: NeteasePayload): Song {
        val source = TimelineParser.parseYrc(payload.yrc).ifEmpty { TimelineParser.parseLrc(payload.lrc) }
        val translations = TimelineParser.parseLrc(payload.yrcTranslated).ifEmpty {
            TimelineParser.parseLrc(payload.translated)
        }
        val romas = TimelineParser.parseLrc(payload.roma)
        val rich = source.map { line ->
            RichLyricLine().apply {
                begin = line.begin
                end = line.end
                duration = (line.end - line.begin).coerceAtLeast(0L)
                text = line.text
                words = line.words.takeIf(List<LyricWord>::isNotEmpty)
                translation = closest(translations, line.begin)?.text
                roma = closest(romas, line.begin)?.text
            }
        }
        return Song().apply {
            id = track.id.toString()
            name = track.title
            artist = track.artist
            duration = track.duration.takeIf { it > 0 } ?: rich.lastOrNull()?.end ?: 0L
            lyrics = rich.takeIf { it.isNotEmpty() && !payload.pureMusic }
        }
    }

    private fun closest(lines: List<TimelineLine>, position: Long): TimelineLine? = lines
        .minByOrNull { kotlin.math.abs(it.begin - position) }
        ?.takeIf { kotlin.math.abs(it.begin - position) <= 1_000L }
}
