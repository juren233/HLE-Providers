/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qishui

import android.app.Application
import android.media.MediaMetadata
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodsCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHost
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMetadataCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlaybackStateCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlugin
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

object QishuiPluginEntry : OfficialProviderPlugin {
    private const val TAG = "HLEProvider/Qishui"
    private const val TARGET_PACKAGE = "com.luna.music"
    private const val PROVIDER_PACKAGE =
        "com.juren233.hyperlyricsenhanced.provider.qishui"

    private val installed = AtomicBoolean(false)

    @Volatile
    private var runtime: QishuiRuntime? = null

    override fun install(host: OfficialProviderHost) {
        require(host.packageName == TARGET_PACKAGE) {
            "Unexpected target package: ${host.packageName}"
        }
        host.hookApplication { application ->
            if (Application.getProcessName() != host.packageName) return@hookApplication
            if (!installed.compareAndSet(false, true)) return@hookApplication
            QishuiRuntime(application, host).start()
        }
        host.hookMediaSession(
            playbackStateCallback = OfficialProviderPlaybackStateCallback { state ->
                runtime?.provider?.player?.setPlaybackState(state)
            },
            metadataCallback = OfficialProviderMetadataCallback { metadata ->
                runtime?.postMetadata(metadata)
            },
        )
        Log.i(TAG, "汽水音乐 Provider Hook 已安装")
    }

    private class QishuiRuntime(
        private val application: Application,
        private val host: OfficialProviderHost,
    ) {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val firstLyricHit = AtomicBoolean(false)
        private val firstNextTrackHit = AtomicBoolean(false)
        private val firstQueueFailure = AtomicBoolean(false)
        private val lyricCache = object : LinkedHashMap<String, QishuiLyricPayload>(32, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, QishuiLyricPayload>?,
            ): Boolean = size > MAX_LYRIC_CACHE_SIZE
        }

        private var currentTrack: QishuiTrackMetadata? = null
        private var activeQueueCurrentId: String? = null
        private var queueResolver: QishuiNextTrackResolver? = null
        private var lastPublishedSongKey: String? = null
        private var lastNextTrackFrame: String? = null
        private var lastNextTrackFrameSentAtMs = 0L

        private val requestedQueueCapture = Runnable(::captureQueue)
        private val periodicQueueCapture = object : Runnable {
            override fun run() {
                captureQueue()
                mainHandler.postDelayed(this, NEXT_TRACK_POLL_INTERVAL_MS)
            }
        }

        @Volatile
        var provider: LyriconProvider? = null
            private set

        fun start() {
            provider = LyriconFactory.createProvider(
                context = application,
                providerPackageName = PROVIDER_PACKAGE,
                playerPackageName = host.packageName,
            ).also {
                it.player.setDisplayTranslation(true)
                it.player.setDisplayRoma(false)
                it.register()
            }
            runtime = this
            installLyricsHook()
            resolveQueueTargets()
            val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            Log.i(
                TAG,
                "汽水音乐 Lyricon Provider 已注册: version=${packageInfo.versionName}/" +
                    "${packageInfo.longVersionCode}, process=${Application.getProcessName()}",
            )
        }

        fun postMetadata(metadata: MediaMetadata?) {
            mainHandler.post { onMetadata(metadata) }
        }

        private fun installLyricsHook() {
            host.hookAfterDexMethod(
                application = application,
                query = QishuiHookProfiles.lyricConversionQuery,
                callback = OfficialProviderMethodCallback { _, arguments ->
                    val payload = QishuiPayloadExtractor.extract(arguments) ?: return@OfficialProviderMethodCallback
                    if (BuildConfig.DEBUG && firstLyricHit.compareAndSet(false, true)) {
                        Log.i(
                            TAG,
                            "汽水歌词接口 Hook 首次命中: trackId=${payload.trackId}, " +
                                "type=${payload.type}, translations=${payload.translations.size}",
                        )
                    }
                    mainHandler.post { onLyricsPayload(payload) }
                },
            )
            Log.i(TAG, "汽水歌词接口 Hook 安装流程已启动")
        }

        private fun resolveQueueTargets() {
            host.resolveDexMethods(
                application = application,
                queries = QishuiHookProfiles.queueQueries(),
                callback = OfficialProviderDexMethodsCallback { targets ->
                    mainHandler.post {
                        queueResolver = runCatching {
                            QishuiNextTrackResolver.create(application.classLoader, targets)
                        }.onFailure { error ->
                            Log.w(TAG, "汽水下一首目标校验失败", error)
                        }.getOrNull()
                        if (queueResolver != null) {
                            mainHandler.removeCallbacks(periodicQueueCapture)
                            mainHandler.post(periodicQueueCapture)
                            Log.i(TAG, "汽水下一首队列目标已解析并启用轮询")
                        }
                    }
                },
            )
        }

        private fun onMetadata(value: MediaMetadata?) {
            if (value == null) {
                currentTrack = null
                activeQueueCurrentId = null
                lastPublishedSongKey = null
                publishNextTrack(null, null)
                return
            }
            val track = QishuiTrackMetadata(
                mediaId = value.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                title = value.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = value.getString(MediaMetadata.METADATA_KEY_ARTIST),
                album = value.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = value.getLong(MediaMetadata.METADATA_KEY_DURATION),
            )
            if (track.mediaId.isNullOrBlank() && track.title.isNullOrBlank()) return
            val sameTrack = QishuiTrackIdentity.sameTrack(currentTrack, track)
            currentTrack = track
            if (!sameTrack) {
                activeQueueCurrentId = null
                publishPlaceholder(track)
            } else if (lastPublishedSongKey == null) {
                publishPlaceholder(track)
            }
            publishCachedLyrics()
            requestQueueCapture()
        }

        private fun onLyricsPayload(payload: QishuiLyricPayload) {
            lyricCache[payload.trackId] = payload
            publishCachedLyrics()
        }

        private fun publishCachedLyrics() {
            val metadata = currentTrack ?: return
            val ids = buildSet {
                addAll(QishuiTrackIdentity.candidates(metadata.mediaId))
                activeQueueCurrentId?.takeIf(String::isNotBlank)?.let(::add)
            }
            val payload = ids.asSequence().mapNotNull(lyricCache::get).firstOrNull() ?: return
            val lines = QishuiLyricsParser.parse(payload, metadata.durationMs)
            if (lines.isEmpty()) return
            val songKey = "lyrics:${payload.trackId}:${payload.content.hashCode()}:" +
                payload.translations.hashCode()
            if (songKey == lastPublishedSongKey) return
            val song = Song().apply {
                id = payload.trackId
                name = metadata.title
                artist = metadata.artist
                duration = metadata.durationMs.takeIf { it > 0L } ?: lines.last().end
                lyrics = lines.map { line ->
                    RichLyricLine().apply {
                        begin = line.begin
                        end = line.end
                        duration = (line.end - line.begin).coerceAtLeast(0L)
                        text = line.text
                        translation = line.translation
                        words = line.words.map { word ->
                            LyricWord().apply {
                                begin = word.begin
                                end = word.end
                                duration = (word.end - word.begin).coerceAtLeast(0L)
                                text = word.text
                            }
                        }.takeIf(List<LyricWord>::isNotEmpty)
                    }
                }
            }
            provider?.player?.setSong(song)
            lastPublishedSongKey = songKey
            if (BuildConfig.DEBUG) {
                Log.i(
                    TAG,
                    "汽水歌词已发布: trackId=${payload.trackId}, lines=${lines.size}, " +
                        "words=${lines.sumOf { it.words.size }}",
                )
            }
        }

        private fun publishPlaceholder(track: QishuiTrackMetadata) {
            val id = QishuiTrackIdentity.candidates(track.mediaId).firstOrNull()
                ?: "qishui:${track.title.orEmpty()}:${track.artist.orEmpty()}"
            val songKey = "placeholder:$id:${track.title}:${track.artist}:${track.durationMs}"
            if (songKey == lastPublishedSongKey) return
            provider?.player?.setSong(Song().apply {
                this.id = id
                name = track.title
                artist = track.artist
                duration = track.durationMs.coerceAtLeast(0L)
            })
            lastPublishedSongKey = songKey
        }

        private fun requestQueueCapture() {
            if (queueResolver == null) return
            mainHandler.removeCallbacks(requestedQueueCapture)
            mainHandler.post(requestedQueueCapture)
        }

        private fun captureQueue() {
            require(Looper.myLooper() == Looper.getMainLooper())
            val metadata = currentTrack
            val resolver = queueResolver
            if (metadata == null || resolver == null) {
                publishNextTrack(metadata, null)
                return
            }
            runCatching { resolver.resolve() }
                .onSuccess { rawSnapshot ->
                    val snapshot = QishuiNextTrackBinding.align(metadata, rawSnapshot)
                    activeQueueCurrentId = snapshot?.current?.id
                    publishCachedLyrics()
                    if (
                        BuildConfig.DEBUG &&
                        snapshot != null &&
                        firstNextTrackHit.compareAndSet(false, true)
                    ) {
                        Log.i(
                            TAG,
                            "汽水下一首队列首次命中: current=${snapshot.current.id}, " +
                                "next=${snapshot.next?.id}",
                        )
                    }
                    publishNextTrack(metadata, snapshot)
                }
                .onFailure { error ->
                    if (BuildConfig.DEBUG && firstQueueFailure.compareAndSet(false, true)) {
                        Log.w(TAG, "汽水下一首队列首次采集失败", error)
                    }
                    publishNextTrack(metadata, null)
                }
        }

        private fun publishNextTrack(
            metadata: QishuiTrackMetadata?,
            snapshot: QishuiQueueSnapshot?,
        ) {
            val current = snapshot?.current
            val currentId = current?.id?.takeIf(String::isNotBlank)
                ?: QishuiTrackIdentity.candidates(metadata?.mediaId).firstOrNull().orEmpty()
            val currentTitle = current?.title?.takeIf(String::isNotBlank)
                ?: metadata?.title.orEmpty()
            val currentArtist = current?.artist?.takeIf(String::isNotBlank)
                ?: metadata?.artist.orEmpty()
            val next = snapshot?.next
            val frame = when {
                metadata == null -> OfficialProviderControlProtocol.encodeNextTrackClear()
                next == null || next.title.isBlank() ->
                    OfficialProviderControlProtocol.encodeNextTrackClear(
                        currentId = currentId,
                        currentTitle = currentTitle,
                        currentArtist = currentArtist,
                    )
                else -> OfficialProviderControlProtocol.encodeNextTrack(
                    currentId = currentId,
                    currentTitle = currentTitle,
                    currentArtist = currentArtist,
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
            }
        }
    }

    private object QishuiPayloadExtractor {
        fun extract(arguments: Array<Any?>): QishuiLyricPayload? {
            val netLyric = arguments.getOrNull(0) ?: return null
            val trackId = arguments.getOrNull(1)?.toString()?.trim().orEmpty()
            if (trackId.isBlank()) return null
            val content = netLyric.invokeNoArg("getContent")?.toString().orEmpty()
            if (content.isBlank()) return null
            val typeValue = netLyric.invokeNoArg("getType") ?: return null
            val type = (typeValue as? Enum<*>)?.name ?: typeValue.toString()
            val translations = (netLyric.invokeNoArg("getLangTranslations") as? Map<*, *>)
                ?.values
                ?.mapNotNull { value ->
                    value?.invokeNoArg("getContent")?.toString()?.takeIf(String::isNotBlank)
                }
                .orEmpty()
            return QishuiLyricPayload(
                trackId = trackId,
                type = type,
                content = content,
                translations = translations,
            )
        }

        private fun Any.invokeNoArg(name: String): Any? = runCatching {
            javaClass.getMethod(name).invoke(this)
        }.getOrNull()
    }

    private const val MAX_LYRIC_CACHE_SIZE = 32
    private const val NEXT_TRACK_POLL_INTERVAL_MS = 1_500L
    private const val NEXT_TRACK_HEARTBEAT_MS = 5_000L
}
