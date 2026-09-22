/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

import android.app.Application
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodQuery
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget

internal object BodianBufferHookResolver {
    const val BUFFER_START_CACHE_KEY = "bodian-buffer-start-v1"
    const val BUFFER_END_CACHE_KEY = "bodian-buffer-end-v1"

    private const val VERIFIED_IMPL_CLASS = "com.tme.push.p3.e"
    private const val IMPL_CLASS_ANCHOR = "api/play/music/v2/audioUrl"
    private const val VERIFIED_VERSION_NAME = "5.8.7"
    private const val VERIFIED_VERSION_CODE = 472L

    /**
     * 已对照波点 5.8.7（472）原始 DEX 验证：AIDL 播放事件收口
     * AIDLPlayDelegateImpl（混淆名 com.tme.push.p3.e，extends 未混淆的
     * AIDLPlayDelegate.Stub）的 PlayDelegate_WaitForBuffering / Finish 是权威缓冲边界。
     *
     * 该类存在多个同为 ()V 实例方法的 AIDL 覆写（PlayDelegate_Continue/OnRestart 等），
     * 查询 schema 没有方法名维度，脱离精确目标的结构查询必然歧义；因此只在已验证
     * 版本直连精确目标，其余版本返回 null 跳过缓冲合成（歌词主链路不受影响）。
     * 波点更新后必须重新取证，禁止猜测别名——与 KuwoHookProfiles 同一纪律。
     */
    fun queries(application: Application): List<OfficialProviderDexMethodQuery>? {
        val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
        return queries(
            packageName = application.packageName,
            versionName = packageInfo.versionName.orEmpty(),
            versionCode = packageInfo.longVersionCode,
        )
    }

    internal fun queries(
        packageName: String,
        versionName: String,
        versionCode: Long,
    ): List<OfficialProviderDexMethodQuery>? {
        if (packageName != KuwoHostPlan.BODIAN_PACKAGE) return null
        if (versionName != VERIFIED_VERSION_NAME || versionCode != VERIFIED_VERSION_CODE) return null
        fun verifiedTarget(methodName: String) = OfficialProviderMethodTarget(
            className = VERIFIED_IMPL_CLASS,
            methodName = methodName,
            parameterTypeNames = emptyList(),
            returnTypeName = "void",
            isStatic = false,
        )
        return listOf(
            OfficialProviderDexMethodQuery(
                cacheKey = BUFFER_START_CACHE_KEY,
                preferredTarget = verifiedTarget("PlayDelegate_WaitForBuffering"),
                requiredStrings = listOf(IMPL_CLASS_ANCHOR),
                parameterTypeNames = emptyList(),
                returnTypeName = "void",
                isStatic = false,
            ),
            OfficialProviderDexMethodQuery(
                cacheKey = BUFFER_END_CACHE_KEY,
                preferredTarget = verifiedTarget("PlayDelegate_WaitForBufferingFinish"),
                requiredStrings = listOf(IMPL_CLASS_ANCHOR),
                parameterTypeNames = emptyList(),
                returnTypeName = "void",
                isStatic = false,
            ),
        )
    }
}
