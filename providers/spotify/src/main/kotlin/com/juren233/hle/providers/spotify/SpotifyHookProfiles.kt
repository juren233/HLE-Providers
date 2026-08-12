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
    const val LYRICS_CLIENT_INTERFACE = "p.kg80"

    // Spotify 9.1.72.1891 原始 classes6.dex：
    // Lp/am80;-><init>(Lp/xl80;Lp/q2m;Lp/xhe;)V 封装 v3 Retrofit；
    // Lp/lg80;-><init>(Lp/g980;Lp/q2m;Lp/q2m;Lp/xhe;)V 封装 v2 Retrofit。
    // 二者都由 Spotify DI 直接创建，b(trackUri, language) 会自行计算
    // vocalRemoval、preview 与 clientLanguage，并把 protobuf 映射成 p.s2e。
    // 必须捕获构造完成后的实例，不能自行构造客户端或读取 token。
    val lyricsClientConstructors = listOf(
        SpotifyLyricsClientConstructorProfile(
            endpoint = SpotifyLyricsEndpoint.V3,
            target = OfficialProviderConstructorTarget(
                className = "p.am80",
                parameterTypeNames = listOf("p.xl80", "p.q2m", "p.xhe"),
            ),
        ),
        SpotifyLyricsClientConstructorProfile(
            endpoint = SpotifyLyricsEndpoint.V2,
            target = OfficialProviderConstructorTarget(
                className = "p.lg80",
                parameterTypeNames = listOf("p.g980", "p.q2m", "p.q2m", "p.xhe"),
            ),
        ),
    )

    // p.hx3.b() 对应远程配置 enable_v3_lyrics_endpoint。原始 DI 分支确认
    // true 选择 v3 am80，false 选择 v2 lg80。观察结果只决定使用哪个已经
    // 捕获的 Spotify 客户端，不修改返回值，也不自行覆盖远程配置。
    val lyricsEndpointSelection = OfficialProviderMethodTarget(
        className = "p.hx3",
        methodName = "b",
        parameterTypeNames = emptyList(),
        returnTypeName = "boolean",
        isStatic = false,
    )

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

    // Spotify 9.1.72.1891 原始 classes6.dex：
    // Lcom/spotify/player/model/AutoValue_PlayerState;->nextTracks()Lp/f320;
    // 这是 PlayerState 的真实 AutoValue 实现，不是反编译器显示别名。保留
    // p.zw21.g DexKit 主路径，同时直接观察所有真实 PlayerState 队列读取，
    // 避免特定播放上下文不经过 p.zw21.g 时永久拿不到下一首。
    val nextTracksAccessorTarget = OfficialProviderMethodTarget(
        className = "com.spotify.player.model.AutoValue_PlayerState",
        methodName = "nextTracks",
        parameterTypeNames = emptyList(),
        returnTypeName = "p.f320",
        isStatic = false,
    )

}

internal enum class SpotifyLyricsEndpoint {
    V2,
    V3,
    ;

    companion object {
        fun fromEnableV3(enableV3: Boolean): SpotifyLyricsEndpoint =
            if (enableV3) V3 else V2
    }
}

internal data class SpotifyLyricsClientConstructorProfile(
    val endpoint: SpotifyLyricsEndpoint,
    val target: OfficialProviderConstructorTarget,
)
