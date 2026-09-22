/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.netease

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** The original 9.5.81 DEX, rather than JADX's displayed inner-class aliases, defines this target. */
internal object NeteaseAppLyricsProfile {
    private const val VERIFIED_VERSION_CODE = 9_005_081L
    private const val PLAYER_PACKAGE = "com.netease.cloudmusic"
    const val LYRIC_DATA_CLASS = "com.netease.cloudmusic.meta.LyricData"

    // classes19.dex: Lcom/netease/cloudmusic/module/lyric/e;->v0(
    //   Lcom/netease/cloudmusic/meta/LyricData;
    //   Lcom/netease/cloudmusic/module/lyric/e$d;)
    //   Lcom/netease/cloudmusic/meta/LyricInfo; (private instance method).
    // classes17.dex: LyricData.getMusicId()J and the seven String getters below.
    private val lyricResultTarget = OfficialProviderMethodTarget(
        className = "com.netease.cloudmusic.module.lyric.e",
        methodName = "v0",
        parameterTypeNames = listOf(
            LYRIC_DATA_CLASS,
            "com.netease.cloudmusic.module.lyric.e\$d",
        ),
        returnTypeName = "com.netease.cloudmusic.meta.LyricInfo",
        isStatic = false,
    )

    fun targetFor(packageName: String, versionCode: Long): OfficialProviderMethodTarget? =
        lyricResultTarget.takeIf {
            packageName == PLAYER_PACKAGE && versionCode == VERIFIED_VERSION_CODE
        }
}

internal data class NeteaseAppLyricsSnapshot(
    val musicId: Long,
    val lrc: String?,
    val yrc: String?,
    val lrcTranslation: String?,
    val yrcTranslation: String?,
    val lrcRomanization: String?,
    val yrcRomanization: String?,
)

/** Reads only the original LyricData object handed to NetEase's own lyric parser. */
internal object NeteaseAppLyricsReader {
    private data class Accessors(
        val musicId: Method,
        val lrc: Method,
        val yrc: Method,
        val lrcTranslation: Method,
        val yrcTranslation: Method,
        val lrcRomanization: Method,
        val yrcRomanization: Method,
    )

    private val accessors = ConcurrentHashMap<Class<*>, Accessors>()

    fun read(argument: Any?): NeteaseAppLyricsSnapshot? {
        val data = argument ?: return null
        val clazz = data.javaClass
        if (clazz.name != NeteaseAppLyricsProfile.LYRIC_DATA_CLASS &&
            clazz.superclass?.name != NeteaseAppLyricsProfile.LYRIC_DATA_CLASS
        ) return null
        val methods = accessors.computeIfAbsent(clazz) { type ->
            Accessors(
                musicId = type.getMethod("getMusicId"),
                lrc = type.getMethod("getLrc"),
                yrc = type.getMethod("getYrc"),
                lrcTranslation = type.getMethod("getLrcTranslateLyric"),
                yrcTranslation = type.getMethod("getYrcTranslateLyric"),
                lrcRomanization = type.getMethod("getLrcRomeLyric"),
                yrcRomanization = type.getMethod("getYrcRomeLyric"),
            )
        }
        val musicId = (methods.musicId.invoke(data) as? Number)?.toLong() ?: return null
        if (musicId <= 0L) return null
        val lrc = methods.lrc.invoke(data) as? String
        val yrc = methods.yrc.invoke(data) as? String
        if (lrc.isNullOrBlank() && yrc.isNullOrBlank()) return null
        return NeteaseAppLyricsSnapshot(
            musicId = musicId,
            lrc = lrc,
            yrc = yrc,
            lrcTranslation = methods.lrcTranslation.invoke(data) as? String,
            yrcTranslation = methods.yrcTranslation.invoke(data) as? String,
            lrcRomanization = methods.lrcRomanization.invoke(data) as? String,
            yrcRomanization = methods.yrcRomanization.invoke(data) as? String,
        )
    }
}
