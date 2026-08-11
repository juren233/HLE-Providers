/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodQuery
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget

internal object SpotifyHookProfiles {
    const val VERIFIED_VERSION_NAME = "9.1.72.1891"
    const val VERIFIED_VERSION_CODE = 144716725L
    const val PLAYER_STATE_CLASS = "com.spotify.player.model.PlayerState"

    // Retrofit paths verified in p.g980 annotations:
    // color-lyrics/v2/track/{trackId} and color-lyrics/v2/download/track/{trackId}.
    val lyricsQuery = OfficialProviderDexMethodQuery(
        cacheKey = "spotify-color-lyrics-cache-write-v2",
        preferredTarget = OfficialProviderMethodTarget(
            className = "p.tix0",
            methodName = "n",
            parameterTypeNames = listOf("java.lang.Object", "java.lang.Object"),
            returnTypeName = "java.lang.Object",
            isStatic = false,
        ),
        declaringClassNamePrefix = "p.",
        requiredInvokedMethodDescriptors = listOf(
            "Ljava/util/AbstractMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
        ),
        requiredInvokedMethodNames = listOf("e", "p"),
        parameterTypeNames = listOf("java.lang.Object", "java.lang.Object"),
        returnTypeName = "java.lang.Object",
        isStatic = false,
        requiredCallerMethodNames = listOf("accept"),
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
