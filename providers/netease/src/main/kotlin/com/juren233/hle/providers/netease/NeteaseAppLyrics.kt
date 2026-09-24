/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.netease

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodQuery
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Every direct target below was checked against the corresponding original APK DEX. */
internal object NeteaseAppLyricsProfile {
    private val VERIFIED_VERSION_CODES = setOf(9_004_025L, 9_005_081L, 9_006_000L)
    private const val PLAYER_PACKAGE = "com.netease.cloudmusic"
    const val LYRIC_DATA_CLASS = "com.netease.cloudmusic.meta.LyricData"

    // Original 9.4.25 classes19.dex: f.u0(LyricData,f$e):LyricInfo at 0x17a2bc.
    // f.k0 invokes it on the actual lyric-load path; e is only a Runnable in this APK.
    private val v9_4_25LyricResultTarget = OfficialProviderMethodTarget(
        className = "com.netease.cloudmusic.module.lyric.f",
        methodName = "u0",
        parameterTypeNames = listOf(
            LYRIC_DATA_CLASS,
            "com.netease.cloudmusic.module.lyric.f\$e",
        ),
        returnTypeName = "com.netease.cloudmusic.meta.LyricInfo",
        isStatic = false,
    )

    // classes19.dex: Lcom/netease/cloudmusic/module/lyric/e;->v0(
    //   Lcom/netease/cloudmusic/meta/LyricData;
    //   Lcom/netease/cloudmusic/module/lyric/e$d;)
    //   Lcom/netease/cloudmusic/meta/LyricInfo; (private instance method).
    // 9.6.0 original classes18.dex retains the exact v0 descriptor at 0x26a7ac;
    // classes16.dex retains LyricData.getMusicId()J and the seven String getters.
    // Do not use JADX's displayed inner-class aliases for this runtime lookup.
    private val v9_5_81And9_6_0LyricResultTarget = OfficialProviderMethodTarget(
        className = "com.netease.cloudmusic.module.lyric.e",
        methodName = "v0",
        parameterTypeNames = listOf(
            LYRIC_DATA_CLASS,
            "com.netease.cloudmusic.module.lyric.e\$d",
        ),
        returnTypeName = "com.netease.cloudmusic.meta.LyricInfo",
        isStatic = false,
    )

    fun targetFor(packageName: String, versionCode: Long): OfficialProviderMethodTarget? {
        if (packageName != PLAYER_PACKAGE) return null
        return when (versionCode) {
            9_004_025L -> v9_4_25LyricResultTarget
            9_005_081L, 9_006_000L -> v9_5_81And9_6_0LyricResultTarget
            else -> null
        }
    }

    /** Resolve the non-obfuscated conversion shared by the 9.4.25 and 9.6.0 lyric paths. */
    fun compatibilityQueryFor(
        packageName: String,
        versionCode: Long,
    ): OfficialProviderDexMethodQuery? {
        if (packageName != PLAYER_PACKAGE || versionCode <= 0L || versionCode in VERIFIED_VERSION_CODES) {
            return null
        }
        return OfficialProviderDexMethodQuery(
            cacheKey = "netease-app-lyrics-conversion-v2",
            declaringClassName = "com.netease.cloudmusic.meta.LyricInfo",
            parameterTypeNames = listOf(LYRIC_DATA_CLASS),
            returnTypeName = "com.netease.cloudmusic.meta.LyricInfo",
            isStatic = true,
        )
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
