/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kugou

import android.app.Application
import android.media.MediaMetadata
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodQuery
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodsCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexTypeReference
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexTypeSource
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHost
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMetadataCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlaybackStateCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlugin
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object KuGouPluginEntry : OfficialProviderPlugin {
    private const val TAG = "HLEProvider/KuGou"
    private const val FULL_PACKAGE = "com.kugou.android"
    private const val LITE_PACKAGE = "com.kugou.android.lite"
    private const val FULL_SUPPORT_PROCESS = "$FULL_PACKAGE.support"
    private const val LITE_SUPPORT_PROCESS = "$LITE_PACKAGE.support"
    private const val PROVIDER_PACKAGE = "com.juren233.hyperlyricsenhanced.provider.kugou"
    private const val NEXT_TRACK_CAPTURE_INTERVAL_MS = 1_000L
    private const val NEXT_TRACK_HEARTBEAT_MS = 10_000L

    private val installed = AtomicBoolean(false)

    @Volatile
    private var runtime: KuGouRuntime? = null

    override fun install(host: OfficialProviderHost) {
        require(host.packageName == FULL_PACKAGE || host.packageName == LITE_PACKAGE) {
            "Unsupported KuGou package: ${host.packageName}"
        }
        val supportProcess = when (host.packageName) {
            FULL_PACKAGE -> FULL_SUPPORT_PROCESS
            LITE_PACKAGE -> LITE_SUPPORT_PROCESS
            else -> error("unreachable")
        }
        if (host.processName != supportProcess) return

        host.hookApplication { application ->
            if (Application.getProcessName() != supportProcess) return@hookApplication
            if (!installed.compareAndSet(false, true)) return@hookApplication

            val provider = runCatching {
                LyriconFactory.createProvider(
                    context = application,
                    providerPackageName = PROVIDER_PACKAGE,
                    playerPackageName = host.packageName,
                ).also {
                    it.player.setDisplayTranslation(true)
                    it.register()
                }
            }.onFailure { error ->
                installed.set(false)
                Log.e(TAG, "酷狗音乐 Provider 注册失败", error)
            }.getOrNull() ?: return@hookApplication

            val currentRuntime = KuGouRuntime(application, provider).also { runtime = it }
            currentRuntime.start()
            host.resolveDexMethods(
                application = application,
                queries = nextTrackQueriesFor(host.packageName),
                callback = OfficialProviderDexMethodsCallback { targets ->
                    currentRuntime.installNextTrackResolver(targets)
                },
            )
            Log.i(
                TAG,
                "酷狗音乐 Provider 已注册: package=${host.packageName} " +
                    "process=${host.processName} lyricSource=v2-api",
            )
        }
        host.hookMediaSession(
            playbackStateCallback = OfficialProviderPlaybackStateCallback { state ->
                runtime?.provider?.player?.setPlaybackState(state)
            },
            metadataCallback = OfficialProviderMetadataCallback { metadata ->
                runtime?.onMetadata(metadata)
            },
        )
    }

    internal fun nextTrackQueriesFor(packageName: String): List<OfficialProviderDexMethodQuery> {
        val full = packageName == FULL_PACKAGE
        require(full || packageName == LITE_PACKAGE) { "Unsupported KuGou package: $packageName" }
        val managerClass = "com.kugou.framework.service.KGPlayerManager"
        val queueManagerClass = "com.kugou.common.player.manager.QueuePlayerManager"
        val mediaInterface = "com.kugou.common.player.manager.IMedia"
        val managerType = OfficialProviderDexTypeReference(
            queryCacheKey = if (full) "kugou-full-player-singleton-v2" else
                "kugou-lite-player-singleton-v2",
            source = OfficialProviderDexTypeSource.RETURN_TYPE,
        )
        val nextMediaQuery = if (full) {
            // Original KuGou 20.7.5 DEX:
            // Lcom/kugou/common/player/manager/QueuePlayerManager;->k()L.../IMedia;
            // calls PlayQueue.w(): int followed by PlayQueue.v(int): Object.
            OfficialProviderDexMethodQuery(
                cacheKey = "kugou-full-next-media-v1",
                preferredTarget = OfficialProviderMethodTarget(
                    className = queueManagerClass,
                    methodName = "k",
                    returnTypeName = mediaInterface,
                    isStatic = false,
                ),
                declaringClassName = queueManagerClass,
                parameterTypeNames = emptyList(),
                returnTypeName = mediaInterface,
                isStatic = false,
            )
        } else {
            // Original KuGou Lite 5.2.4 DEX proves k() reads the current item through
            // PlayQueue.n(). The real next implementation is currently P0(), but its
            // stable bridge getNextMedia() calls it. Resolve by that caller relationship
            // so a future obfuscation rename cannot silently turn the current item into next.
            OfficialProviderDexMethodQuery(
                cacheKey = "kugou-lite-next-media-v2",
                declaringClassName = queueManagerClass,
                requiredCallerMethodNames = listOf("getNextMedia"),
                parameterTypeNames = emptyList(),
                returnTypeName = mediaInterface,
                isStatic = false,
            )
        }
        return listOf(
            OfficialProviderDexMethodQuery(
                cacheKey = managerType.queryCacheKey,
                preferredTarget = OfficialProviderMethodTarget(
                    className = managerClass,
                    methodName = if (full) "K4" else "c4",
                    returnTypeName = managerClass,
                    isStatic = true,
                ),
                declaringClassNamePrefix = "com.kugou.framework.service.",
                parameterTypeNames = emptyList(),
                returnTypeMatchesDeclaringClass = true,
                isStatic = true,
            ),
            nextMediaQuery,
        )
    }

    private class KuGouRuntime(
        private val application: Application,
        val provider: LyriconProvider,
    ) {
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HLE-KuGou-Lyrics").apply { isDaemon = true }
        }
        private val generation = AtomicLong(0L)
        private val cacheDir = File(application.filesDir, "hle-provider/kugou")
        private val clientMid = KuGouApiProtocol.clientMid(
            buildString {
                append(application.packageName)
                append('\u0000')
                append(
                    Settings.Secure.getString(
                        application.contentResolver,
                        Settings.Secure.ANDROID_ID,
                    ).orEmpty(),
                )
            },
        )
        private val mainHandler = Handler(Looper.getMainLooper())
        private val periodicNextTrackCapture = object : Runnable {
            override fun run() {
                captureNextTrack()
                mainHandler.postDelayed(this, NEXT_TRACK_CAPTURE_INTERVAL_MS)
            }
        }

        @Volatile
        private var track = KuGouTrackMetadata(null, null, null, null, 0L)

        @Volatile
        private var lastSong: Song? = null

        @Volatile
        private var nextTrackResolver: KuGouNextTrackResolver? = null

        private var pendingLyricsTask: Future<*>? = null

        private var lastNextTrackFrame: String? = null
        private var lastNextTrackFrameSentAtMs = 0L

        fun start() {
            cacheDir.mkdirs()
            publish(placeholder(track))
        }

        fun onMetadata(value: MediaMetadata?) {
            val next = KuGouTrackMetadata(
                mediaId = value?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                title = value?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = value?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                album = value?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = value?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            )
            val previous = track
            if (next == previous) return
            track = next
            if (!KuGouTrackUpdatePolicy.shouldReloadLyrics(previous, next)) {
                if (BuildConfig.DEBUG) {
                    Log.i(
                        TAG,
                        "酷狗同曲元数据已更新，保留现有歌词: id=${next.identity}, " +
                            "album=${next.album}",
                    )
                }
                mainHandler.post(::captureNextTrack)
                return
            }
            val requestGeneration = generation.incrementAndGet()
            pendingLyricsTask?.cancel(true)
            pendingLyricsTask = null
            publish(placeholder(next))
            if (next.isSearchable) {
                pendingLyricsTask = executor.submit {
                    loadLyrics(requestGeneration, next)
                }
            }
            mainHandler.post(::captureNextTrack)
        }

        private fun loadLyrics(
            requestGeneration: Long,
            requestTrack: KuGouTrackMetadata,
        ) {
            val candidate = runCatching { KuGouApiClient.search(requestTrack, clientMid) }
                .onFailure { error ->
                    if (!Thread.currentThread().isInterrupted) {
                        Log.w(
                            TAG,
                            "酷狗歌词搜索失败: title=${requestTrack.title}",
                            error,
                        )
                    }
                }
                .getOrNull()
            if (candidate == null) {
                if (!Thread.currentThread().isInterrupted) {
                    Log.w(
                        TAG,
                        "酷狗歌词未匹配: title=${requestTrack.title}, " +
                            "artist=${requestTrack.artist}",
                    )
                }
                return
            }
            if (!isCurrent(requestGeneration, requestTrack)) return

            loadCached(candidate.downloadId)?.let { cached ->
                val parsed = decodeLyrics(cached, requestTrack.durationMs)
                if (parsed.isNotEmpty()) {
                    if (!isCurrent(requestGeneration, requestTrack)) return
                    publish(toSong(requestTrack, parsed))
                    if (BuildConfig.DEBUG) {
                        val translationCount = parsed.count { !it.translation.isNullOrBlank() }
                        Log.i(
                            TAG,
                            "酷狗歌词已从缓存发布: id=${candidate.downloadId}, " +
                                "lines=${parsed.size}, translations=$translationCount",
                        )
                    }
                    return
                }
            }

            val raw = runCatching { KuGouApiClient.download(candidate, clientMid) }
                .onFailure { error ->
                    if (!Thread.currentThread().isInterrupted) {
                        Log.w(TAG, "酷狗歌词下载失败: id=${candidate.downloadId}", error)
                    }
                }
                .getOrNull() ?: return
            if (!isCurrent(requestGeneration, requestTrack)) return

            val parsed = decodeLyrics(raw, requestTrack.durationMs)
            if (parsed.isEmpty()) {
                Log.w(TAG, "酷狗歌词无可解析时间轴: id=${candidate.downloadId}")
                return
            }
            writeCache(candidate.downloadId, raw)
            if (!isCurrent(requestGeneration, requestTrack)) return
            publish(toSong(requestTrack, parsed))
            if (BuildConfig.DEBUG) {
                val wordCount = parsed.sumOf { it.words.size }
                val translationCount = parsed.count { !it.translation.isNullOrBlank() }
                Log.i(
                    TAG,
                    "酷狗 v2 歌词已发布: id=${candidate.downloadId}, " +
                        "contentType=${candidate.contentType}, lines=${parsed.size}, " +
                        "words=$wordCount, translations=$translationCount",
                )
            }
        }

        fun installNextTrackResolver(targets: List<OfficialProviderMethodTarget>) {
            mainHandler.post {
                nextTrackResolver = runCatching {
                    KuGouNextTrackResolver.create(application, targets)
                }.onFailure { error ->
                    Log.w(TAG, "酷狗下一首解析器校验失败", error)
                }.getOrNull()
                if (nextTrackResolver == null) return@post
                mainHandler.removeCallbacks(periodicNextTrackCapture)
                mainHandler.post(periodicNextTrackCapture)
                Log.i(TAG, "酷狗下一首解析器已启用")
            }
        }

        private fun captureNextTrack() {
            val current = track
            val resolvedNext = runCatching { nextTrackResolver?.resolve() }
                .onFailure { error -> Log.w(TAG, "酷狗下一首读取失败", error) }
                .getOrNull()
            val next = resolvedNext?.takeUnless { candidate ->
                KuGouNextTrackCandidatePolicy.isCurrent(
                    currentTitle = current.title,
                    currentArtist = current.artist,
                    candidateTitle = candidate.title,
                    candidateArtist = candidate.artist,
                )
            }
            if (resolvedNext != null && next == null && BuildConfig.DEBUG) {
                Log.w(
                    TAG,
                    "酷狗下一首候选与当前曲一致，已清空: " +
                        "current=${current.title}/${current.artist}, " +
                        "candidate=${resolvedNext.title}/${resolvedNext.artist}",
                )
            }
            val frame = if (next == null || next.title.isBlank()) {
                OfficialProviderControlProtocol.encodeNextTrackClear(
                    currentId = current.identity,
                    currentTitle = current.title.orEmpty(),
                    currentArtist = current.artist.orEmpty(),
                )
            } else {
                OfficialProviderControlProtocol.encodeNextTrack(
                    currentId = current.identity,
                    currentTitle = current.title.orEmpty(),
                    currentArtist = current.artist.orEmpty(),
                    nextId = next.id,
                    nextTitle = next.title,
                    nextArtist = next.artist,
                )
            }
            val now = SystemClock.elapsedRealtime()
            if (frame == lastNextTrackFrame &&
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
                        "酷狗下一首控制帧已发送: " +
                            "current=${current.title}/${current.artist}, " +
                            "next=${next?.title}/${next?.artist}, id=${next?.id}",
                    )
                }
            }
        }

        private fun publish(song: Song) {
            if (lastSong == song) return
            lastSong = song
            provider.player.setSong(song)
        }

        private fun placeholder(track: KuGouTrackMetadata): Song = Song().apply {
            id = track.identity
            name = track.title
            artist = track.artist
            duration = track.durationMs
        }

        private fun toSong(track: KuGouTrackMetadata, lines: List<ParsedLine>): Song = Song().apply {
            id = track.identity
            name = track.title
            artist = track.artist
            duration = track.durationMs.takeIf { it > 0 } ?: lines.lastOrNull()?.end ?: 0L
            lyrics = lines.map { line ->
                RichLyricLine().apply {
                    begin = line.begin
                    end = line.end
                    duration = line.end - line.begin
                    text = line.text
                    translation = line.translation
                    words = line.words.takeIf(List<LyricWord>::isNotEmpty)
                }
            }
        }

        private fun decodeLyrics(raw: ByteArray, durationMs: Long): List<ParsedLine> {
            val text = if (raw.hasKrcHeader()) {
                KrcDecryptor.decrypt(raw) ?: return emptyList()
            } else {
                raw.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            }
            val lines = KrcLyricsParser.parse(text).takeIf(List<ParsedLine>::isNotEmpty)
                ?: LrcLyricsParser.parse(text, durationMs)
            val translations = KuGouLanguageParser.translations(text, lines.size)
            return if (translations == null) {
                lines
            } else {
                lines.mapIndexed { index, line ->
                    line.copy(translation = translations[index])
                }
            }
        }

        private fun isCurrent(
            requestGeneration: Long,
            requestTrack: KuGouTrackMetadata,
        ): Boolean = !Thread.currentThread().isInterrupted &&
            requestGeneration == generation.get() &&
            requestTrack.identity == track.identity

        private fun loadCached(downloadId: String): ByteArray? {
            val file = cacheFile(downloadId)
            if (!file.isFile) return null
            return runCatching { file.readBytes() }
                .onFailure { error ->
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "酷狗歌词缓存读取失败: id=$downloadId", error)
                    }
                }
                .getOrNull()
        }

        private fun writeCache(downloadId: String, raw: ByteArray) {
            runCatching { cacheFile(downloadId).writeBytes(raw) }
                .onFailure { error ->
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "酷狗歌词缓存写入失败: id=$downloadId", error)
                    }
                }
        }

        private fun cacheFile(downloadId: String): File =
            File(cacheDir, "${sha256Hex(downloadId)}.lyrics")

        private fun ByteArray.hasKrcHeader(): Boolean = size >= 4 &&
            this[0] == 'k'.code.toByte() &&
            this[1] == 'r'.code.toByte() &&
            this[2] == 'c'.code.toByte() &&
            this[3] == '1'.code.toByte()
    }

    private data class NextTrack(
        val id: String,
        val title: String,
        val artist: String,
    )

    private class KuGouNextTrackResolver private constructor(
        private val singletonMethod: Method,
        private val nextMediaMethod: Method,
    ) {
        @Volatile
        private var mediaAccessors: MediaAccessors? = null

        fun resolve(): NextTrack? {
            val manager = singletonMethod.invoke(null) ?: return null
            val media = nextMediaMethod.invoke(manager) ?: return null
            val accessors = mediaAccessors
                ?.takeIf { it.ownerClass.isInstance(media) }
                ?: MediaAccessors.create(media.javaClass).also { mediaAccessors = it }
            return NextTrack(
                id = accessors.hashMethod.invoke(media) as? String ?: "",
                title = accessors.titleMethod.invoke(media) as? String ?: "",
                artist = accessors.artistMethod.invoke(media) as? String ?: "",
            )
        }

        private data class MediaAccessors(
            val ownerClass: Class<*>,
            val hashMethod: Method,
            val titleMethod: Method,
            val artistMethod: Method,
        ) {
            companion object {
                fun create(ownerClass: Class<*>): MediaAccessors = MediaAccessors(
                    ownerClass = ownerClass,
                    hashMethod = ownerClass.getMethod("getHashValue").apply { isAccessible = true },
                    titleMethod = ownerClass.getMethod("getTrackName").apply { isAccessible = true },
                    artistMethod = ownerClass.getMethod("getArtistName").apply { isAccessible = true },
                )
            }
        }

        companion object {
            fun create(
                application: Application,
                targets: List<OfficialProviderMethodTarget>,
            ): KuGouNextTrackResolver {
                require(targets.size == 2) { "酷狗下一首目标数量错误" }
                val loader = application.classLoader
                val singletonMethod = targets[0].toMethod(loader)
                val nextMediaMethod = targets[1].toMethod(loader)
                require(Modifier.isStatic(singletonMethod.modifiers))
                require(!Modifier.isStatic(nextMediaMethod.modifiers))
                return KuGouNextTrackResolver(
                    singletonMethod = singletonMethod,
                    nextMediaMethod = nextMediaMethod,
                )
            }

            private fun OfficialProviderMethodTarget.toMethod(loader: ClassLoader): Method {
                val clazz = loader.loadClass(className)
                val parameters = parameterTypeNames.map { name ->
                    when (name) {
                        "boolean" -> Boolean::class.javaPrimitiveType!!
                        "int" -> Int::class.javaPrimitiveType!!
                        "long" -> Long::class.javaPrimitiveType!!
                        else -> loader.loadClass(name)
                    }
                }.toTypedArray()
                return clazz.getDeclaredMethod(methodName, *parameters).apply {
                    isAccessible = true
                }
            }
        }
    }

    private data class ParsedLine(
        val begin: Long,
        val end: Long,
        val text: String,
        val words: List<LyricWord> = emptyList(),
        val translation: String? = null,
    )

    private object KrcDecryptor {
        private val key = byteArrayOf(
            64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45,
            206.toByte(), 210.toByte(), 110, 105,
        )

        fun decrypt(input: ByteArray): String? = runCatching {
            require(input.size > 4)
            val decoded = ByteArray(input.size - 4) { index ->
                (input[index + 4].toInt() xor key[index % key.size].toInt()).toByte()
            }
            java.util.zip.InflaterInputStream(decoded.inputStream()).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    private object KrcLyricsParser {
        private val linePattern = Regex("^\\[(\\d+)\\s*,\\s*(\\d+)](.*)$")
        private val wordPattern = Regex("<(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)>")

        fun parse(content: String): List<ParsedLine> = content.lineSequence().mapNotNull { raw ->
            val match = linePattern.matchEntire(raw.trim()) ?: return@mapNotNull null
            val begin = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val duration = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val body = match.groupValues[3]
            val tags = wordPattern.findAll(body).toList()
            if (tags.isEmpty()) {
                return@mapNotNull ParsedLine(begin, begin + duration, body.trim())
            }
            val words = buildList {
                tags.forEachIndexed { index, tag ->
                    val start = tag.range.last + 1
                    val end = tags.getOrNull(index + 1)?.range?.first ?: body.length
                    val text = body.substring(start, end)
                    val offset = tag.groupValues[1].toLongOrNull() ?: 0L
                    val wordDuration = tag.groupValues[2].toLongOrNull() ?: 0L
                    add(
                        LyricWord(
                            begin = begin + offset,
                            end = begin + offset + wordDuration,
                            duration = wordDuration,
                            text = text,
                        )
                    )
                }
            }
            ParsedLine(begin, begin + duration, words.joinToString("") { it.text.orEmpty() }, words)
        }.toList().sortedBy(ParsedLine::begin)
    }

    private object LrcLyricsParser {
        private val timestamp = Regex("\\[(\\d{1,3})[:.]([0-5]\\d)(?:[:.]([0-9]{1,3}))?]")

        fun parse(content: String, duration: Long): List<ParsedLine> {
            val rows = content.lineSequence().flatMap { raw ->
                val matches = timestamp.findAll(raw).toList()
                if (matches.isEmpty() || matches.first().range.first != 0) {
                    emptySequence()
                } else {
                    val text = raw.substring(matches.last().range.last + 1).trim()
                    matches.asSequence().map { match ->
                        val minute = match.groupValues[1].toLongOrNull() ?: 0L
                        val second = match.groupValues[2].toLongOrNull() ?: 0L
                        val fraction = match.groupValues.getOrNull(3).orEmpty()
                        val millis = when (fraction.length) {
                            1 -> fraction.toLong() * 100
                            2 -> fraction.toLong() * 10
                            3 -> fraction.toLong()
                            else -> 0L
                        }
                        minute * 60_000 + second * 1_000 + millis to text
                    }
                }
            }.toList().sortedBy { it.first }
            return rows.mapIndexed { index, (begin, text) ->
                val end = rows.getOrNull(index + 1)?.first
                    ?: duration.takeIf { it > begin } ?: begin + 5_000L
                ParsedLine(begin, end, text)
            }
        }
    }

}

internal object KuGouNextTrackCandidatePolicy {
    private val whitespace = Regex("\\s+")

    fun isCurrent(
        currentTitle: String?,
        currentArtist: String?,
        candidateTitle: String,
        candidateArtist: String,
    ): Boolean {
        val normalizedCurrentTitle = normalize(currentTitle)
        if (normalizedCurrentTitle.isEmpty()) return false
        return normalizedCurrentTitle == normalize(candidateTitle) &&
            normalize(currentArtist) == normalize(candidateArtist)
    }

    private fun normalize(value: String?): String = value
        .orEmpty()
        .trim()
        .replace(whitespace, " ")
        .lowercase(Locale.ROOT)
}
