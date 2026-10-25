/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kugou

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodQuery
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget

/**
 * 酷狗完整版「下一首」读取路径在某个版本区间内的混淆名。
 *
 * 路径是 `KGPlayerManager` 单例 → `QueuePlayerManager` 里返回 `IMedia` 的下一首方法，
 * 后者调用 `PlayQueue` 的「下一首下标」`()I` 与「按下标取项」`(I)Object`。
 * 这些方法名每个大版本都会重排，宿主的 DexKit 查询只能按名字匹配被调方法，所以名字必须
 * 逐版本登记，不能凭记忆沿用上一版。
 */
internal data class KuGouFullNextTrackProfile(
    /** 该档适用的最小 versionCode（含）。 */
    val minVersionCode: Long,
    /** 该档由哪个酷狗安装包的原始 DEX 核对得出，仅用于日志与文档。 */
    val verifiedAgainst: String,
    val singletonMethodName: String,
    val nextMediaMethodName: String,
    /** `PlayQueue.()I`，按播放模式算出下一首的下标。 */
    val nextIndexMethodName: String,
    /** `PlayQueue.(I)Object`，按下标取队列项。 */
    val mediaAtIndexMethodName: String,
    val nextMediaCacheKey: String,
)

internal object KuGouNextTrackProfiles {
    const val MANAGER_CLASS = "com.kugou.framework.service.KGPlayerManager"
    const val QUEUE_MANAGER_CLASS = "com.kugou.common.player.manager.QueuePlayerManager"
    const val MEDIA_INTERFACE = "com.kugou.common.player.manager.IMedia"

    const val FULL_SINGLETON_CACHE_KEY = "kugou-full-player-singleton-v3"
    const val LITE_SINGLETON_CACHE_KEY = "kugou-lite-player-singleton-v3"
    const val LITE_NEXT_MEDIA_CACHE_KEY = "kugou-lite-next-media-v2"

    /**
     * 按 [KuGouFullNextTrackProfile.minVersionCode] 从大到小排列。
     *
     * - 20829（20.8.2）：`QueuePlayerManager.j()` 调 `PlayQueue.t()` 与 `PlayQueue.s(int)`。
     *   酷狗自己的日志串 `QUEUE_CHANGED -> getNextMedia():` 就在调用 `j()` 的方法里；
     *   `t()` 取 `k+1`（随机模式取 `RandomPlayQueue.e()`），上一首的 `w()` 取 `i-1`。
     * - 20759（20.7.5）：`QueuePlayerManager.k()` 调 `PlayQueue.w()` 与 `PlayQueue.v(int)`。
     *   完整版 APK 没有名为 getNextMedia 的方法，不能套用概念版的调用方锚点。
     */
    private val fullProfiles = listOf(
        KuGouFullNextTrackProfile(
            minVersionCode = 20829L,
            verifiedAgainst = "20.8.2 (20829)",
            singletonMethodName = "I4",
            nextMediaMethodName = "j",
            nextIndexMethodName = "t",
            mediaAtIndexMethodName = "s",
            nextMediaCacheKey = "kugou-full-next-media-v3",
        ),
        KuGouFullNextTrackProfile(
            minVersionCode = 0L,
            verifiedAgainst = "20.7.5 (20759)",
            singletonMethodName = "K4",
            nextMediaMethodName = "k",
            nextIndexMethodName = "w",
            mediaAtIndexMethodName = "v",
            nextMediaCacheKey = "kugou-full-next-media-v2",
        ),
    )

    /**
     * 取 `minVersionCode` 不大于 [versionCode] 的最新一档；版本读不到（null）时按最新一档。
     * 20760–20828 与 20829 之后的版本没有核对过，沿用相邻一档：名字对不上时 DexKit 查询为空，
     * 解析器不会启用，不会读到错误的曲目。
     */
    fun fullProfileFor(versionCode: Long?): KuGouFullNextTrackProfile =
        if (versionCode == null) {
            fullProfiles.first()
        } else {
            fullProfiles.first { versionCode >= it.minVersionCode }
        }

    fun queriesFor(
        full: Boolean,
        versionCode: Long?,
    ): List<OfficialProviderDexMethodQuery> {
        val singletonCacheKey = if (full) FULL_SINGLETON_CACHE_KEY else LITE_SINGLETON_CACHE_KEY
        val profile = if (full) fullProfileFor(versionCode) else null
        val nextMediaQuery = if (profile != null) {
            OfficialProviderDexMethodQuery(
                cacheKey = profile.nextMediaCacheKey,
                preferredTarget = OfficialProviderMethodTarget(
                    className = QUEUE_MANAGER_CLASS,
                    methodName = profile.nextMediaMethodName,
                    returnTypeName = MEDIA_INTERFACE,
                    isStatic = false,
                ),
                declaringClassName = QUEUE_MANAGER_CLASS,
                requiredInvokedMethodNames = listOf(
                    profile.nextIndexMethodName,
                    profile.mediaAtIndexMethodName,
                ),
                parameterTypeNames = emptyList(),
                returnTypeName = MEDIA_INTERFACE,
                isStatic = false,
            )
        } else {
            // Original KuGou Lite 5.2.4 DEX proves k() reads the current item through
            // PlayQueue.n(). The real next implementation is currently P0(), but its
            // stable bridge getNextMedia() calls it. Resolve by that caller relationship
            // so a future obfuscation rename cannot silently turn the current item into next.
            OfficialProviderDexMethodQuery(
                cacheKey = LITE_NEXT_MEDIA_CACHE_KEY,
                declaringClassName = QUEUE_MANAGER_CLASS,
                requiredCallerMethodNames = listOf("getNextMedia"),
                parameterTypeNames = emptyList(),
                returnTypeName = MEDIA_INTERFACE,
                isStatic = false,
            )
        }
        return listOf(
            OfficialProviderDexMethodQuery(
                cacheKey = singletonCacheKey,
                preferredTarget = OfficialProviderMethodTarget(
                    className = MANAGER_CLASS,
                    methodName = profile?.singletonMethodName ?: "c4",
                    returnTypeName = MANAGER_CLASS,
                    isStatic = true,
                ),
                declaringClassName = MANAGER_CLASS,
                parameterTypeNames = emptyList(),
                returnTypeName = MANAGER_CLASS,
                isStatic = true,
            ),
            nextMediaQuery,
        )
    }
}
