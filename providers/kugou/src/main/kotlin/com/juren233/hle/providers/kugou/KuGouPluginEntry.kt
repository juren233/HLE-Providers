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
import android.util.Log
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodQuery
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodsCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHost
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMetadataCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodCallback
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
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object KuGouPluginEntry : OfficialProviderPlugin {
    private const val TAG = "HLEProvider/KuGou"
    private const val FULL_PACKAGE = "com.kugou.android"
    private const val LITE_PACKAGE = "com.kugou.android.lite"
    private const val FULL_SUPPORT_PROCESS = "$FULL_PACKAGE.support"
    private const val LITE_SUPPORT_PROCESS = "$LITE_PACKAGE.support"
    private const val PROVIDER_PACKAGE = "com.juren233.hyperlyricsenhanced.provider.kugou"
    private const val LYRIC_MANAGER_CLASS = "com.kugou.framework.lyric.LyricManager"
    private const val LYRIC_FEATURE = "file is not krc or lyc or txt file"
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
            host.hookAfterDexMethod(
                application = application,
                query = queryFor(host.packageName),
                callback = OfficialProviderMethodCallback { _, arguments ->
                    val path = arguments.firstOrNull() as? String ?: return@OfficialProviderMethodCallback
                    currentRuntime.onLyricPath(path)
                },
            )
            host.resolveDexMethods(
                application = application,
                queries = nextTrackQueriesFor(host.packageName),
                callback = OfficialProviderDexMethodsCallback { targets ->
                    currentRuntime.installNextTrackResolver(targets)
                },
            )
            Log.i(
                TAG,
                "酷狗音乐 Provider 已注册: package=${host.packageName} process=${host.processName}",
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

    internal fun queryFor(packageName: String): OfficialProviderDexMethodQuery = when (packageName) {
        FULL_PACKAGE -> OfficialProviderDexMethodQuery(
            // Original 20.7.5 DEX descriptor:
            // Lcom/kugou/framework/lyric/LyricManager;->k(Ljava/lang/String;)
            //     Lcom/kugou/framework/lyric/k;
            // This is the shared player path. Lyv2/b;->a(String) is only used by
            // share-video/poster flows and is intentionally not a runtime target.
            cacheKey = "kugou-full-lyric-manager-v3",
            preferredTarget = OfficialProviderMethodTarget(
                className = LYRIC_MANAGER_CLASS,
                methodName = "l",
                parameterTypeNames = listOf("java.lang.String", "boolean"),
                returnTypeName = "com.kugou.framework.lyric.k",
                isStatic = false,
            ),
            requiredStrings = listOf(
                "filePath: ",
                "lyric path is empty",
                ".krc",
                ".lrc",
                ".txt",
            ),
            parameterTypeNames = listOf("java.lang.String", "boolean"),
            isStatic = false,
        )
        LITE_PACKAGE -> OfficialProviderDexMethodQuery(
            cacheKey = "kugou-lite-lyric-manager-v2",
            preferredTarget = OfficialProviderMethodTarget(
                className = LYRIC_MANAGER_CLASS,
                methodName = "k",
                parameterTypeNames = listOf("java.lang.String", "boolean"),
                returnTypeName = "com.kugou.framework.lyric.m",
                isStatic = false,
            ),
            requiredStrings = listOf("filePath: ", "lyric path is empty", LYRIC_FEATURE),
            parameterTypeNames = listOf("java.lang.String", "boolean"),
            isStatic = false,
        )
        else -> error("Unsupported KuGou package: $packageName")
    }

    internal fun nextTrackQueriesFor(packageName: String): List<OfficialProviderDexMethodQuery> {
        val full = packageName == FULL_PACKAGE
        require(full || packageName == LITE_PACKAGE) { "Unsupported KuGou package: $packageName" }
        val managerClass = "com.kugou.framework.service.KGPlayerManager"
        return listOf(
            OfficialProviderDexMethodQuery(
                cacheKey = if (full) "kugou-full-player-singleton-v1" else
                    "kugou-lite-player-singleton-v1",
                preferredTarget = OfficialProviderMethodTarget(
                    className = managerClass,
                    methodName = if (full) "K4" else "c4",
                    returnTypeName = managerClass,
                    isStatic = true,
                ),
                declaringClassName = managerClass,
                parameterTypeNames = emptyList(),
                returnTypeName = managerClass,
                isStatic = true,
            ),
            OfficialProviderDexMethodQuery(
                cacheKey = if (full) "kugou-full-next-media-v1" else
                    "kugou-lite-next-media-v1",
                preferredTarget = OfficialProviderMethodTarget(
                    className = "com.kugou.common.player.manager.QueuePlayerManager",
                    methodName = "k",
                    returnTypeName = "com.kugou.common.player.manager.IMedia",
                    isStatic = false,
                ),
                declaringClassName = "com.kugou.common.player.manager.QueuePlayerManager",
                parameterTypeNames = emptyList(),
                returnTypeName = "com.kugou.common.player.manager.IMedia",
                isStatic = false,
            ),
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
        private val mainHandler = Handler(Looper.getMainLooper())
        private val periodicNextTrackCapture = object : Runnable {
            override fun run() {
                captureNextTrack()
                mainHandler.postDelayed(this, NEXT_TRACK_CAPTURE_INTERVAL_MS)
            }
        }

        @Volatile
        private var track = Track()

        @Volatile
        private var lastSong: Song? = null

        @Volatile
        private var nextTrackResolver: KuGouNextTrackResolver? = null

        private var lastNextTrackFrame: String? = null
        private var lastNextTrackFrameSentAtMs = 0L

        fun start() {
            cacheDir.mkdirs()
            publish(placeholder(track))
        }

        fun onMetadata(value: MediaMetadata?) {
            val next = Track(
                id = value?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                title = value?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = value?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                album = value?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = value?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            )
            if (next.identity == track.identity) return
            track = next
            generation.incrementAndGet()
            publish(placeholder(next))
            mainHandler.post(::captureNextTrack)
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
            val next = runCatching { nextTrackResolver?.resolve() }
                .onFailure { error -> Log.w(TAG, "酷狗下一首读取失败", error) }
                .getOrNull()
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
                    Log.i(TAG, "酷狗下一首控制帧已发送: next=${next?.id}")
                }
            }
        }

        fun onLyricPath(path: String) {
            val requestGeneration = generation.get()
            val requestTrack = track
            executor.execute {
                val parsed = runCatching { parseFile(File(path)) }
                    .onFailure { error -> Log.w(TAG, "酷狗歌词文件解析失败: path=$path", error) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@execute
                if (requestGeneration != generation.get() || requestTrack.identity != track.identity) {
                    return@execute
                }
                publish(toSong(requestTrack, parsed))
            }
        }

        private fun publish(song: Song) {
            if (lastSong == song) return
            lastSong = song
            provider.player.setSong(song)
        }

        private fun placeholder(track: Track): Song = Song().apply {
            id = track.identity
            name = track.title
            artist = track.artist
            duration = track.durationMs
        }

        private fun toSong(track: Track, lines: List<ParsedLine>): Song = Song().apply {
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
                    words = line.words.takeIf(List<LyricWord>::isNotEmpty)
                }
            }
        }

        private fun parseFile(file: File): List<ParsedLine> {
            if (!file.isFile) return emptyList()
            return when (file.extension.lowercase()) {
                "krc" -> KrcLyricsParser.parse(
                    KrcDecryptor.decrypt(file.readBytes()) ?: return emptyList(),
                )
                "lrc", "txt" -> LrcLyricsParser.parse(file.readText(), track.durationMs)
                else -> emptyList()
            }
        }
    }

    private data class Track(
        val id: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val durationMs: Long = 0L,
    ) {
        val identity: String
            get() = id?.takeIf(String::isNotBlank) ?: stableIdentity(title, artist, durationMs)
    }

    private data class NextTrack(
        val id: String,
        val title: String,
        val artist: String,
    )

    private class KuGouNextTrackResolver private constructor(
        private val singletonMethod: Method,
        private val nextMediaMethod: Method,
        private val hashMethod: Method,
        private val titleMethod: Method,
        private val artistMethod: Method,
    ) {
        fun resolve(): NextTrack? {
            val manager = singletonMethod.invoke(null) ?: return null
            val media = nextMediaMethod.invoke(manager) ?: return null
            return NextTrack(
                id = hashMethod.invoke(media) as? String ?: "",
                title = titleMethod.invoke(media) as? String ?: "",
                artist = artistMethod.invoke(media) as? String ?: "",
            )
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
                val wrapperClass = loader.loadClass(
                    "com.kugou.framework.service.entity.KGMusicWrapper",
                )
                return KuGouNextTrackResolver(
                    singletonMethod = singletonMethod,
                    nextMediaMethod = nextMediaMethod,
                    hashMethod = wrapperClass.getMethod("getHashValue"),
                    titleMethod = wrapperClass.getMethod("getTrackName"),
                    artistMethod = wrapperClass.getMethod("getArtistName"),
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

    private fun stableIdentity(title: String?, artist: String?, durationMs: Long): String {
        val raw = "${title.orEmpty()}\u0000${artist.orEmpty()}\u0000$durationMs"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
