/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import android.app.Application
import android.media.MediaMetadata
import android.os.SystemClock
import android.util.Log
import com.juren233.hle.providers.saltplayer.BuildConfig
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderApplicationCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHost
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMetadataCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlaybackStateCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlugin
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object SaltPlayerPluginEntry : OfficialProviderPlugin {
    private const val TAG = "HLEProvider/SaltPlayer"
    private const val TARGET_PACKAGE = "com.salt.music"
    private const val PROVIDER_PACKAGE =
        "com.juren233.hyperlyricsenhanced.provider.salt-player"

    private val initialized = AtomicBoolean(false)

    @Volatile
    private var provider: LyriconProvider? = null

    @Volatile
    private var runtime: SaltPlayerRuntime? = null

    override fun install(host: OfficialProviderHost) {
        require(host.packageName == TARGET_PACKAGE) {
            "Unexpected target package: ${host.packageName}"
        }
        host.hookApplication(OfficialProviderApplicationCallback { application ->
            if (Application.getProcessName() != host.packageName) return@OfficialProviderApplicationCallback
            val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            val profile = SaltPlayerHookProfiles.resolve(
                packageInfo.versionName.orEmpty(),
                packageInfo.longVersionCode,
            )
            if (profile == null) {
                Log.w(
                    TAG,
                    "椒盐音乐版本未匹配，跳过 Provider: " +
                        "version=${packageInfo.versionName}(${packageInfo.longVersionCode})",
                )
                return@OfficialProviderApplicationCallback
            }
            if (!initialized.compareAndSet(false, true)) return@OfficialProviderApplicationCallback

            runCatching {
                host.hookAfterMethod(
                    target = profile.publishLyricsDocument,
                    callback = OfficialProviderMethodCallback { _, arguments ->
                        runtime?.onLyricsDocument(arguments.firstOrNull())
                    },
                )
            }.onFailure { error ->
                initialized.set(false)
                Log.e(
                    TAG,
                    "椒盐音乐完整歌词 Hook 安装失败: " +
                        "version=${profile.versionName}(${profile.versionCode})",
                    error,
                )
                return@OfficialProviderApplicationCallback
            }

            provider = LyriconFactory.createProvider(
                context = application,
                providerPackageName = PROVIDER_PACKAGE,
                playerPackageName = host.packageName,
            ).apply {
                register()
            }
            runtime = provider?.let {
                SaltPlayerRuntime(
                    application = application,
                    provider = it,
                    profile = profile,
                ).apply { startNextTrackCapture() }
            }
            Log.i(
                TAG,
                "椒盐音乐 Provider 已注册: " +
                    "version=${profile.versionName}(${profile.versionCode})",
            )
        })
        host.hookMediaSession(
            playbackStateCallback = OfficialProviderPlaybackStateCallback { state ->
                provider?.player?.setPlaybackState(state)
            },
            metadataCallback = OfficialProviderMetadataCallback { metadata ->
                runtime?.onMetadata(metadata)
            },
        )
        Log.i(TAG, "椒盐音乐 Provider 生命周期 Hook 已安装")
    }

    private class SaltPlayerRuntime(
        private val application: Application,
        private val provider: LyriconProvider,
        private val profile: SaltPlayerHookProfile,
    ) {
        private val nextTrackScheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "HLE-SaltPlayer-NextTrack").apply { isDaemon = true }
            }
        private var metadata = SaltPlayerTrackMetadata()
        private var document: SaltPlayerLyricsDocument? = null
        private var lastSong: io.github.proify.lyricon.lyric.model.Song? = null
        private var nextTrackResolver: SaltPlayerNextTrackResolver? = null
        private var lastNextTrackFrame: String? = null
        private var lastNextTrackFrameSentAtMs = 0L

        fun startNextTrackCapture() {
            nextTrackResolver = runCatching {
                SaltPlayerNextTrackResolver.create(application, profile)
            }.onFailure { error ->
                Log.w(TAG, "椒盐音乐下一首解析器校验失败", error)
            }.getOrNull()
            if (nextTrackResolver == null) {
                Log.w(TAG, "椒盐音乐下一首 Profile 未通过运行时校验")
                return
            }
            nextTrackScheduler.scheduleWithFixedDelay(
                ::captureNextTrack,
                0L,
                NEXT_TRACK_POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
            Log.i(TAG, "椒盐音乐下一首采集已启动")
        }

        @Synchronized
        fun onMetadata(value: MediaMetadata?) {
            val next = SaltPlayerTrackMetadata(
                id = value?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                title = value?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = value?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                album = value?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = value?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            )
            if (metadata.identity != null && next.identity != metadata.identity) {
                document = null
                lastSong = null
            }
            metadata = next
            publishCurrent()
            requestNextTrackCapture()
        }

        @Synchronized
        fun onLyricsDocument(value: Any?) {
            val decoded = SaltPlayerLyricsDecoder.decode(value, profile) ?: return
            document = decoded.takeIf { it.lines.isNotEmpty() }
            publishCurrent()
        }

        @Synchronized
        private fun currentMetadata(): SaltPlayerTrackMetadata = metadata

        private fun publishCurrent() {
            val currentDocument = document
            val song = if (currentDocument == null) {
                SaltPlayerLyricsMapper.placeholder(metadata)
            } else {
                SaltPlayerLyricsMapper.map(currentDocument, metadata)
            }
            val hasTranslation = song.lyrics?.any { !it.translation.isNullOrBlank() } == true
            provider.player.setDisplayTranslation(hasTranslation)
            provider.player.setDisplayRoma(false)
            if (song == lastSong) return
            lastSong = song
            provider.player.setSong(song)
        }

        private fun requestNextTrackCapture() {
            if (nextTrackResolver != null) nextTrackScheduler.execute(::captureNextTrack)
        }

        private fun captureNextTrack() {
            val resolver = nextTrackResolver ?: return
            val current = currentMetadata()
            runCatching { resolver.resolve(current) }
                .onSuccess { next -> publishNextTrack(current, next) }
                .onFailure { error ->
                    if (BuildConfig.DEBUG) Log.w(TAG, "椒盐音乐下一首采集失败", error)
                }
        }

        private fun publishNextTrack(
            current: SaltPlayerTrackMetadata,
            next: SaltPlayerTrackSnapshot?,
        ) {
            val frame = when {
                current.identity == null -> OfficialProviderControlProtocol.encodeNextTrackClear()
                next == null || next.title.isBlank() ->
                    OfficialProviderControlProtocol.encodeNextTrackClear(
                        currentId = current.id.orEmpty(),
                        currentTitle = current.title.orEmpty(),
                        currentArtist = current.artist.orEmpty(),
                    )
                else -> OfficialProviderControlProtocol.encodeNextTrack(
                    currentId = current.id.orEmpty(),
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
            if (provider.player.sendText(frame)) {
                lastNextTrackFrame = frame
                lastNextTrackFrameSentAtMs = now
                if (BuildConfig.DEBUG) {
                    Log.i(
                        TAG,
                        "椒盐下一首控制帧已发送: current=${current.id}, next=${next?.id}",
                    )
                }
            }
        }
    }

    private const val NEXT_TRACK_POLL_INTERVAL_MS = 1_500L
    private const val NEXT_TRACK_HEARTBEAT_MS = 5_000L
}
