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

    // Spotify 9.1.72.1891 原始 DEX 确认 p.kg80 只有两个具体实现，
    // 依赖注入同时构造二者并由 p.hx3.b() 功能开关选择：
    // Lp/am80;->b(Ljava/lang/String;Ljava/lang/String;)Lio/reactivex/rxjava3/core/Single;
    // Lp/lg80;->b(Ljava/lang/String;Ljava/lang/String;)Lio/reactivex/rxjava3/core/Single;
    // 两条分支都返回 Single<p.s2e>，必须同时安装结果 Hook；p.v581 仅是
    // 其中一个 Mobius 消费分支，不能用作全局成功边界。
    val lyricsRequests = listOf(
        lyricsRequestTarget("p.am80"),
        lyricsRequestTarget("p.lg80"),
    )

    private fun lyricsRequestTarget(className: String) = OfficialProviderMethodTarget(
        className = className,
        methodName = "b",
        parameterTypeNames = listOf("java.lang.String", "java.lang.String"),
        returnTypeName = "io.reactivex.rxjava3.core.Single",
        isStatic = false,
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
