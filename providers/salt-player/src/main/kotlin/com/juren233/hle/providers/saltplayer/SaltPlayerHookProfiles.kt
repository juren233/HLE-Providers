/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget

/**
 * Runtime identifiers verified from the original DEX of 椒盐音乐 12.1.1
 * (versionCode 2026070502). These are raw DEX names, not JADX display aliases.
 */
internal object SaltPlayerHookProfiles {
    const val VERIFIED_VERSION_NAME = "12.1.1"
    const val VERIFIED_VERSION_CODE = 2026070502L

    const val LYRIC_GETTER_API_CLASS = "cn.lyric.getter.api.API"
    const val LYRIC_GETTER_EXTRA_DATA_CLASS = "cn.lyric.getter.api.data.ExtraData"

    val sendLyric = OfficialProviderMethodTarget(
        className = LYRIC_GETTER_API_CLASS,
        methodName = "sendLyric",
        parameterTypeNames = listOf(
            "java.lang.String",
            LYRIC_GETTER_EXTRA_DATA_CLASS,
        ),
        returnTypeName = "void",
        isStatic = false,
    )

    val clearLyric = OfficialProviderMethodTarget(
        className = LYRIC_GETTER_API_CLASS,
        methodName = "clearLyric",
        returnTypeName = "void",
        isStatic = false,
    )
}
