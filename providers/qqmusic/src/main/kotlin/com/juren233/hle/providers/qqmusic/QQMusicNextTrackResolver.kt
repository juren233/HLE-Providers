/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import android.app.Application
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodQuery
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexTypeReference
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexTypeSource
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
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val cacheNamespace: String,
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
        packageName = QQMusicRuntimePlan.MOBILE_PACKAGE,
        versionName = "20.6.5.8",
        versionCode = 7228L,
        cacheNamespace = "qqmusic-mobile",
        managerClassName = "com.tencent.qqmusic.common.player.d",
        singletonMethodName = "z",
        currentSongMethodName = "M",
        nextSongMethodName = "E",
        songInfoClassName = "com.tencent.qqmusicplayerprocess.songinfo.SongInfo",
        songIdMethodName = "D2",
        songTitleMethodName = "f3",
        songArtistMethodName = "R3",
    )

    // Verified from the original QQ Music HD 6.12.0.5 APK DEX on 2026-08-11.
    // Exact descriptors:
    // Lcom/tencent/qqmusic/qplayer/core/player/MusicPlayerHelper;
    // ->a0()Lcom/tencent/qqmusic/qplayer/core/player/MusicPlayerHelper;
    // ->l0()/g0()Lcom/tencent/qqmusic/openapisdk/model/SongInfo;
    // SongInfo.getSongId()J, getSongName()/getSingerName()Ljava/lang/String;.
    val HD_V6_12_0_5 = QQMusicNextTrackProfile(
        packageName = QQMusicRuntimePlan.HD_PACKAGE,
        versionName = "6.12.0.5",
        versionCode = 6_120_005L,
        cacheNamespace = "qqmusic-hd",
        managerClassName = "com.tencent.qqmusic.qplayer.core.player.MusicPlayerHelper",
        singletonMethodName = "a0",
        currentSongMethodName = "l0",
        nextSongMethodName = "g0",
        songInfoClassName = "com.tencent.qqmusic.openapisdk.model.SongInfo",
        songIdMethodName = "getSongId",
        songTitleMethodName = "getSongName",
        songArtistMethodName = "getSingerName",
    )

    private val profiles = listOf(V20_6_5_8, HD_V6_12_0_5)

    fun resolve(
        packageName: String,
        versionName: String,
        versionCode: Long,
    ): QQMusicNextTrackProfile? = profiles.firstOrNull {
        it.packageName == packageName &&
            it.versionName == versionName &&
            it.versionCode == versionCode
    }
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
            return queries(
                packageName = application.packageName,
                versionName = packageInfo.versionName.orEmpty(),
                versionCode = packageInfo.longVersionCode,
            )
        }

        internal fun queries(
            packageName: String,
            versionName: String,
            versionCode: Long,
        ): List<OfficialProviderDexMethodQuery>? {
            val profile = QQMusicNextTrackProfiles.resolve(
                packageName,
                versionName,
                versionCode,
            ) ?: return null
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
            val cacheNamespace = profile.cacheNamespace
            val managerType = OfficialProviderDexTypeReference(
                queryCacheKey = "$cacheNamespace-player-singleton-v3",
                source = OfficialProviderDexTypeSource.RETURN_TYPE,
            )
            val songInfoType = OfficialProviderDexTypeReference(
                queryCacheKey = "$cacheNamespace-current-song-v3",
                source = OfficialProviderDexTypeSource.RETURN_TYPE,
            )
            val songInfo = profile.songInfoClassName
            val isHd = profile.packageName == QQMusicRuntimePlan.HD_PACKAGE
            return listOf(
                OfficialProviderDexMethodQuery(
                    cacheKey = managerType.queryCacheKey,
                    preferredTarget = target(
                        profile.managerClassName,
                        profile.singletonMethodName,
                        profile.managerClassName,
                        isStatic = true,
                    ),
                    declaringClassName = profile.managerClassName.takeIf { isHd },
                    declaringClassNamePrefix = "com.tencent.qqmusic.common.player."
                        .takeUnless { isHd },
                    requiredStrings = if (isHd) {
                        emptyList()
                    } else {
                        listOf("MusicPlayerHelper CAN'T use in Play Process")
                    },
                    parameterTypeNames = emptyList(),
                    returnTypeMatchesDeclaringClass = true,
                    isStatic = true,
                ),
                OfficialProviderDexMethodQuery(
                    cacheKey = songInfoType.queryCacheKey,
                    preferredTarget = target(
                        profile.managerClassName,
                        profile.currentSongMethodName,
                        songInfo,
                    ).takeUnless { isHd },
                    declaringClassReference = managerType,
                    requiredInvokedMethodNames = if (isHd) emptyList() else listOf("getPlaySong"),
                    requiredCallerMethodNames = if (isHd) {
                        listOf("getCurrentSongInfo")
                    } else {
                        emptyList()
                    },
                    parameterTypeNames = emptyList(),
                    returnTypeName = songInfo,
                    isStatic = false,
                ),
                OfficialProviderDexMethodQuery(
                    cacheKey = "$cacheNamespace-next-song-v3",
                    preferredTarget = target(
                        profile.managerClassName,
                        profile.nextSongMethodName,
                        songInfo,
                    ).takeUnless { isHd },
                    declaringClassReference = managerType,
                    requiredInvokedMethodNames = if (isHd) emptyList() else listOf("getNextSong"),
                    requiredCallerMethodNames = if (isHd) {
                        listOf("getNextSongInfo")
                    } else {
                        emptyList()
                    },
                    parameterTypeNames = emptyList(),
                    returnTypeReference = songInfoType,
                    isStatic = false,
                ),
                OfficialProviderDexMethodQuery(
                    cacheKey = "$cacheNamespace-song-id-v3",
                    preferredTarget = target(songInfo, profile.songIdMethodName, "long"),
                    declaringClassReference = songInfoType,
                    parameterTypeNames = emptyList(),
                    returnTypeName = "long",
                    isStatic = false,
                ),
                OfficialProviderDexMethodQuery(
                    cacheKey = "$cacheNamespace-song-title-v3",
                    preferredTarget = target(songInfo, profile.songTitleMethodName, "java.lang.String"),
                    declaringClassReference = songInfoType,
                    parameterTypeNames = emptyList(),
                    returnTypeName = "java.lang.String",
                    isStatic = false,
                ),
                OfficialProviderDexMethodQuery(
                    cacheKey = "$cacheNamespace-song-artist-v3",
                    preferredTarget = target(songInfo, profile.songArtistMethodName, "java.lang.String"),
                    declaringClassReference = songInfoType,
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

            require(Modifier.isStatic(singletonMethod.modifiers))
            require(singletonMethod.parameterCount == 0)
            require(singletonMethod.declaringClass == managerClass)
            require(currentSongMethod.declaringClass == managerClass)
            require(nextSongMethod.declaringClass == managerClass)
            require(!Modifier.isStatic(currentSongMethod.modifiers) && currentSongMethod.parameterCount == 0)
            require(!Modifier.isStatic(nextSongMethod.modifiers) && nextSongMethod.parameterCount == 0)
            require(nextSongMethod.returnType == songInfoClass)
            require(songIdMethod.declaringClass == songInfoClass)
            require(songTitleMethod.declaringClass == songInfoClass)
            require(songArtistMethod.declaringClass == songInfoClass)
            require(!Modifier.isStatic(songIdMethod.modifiers) && songIdMethod.parameterCount == 0)
            require(!Modifier.isStatic(songTitleMethod.modifiers) && songTitleMethod.parameterCount == 0)
            require(!Modifier.isStatic(songArtistMethod.modifiers) && songArtistMethod.parameterCount == 0)
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
