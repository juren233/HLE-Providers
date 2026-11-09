/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * QQ Music mobile exposes lyric playback metadata from QQPlayerService while
 * QQ Music HD keeps both lyric and queue state in its main process. Both apps
 * share the same lyric backend and Pack, with package-specific runtime routing.
 */

package com.juren233.hle.providers.qqmusic

import android.app.Application
import android.app.Notification
import android.content.Context
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.juren233.hle.providers.locallyrics.LocalAudioLibrary
import com.juren233.hle.providers.locallyrics.LocalAudioQuery
import com.juren233.hle.providers.locallyrics.LocalLrcParser
import com.juren233.hle.providers.locallyrics.LocalLyricsDocument
import com.juren233.hle.providers.locallyrics.LocalLyricsLineMapper
import com.juren233.hle.providers.qqmusic.BuildConfig
import com.juren233.hyperlyricsenhanced.provider.OfficialCoreHostGuard
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodsCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHost
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMetadataCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlaybackStateCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlugin
import com.juren233.hle.providers.qqmusic.qrc.QqQrcDecrypter
import com.juren233.hle.providers.qqmusic.qrc.QqQrcLine
import com.juren233.hle.providers.qqmusic.qrc.QqQrcParser
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import org.json.JSONObject
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object QQMusicPluginEntry : OfficialProviderPlugin {
    private const val TAG = "HLEProvider/QQMusic"
    private const val PROVIDER_PACKAGE = "com.juren233.hyperlyricsenhanced.provider.qqmusic"

    /** 小米音乐媒体通知 extras 里 mediaFocusParam JSON 的键（MiuiSystemUI 反编译确认）。 */
    private const val NOTIFICATION_FOCUS_PARAM_KEY = "miui.focus.param.media"

    /**
     * 随 Song 元数据透传 MediaSession MEDIA_ID 的键（与核心侧
     * LyricMetadataKeys.SESSION_MEDIA_ID 配对），SystemUI 时间轴据此做
     * 包名+mediaId 身份匹配，绕开车载歌词对标题/歌手的污染。
     */
    private const val SESSION_MEDIA_ID_METADATA_KEY = "hleSessionMediaId"
    /** 与核心仓库 LyricMetadataKeys.LYRICS_UNAVAILABLE 取值一致。 */
    private const val LYRICS_UNAVAILABLE_METADATA_KEY = "hleLyricsUnavailable"
    private val installed = AtomicBoolean(false)

    @Volatile
    private var runtime: QQRuntime? = null

    @Volatile
    private var nextTrackRuntime: QQNextTrackRuntime? = null

    @Volatile
    private var bufferRuntime: QQBufferRuntime? = null

    override fun install(host: OfficialProviderHost) {
        if (OfficialCoreHostGuard.isForeignCoreHost(host)) return
        require(QQMusicRuntimePlan.supports(host.packageName)) {
            "Unsupported QQ Music package: ${host.packageName}"
        }
        host.hookApplication { application ->
            val processName = Application.getProcessName()
            val features = QQMusicRuntimePlan.resolve(host.packageName, processName)
            if (features.isEmpty()) return@hookApplication
            if (!installed.compareAndSet(false, true)) return@hookApplication
            var lyricRuntime: QQRuntime? = null
            if (QQMusicRuntimeFeature.LYRICS in features) {
                lyricRuntime = QQRuntime(application, host, host.packageName).also { it.start() }
            }
            if (QQMusicRuntimeFeature.BUFFERING_STATE in features && lyricRuntime != null) {
                QQBufferRuntime(application, host, lyricRuntime).also {
                    it.start()
                    bufferRuntime = it
                }
            }
            if (QQMusicRuntimeFeature.NEXT_TRACK in features) {
                QQNextTrackRuntime(application, host.packageName, host).start()
            }
        }
        host.hookMediaSession(
            playbackStateCallback = OfficialProviderPlaybackStateCallback { state ->
                OfficialCoreHostGuard.onPlaybackStateChanged(state)
                if (OfficialCoreHostGuard.isDeactivated()) return@OfficialProviderPlaybackStateCallback
                runtime?.onPlaybackState(state)
            },
            metadataCallback = OfficialProviderMetadataCallback { metadata ->
                if (OfficialCoreHostGuard.isDeactivated()) return@OfficialProviderMetadataCallback
                runtime?.onMetadata(metadata)
            },
        )
        Log.i(TAG, "QQ 音乐 Provider Hook 已安装: package=${host.packageName}")
    }

    private class QQRuntime(
        private val application: Application,
        private val host: OfficialProviderHost,
        private val playerPackage: String,
    ) {
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HLE-QQMusic-Lyrics").apply { isDaemon = true }
        }

        /**
         * 本地文件歌词单独一个线程：不排在上一首的在线换算/下载后面，切歌后能立刻读文件。
         * 读不到内嵌歌词时再把在线取词任务交给 [executor]。
         */
        private val localLyricsExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HLE-QQMusic-LocalLyrics").apply { isDaemon = true }
        }
        private val localAudioLibrary = LocalAudioLibrary(application.contentResolver)
        private val trackCoordinator = QQMusicLyricTrackCoordinator(playerPackage)
        private val bufferCoordinator = QQMusicBufferStateCoordinator()
        private val carLyricsPolicy = QQMusicCarLyricsMetadataPolicy()
        private val legacyCacheDir = File(application.filesDir, "hle-provider/qqmusic")
        private val cacheDir = File(legacyCacheDir, "v2")
        private var activeLoadKey: String? = null
        private var lastSong: Song? = null

        /**
         * 最近一次成功带上歌词发布的歌曲身份（归一化标题|歌手）。songmid 路径与
         * 数字 ID 路径会先后为同一首歌发起加载，第二次加载若先发占位会把已上屏
         * 的歌词抹掉再恢复（真机表现为歌词进度闪动）；身份一致且已带歌词时直接跳过。
         */
        private var publishedLyricIdentity: String? = null

        /** 当前上屏歌词所用的在线歌曲 ID；QQ 关联 ID 与它不同时才替换歌词。 */
        private var publishedSongId: String? = null

        /** 已上屏歌词来自 QQ 关联 ID（含其缓存）：按歌名匹配的结果不能覆盖它。 */
        private var publishedAppLinked = false

        /** 已上屏歌词来自音乐文件内嵌歌词：同一首歌不再换成在线歌词。 */
        private var publishedEmbedded = false
        private var loadGeneration = 0L

        private fun identityKeyOf(track: QQMusicLyricTrack): String =
            track.publishedIdentityKey(playerPackage)
        private var lastMetadataId: String? = null
        private var lastShareSongMid: String? = null

        /**
         * 最近一次会话元数据的 MEDIA_ID。下一首控制帧的当前曲身份优先用它：
         * SystemUI 按同一 MediaSession 的 MEDIA_ID 查询下一首缓存，SDK 队列内部
         * long id 与其结构性不等（XIAOMI-MUSIC-NEXT-PREVIEW-IDENTITY-001），
         * 车载歌词污染标题后 text 别名也无兜底，帧身份必须与会话同源。
         */
        @Volatile
        var latestSessionMediaId: String? = null
            private set
        private val diagLogger = ThrottledLogger()
        private val tickerSuccessLogged = AtomicBoolean(false)
        private var latestPlaybackState: PlaybackState? = null

        // 小米音乐 QQMusicCar 会话的 PlaybackState position 长期上报 0/冻结/滞后，
        // 不能作为进度锚点；SDK 的 getCurrTimeExact()（binder 到播放服务）是 App 内
        // 同源的真实进度，按 1s 轮询换算成权威 PlaybackState 发布给 Core。
        // 1.0.25 真机教训：构造时 SDK 未就绪 getInstance() 返回 null 且被永久缓存
        // （hasPlayer=false），进度发布从此失效；改为懒获取 + 5s 节流重试。
        @Volatile
        private var sdkPlayerInstance: Any? = null

        private var lastSdkAcquireAttemptAtMs = 0L

        private val miuiProgressTicker: ScheduledExecutorService? = if (
            playerPackage == QQMusicRuntimePlan.MIUI_PACKAGE
        ) {
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "HLE-QQMusic-MiuiProgress").apply { isDaemon = true }
            }
        } else {
            null
        }
        private var lastTickState: Int = Int.MIN_VALUE
        private var lastTickPosition: Long = Long.MIN_VALUE

        init {
            miuiProgressTicker?.scheduleWithFixedDelay(
                { tickSdkProgress() },
                1_000L,
                1_000L,
                TimeUnit.MILLISECONDS,
            )
        }

        private val SDK_MUSIC_PLAYER_CLASS = "com.tencent.qqmusicsdk.protocol.MusicPlayer"
        private val SDK_GET_INSTANCE = "getInstance"
        private val SDK_GET_CURR_TIME = "getCurrTimeExact"

        @Volatile
        var provider: LyriconProvider? = null
            private set

        fun start() {
            cacheDir.mkdirs()
            executor.execute { purgeLegacyCache() }
            provider = LyriconFactory.createProvider(
                context = application,
                providerPackageName = PROVIDER_PACKAGE,
                playerPackageName = playerPackage,
            ).also {
                it.register()
                refreshDisplayPreference(it)
            }
            runtime = this
            if (playerPackage == QQMusicRuntimePlan.MIUI_PACKAGE) {
                registerNotificationHooks()
            }
            if (playerPackage == QQMusicRuntimePlan.MOBILE_PACKAGE) {
                runCatching {
                    QQMusicQueueHintChannel.register(application) { snapshot ->
                        if (!OfficialCoreHostGuard.isDeactivated()) onQueueSnapshot(snapshot)
                    }
                }.onFailure { Log.w(TAG, "QQ 队列快照接收注册失败", it) }
            }
            Log.i(TAG, "QQ 音乐 Lyricon Provider 已注册: process=${Application.getProcessName()}")
        }

        /**
         * mediaFocusParam（干净 songmid + 真名歌手）由小米音乐写进媒体通知 extras
         * （key=miui.focus.param.media；MiuiSystemUI LegacyMediaDataManagerImpl 反编译
         * 确认），MediaSession 元数据里没有。hook NotificationManager.notify 两个
         * 重载在通知发布时捕获；串曲防护由 onMetadata 侧的同曲校验承担。
         */
        private fun registerNotificationHooks() {
            val targets = listOf(
                OfficialProviderMethodTarget(
                    className = "android.app.NotificationManager",
                    methodName = "notify",
                    parameterTypeNames = listOf("java.lang.String", "int", "android.app.Notification"),
                    returnTypeName = "void",
                    isStatic = false,
                ),
                OfficialProviderMethodTarget(
                    className = "android.app.NotificationManager",
                    methodName = "notify",
                    parameterTypeNames = listOf("int", "android.app.Notification"),
                    returnTypeName = "void",
                    isStatic = false,
                ),
            )
            targets.forEach { target ->
                runCatching {
                    host.hookAfterMethod(target) { _, arguments ->
                        val notification = arguments.lastOrNull() as? Notification
                        onNotificationPosted(notification)
                    }
                }.onFailure { error ->
                    Log.w(TAG, "QQ 媒体通知 Hook 注册失败: ${target.parameterTypeNames}", error)
                }
            }
        }

        @Volatile
        private var notificationShareIdentity: QQShareSongIdentity? = null

        private fun onNotificationPosted(notification: Notification?) {
            val json = runCatching {
                notification?.extras?.getString(NOTIFICATION_FOCUS_PARAM_KEY)
            }.getOrNull() ?: return
            val identity = extractShareSongIdentity(listOf(json)) ?: return
            val previous = notificationShareIdentity
            notificationShareIdentity = identity
            if (previous?.songMid != identity.songMid) {
                Log.i(
                    TAG,
                    "QQ 媒体通知 shareData: songMid=${identity.songMid}, " +
                        "title=${identity.title}, artist=${identity.artist}",
                )
            }
        }

        /**
         * 通知里的 shareData 属于「发布通知那一刻的歌」；元数据回调先于新通知到达时
         * 直接采纳会把上一首的 songmid 贴到新歌上。用归一化包含校验（标题必中，
         * 歌手可选）：车载歌词把元数据改写成「歌名-歌手」粘连或歌词行时，
         * 真名歌名/歌手仍是元数据的子串；漂移歌词行则校验失败、暂不采纳。
         */
        private fun matchesCurrentMetadata(
            identity: QQShareSongIdentity,
            rawTitle: String?,
            rawArtist: String?,
        ): Boolean {
            val title = identity.title?.let(QQMusicSongMidResolver::normalizeForMatch).orEmpty()
            if (title.length < 2) return false
            val haystack = QQMusicSongMidResolver.normalizeForMatch(
                rawTitle.orEmpty() + " " + rawArtist.orEmpty(),
            )
            if (!haystack.contains(title)) return false
            val artist = identity.artist?.let(QQMusicSongMidResolver::normalizeForMatch).orEmpty()
            return artist.isEmpty() || haystack.contains(artist)
        }

        @Synchronized
        fun onMetadata(value: MediaMetadata?) {
            val id = value?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.trim()
                ?.takeIf(String::isNotEmpty) ?: return
            refreshDisplayPreference(provider)
            val rawTitle = value.getString(MediaMetadata.METADATA_KEY_TITLE)
            val rawArtist = value.getString(MediaMetadata.METADATA_KEY_ARTIST)
            val extrasBundle = if (playerPackage == QQMusicRuntimePlan.MIUI_PACKAGE) {
                metadataExtras(value)
            } else {
                null
            }
            // MEDIA_ID 不是 songmid（4.44.0.9 真机证伪）。首选随同一次 setMetadata
            // 写入的元数据 Bundle；媒体通知 extras 的 mediaFocusParam（干净 songmid
            // +真名歌手）按「与当前元数据同曲」校验通过后采纳，防止串曲。
            val share = when {
                playerPackage != QQMusicRuntimePlan.MIUI_PACKAGE -> null
                else -> extrasBundle?.let(::identityFromBundle)
                    ?: notificationShareIdentity?.takeIf {
                        matchesCurrentMetadata(it, rawTitle, rawArtist)
                    }
            }
            if (lastMetadataId != id || lastShareSongMid != share?.songMid) {
                lastMetadataId = id
                lastShareSongMid = share?.songMid
                bufferCoordinator.reset()
                Log.i(
                    TAG,
                    "QQ 会话元数据更新: id=$id, shareSongMid=${share?.songMid}, " +
                        "extrasKeys=${extrasBundle?.keySet()}, " +
                        "title=$rawTitle, artist=$rawArtist",
                )
            }
            diagLogger.log(TAG, "meta_heartbeat", 15_000L) {
                "QQ 会话元数据到达: id=$id, title=$rawTitle"
            }
            latestSessionMediaId = id
            // 小米音乐与 QQ 音乐本体都存在车载歌词改写元数据的形态，
            // 策略只在拿到正向污染证据时才改写
            val normalized = carLyricsPolicy.normalize(id, rawTitle, rawArtist)
            val track = QQMusicLyricTrack(
                id = share?.songMid ?: id,
                title = share?.title?.takeIf(String::isNotBlank) ?: normalized.title,
                artist = share?.artist?.takeIf(String::isNotBlank) ?: normalized.artist,
                duration = value.getLong(MediaMetadata.METADATA_KEY_DURATION),
                sessionMediaId = id,
            )
            applyTrackDecision(trackCoordinator.onMetadata(track))
        }

        /**
         * MediaMetadata 没有 public extras 访问器（android.media.MediaMetadata
         * 无 getExtras）；AOSP 隐藏方法 getBundle() 返回底层 Bundle，MIUI 系统侧
         * 的 mediaFocusParam 即随元数据 Bundle 下发。反射失败时返回 null，
         * 仅失去 extras 通道，不影响主流程。Method 句柄进程内缓存（元数据
         * 回调逐行到达，避免每秒getMethod查找）。
         */
        private val metadataGetBundleMethod: java.lang.reflect.Method? = runCatching {
            MediaMetadata::class.java.getMethod("getBundle")
        }.getOrNull()

        private fun metadataExtras(metadata: MediaMetadata): android.os.Bundle? = runCatching {
            metadataGetBundleMethod?.invoke(metadata) as? android.os.Bundle
        }.getOrNull()

        private fun identityFromBundle(bundle: android.os.Bundle): QQShareSongIdentity? {
            val values = runCatching {
                bundle.keySet().mapNotNull { key -> bundle.getString(key) }
            }.getOrDefault(emptyList())
            return extractShareSongIdentity(values)
        }

        private fun shareIdentityOf(metadata: MediaMetadata): QQShareSongIdentity? =
            metadataExtras(metadata)?.let(::identityFromBundle)

        /**
         * 发现通道（本轮只记日志不改行为）：若 mediaFocusParam 不在元数据 Bundle
         * 而在 PlaybackState extras，则下一轮据此把提取来源迁移到此处。
         */
        private fun diagPlaybackStateExtras(state: PlaybackState?) {
            val extras = state?.extras ?: return
            val identity = runCatching {
                extractShareSongIdentity(extras.keySet().mapNotNull { key -> extras.getString(key) })
            }.getOrNull() ?: return
            diagLogger.log(TAG, "state_share", 30_000L) {
                "QQ PlaybackState extras 携带 shareData: songMid=${identity.songMid}, " +
                    "title=${identity.title}, artist=${identity.artist}"
            }
        }

        private fun acquireSdkPlayer(): Any? {
            sdkPlayerInstance?.let { return it }
            val now = SystemClock.elapsedRealtime()
            if (now - lastSdkAcquireAttemptAtMs < 5_000L) return null
            lastSdkAcquireAttemptAtMs = now
            val acquired = runCatching {
                application.classLoader.loadClass(SDK_MUSIC_PLAYER_CLASS)
                    .getMethod(SDK_GET_INSTANCE)
                    .invoke(null)
            }.getOrNull()
            if (acquired != null) {
                sdkPlayerInstance = acquired
                Log.i(TAG, "QQ SDK Player 实例就绪(懒获取): player=$acquired")
            }
            return acquired
        }

        @Synchronized
        private fun tickSdkProgress() {
            if (playerPackage != QQMusicRuntimePlan.MIUI_PACKAGE) return
            val session = latestPlaybackState ?: return
            val state = session.state
            if (state != PlaybackState.STATE_PLAYING && state != PlaybackState.STATE_PAUSED) return
            val player = acquireSdkPlayer()
            val position = runCatching {
                (player?.javaClass?.getMethod(SDK_GET_CURR_TIME)?.invoke(player) as? Number)
                    ?.toLong()
            }.getOrNull()
            if (position == null || position < 0L) {
                diagLogger.log(TAG, "sdk_progress_fail", 30_000L) {
                    "QQ SDK 进度读取失败: hasPlayer=${sdkPlayerInstance != null}, state=$state"
                }
                return
            }
            if (tickerSuccessLogged.compareAndSet(false, true)) {
                Log.i(TAG, "QQ SDK 进度读取成功: position=$position")
            }
            if (state == lastTickState && position == lastTickPosition) return
            lastTickState = state
            lastTickPosition = position
            val playbackState = PlaybackState.Builder()
                .setState(
                    state,
                    position,
                    session.playbackSpeed.coerceAtLeast(0f),
                    SystemClock.elapsedRealtime(),
                )
                .build()
            provider?.player?.setPlaybackState(playbackState)
        }

        @Synchronized
        fun onPlaybackState(state: PlaybackState?) {
            latestPlaybackState = state
            if (playerPackage == QQMusicRuntimePlan.MIUI_PACKAGE) {
                // QQMusicCar 会话 position 长期上报 0/冻结，缓冲合成状态不适用；
                // 进度统一由 SDK 真实位置 1s 轮询发布，会话仅提供播放/暂停态
                diagPlaybackStateExtras(state)
                tickSdkProgress()
                return
            }
            when (val decision = bufferCoordinator.onPlaybackState(state?.toSnapshot())) {
                QQMusicPlaybackDecision.Forward -> provider?.player?.setPlaybackState(state)
                QQMusicPlaybackDecision.Ignore -> Unit
                is QQMusicPlaybackDecision.Publish -> publishSyntheticPlaybackState(
                    decision.state,
                    reason = "media_session_while_buffering",
                )
            }
        }

        @Synchronized
        fun onBufferStarted() {
            when (val decision = bufferCoordinator.onBufferStarted(SystemClock.elapsedRealtime())) {
                is QQMusicPlaybackDecision.Publish -> publishSyntheticPlaybackState(
                    decision.state,
                    reason = "buffer_started",
                )
                QQMusicPlaybackDecision.Forward,
                QQMusicPlaybackDecision.Ignore,
                -> Unit
            }
        }

        @Synchronized
        fun onBufferEnded() {
            when (val decision = bufferCoordinator.onBufferEnded(SystemClock.elapsedRealtime())) {
                is QQMusicPlaybackDecision.Publish -> publishSyntheticPlaybackState(
                    decision.state,
                    reason = "buffer_ended",
                )
                QQMusicPlaybackDecision.Forward,
                QQMusicPlaybackDecision.Ignore,
                -> Unit
            }
        }

        private fun PlaybackState.toSnapshot() = QQMusicPlaybackSnapshot(
            state = state,
            position = position,
            updatedAtMs = lastPositionUpdateTime,
            speed = playbackSpeed,
        )

        private fun publishSyntheticPlaybackState(
            snapshot: QQMusicPlaybackSnapshot,
            reason: String,
        ) {
            val builder = latestPlaybackState?.let(PlaybackState::Builder) ?: PlaybackState.Builder()
            val state = builder.setState(
                snapshot.state,
                snapshot.position,
                snapshot.speed,
                snapshot.updatedAtMs,
            ).build()
            val result = provider?.player?.setPlaybackState(state)
            if (BuildConfig.DEBUG) {
                Log.i(
                    TAG,
                    "QQ 缓冲状态已发布: reason=$reason state=${snapshot.state}, " +
                        "position=${snapshot.position}, updatedAt=${snapshot.updatedAtMs}, result=$result",
                )
            }
        }

        @Synchronized
        fun onQueueSnapshot(snapshot: QQMusicQueueSnapshot?) {
            val decision = trackCoordinator.onQueueSnapshot(snapshot)
            if (BuildConfig.DEBUG && playerPackage == QQMusicRuntimePlan.MOBILE_PACKAGE && snapshot != null) {
                logQueueSnapshotMatch(snapshot, decision)
            }
            applyTrackDecision(decision)
        }

        private var lastQueueSnapshotDiagKey: String? = null

        /** Debug：手机版快照与会话逐字段对照，只在内容或判定变化时输出。 */
        private fun logQueueSnapshotMatch(
            snapshot: QQMusicQueueSnapshot,
            decision: QQMusicLyricTrackDecision,
        ) {
            val media = trackCoordinator.diagnosticMediaTrack
            fun QQMusicTrackSnapshot.describe() = "id=$id title=「$title」 artist=「$artist」"
            val message = "QQ 快照对照: process=${Application.getProcessName()} " +
                "session=[id=${media?.id} title=「${media?.title}」 artist=「${media?.artist}」] " +
                "current=[${snapshot.current.describe()}] " +
                "next=[${snapshot.next?.describe() ?: "none"}] " +
                "decision=${decision::class.java.simpleName}"
            if (message == lastQueueSnapshotDiagKey) return
            lastQueueSnapshotDiagKey = message
            Log.i(TAG, message)
        }

        private fun applyTrackDecision(decision: QQMusicLyricTrackDecision) {
            when (decision) {
                is QQMusicLyricTrackDecision.AwaitingVerifiedId -> {
                    activeLoadKey = null
                    publishedLyricIdentity = null
                    publishedSongId = null
                    publishedAppLinked = false
                    publishedEmbedded = false
                    if (BuildConfig.DEBUG && playerPackage == QQMusicRuntimePlan.HD_PACKAGE) {
                        Log.i(
                            TAG,
                            "QQ HD 歌词等待真实歌曲 ID: " +
                                "mediaId=${decision.track.id}, title=${decision.track.title}",
                        )
                    }
                    publish(placeholder(decision.track))
                }
                is QQMusicLyricTrackDecision.Load -> {
                    if (BuildConfig.DEBUG && playerPackage == QQMusicRuntimePlan.HD_PACKAGE) {
                        Log.i(
                            TAG,
                            "QQ HD 歌词使用 SongInfo 歌曲 ID: " +
                                "songId=${decision.track.id}, title=${decision.track.title}",
                        )
                    }
                    load(decision.track)
                }
                QQMusicLyricTrackDecision.Unchanged -> Unit
            }
        }

        private fun load(track: QQMusicLyricTrack) {
            val identity = identityKeyOf(track)
            val sameSongShowing = publishedLyricIdentity == identity
            // 本地歌优先用音乐文件内嵌歌词：已上屏的是内嵌歌词时，快照候选升级也不替换
            if (sameSongShowing && publishedEmbedded) return
            // 同一首歌已带歌词上屏：只有快照候选 ID 都不是上屏所用的在线 ID 时才尝试升级，
            // 且不先发占位/缓存（避免歌词闪掉），换算或下载失败也保留现有歌词。
            val linkedIds = track.appLinkedSongIds.map(QQMusicSongMidResolver::sanitizeNumericSongId)
            if (sameSongShowing && (linkedIds.isEmpty() || publishedSongId in linkedIds)) {
                return
            }
            val upgrade = sameSongShowing
            // 每次加载一个新代次：同一首歌可能先按歌名匹配、再按 QQ 关联 ID 各起一次任务，
            // 只有最新一次的结果能发布
            val loadKey = "${track.loadKey()}\u0000${++loadGeneration}"
            activeLoadKey = loadKey
            if (!upgrade) {
                publishedLyricIdentity = null
                publishedSongId = null
                publishedAppLinked = false
                publishedEmbedded = false
                val cached = loadCached(track)
                if (cached != null) {
                    val published = publish(cached.song)
                    if (published && !cached.song.lyrics.isNullOrEmpty()) {
                        publishedLyricIdentity = identity
                        publishedSongId = cached.songId
                        publishedAppLinked = cached.appLinked
                    }
                } else {
                    publish(placeholder(track))
                }
            }
            val onlineTask: () -> Unit = execute@{
                // 1.0.25 真机教训：单线程执行器里任何一个任务卡死（HTTP/DNS 无限
                // 等待、解析死循环、发布 binder 挂起）都会让后续所有歌曲的换算与
                // 发布永远排队，且无任何失败日志。以下阶段日志（release 可见、按
                // 歌曲次数输出）用于在真机日志里直接定位卡住的阶段。
                Log.i(TAG, "QQ 歌词任务开始: id=${track.id}, title=${track.title}")
                val resolution = runCatching {
                    if (upgrade) {
                        // 升级只认核对通过的快照候选：候选都不符（例如 QQ 已切到下一首、会话
                        // 元数据还没到）时不能退回按歌名匹配，否则会把已上屏的正确版本换掉
                        QQMusicSongMidResolver.resolveAppLinkedCandidates(
                            sessionId = track.id,
                            candidates = track.appLinkedSongIds,
                            title = track.title,
                            artist = track.artist,
                        ) ?: run {
                            Log.i(TAG, "QQ 歌词任务结束(快照候选无同曲，保留现有歌词): id=${track.id}")
                            return@execute
                        }
                    } else {
                        QQMusicSongMidResolver.resolve(
                            id = track.id,
                            title = track.title,
                            artist = track.artist,
                            durationMs = track.duration,
                            trustedId = track.idTrusted,
                            appLinkedSongIds = track.appLinkedSongIds,
                        )
                    }
                }
                    .onFailure { error ->
                        Log.w(TAG, "QQ 歌曲 ID 换算失败: id=${track.id}", error)
                        Log.i(TAG, "QQ 歌词任务结束(换算失败): id=${track.id}")
                    }
                    .getOrNull()
                    ?: run {
                        if (!upgrade) publishUnavailable(track, loadKey)
                        return@execute
                    }
                Log.i(TAG, "QQ 换算完成: id=${track.id}, songId=${resolution.numericSongId}")
                // 切歌瞬间快照可能还停在上一首，候选都核对不过而退回按歌名匹配；此时若已上屏的是
                // QQ 关联 ID 核对过的版本（通常来自缓存），不能用按歌名匹配的结果把它覆盖
                // （2026-11-09 16:41 真机：缓存 105345627 被按歌名匹配的 200672361 覆盖并固定下来）
                if (!resolution.appLinked) {
                    val keepLinked = synchronized(this@QQRuntime) {
                        activeLoadKey == loadKey && publishedAppLinked &&
                            publishedLyricIdentity == identity
                    }
                    if (keepLinked) {
                        Log.i(
                            TAG,
                            "QQ 已上屏 QQ 关联版本，不用按歌名匹配结果覆盖: id=${track.id}, " +
                                "kept=$publishedSongId, matched=${resolution.numericSongId}",
                        )
                        return@execute
                    }
                }
                if (upgrade && resolution.numericSongId == publishedSongId) {
                    Log.i(TAG, "QQ 关联 ID 与已上屏版本一致，不重新下载: id=${track.id}")
                    return@execute
                }
                runCatching { fetchWithAlternatives(track, resolution) }
                    .onSuccess { (chosen, payload) ->
                        Log.i(
                            TAG,
                            "QQ 歌词下载完成: id=${track.id}, songId=${chosen.numericSongId}, " +
                                "lyricChars=${payload.lyric?.length ?: 0}",
                        )
                        val enriched = payload.copy(
                            name = chosen.songName,
                            singer = chosen.singerName,
                            songId = chosen.numericSongId,
                            appLinked = chosen.appLinked,
                        )
                        val song = toSong(track, enriched)
                        Log.i(TAG, "QQ 歌词解析完成: id=${track.id}, lines=${song.lyrics?.size ?: 0}")
                        if (song.lyrics.isNullOrEmpty()) {
                            Log.w(
                                TAG,
                                "QQ 歌词内容为空，不发布以免覆盖已有歌词: id=${track.id} " +
                                    "songId=${chosen.numericSongId}",
                            )
                            Log.i(TAG, "QQ 歌词任务结束(空歌词): id=${track.id}")
                            if (!upgrade) publishUnavailable(track, loadKey)
                            return@onSuccess
                        }
                        // 未校验的透传结果（来源不可信且无权威 name）没有身份证明，不落缓存，
                        // 否则限流窗口内放行的内容会按 track.id 永久回放（issue #62）
                        if (track.idTrusted || chosen.songName != null) {
                            writeCache(track, enriched)
                        }
                        synchronized(this@QQRuntime) {
                            if (activeLoadKey == loadKey) {
                                val published = publish(song)
                                Log.i(
                                    TAG,
                                    "QQ 已发布: id=${track.id}, lines=${song.lyrics?.size ?: 0}, " +
                                        "result=$published",
                                )
                                if (published) {
                                    publishedLyricIdentity = identityKeyOf(track)
                                    publishedSongId = chosen.numericSongId
                                    publishedAppLinked = chosen.appLinked
                                } else {
                                    Log.i(TAG, "QQ 发布未成功，保留重试机会: id=${track.id}")
                                }
                            } else {
                                Log.i(TAG, "QQ 发布跳过(轨道已切换): id=${track.id}")
                            }
                        }
                    }
                    .onFailure { error ->
                        Log.w(TAG, "QQ 歌词下载失败: id=${track.id} songId=${resolution.numericSongId}", error)
                        Log.i(TAG, "QQ 歌词任务结束(下载失败): id=${track.id}")
                        if (!upgrade) publishUnavailable(track, loadKey)
                    }
            }
            if (upgrade) {
                executor.execute(onlineTask)
                return
            }
            localLyricsExecutor.execute {
                if (!loadEmbeddedLyrics(track, identity, loadKey)) executor.execute(onlineTask)
            }
        }

        /**
         * 本地歌优先用音乐文件内嵌的歌词：按歌名 + 时长在系统媒体库里找到唯一对应的文件，
         * 读出标签里的歌词并上屏，返回 true，本次不再走在线换算。没有对应文件、文件没有内嵌
         * 歌词或解析不出带时间的行时返回 false，交给在线取词。
         */
        private fun loadEmbeddedLyrics(
            track: QQMusicLyricTrack,
            identity: String,
            loadKey: String,
        ): Boolean {
            synchronized(this) { if (activeLoadKey != loadKey) return true }
            val document = runCatching { readEmbeddedLyrics(track) }
                .onFailure { Log.w(TAG, "QQ 内嵌歌词读取失败: id=${track.id}", it) }
                .getOrNull()
                ?: return false
            val song = toEmbeddedSong(track, document)
            synchronized(this) {
                if (activeLoadKey != loadKey) {
                    Log.i(TAG, "QQ 内嵌歌词发布跳过(轨道已切换): id=${track.id}")
                    return true
                }
                val published = publish(song)
                Log.i(
                    TAG,
                    "QQ 采用音乐文件内嵌歌词: id=${track.id}, lines=${song.lyrics?.size ?: 0}, " +
                        "result=$published",
                )
                if (published) {
                    publishedLyricIdentity = identity
                    publishedSongId = null
                    publishedAppLinked = false
                    publishedEmbedded = true
                }
            }
            // 这首歌以后都用内嵌歌词：删掉以前在线取词留下的缓存，免得下次先闪一下在线版本
            runCatching { cacheFileOf(track).delete() }
            return true
        }

        private fun readEmbeddedLyrics(track: QQMusicLyricTrack): LocalLyricsDocument? {
            val query = LocalAudioQuery(
                title = track.title,
                artist = track.artist,
                album = null,
                durationMs = track.duration,
            )
            val media = localAudioLibrary.findByMetadata(query) ?: run {
                if (BuildConfig.DEBUG) Log.i(TAG, "QQ 媒体库无唯一对应文件: id=${track.id}, title=${track.title}")
                return null
            }
            val text = localAudioLibrary.readEmbeddedLyrics(media)
            val document = text?.let { LocalLrcParser.parse(it, track.duration) }
                ?.takeIf { it.lines.isNotEmpty() }
            if (BuildConfig.DEBUG) {
                Log.i(
                    TAG,
                    "QQ 本地文件内嵌歌词: id=${track.id}, file=${media.dataPath ?: media.mediaUri}, " +
                        "chars=${text?.length ?: 0}, lines=${document?.lines?.size ?: 0}",
                )
            }
            return document
        }

        /**
         * 主条目没有歌词时（按歌名匹配选中的条目可能无词，如《Broken》605895210），按顺序核对
         * 其余同曲候选，取第一个有歌词的；都没有时返回主条目的结果，由调用方按空歌词处理。
         */
        private fun fetchWithAlternatives(
            track: QQMusicLyricTrack,
            resolution: QQMusicSongMidResolver.SongMidResolution,
        ): Pair<QQMusicSongMidResolver.SongMidResolution, QQPayload> {
            val primary = QQClient.fetch(resolution.numericSongId)
            if (hasLyrics(track, primary) || resolution.alternatives.isEmpty()) {
                return resolution to primary
            }
            for (candidate in resolution.alternatives) {
                val verified = runCatching {
                    QQMusicSongMidResolver.verifyAlternative(candidate, track.title, track.artist)
                }.onFailure { Log.w(TAG, "QQ 同曲候选核对失败: songId=$candidate", it) }
                    .getOrNull() ?: continue
                val payload = runCatching { QQClient.fetch(verified.numericSongId) }
                    .onFailure { Log.w(TAG, "QQ 同曲候选歌词下载失败: songId=$candidate", it) }
                    .getOrNull() ?: continue
                if (hasLyrics(track, payload)) {
                    Log.i(
                        TAG,
                        "QQ 主条目无歌词，改用同曲候选: id=${track.id}, " +
                            "${resolution.numericSongId} -> ${verified.numericSongId}",
                    )
                    return verified to payload
                }
            }
            return resolution to primary
        }

        private fun hasLyrics(track: QQMusicLyricTrack, payload: QQPayload): Boolean =
            runCatching { !toSong(track, payload).lyrics.isNullOrEmpty() }.getOrDefault(false)

        private fun QQMusicLyricTrack.loadKey(): String =
            "$id\u0000$title\u0000$artist\u0000${sessionMediaId.orEmpty()}"

        private fun refreshDisplayPreference(provider: LyriconProvider?) {
            val target = provider ?: return
            val prefs = application.getSharedPreferences("qqmusicplayer", Context.MODE_PRIVATE)
            target.player.setDisplayTranslation(prefs.getBoolean("showtranslyric", false))
            target.player.setDisplayRoma(prefs.getBoolean("showromalyric", false))
        }

        private fun publish(song: Song): Boolean {
            if (lastSong == song) return true
            lastSong = song
            return provider?.player?.setSong(song) ?: false
        }

        private fun placeholder(track: QQMusicLyricTrack): Song = Song().apply {
            id = track.id
            name = track.title
            artist = track.artist
            duration = track.duration
        }

        /** 本 Provider 没有重试：换算失败、下载失败、歌词为空都是最终结果，在占位歌曲上打「歌词确认缺失」标记。 */
        private fun publishUnavailable(track: QQMusicLyricTrack, loadKey: String) {
            synchronized(this) {
                if (activeLoadKey != loadKey) return
                publish(placeholder(track).apply {
                    metadata = io.github.proify.lyricon.lyric.model.LyricMetadata(
                        buildMap {
                            track.sessionMediaId?.takeIf(String::isNotBlank)
                                ?.let { put(SESSION_MEDIA_ID_METADATA_KEY, it) }
                            put(LYRICS_UNAVAILABLE_METADATA_KEY, "true")
                        },
                    )
                })
            }
        }

        private class CachedLyric(val song: Song, val songId: String?, val appLinked: Boolean)

        private fun cacheFileOf(track: QQMusicLyricTrack): File =
            File(cacheDir, QQMusicLyricCachePolicy.cacheFileName(track.id, track.title, track.artist))

        private fun loadCached(track: QQMusicLyricTrack): CachedLyric? {
            val file = cacheFileOf(track)
            if (!file.isFile) return null
            val payload = runCatching { QQPayload.fromJson(JSONObject(file.readText())) }.getOrNull()
            if (payload == null) {
                runCatching { file.delete() }
                return null
            }
            if (!track.idTrusted &&
                !QQMusicLyricCachePolicy.belongsToSession(
                    payload.name, payload.singer, track.title, track.artist,
                )
            ) {
                // 无法证明缓存属于当前曲目：删除自愈，按 miss 重新走校验取词
                runCatching { file.delete() }
                    .onFailure { Log.w(TAG, "QQ 歌词缓存删除失败: id=${track.id}", it) }
                Log.i(
                    TAG,
                    "QQ 歌词缓存身份不符已删除: id=${track.id}, cacheName=${payload.name}, " +
                        "title=${track.title}, artist=${track.artist}",
                )
                return null
            }
            // 不按快照候选跳过缓存：候选可能是滞后快照里的上一首/下一首，无法离线判断哪个属于
            // 本曲。缓存先上屏，本次任务核对候选后若版本不同会重新下载并覆盖。
            val song = runCatching { toSong(track, payload) }.getOrNull() ?: return null
            return CachedLyric(song, payload.songId, payload.appLinked)
        }

        private fun writeCache(track: QQMusicLyricTrack, payload: QQPayload) {
            runCatching { cacheFileOf(track).writeText(payload.toJson().toString()) }
                .onFailure { Log.w(TAG, "QQ 歌词缓存写入失败: id=${track.id}", it) }
        }

        /**
         * 1.0.34 起缓存按「会话 ID + 歌名 + 歌手」存放并记录所用在线 ID（[QQMusicLyricCachePolicy.cacheFileName]）。
         * 旧缓存只按会话 ID 命名：本地库编号与在线 ID 同号时会互相覆盖，且不知道用的是哪个版本
         * （含按歌名匹配选错版本的条目），一律删除重取。
         */
        private fun purgeLegacyCache() {
            val legacy = legacyCacheDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
                ?: return
            if (legacy.isEmpty()) return
            val deleted = legacy.count { runCatching { it.delete() }.getOrDefault(false) }
            Log.i(TAG, "QQ 旧版歌词缓存已清除: files=$deleted/${legacy.size}")
        }
    }

    private class QQBufferRuntime(
        private val application: Application,
        private val host: OfficialProviderHost,
        private val lyricRuntime: QQRuntime,
    ) {
        private val firstStartCallback = AtomicBoolean(false)
        private val firstEndCallback = AtomicBoolean(false)

        fun start() {
            val queries = QQMusicBufferHookResolver.queries(application) ?: return
            queries.forEach { query ->
                host.hookAfterDexMethod(
                    application = application,
                    query = query,
                    callback = com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodCallback {
                            _, _ ->
                        when (query.cacheKey) {
                            QQMusicBufferHookResolver.BUFFER_START_CACHE_KEY -> {
                                if (BuildConfig.DEBUG && firstStartCallback.compareAndSet(false, true)) {
                                    Log.i(TAG, "QQ 缓冲开始 Hook 首次命中")
                                }
                                lyricRuntime.onBufferStarted()
                            }
                            QQMusicBufferHookResolver.BUFFER_END_CACHE_KEY -> {
                                if (BuildConfig.DEBUG && firstEndCallback.compareAndSet(false, true)) {
                                    Log.i(TAG, "QQ 缓冲结束 Hook 首次命中")
                                }
                                lyricRuntime.onBufferEnded()
                            }
                        }
                    },
                )
            }
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "QQ 缓冲边界 Hook 已请求安装: queries=${queries.size}")
            }
        }
    }

    private class QQNextTrackRuntime(
        private val application: Application,
        private val playerPackage: String,
        private val host: OfficialProviderHost,
    ) {
        private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "HLE-QQMusic-NextTrack").apply { isDaemon = true }
        }
        private var lastFrame: String? = null
        private var lastFrameSentAtMs = 0L
        private val mainHandler = Handler(Looper.getMainLooper())
        private val resolverGeneration = AtomicLong(0L)

        @Volatile
        private var pollingTask: ScheduledFuture<*>? = null

        @Volatile
        private var nextTrackValidationKeys: List<String> = emptyList()

        @Volatile
        private var provider: LyriconProvider? = null

        fun start() {
            val queries = QQMusicNextTrackResolver.queries(application)
            if (queries == null) {
                Log.w(TAG, "QQ 音乐包名不受支持，跳过下一首适配")
                return
            }
            nextTrackValidationKeys = queries.map { it.cacheKey }
            host.resolveDexMethods(
                application = application,
                queries = queries,
                callback = OfficialProviderDexMethodsCallback { targets ->
                    mainHandler.post { startResolved(targets) }
                },
            )
        }

        @Synchronized
        private fun startResolved(
            targets: List<com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget>,
        ) {
            val resolverResult = runCatching {
                QQMusicNextTrackResolver.create(application, targets)
            }
            val resolver = resolverResult.getOrElse { error ->
                Log.w(TAG, "QQ 音乐下一首解析器校验失败", error)
                reportNextTrackValidation(
                    valid = false,
                    detail = "resolver_validation:${error::class.java.simpleName}: ${error.message}",
                )
                return
            }
            val sharedProvider = runtime?.provider
            provider = sharedProvider ?: LyriconFactory.createProvider(
                context = application,
                providerPackageName = PROVIDER_PACKAGE,
                playerPackageName = playerPackage,
            ).also { it.register() }
            nextTrackRuntime = this
            if (BuildConfig.DEBUG) {
                Log.i(
                    TAG,
                    "QQ 队列快照方法: process=${Application.getProcessName()} " +
                        targets.joinToString { "${it.className}.${it.methodName}():${it.returnTypeName}" },
                )
            }
            pollingTask?.cancel(false)
            val generation = resolverGeneration.incrementAndGet()
            pollingTask = scheduler.scheduleWithFixedDelay(
                { capture(resolver, generation) },
                0L,
                NEXT_TRACK_POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
            Log.i(
                TAG,
                "QQ 音乐下一首 Provider ${if (sharedProvider == null) "已注册" else "已复用"}: " +
                    "process=${Application.getProcessName()}",
            )
        }

        // 1.0.24 真机教训：任何一次瞬时异常都 stopPolling 会让快照通道在进程启动
        // 瞬间（SDK 未就绪）静默自毁且 release 无日志，1.0.25 改为连续失败达到阈值
        // 才停并上报；采集失败与快照为空都在 release 以节流日志可见。
        private var consecutiveCaptureFailures = 0
        private var lastSnapshotCurrentId: String? = null
        private val nextTrackDiagLogger = ThrottledLogger()

        private fun capture(resolver: QQMusicNextTrackResolver, generation: Long) {
            if (resolverGeneration.get() != generation) return
            runCatching {
                val snapshot = resolver.resolve()
                if (resolverGeneration.get() != generation) return
                runtime?.onQueueSnapshot(snapshot)
                forwardQueueHint(snapshot)
                publish(snapshot)
                snapshot
            }
                .onSuccess { snapshot ->
                    if (resolverGeneration.get() != generation) return@onSuccess
                    consecutiveCaptureFailures = 0
                    val current = snapshot?.current
                    if (current != null && current.id != lastSnapshotCurrentId) {
                        lastSnapshotCurrentId = current.id
                        Log.i(
                            TAG,
                            "QQ 队列快照: current=${current.id}, " +
                                "sessionId=${runtime?.latestSessionMediaId ?: "none"}, " +
                                "title=${current.title}, next=${snapshot?.next?.id ?: "none"}",
                        )
                    }
                    if (snapshot == null) {
                        nextTrackDiagLogger.log(TAG, "snapshot_null", 30_000L) {
                            "QQ 队列快照为空: getCurSong 无当前曲（SDK 未就绪或未在播放）"
                        }
                    }
                    reportNextTrackValidation(
                        valid = true,
                        detail = "next=${snapshot?.next?.id ?: "none"}",
                    )
                }
                .onFailure { error ->
                    if (resolverGeneration.get() != generation) return@onFailure
                    consecutiveCaptureFailures += 1
                    if (consecutiveCaptureFailures == 1) {
                        Log.w(
                            TAG,
                            "QQ 下一首采集失败: ${error::class.java.simpleName}: ${error.message}",
                        )
                    } else {
                        nextTrackDiagLogger.log(TAG, "capture_fail", 30_000L) {
                            "QQ 下一首采集持续失败: 连续=$consecutiveCaptureFailures, " +
                                "${error::class.java.simpleName}: ${error.message}"
                        }
                    }
                    if (consecutiveCaptureFailures >= 10) {
                        reportNextTrackValidation(
                            valid = false,
                            detail = "${error::class.java.simpleName}: ${error.message}",
                        )
                        stopPolling(generation)
                    }
                }
        }

        private var lastHint: String? = null
        private var lastHintSentAtMs = 0L

        /** 手机版歌词在 :QQPlayerService，快照转发过去；内容变化即发，否则按心跳补发（播放进程重启后能收到）。 */
        private fun forwardQueueHint(snapshot: QQMusicQueueSnapshot?) {
            if (playerPackage != QQMusicRuntimePlan.MOBILE_PACKAGE || snapshot == null) return
            val encoded = QQMusicQueueHintChannel.encode(snapshot)
            val now = SystemClock.elapsedRealtime()
            if (encoded == lastHint && now - lastHintSentAtMs < NEXT_TRACK_HEARTBEAT_MS) return
            runCatching { QQMusicQueueHintChannel.send(application, snapshot) }
                .onSuccess {
                    lastHint = encoded
                    lastHintSentAtMs = now
                }
                .onFailure { Log.w(TAG, "QQ 队列快照转发失败", it) }
        }

        private fun reportNextTrackValidation(valid: Boolean, detail: String) {
            if (valid && !BuildConfig.DEBUG) return
            nextTrackValidationKeys.forEach { key ->
                host.reportDexMethodValidation(key, valid, detail)
            }
        }

        @Synchronized
        private fun stopPolling(generation: Long) {
            if (resolverGeneration.get() != generation) return
            resolverGeneration.incrementAndGet()
            pollingTask?.cancel(false)
            pollingTask = null
        }

        private fun publish(snapshot: QQMusicQueueSnapshot?) {
            val frameSessionId = runtime?.latestSessionMediaId
            val frame = when {
                snapshot == null -> OfficialProviderControlProtocol.encodeNextTrackClear()
                snapshot.next == null || snapshot.next.title.isBlank() ->
                    OfficialProviderControlProtocol.encodeNextTrackClear(
                        currentId = nextTrackFrameCurrentId(frameSessionId, snapshot.current),
                        currentTitle = snapshot.current.title,
                        currentArtist = snapshot.current.artist,
                    )
                else -> OfficialProviderControlProtocol.encodeNextTrack(
                    currentId = nextTrackFrameCurrentId(frameSessionId, snapshot.current),
                    currentTitle = snapshot.current.title,
                    currentArtist = snapshot.current.artist,
                    nextId = snapshot.next.id,
                    nextTitle = snapshot.next.title,
                    nextArtist = snapshot.next.artist,
                )
            }
            val now = SystemClock.elapsedRealtime()
            if (frame == lastFrame && now - lastFrameSentAtMs < NEXT_TRACK_HEARTBEAT_MS) return
            if (provider?.player?.sendText(frame) == true) {
                lastFrame = frame
                lastFrameSentAtMs = now
                if (BuildConfig.DEBUG) {
                    Log.i(
                        TAG,
                        "QQ 下一首控制帧已发送: current=${snapshot?.current?.id}, " +
                            "next=${snapshot?.next?.id}",
                    )
                }
            }
        }
    }

    private const val NEXT_TRACK_POLL_INTERVAL_MS = 1_500L
    private const val NEXT_TRACK_HEARTBEAT_MS = 5_000L

    private data class QQPayload(
        val lyric: String?,
        val translation: String?,
        val roma: String?,
        val name: String? = null,
        val singer: String? = null,
        /** 这份歌词所用的在线歌曲 ID（v2 缓存起记录）。 */
        val songId: String? = null,
        /** 这份歌词来自 QQ 关联 ID 核对过的版本。 */
        val appLinked: Boolean = false,
    ) {
        fun toJson() = JSONObject().apply {
            putOpt("lyric", lyric)
            putOpt("translation", translation)
            putOpt("roma", roma)
            putOpt("name", name)
            putOpt("singer", singer)
            putOpt("songId", songId)
            if (appLinked) put("appLinked", true)
        }

        companion object {
            fun fromJson(json: JSONObject) = QQPayload(
                lyric = json.optString("lyric").takeIf(String::isNotBlank),
                translation = json.optString("translation").takeIf(String::isNotBlank),
                roma = json.optString("roma").takeIf(String::isNotBlank),
                name = json.optString("name").takeIf(String::isNotBlank),
                singer = json.optString("singer").takeIf(String::isNotBlank),
                songId = json.optString("songId").takeIf(String::isNotBlank),
                appLinked = json.optBoolean("appLinked", false),
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
                    // lrctype=4 is the QQ Music QRC response. It carries word
                    // timings in the original lyric and the translation used
                    // by the QQ Music client; QqQrcDecrypter transparently
                    // leaves ordinary LRC values unchanged.
                    "lrctype" to "4",
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
                    lyric = QqQrcDecrypter.decode(cdata(raw, "content")),
                    translation = QqQrcDecrypter.decode(cdata(raw, "contentts")),
                    roma = QqQrcDecrypter.decode(cdata(raw, "contentroma")),
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
        val words: List<LyricWord> = emptyList(),
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

    private fun toSong(track: QQMusicLyricTrack, payload: QQPayload): Song {
        val source = QqQrcParser.parse(payload.lyric).map { it.toTimelineLine() }
            .ifEmpty { LrcParser.parse(payload.lyric) }
        val translations = LrcParser.parse(payload.translation).ifEmpty {
            QqQrcParser.parse(payload.translation).map { it.toTimelineLine() }
        }
        val romas = LrcParser.parse(payload.roma).ifEmpty {
            QqQrcParser.parse(payload.roma).map { it.toTimelineLine() }
        }
        val rich = source.map { line ->
            RichLyricLine().apply {
                begin = line.begin
                end = line.end
                duration = (line.end - line.begin).coerceAtLeast(0L)
                text = line.text
                words = line.words.takeIf(List<LyricWord>::isNotEmpty)
                translation = closest(translations, line.begin)?.text
                    ?.takeUnless { it.trim() == "//" }
                roma = closest(romas, line.begin)?.text
            }
        }
        return Song().apply {
            id = track.id
            // 小米音乐车载歌词会把歌词行写进 MediaSession 标题；换算接口返回的
            // name/singer 是权威曲目信息，能用时优先于本地 MediaSession 元数据。
            name = payload.name ?: track.title
            artist = payload.singer ?: track.artist
            duration = track.duration.takeIf { it > 0 } ?: rich.lastOrNull()?.end ?: 0L
            lyrics = rich.takeIf { it.isNotEmpty() }
            attachSessionMediaId(track)
        }
    }

    private fun toEmbeddedSong(track: QQMusicLyricTrack, document: LocalLyricsDocument): Song {
        val lines = LocalLyricsLineMapper.map(document)
        return Song().apply {
            id = track.id
            name = track.title
            artist = track.artist
            duration = track.duration.takeIf { it > 0 } ?: lines.lastOrNull()?.end ?: 0L
            lyrics = lines.takeIf { it.isNotEmpty() }
            attachSessionMediaId(track)
        }
    }

    /**
     * 会话 mediaId 随元数据透传：SystemUI 侧用它做 TrackIdentity 匹配，
     * 车载歌词污染的标题/歌手不再影响身份判定。
     */
    private fun Song.attachSessionMediaId(track: QQMusicLyricTrack) {
        track.sessionMediaId?.takeIf(String::isNotBlank)?.let {
            metadata = io.github.proify.lyricon.lyric.model.LyricMetadata(
                mapOf(SESSION_MEDIA_ID_METADATA_KEY to it)
            )
        }
    }

    private fun closest(lines: List<TimelineLine>, position: Long): TimelineLine? = lines
        .minByOrNull { kotlin.math.abs(it.begin - position) }
        ?.takeIf { kotlin.math.abs(it.begin - position) <= 1_000L }

    private fun QqQrcLine.toTimelineLine() = TimelineLine(
        begin = begin,
        end = end,
        text = text,
        words = words,
    )
}

internal enum class QQMusicRuntimeFeature {
    LYRICS,
    BUFFERING_STATE,
    NEXT_TRACK,
}

/**
 * 下一首控制帧的当前曲身份：优先用会话 MEDIA_ID——SystemUI 查询下一首缓存用的
 * 正是同一 MediaSession 的 MEDIA_ID（XIAOMI-MUSIC-NEXT-PREVIEW-IDENTITY-001，
 * 小米音乐 SDK 队列内部 long id 与其结构性不等、车载歌词污染标题又毁掉 text
 * 别名）。会话身份未知（手机 QQ 主进程只跑 NEXT_TRACK、无歌词 runtime）时回退
 * SDK 队列 id，保持既有行为。
 */
internal fun nextTrackFrameCurrentId(
    sessionMediaId: String?,
    current: QQMusicTrackSnapshot,
): String = sessionMediaId?.trim()?.takeIf(String::isNotEmpty) ?: current.id

internal object QQMusicRuntimePlan {
    const val MOBILE_PACKAGE = "com.tencent.qqmusic"
    const val HD_PACKAGE = "com.tencent.qqmusicpad"
    const val MIUI_PACKAGE = "com.miui.player"
    private const val MOBILE_PLAYER_PROCESS = "$MOBILE_PACKAGE:QQPlayerService"
    private const val MIUI_REMOTE_PROCESS = "$MIUI_PACKAGE:remote"

    fun supports(packageName: String): Boolean =
        packageName == MOBILE_PACKAGE || packageName == HD_PACKAGE || packageName == MIUI_PACKAGE

    fun resolve(packageName: String, processName: String): Set<QQMusicRuntimeFeature> = when {
        packageName == MOBILE_PACKAGE && processName == MOBILE_PACKAGE ->
            setOf(QQMusicRuntimeFeature.NEXT_TRACK)
        packageName == MOBILE_PACKAGE && processName == MOBILE_PLAYER_PROCESS ->
            setOf(QQMusicRuntimeFeature.LYRICS, QQMusicRuntimeFeature.BUFFERING_STATE)
        packageName == HD_PACKAGE && processName == HD_PACKAGE ->
            setOf(QQMusicRuntimeFeature.LYRICS, QQMusicRuntimeFeature.NEXT_TRACK)
        // 小米音乐跨版本会把 QQ 播放管线在主进程与 :remote 之间搬动：
        // 4440009 实测 QQPlayerServiceNew + MediaSession 全在主进程，早期版本曾按 :remote 设计。
        // 两个进程都启用 LYRICS，谁真正承载播放谁产出事件；
        // 每进程各持独立 JVM 单例，MediaSession 回调只在持有会话的进程派发，
        // 空闲侧不会产生事件，中央 ActivePlayerCoordinator 按“有事件者活跃”仲裁，双注册无冲突。
        packageName == MIUI_PACKAGE && processName == MIUI_PACKAGE ->
            setOf(QQMusicRuntimeFeature.LYRICS, QQMusicRuntimeFeature.NEXT_TRACK)
        packageName == MIUI_PACKAGE && processName == MIUI_REMOTE_PROCESS ->
            setOf(QQMusicRuntimeFeature.LYRICS)
        else -> emptySet()
    }
}
