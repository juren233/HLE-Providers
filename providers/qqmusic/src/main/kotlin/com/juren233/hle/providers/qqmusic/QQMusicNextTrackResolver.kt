/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import android.app.Application
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
        fun create(application: Application): QQMusicNextTrackResolver? {
            val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            val profile = QQMusicNextTrackProfiles.resolve(
                packageInfo.versionName.orEmpty(),
                packageInfo.longVersionCode,
            ) ?: return null
            val loader = application.classLoader
            val managerClass = loader.loadClass(profile.managerClassName)
            val songInfoClass = loader.loadClass(profile.songInfoClassName)
            val singletonMethod = managerClass.getDeclaredMethod(profile.singletonMethodName).accessible()
            val currentSongMethod = managerClass.getDeclaredMethod(profile.currentSongMethodName).accessible()
            val nextSongMethod = managerClass.getDeclaredMethod(profile.nextSongMethodName).accessible()
            val songIdMethod = songInfoClass.getDeclaredMethod(profile.songIdMethodName).accessible()
            val songTitleMethod = songInfoClass.getDeclaredMethod(profile.songTitleMethodName).accessible()
            val songArtistMethod = songInfoClass.getDeclaredMethod(profile.songArtistMethodName).accessible()

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

        private fun Method.accessible(): Method = apply { isAccessible = true }
    }
}
