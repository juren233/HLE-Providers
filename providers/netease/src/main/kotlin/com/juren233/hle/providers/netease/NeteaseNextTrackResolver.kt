/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.netease

import android.app.Application
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class NeteaseNextTrackSnapshot(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

internal data class NeteaseNextTrackProfile(
    val versionName: String,
    val versionCode: Long,
    val serviceClassName: String,
    val playerManagerClassName: String,
    val musicInfoClassName: String,
    val simpleMusicInfoClassName: String,
    val playerManagerAccessorName: String,
    val nextMusicMethodName: String,
    val toSimpleMusicInfoMethodName: String,
    val idMethodName: String,
    val titleMethodName: String,
    val artistMethodName: String,
    val albumMethodName: String,
    val durationMethodName: String,
)

internal object NeteaseNextTrackProfiles {
    // Verified from the original NetEase Cloud Music 9.5.61 APK DEX on 2026-08-06.
    // Exact descriptors:
    // MainProcessPlayService.E1()Ltr0/z; -> tr0.z.g()L.../MusicInfo;
    // MusicInfo.toSimpleMusicInfo()L.../meta/virtual/SimpleMusicInfo;.
    val V9_5_61 = NeteaseNextTrackProfile(
        versionName = "9.5.61",
        versionCode = 9_005_061L,
        serviceClassName = "com.netease.cloudmusic.service.MainProcessPlayService",
        playerManagerClassName = "tr0.z",
        musicInfoClassName = "com.netease.cloudmusic.meta.MusicInfo",
        simpleMusicInfoClassName = "com.netease.cloudmusic.meta.virtual.SimpleMusicInfo",
        playerManagerAccessorName = "E1",
        nextMusicMethodName = "g",
        toSimpleMusicInfoMethodName = "toSimpleMusicInfo",
        idMethodName = "getId",
        titleMethodName = "getMusicName",
        artistMethodName = "getSingerName",
        albumMethodName = "getAlbumName",
        durationMethodName = "getDuration",
    )

    fun resolve(versionName: String, versionCode: Long): NeteaseNextTrackProfile? =
        V9_5_61.takeIf { it.versionName == versionName && it.versionCode == versionCode }
}

internal class NeteaseNextTrackResolver private constructor(
    private val playerManagerAccessor: Method,
    private val nextMusicMethod: Method,
    private val toSimpleMusicInfoMethod: Method,
    private val idMethod: Method,
    private val titleMethod: Method,
    private val artistMethod: Method,
    private val albumMethod: Method,
    private val durationMethod: Method,
) {
    fun resolve(): NeteaseNextTrackSnapshot? {
        val manager = playerManagerAccessor.invoke(null) ?: return null
        val musicInfo = nextMusicMethod.invoke(manager) ?: return null
        val simpleMusicInfo = toSimpleMusicInfoMethod.invoke(musicInfo) ?: return null
        return NeteaseNextTrackSnapshot(
            id = (idMethod.invoke(simpleMusicInfo) as Number).toLong().toString(),
            title = titleMethod.invoke(simpleMusicInfo) as? String ?: "",
            artist = artistMethod.invoke(simpleMusicInfo) as? String ?: "",
            album = albumMethod.invoke(simpleMusicInfo) as? String ?: "",
            durationMs = (durationMethod.invoke(simpleMusicInfo) as Number).toLong(),
        )
    }

    companion object {
        fun create(application: Application): NeteaseNextTrackResolver? {
            val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            val profile = NeteaseNextTrackProfiles.resolve(
                packageInfo.versionName.orEmpty(),
                packageInfo.longVersionCode,
            ) ?: return null
            val loader = application.classLoader
            val serviceClass = loader.loadClass(profile.serviceClassName)
            val playerManagerClass = loader.loadClass(profile.playerManagerClassName)
            val musicInfoClass = loader.loadClass(profile.musicInfoClassName)
            val simpleMusicInfoClass = loader.loadClass(profile.simpleMusicInfoClassName)
            val playerManagerAccessor = serviceClass
                .getDeclaredMethod(profile.playerManagerAccessorName)
                .accessible()
            val nextMusicMethod = playerManagerClass
                .getDeclaredMethod(profile.nextMusicMethodName)
                .accessible()
            val toSimpleMusicInfoMethod = musicInfoClass
                .getDeclaredMethod(profile.toSimpleMusicInfoMethodName)
                .accessible()
            val idMethod = simpleMusicInfoClass.getDeclaredMethod(profile.idMethodName).accessible()
            val titleMethod = simpleMusicInfoClass.getDeclaredMethod(profile.titleMethodName).accessible()
            val artistMethod = simpleMusicInfoClass.getDeclaredMethod(profile.artistMethodName).accessible()
            val albumMethod = simpleMusicInfoClass.getDeclaredMethod(profile.albumMethodName).accessible()
            val durationMethod = simpleMusicInfoClass.getDeclaredMethod(profile.durationMethodName).accessible()

            require(Modifier.isStatic(playerManagerAccessor.modifiers))
            require(playerManagerAccessor.returnType == playerManagerClass)
            require(nextMusicMethod.returnType == musicInfoClass)
            require(toSimpleMusicInfoMethod.returnType == simpleMusicInfoClass)
            require(idMethod.returnType == Long::class.javaPrimitiveType)
            require(titleMethod.returnType == String::class.java)
            require(artistMethod.returnType == String::class.java)
            require(albumMethod.returnType == String::class.java)
            require(durationMethod.returnType == Long::class.javaPrimitiveType)
            return NeteaseNextTrackResolver(
                playerManagerAccessor,
                nextMusicMethod,
                toSimpleMusicInfoMethod,
                idMethod,
                titleMethod,
                artistMethod,
                albumMethod,
                durationMethod,
            )
        }

        private fun Method.accessible(): Method = apply { isAccessible = true }
    }
}
