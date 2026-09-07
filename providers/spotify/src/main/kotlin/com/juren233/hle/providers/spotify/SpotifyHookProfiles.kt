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
    const val PLAYER_STATE_CLASS = "com.spotify.player.model.PlayerState"

    // R8 每个版本都会整体挪用短混淆名（9.1.80 把 p.am80/p.lg80/p.kg80/p.hx3
    // 全部变成了无关类，见 DEBUGGING_MISTAKES.md SPOTIFY-LYRICS-002），
    // 歌词链路的精确名 Hook 必须绑定已验证的宿主版本。
    // 未知版本回退最新已验证档案：名字对不上时安装失败的表现与旧档案一致，
    // 而未来版本与新档案结构更接近。
    fun profileFor(versionCode: Long): SpotifyVersionProfile =
        VERSION_PROFILES.lastOrNull { it.versionCode == versionCode }
            ?: VERSION_PROFILES.last()

    // Spotify 9.1.72.1891 原始 classes6.dex：
    // Lp/am80;-><init>(Lp/xl80;Lp/q2m;Lp/xhe;)V 封装 v3 Retrofit；
    // Lp/lg80;-><init>(Lp/g980;Lp/q2m;Lp/q2m;Lp/xhe;)V 封装 v2 Retrofit。
    // 二者都由 Spotify DI 直接创建，b(trackUri, language) 会自行计算
    // vocalRemoval、preview 与 clientLanguage，并把 protobuf 映射成 p.s2e。
    // 必须捕获构造完成后的实例，不能自行构造客户端或读取 token。
    private val SPOTIFY_9_1_72_1891 = SpotifyVersionProfile(
        versionName = "9.1.72.1891",
        versionCode = 144716725L,
        lyricsClientConstructors = listOf(
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
        ),
        // p.hx3.b() 对应远程配置 enable_v3_lyrics_endpoint。原始 DI 分支确认
        // true 选择 v3 am80，false 选择 v2 lg80。观察结果只决定使用哪个已经
        // 捕获的 Spotify 客户端，不修改返回值，也不自行覆盖远程配置。
        lyricsEndpointSelection = OfficialProviderMethodTarget(
            className = "p.hx3",
            methodName = "b",
            parameterTypeNames = emptyList(),
            returnTypeName = "boolean",
            isStatic = false,
        ),
        // p.kg80 只有两个具体实现，依赖注入同时构造二者并由 p.hx3.b() 开关
        // 选择：am80.b / lg80.b 都返回 Single<p.s2e>，必须同时安装结果 Hook；
        // p.v581 仅是其中一个 Mobius 消费分支，不能用作全局成功边界。
        lyricsRequests = listOf(
            lyricsRequestProfile(SpotifyLyricsEndpoint.V3, "p.am80"),
            lyricsRequestProfile(SpotifyLyricsEndpoint.V2, "p.lg80"),
        ),
    )

    // Spotify 9.1.80.2221 原始 DEX（2026-09-07 dexdump + 注解解析取证）：
    // v3 包装类 Lp/vja0;（classes8）ctor (Lp/sja0;Lp/p4n;Lp/qbf;)V，
    // v2 包装类 Lp/gea0;（classes2）ctor (Lp/h7a0;Lp/p4n;Lp/p4n;Lp/qbf;)V，
    // 请求方法同为 b(Ljava/lang/String;Ljava/lang/String;)
    // :Lio/reactivex/rxjava3/core/Single;，由 DI 直接创建。
    // 服务接口以注解串锚定：Lp/sja0; 的 b 带 @GET
    // color-lyrics/v3/track/{trackId}，Lp/h7a0; 的 a 带 v2 同名端点。
    // 与 9.1.72 的差异：vja0/gea0 不再实现公共接口（旧 p.kg80 抽象被内联），
    // 主动请求直接调用具体类的 b；enable_v3 的消费方走接口分发（p.a74 只是
    // 配置模型），静态无法定位开关选择器，因此本档案不装开关 Hook，
    // 活动 endpoint 由请求结果 Hook 首次命中的包装类观测得出。
    private val SPOTIFY_9_1_80_2221 = SpotifyVersionProfile(
        versionName = "9.1.80.2221",
        versionCode = 145767611L,
        lyricsClientConstructors = listOf(
            SpotifyLyricsClientConstructorProfile(
                endpoint = SpotifyLyricsEndpoint.V3,
                target = OfficialProviderConstructorTarget(
                    className = "p.vja0",
                    parameterTypeNames = listOf("p.sja0", "p.p4n", "p.qbf"),
                ),
            ),
            SpotifyLyricsClientConstructorProfile(
                endpoint = SpotifyLyricsEndpoint.V2,
                target = OfficialProviderConstructorTarget(
                    className = "p.gea0",
                    parameterTypeNames = listOf("p.h7a0", "p.p4n", "p.p4n", "p.qbf"),
                ),
            ),
        ),
        lyricsEndpointSelection = null,
        lyricsRequests = listOf(
            lyricsRequestProfile(SpotifyLyricsEndpoint.V3, "p.vja0"),
            lyricsRequestProfile(SpotifyLyricsEndpoint.V2, "p.gea0"),
        ),
    )

    private fun lyricsRequestProfile(
        endpoint: SpotifyLyricsEndpoint,
        className: String,
    ) = SpotifyLyricsRequestProfile(
        endpoint = endpoint,
        target = OfficialProviderMethodTarget(
            className = className,
            methodName = "b",
            parameterTypeNames = listOf("java.lang.String", "java.lang.String"),
            returnTypeName = "io.reactivex.rxjava3.core.Single",
            isStatic = false,
        ),
    )

    private val VERSION_PROFILES = listOf(SPOTIFY_9_1_72_1891, SPOTIFY_9_1_80_2221)

    // Spotify 9.1.72.1891 原始 classes6.dex：
    // Lcom/spotify/player/model/AutoValue_PlayerState;->nextTracks()Lp/f320;
    // 这是 PlayerState 的真实 AutoValue 实现，不是反编译器显示别名。保留
    // p.zw21.g DexKit 主路径，同时直接观察所有真实 PlayerState 队列读取，
    // 避免特定播放上下文不经过 p.zw21.g 时永久拿不到下一首。
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

    val nextTracksAccessorTarget = OfficialProviderMethodTarget(
        className = "com.spotify.player.model.AutoValue_PlayerState",
        methodName = "nextTracks",
        parameterTypeNames = emptyList(),
        returnTypeName = "p.f320",
        isStatic = false,
    )
}

internal data class SpotifyVersionProfile(
    val versionName: String,
    val versionCode: Long,
    val lyricsClientConstructors: List<SpotifyLyricsClientConstructorProfile>,
    val lyricsEndpointSelection: OfficialProviderMethodTarget?,
    val lyricsRequests: List<SpotifyLyricsRequestProfile>,
)

internal data class SpotifyLyricsRequestProfile(
    val endpoint: SpotifyLyricsEndpoint,
    val target: OfficialProviderMethodTarget,
)

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
