/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import android.app.Application
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodQuery
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class QQMusicTrackSnapshot(
    val id: String,
    val title: String,
    val artist: String,
)

internal data class QQMusicQueueSnapshot(
    val current: QQMusicTrackSnapshot,
    val next: QQMusicTrackSnapshot?,
)

internal data class QQMusicNextTrackProfile(
    val versionName: String,
    val versionCode: Long,
    val managerClassName: String,
    val singletonMethodName: String,
    val currentSongMethodName: String,
    val nextSongMethodName: String,
    val songInfoClassName: String,
    val songIdMethodName: String,
    val songTitleMethodName: String,
    val songArtistMethodName: String,
)

internal object QQMusicNextTrackProfiles {
    // Verified from the original QQ Music 20.6.5.8 APK DEX on 2026-08-06.
    // Exact descriptors:
    // Lcom/tencent/qqmusic/common/player/d;->z()Lcom/tencent/qqmusic/common/player/d;
    // ->M()/E()Lcom/tencent/qqmusicplayerprocess/songinfo/SongInfo;
    // SongInfo.D2()J, f3()Ljava/lang/String;, R3()Ljava/lang/String;.
    val V20_6_5_8 = QQMusicNextTrackProfile(
        versionName = "20.6.5.8",
        versionCode = 7228L,
        managerClassName = "com.tencent.qqmusic.common.player.d",
        singletonMethodName = "z",
        currentSongMethodName = "M",
        nextSongMethodName = "E",
        songInfoClassName = "com.tencent.qqmusicplayerprocess.songinfo.SongInfo",
        songIdMethodName = "D2",
        songTitleMethodName = "f3",
        songArtistMethodName = "R3",
    )

    fun resolve(versionName: String, versionCode: Long): QQMusicNextTrackProfile? =
        V20_6_5_8.takeIf { it.versionName == versionName && it.versionCode == versionCode }
}

internal class QQMusicNextTrackResolver private constructor(
    private val singletonMethod: Method,
    private val currentSongMethod: Method,
    private val nextSongMethod: Method,
    private val songIdMethod: Method,
    private val songTitleMethod: Method,
    private val songArtistMethod: Method,
) {
    fun resolve(): QQMusicQueueSnapshot? {
        val manager = singletonMethod.invoke(null) ?: return null
        val current = currentSongMethod.invoke(manager)?.let(::toTrack) ?: return null
        val next = nextSongMethod.invoke(manager)?.let(::toTrack)
        return QQMusicQueueSnapshot(current, next)
    }

    private fun toTrack(value: Any): QQMusicTrackSnapshot {
        val id = (songIdMethod.invoke(value) as Number).toLong().toString()
        val title = songTitleMethod.invoke(value) as? String ?: ""
        val artist = songArtistMethod.invoke(value) as? String ?: ""
        return QQMusicTrackSnapshot(id, title, artist)
    }

    companion object {
        fun queries(application: Application): List<OfficialProviderDexMethodQuery>? {
            val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            val profile = QQMusicNextTrackProfiles.resolve(
                packageInfo.versionName.orEmpty(),
                packageInfo.longVersionCode,
            ) ?: QQMusicNextTrackProfiles.V20_6_5_8
            fun target(
                className: String,
                methodName: String,
                returnTypeName: String,
                isStatic: Boolean = false,
            ) = OfficialProviderMethodTarget(
                className = className,
                methodName = methodName,
                returnTypeName = returnTypeName,
                isStatic = isStatic,
            )
            val songInfo = profile.songInfoClassName
            return listOf(
                OfficialProviderDexMethodQuery(
                    cacheKey = "qqmusic-player-singleton-v1",
                    preferredTarget = target(
                        profile.managerClassName,
                        profile.singletonMethodName,
                        profile.managerClassName,
                        isStatic = true,
                    ),
                    declaringClassNamePrefix = "com.tencent.qqmusic.common.player.",
                    requiredStrings = listOf("MusicPlayerHelper CAN'T use in Play Process"),
                    parameterTypeNames = emptyList(),
                    returnTypeMatchesDeclaringClass = true,
                    isStatic = true,
                ),
                OfficialProviderDexMethodQuery(
                    cacheKey = "qqmusic-current-song-v1",
                    preferredTarget = target(
                        profile.managerClassName,
                        profile.currentSongMethodName,
                        songInfo,
                    ),
                    declaringClassNamePrefix = "com.tencent.qqmusic.common.player.",
                    requiredInvokedMethodDescriptors = listOf(
                        "Lcom/tencent/qqmusic/common/ipc/IPlayProcessMethods;->getPlaySong()" +
                            "Lcom/tencent/qqmusicplayerprocess/songinfo/SongInfo;",
                    ),
                    parameterTypeNames = emptyList(),
                    returnTypeName = songInfo,
                    isStatic = false,
                ),
                OfficialProviderDexMethodQuery(
                    cacheKey = "qqmusic-next-song-v1",
                    preferredTarget = target(
                        profile.managerClassName,
                        profile.nextSongMethodName,
                        songInfo,
                    ),
                    declaringClassNamePrefix = "com.tencent.qqmusic.common.player.",
                    requiredInvokedMethodDescriptors = listOf(
                        "Lcom/tencent/qqmusic/common/ipc/IPlayProcessMethods;->getNextSong()" +
                            "Lcom/tencent/qqmusicplayerprocess/songinfo/SongInfo;",
                    ),
                    parameterTypeNames = emptyList(),
                    returnTypeName = songInfo,
                    isStatic = false,
                ),
                OfficialProviderDexMethodQuery(
                    cacheKey = "qqmusic-song-id-v1",
                    preferredTarget = target(songInfo, profile.songIdMethodName, "long"),
                    declaringClassName = songInfo,
                    parameterTypeNames = emptyList(),
                    returnTypeName = "long",
                    isStatic = false,
                ),
                OfficialProviderDexMethodQuery(
                    cacheKey = "qqmusic-song-title-v1",
                    preferredTarget = target(songInfo, profile.songTitleMethodName, "java.lang.String"),
                    declaringClassName = songInfo,
                    parameterTypeNames = emptyList(),
                    returnTypeName = "java.lang.String",
                    isStatic = false,
                ),
                OfficialProviderDexMethodQuery(
                    cacheKey = "qqmusic-song-artist-v1",
                    preferredTarget = target(songInfo, profile.songArtistMethodName, "java.lang.String"),
                    declaringClassName = songInfo,
                    parameterTypeNames = emptyList(),
                    returnTypeName = "java.lang.String",
                    isStatic = false,
                ),
            )
        }

        fun create(
            application: Application,
            targets: List<OfficialProviderMethodTarget>,
        ): QQMusicNextTrackResolver {
            require(targets.size == 6) { "QQ 音乐下一首目标数量错误" }
            val loader = application.classLoader
            val methods = targets.map { it.toMethod(loader) }
            val singletonMethod = methods[0]
            val currentSongMethod = methods[1]
            val nextSongMethod = methods[2]
            val songIdMethod = methods[3]
            val songTitleMethod = methods[4]
            val songArtistMethod = methods[5]
            val managerClass = singletonMethod.returnType
            val songInfoClass = currentSongMethod.returnType

            require(Modifier.isStatic(singletonMethod.modifiers) && singletonMethod.returnType == managerClass)
            require(currentSongMethod.returnType == songInfoClass)
            require(nextSongMethod.returnType == songInfoClass)
            require(songIdMethod.returnType == Long::class.javaPrimitiveType)
            require(songTitleMethod.returnType == String::class.java)
            require(songArtistMethod.returnType == String::class.java)
            return QQMusicNextTrackResolver(
                singletonMethod,
                currentSongMethod,
                nextSongMethod,
                songIdMethod,
                songTitleMethod,
                songArtistMethod,
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
            return clazz.getDeclaredMethod(methodName, *parameters).apply { isAccessible = true }
        }
    }
}
