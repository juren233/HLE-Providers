/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodQuery
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget

internal object BodianNextTrackHookResolver {
    const val CURRENT_CACHE_KEY = "bodian-play-current-v1"
    const val PREFETCH_CACHE_KEY = "bodian-prefetch-next-v1"

    // 已对照波点 5.8.7（472）原始 DEX 验证：PlayManager 是 :service 进程的播放单例
    // （真机音频焦点日志同一来源），类与方法名均未混淆（AIDL 命令面），跨版本稳定。
    private const val PLAY_MANAGER_CLASS = "cn.kuwo.service.remote.kwplayer.PlayManager"
    private const val MUSIC_BEAN = "cn.kuwo.player.bean.Music"

    fun queries(packageName: String): List<OfficialProviderDexMethodQuery>? {
        if (packageName != KuwoHostPlan.BODIAN_PACKAGE) return null
        return listOf(
            OfficialProviderDexMethodQuery(
                cacheKey = CURRENT_CACHE_KEY,
                preferredTarget = OfficialProviderMethodTarget(
                    className = PLAY_MANAGER_CLASS,
                    methodName = "play",
                    parameterTypeNames = listOf(MUSIC_BEAN, "int", "boolean"),
                    returnTypeName = "void",
                    isStatic = false,
                ),
                declaringClassName = PLAY_MANAGER_CLASS,
                parameterTypeNames = listOf(MUSIC_BEAN, "int", "boolean"),
                returnTypeName = "void",
                isStatic = false,
            ),
            OfficialProviderDexMethodQuery(
                cacheKey = PREFETCH_CACHE_KEY,
                preferredTarget = OfficialProviderMethodTarget(
                    className = PLAY_MANAGER_CLASS,
                    methodName = "prefetch",
                    parameterTypeNames = listOf(MUSIC_BEAN),
                    returnTypeName = "void",
                    isStatic = false,
                ),
                declaringClassName = PLAY_MANAGER_CLASS,
                parameterTypeNames = listOf(MUSIC_BEAN),
                returnTypeName = "void",
                isStatic = false,
            ),
        )
    }
}
