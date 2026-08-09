/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

import android.app.Application
import android.media.MediaMetadata
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol
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

object SpotifyPluginEntry : OfficialProviderPlugin {
    private const val TAG = "HLEProvider/Spotify"
    private const val TARGET_PACKAGE = "com.spotify.music"
    private const val PROVIDER_PACKAGE =
        "com.juren233.hyperlyricsenhanced.provider.spotify"

    private val installed = AtomicBoolean(false)

    @Volatile
    private var runtime: SpotifyRuntime? = null

    override fun install(host: OfficialProviderHost) {
        require(host.packageName == TARGET_PACKAGE) {
            "Unexpected target package: ${host.packageName}"
        }
        host.hookApplication { application ->
            if (Application.getProcessName() != host.packageName) return@hookApplication
            if (!installed.compareAndSet(false, true)) return@hookApplication
            SpotifyRuntime(application, host).start()
        }
        host.hookMediaSession(
            playbackStateCallback = OfficialProviderPlaybackStateCallback { state ->
                runtime?.provider?.player?.setPlaybackState(state)
            },
            metadataCallback = OfficialProviderMetadataCallback { metadata ->
                runtime?.postMetadata(metadata)
            },
        )
        Log.i(TAG, "Spotify Provider Hook 已安装")
    }

    private class SpotifyRuntime(
        private val application: Application,
        private val host: OfficialProviderHost,
    ) {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val firstLyricsHit = AtomicBoolean(false)
        private val firstQueueHit = AtomicBoolean(false)
        private val queueExtractionInProgress = ThreadLocal<Boolean>()
        private val lyricsCache = object : LinkedHashMap<String, SpotifyLyricsPayload>(32, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, SpotifyLyricsPayload>?,
            ): Boolean = size > MAX_LYRICS_CACHE_SIZE
        }

        private var currentTrack: SpotifyTrackMetadata? = null
        private var activeQueueCurrentId: String? = null
        private var latestQueueSnapshot: SpotifyQueueSnapshot? = null
        private var lastPublishedSongKey: String? = null
        private var lastNextTrackFrame: String? = null
        private var lastNextTrackFrameSentAtMs = 0L

        private val nextTrackHeartbeat = object : Runnable {
            override fun run() {
                publishAlignedNextTrack()
                mainHandler.postDelayed(this, NEXT_TRACK_HEARTBEAT_MS)
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
            installQueueHook()
            mainHandler.removeCallbacks(nextTrackHeartbeat)
            mainHandler.post(nextTrackHeartbeat)
            val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            Log.i(
                TAG,
                "Spotify Lyricon Provider 已注册: version=${packageInfo.versionName}/" +
                    "${packageInfo.longVersionCode}, process=${Application.getProcessName()}",
            )
        }

        fun postMetadata(metadata: MediaMetadata?) {
            mainHandler.post { onMetadata(metadata) }
        }

        private fun installLyricsHook() {
            val callback = OfficialProviderMethodCallback { _, arguments ->
                    val payload = SpotifyLyricsPayloadExtractor.extract(
                        trackKey = arguments.getOrNull(0),
                        result = arguments.getOrNull(1),
                    ) ?: return@OfficialProviderMethodCallback
                    if (BuildConfig.DEBUG && firstLyricsHit.compareAndSet(false, true)) {
                        Log.i(
                            TAG,
                            "Spotify color-lyrics Hook 首次命中: track=${payload.trackUri}, " +
                                "lines=${payload.lines.size}, translations=${payload.translations.size}, " +
                                "syncType=${payload.syncType}",
                        )
                    }
                    mainHandler.post { onLyricsPayload(payload) }
                }
            runCatching {
                host.hookAfterMethod(SpotifyHookProfiles.lyricsExactTarget, callback)
            }.onSuccess {
                Log.i(TAG, "Spotify color-lyrics 缓存写入 Hook 已按精确描述符安装")
            }.onFailure { error ->
                Log.w(TAG, "Spotify color-lyrics 精确 Hook 失效，进入 DexKit", error)
                host.hookAfterDexMethod(
                    application = application,
                    query = SpotifyHookProfiles.lyricsFallbackQuery,
                    callback = callback,
                )
            }
        }

        private fun installQueueHook() {
            val callback = OfficialProviderMethodCallback { receiver, arguments ->
                if (queueExtractionInProgress.get() == true) return@OfficialProviderMethodCallback
                queueExtractionInProgress.set(true)
                val snapshot = try {
                    SpotifyQueueExtractor.extract(arguments.getOrNull(0) ?: receiver)
                } finally {
                    queueExtractionInProgress.remove()
                } ?: return@OfficialProviderMethodCallback
                if (BuildConfig.DEBUG && firstQueueHit.compareAndSet(false, true)) {
                    Log.i(
                        TAG,
                        "Spotify nextTracks Hook 首次命中: current=${snapshot.current.id}, " +
                            "next=${snapshot.next?.id}",
                    )
                }
                mainHandler.post { onQueueSnapshot(snapshot) }
            }
            host.hookAfterDexMethod(
                application = application,
                query = SpotifyHookProfiles.queueStateQuery,
                callback = callback,
            )
            runCatching {
                host.hookAfterMethod(SpotifyHookProfiles.nextTracksAccessorTarget, callback)
            }.onFailure { error ->
                Log.w(TAG, "Spotify nextTracks 精确辅助 Hook 安装失败，保留 DexKit 主路径", error)
            }
            Log.i(TAG, "Spotify nextTracks 主 Hook 与精确辅助 Hook 安装流程已启动")
        }

        private fun onMetadata(value: MediaMetadata?) {
            if (value == null) {
                currentTrack = null
                activeQueueCurrentId = null
                lastPublishedSongKey = null
                publishNextTrack(null, null)
                return
            }
            val track = SpotifyTrackMetadata(
                mediaId = value.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                title = value.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = value.getString(MediaMetadata.METADATA_KEY_ARTIST),
                album = value.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = value.getLong(MediaMetadata.METADATA_KEY_DURATION),
            )
            if (track.mediaId.isNullOrBlank() && track.title.isNullOrBlank()) return
            val sameTrack = SpotifyTrackIdentity.sameTrack(currentTrack, track)
            currentTrack = track
            if (!sameTrack) {
                activeQueueCurrentId = null
                publishPlaceholder(track)
            } else if (lastPublishedSongKey == null) {
                publishPlaceholder(track)
            }
            publishCachedLyrics()
            publishAlignedNextTrack()
        }

        private fun onLyricsPayload(payload: SpotifyLyricsPayload) {
            SpotifyTrackIdentity.candidates(payload.trackUri).forEach { id -> lyricsCache[id] = payload }
            publishCachedLyrics()
        }

        private fun onQueueSnapshot(snapshot: SpotifyQueueSnapshot) {
            latestQueueSnapshot = snapshot
            val aligned = SpotifyQueueBinding.align(currentTrack, snapshot)
            activeQueueCurrentId = aligned?.current?.id
            publishCachedLyrics()
            publishNextTrack(currentTrack, aligned)
        }

        private fun publishCachedLyrics() {
            val metadata = currentTrack ?: return
            val ids = buildSet {
                addAll(SpotifyTrackIdentity.candidates(metadata.mediaId))
                activeQueueCurrentId?.let { addAll(SpotifyTrackIdentity.candidates(it)) }
            }
            val payload = ids.asSequence().mapNotNull(lyricsCache::get).firstOrNull() ?: return
            val lines = SpotifyLyricsTimelineMapper.map(payload, metadata.durationMs)
            if (lines.isEmpty()) return
            val songKey = "lyrics:${payload.trackUri}:${payload.lines.hashCode()}:" +
                payload.translations.hashCode()
            if (songKey == lastPublishedSongKey) return
            provider?.player?.setSong(Song().apply {
                id = payload.trackUri
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
            })
            lastPublishedSongKey = songKey
            if (BuildConfig.DEBUG) {
                Log.i(
                    TAG,
                    "Spotify 歌词已发布: track=${payload.trackUri}, lines=${lines.size}, " +
                        "syllables=${lines.sumOf { it.words.size }}",
                )
            }
        }

        private fun publishPlaceholder(track: SpotifyTrackMetadata) {
            val id = SpotifyTrackIdentity.candidates(track.mediaId).firstOrNull()
                ?: "spotify:${track.title.orEmpty()}:${track.artist.orEmpty()}"
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

        private fun publishAlignedNextTrack() {
            val metadata = currentTrack
            val snapshot = SpotifyQueueBinding.align(metadata, latestQueueSnapshot)
            activeQueueCurrentId = snapshot?.current?.id
            publishCachedLyrics()
            publishNextTrack(metadata, snapshot)
        }

        private fun publishNextTrack(
            metadata: SpotifyTrackMetadata?,
            snapshot: SpotifyQueueSnapshot?,
        ) {
            val current = snapshot?.current
            val currentId = current?.id?.takeIf(String::isNotBlank)
                ?: metadata?.mediaId.orEmpty()
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

    private const val MAX_LYRICS_CACHE_SIZE = 64
    private const val NEXT_TRACK_HEARTBEAT_MS = 5_000L
}
