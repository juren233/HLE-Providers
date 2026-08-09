/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qishui

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodQuery
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexTypeReference
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexTypeSource
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget

internal object QishuiHookProfiles {
    const val VERIFIED_VERSION_NAME = "20.4.0"
    const val VERIFIED_VERSION_CODE = 100204030L

    const val NET_TRACK_LYRIC_CLASS =
        "com.luna.common.arch.db.entity.lyrics.NetTrackLyric"
    const val TRACK_LYRIC_CLASS = "com.luna.common.arch.db.entity.TrackLyric"
    const val QUEUE_ITEM_CLASS = "com.luna.common.player.queue.api.IQueueItem"
    const val DELEGATE_CONTROLLER_CLASS =
        "com.luna.biz.playing.player.DelegatePlayerController"

    val lyricConversionQuery = OfficialProviderDexMethodQuery(
        cacheKey = "qishui-net-track-lyric-conversion-v1",
        preferredTarget = OfficialProviderMethodTarget(
            className = "com.luna.common.arch.db.entity.lyrics.NetTrackLyricKt",
            methodName = "a",
            parameterTypeNames = listOf(
                NET_TRACK_LYRIC_CLASS,
                "java.lang.String",
                "java.lang.String",
            ),
            returnTypeName = TRACK_LYRIC_CLASS,
            isStatic = true,
        ),
        declaringClassName = "com.luna.common.arch.db.entity.lyrics.NetTrackLyricKt",
        requiredInvokedMethodNames = listOf(
            "getId",
            "getType",
            "getContent",
            "getLang",
            "getLangTranslations",
        ),
        parameterTypeNames = listOf(
            NET_TRACK_LYRIC_CLASS,
            "java.lang.String",
            "java.lang.String",
        ),
        returnTypeName = TRACK_LYRIC_CLASS,
        isStatic = true,
    )

    fun queueQueries(): List<OfficialProviderDexMethodQuery> {
        val controllerReference = OfficialProviderDexTypeReference(
            queryCacheKey = "qishui-player-controller-singleton-v1",
            source = OfficialProviderDexTypeSource.RETURN_TYPE,
        )
        fun queueTarget(name: String) = OfficialProviderMethodTarget(
            className = DELEGATE_CONTROLLER_CLASS,
            methodName = name,
            parameterTypeNames = emptyList(),
            returnTypeName = QUEUE_ITEM_CLASS,
            isStatic = false,
        )
        return listOf(
            OfficialProviderDexMethodQuery(
                cacheKey = controllerReference.queryCacheKey,
                preferredTarget = OfficialProviderMethodTarget(
                    className = "com.luna.biz.playing.player.PlayerControllerKt",
                    methodName = "a",
                    parameterTypeNames = emptyList(),
                    returnTypeName = DELEGATE_CONTROLLER_CLASS,
                    isStatic = true,
                ),
                declaringClassName = "com.luna.biz.playing.player.PlayerControllerKt",
                parameterTypeNames = emptyList(),
                returnTypeName = DELEGATE_CONTROLLER_CLASS,
                isStatic = true,
            ),
            OfficialProviderDexMethodQuery(
                cacheKey = "qishui-current-queue-item-v1",
                preferredTarget = queueTarget("getCurrentQueueItem"),
                declaringClassReference = controllerReference,
                requiredInvokedMethodNames = listOf("getCurrentQueueItem"),
                parameterTypeNames = emptyList(),
                returnTypeName = QUEUE_ITEM_CLASS,
                isStatic = false,
            ),
            OfficialProviderDexMethodQuery(
                cacheKey = "qishui-real-next-queue-item-v1",
                preferredTarget = queueTarget("getRealNextQueueItem"),
                declaringClassReference = controllerReference,
                requiredInvokedMethodNames = listOf("getRealNextQueueItem"),
                parameterTypeNames = emptyList(),
                returnTypeName = QUEUE_ITEM_CLASS,
                isStatic = false,
            ),
            OfficialProviderDexMethodQuery(
                cacheKey = "qishui-next-queue-item-v1",
                preferredTarget = queueTarget("getNextQueueItem"),
                declaringClassReference = controllerReference,
                requiredInvokedMethodNames = listOf("getNextQueueItem"),
                parameterTypeNames = emptyList(),
                returnTypeName = QUEUE_ITEM_CLASS,
                isStatic = false,
            ),
        )
    }
}
