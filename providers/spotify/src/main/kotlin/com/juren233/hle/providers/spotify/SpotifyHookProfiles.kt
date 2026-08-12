/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodQuery
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderConstructorTarget
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget

internal object SpotifyHookProfiles {
    const val VERIFIED_VERSION_NAME = "9.1.72.1891"
    const val VERIFIED_VERSION_CODE = 144716725L
    const val PLAYER_STATE_CLASS = "com.spotify.player.model.PlayerState"

    // Spotify 9.1.72.1891 原始 DEX 证据：
    // Lp/kg80; 返回同一 track URI 的 color-lyrics 响应后，Lp/e980; 才会构造
    // Lp/v581;-><init>(Ljava/lang/String;Lp/s2e;)V。这是首个同时保留请求标识
    // 和已解析歌词的稳定边界。
    val lyricsSuccessConstructor = OfficialProviderConstructorTarget(
        className = "p.v581",
        parameterTypeNames = listOf("java.lang.String", "p.s2e"),
    )

    val queueStateQuery = OfficialProviderDexMethodQuery(
        cacheKey = "spotify-player-state-next-tracks-v1",
        preferredTarget = OfficialProviderMethodTarget(
            className = "p.zw21",
            methodName = "g",
            parameterTypeNames = listOf(PLAYER_STATE_CLASS, "boolean", "boolean"),
            returnTypeName = "p.bp21",
            isStatic = true,
        ),
        declaringClassNamePrefix = "p.",
        requiredInvokedMethodNames = listOf(
            "prevTracks",
            "track",
            "nextTracks",
            "restrictions",
            "disallowSkippingNextReasons",
            "disallowPeekingNextReasons",
        ),
        parameterTypeNames = listOf(PLAYER_STATE_CLASS, "boolean", "boolean"),
        returnTypeNamePrefix = "p.",
        isStatic = true,
    )

}
