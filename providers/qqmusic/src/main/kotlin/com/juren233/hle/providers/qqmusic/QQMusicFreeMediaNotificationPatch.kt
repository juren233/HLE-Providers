/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import android.app.Notification
import android.media.session.MediaSession
import android.util.Log
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHost
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget

/**
 * QQ 音乐简洁版的播放通知用了 MediaStyle 却没调 setMediaSession（1.0.1 反编译确认：
 * cn.wenyu.bodian.HostMediaNotification 构建通知时只设了 setShowActionsInCompactView），
 * extras 里没有 [Notification.EXTRA_MEDIA_SESSION]，系统把它当普通应用通知，不出媒体卡片、
 * 不上岛，HLE 也就没有原生媒体岛可以注入歌词。
 *
 * 这里在 App 进程内把它自己的 QQFreeMusicSession 会话 token 补进那条通知：
 * - 会话：Hook 框架 MediaSession.setPlaybackState/setMetadata，按 controller tag 认出
 *   QQFreeMusicSession 并记下 token（另一个 QQMusicEdgeSdk 会话只有标题/歌手，不用它）；
 * - 通知：Hook 框架 Notification.Builder.build() 的返回值，只改播放控制渠道的 MediaStyle 通知。
 * 两个 Hook 点都是框架方法，不依赖 App 里混淆过的方法名。
 */
internal class QQMusicFreeMediaNotificationPatch(
    private val host: OfficialProviderHost,
) {
    @Volatile
    private var sessionToken: MediaSession.Token? = null

    @Volatile
    private var attachLogged = false

    fun install() {
        SESSION_METHODS.forEach { (methodName, parameterType) ->
            runCatching {
                host.hookAfterMethod(
                    OfficialProviderMethodTarget(
                        className = "android.media.session.MediaSession",
                        methodName = methodName,
                        parameterTypeNames = listOf(parameterType),
                        returnTypeName = "void",
                        isStatic = false,
                    ),
                ) { receiver, _ -> rememberSession(receiver as? MediaSession) }
            }.onFailure { Log.w(TAG, "简洁版会话 Hook 安装失败: $methodName", it) }
        }
        runCatching {
            host.hookMethodResult(
                OfficialProviderMethodTarget(
                    className = "android.app.Notification\$Builder",
                    methodName = "build",
                    returnTypeName = "android.app.Notification",
                    isStatic = false,
                ),
            ) { _, _, result ->
                (result as? Notification)?.let(::attachSessionToken)
                result
            }
        }.onFailure { Log.w(TAG, "简洁版通知 Hook 安装失败", it) }
    }

    private fun rememberSession(session: MediaSession?) {
        if (session == null || sessionToken != null) return
        val tag = runCatching { session.controller.tag }.getOrNull()
        if (tag != PLAYBACK_SESSION_TAG) return
        sessionToken = session.sessionToken
        Log.i(TAG, "简洁版播放会话已记录: tag=$tag")
    }

    private fun attachSessionToken(notification: Notification) {
        val token = sessionToken ?: return
        val extras = notification.extras ?: return
        val decision = decide(
            channelId = notification.channelId,
            template = extras.getString(Notification.EXTRA_TEMPLATE),
            hasSessionToken = extras.containsKey(Notification.EXTRA_MEDIA_SESSION),
        )
        if (!decision) return
        extras.putParcelable(Notification.EXTRA_MEDIA_SESSION, token)
        if (!attachLogged) {
            attachLogged = true
            Log.i(TAG, "简洁版播放通知已补上会话 token")
        }
    }

    companion object {
        private const val TAG = "HLEProvider/QQMusic"
        const val PLAYBACK_SESSION_TAG = "QQFreeMusicSession"
        const val PLAYBACK_CHANNEL_ID = "com.tencent.qqfreemusic.play"
        private const val MEDIA_STYLE_TEMPLATE = "android.app.Notification\$MediaStyle"

        private val SESSION_METHODS = listOf(
            "setPlaybackState" to "android.media.session.PlaybackState",
            "setMetadata" to "android.media.MediaMetadata",
        )

        /** 只补播放控制渠道、MediaStyle 模板、还没带会话的通知；App 以后自己补上了就不动。 */
        internal fun decide(channelId: String?, template: String?, hasSessionToken: Boolean): Boolean =
            channelId == PLAYBACK_CHANNEL_ID && template == MEDIA_STYLE_TEMPLATE && !hasSessionToken
    }
}
