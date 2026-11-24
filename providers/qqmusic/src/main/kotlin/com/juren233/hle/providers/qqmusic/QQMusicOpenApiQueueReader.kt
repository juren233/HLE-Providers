/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * QQ 音乐简洁版（com.tencent.freemusic，壳是波点音乐的 Flutter 代码，播放走 QQ 音乐 OpenAPI SDK）
 * 的当前曲/下一首采集。它的两个 MediaSession（QQFreeMusicSession、QQMusicEdgeSdk）都不写
 * MEDIA_ID，App 自带的状态栏/车载/蓝牙歌词还会把标题、歌手改成歌词行，所以歌曲身份只从
 * 播放器 SongInfo 取。
 *
 * 1.0.1（1000102）反编译确认的入口（类名、方法名都没混淆，DEX 描述符逐个核对过）：
 * - OpenApiSDK.getPlayerApi()：public static；SDK 未初始化或已销毁时抛 SDKException
 * - PlayerApi.getCurrentSongInfo()：接口方法
 * - PlayerManagerImpl.getNextSongInfo()：实现类上的 public 方法，不在接口里，按运行时类反射；
 *   SDK 走跨进程模式时它返回 null（只影响下一首预告）
 * - SongInfo.getSongId():long、getSongName()、getSingerName()、getSongMid()、
 *   getSongPlayTime():Integer（秒；SDK 自己的 RemoteControlManager 乘 1000 当毫秒用）
 */
internal class QQMusicOpenApiQueueReader(
    private val playerApi: () -> Any?,
) : QQMusicQueueSnapshotSource {
    private val methods = ConcurrentHashMap<Pair<Class<*>, String>, Method>()
    private val missingMethods: MutableSet<Pair<Class<*>, String>> = ConcurrentHashMap.newKeySet()

    override fun resolve(): QQMusicQueueSnapshot? {
        val api = playerApi() ?: return null
        val currentSong = invoke(api, GET_CURRENT_SONG, required = true) ?: return null
        val current = toTrack(currentSong) ?: return null
        val next = runCatching {
            invoke(api, GET_NEXT_SONG, required = false)?.let(::toTrack)
        }.getOrNull()
        return QQMusicQueueSnapshot(current, next)
    }

    private fun toTrack(song: Any): QQMusicTrackSnapshot? {
        val id = (invoke(song, GET_SONG_ID, required = true) as? Number)?.toLong() ?: return null
        val title = (invoke(song, GET_SONG_NAME, required = true) as? String)?.trim().orEmpty()
        val artist = (invoke(song, GET_SINGER_NAME, required = false) as? String)?.trim().orEmpty()
        val songMid = (invoke(song, GET_SONG_MID, required = false) as? String)
            ?.trim()?.takeIf(String::isNotEmpty)
        val seconds = (invoke(song, GET_PLAY_TIME, required = false) as? Number)?.toLong() ?: 0L
        return QQMusicTrackSnapshot(
            id = id.toString(),
            title = title,
            artist = artist,
            songMid = songMid,
            duration = if (seconds > 0L) seconds * 1_000L else 0L,
        )
    }

    /**
     * [required] 的方法缺失直接抛出（采集失败计数，连续失败后停止轮询并上报）；
     * 可选方法缺失记一次后不再查找，按 null 处理。
     */
    private fun invoke(target: Any, name: String, required: Boolean): Any? {
        val key = target.javaClass to name
        if (!required && key in missingMethods) return null
        val method = methods[key] ?: runCatching { target.javaClass.getMethod(name) }
            .getOrElse { error ->
                if (required) throw error
                missingMethods += key
                return null
            }
            .also { methods[key] = it }
        return method.invoke(target)
    }

    companion object {
        const val OPEN_API_SDK_CLASS = "com.tencent.qqmusic.openapisdk.core.OpenApiSDK"
        private const val GET_PLAYER_API = "getPlayerApi"
        private const val GET_CURRENT_SONG = "getCurrentSongInfo"
        private const val GET_NEXT_SONG = "getNextSongInfo"
        private const val GET_SONG_ID = "getSongId"
        private const val GET_SONG_NAME = "getSongName"
        private const val GET_SINGER_NAME = "getSingerName"
        private const val GET_SONG_MID = "getSongMid"
        private const val GET_PLAY_TIME = "getSongPlayTime"

        /** 入口类或静态方法不存在时抛出：说明不是已知的 OpenAPI SDK 形态。 */
        fun create(classLoader: ClassLoader): QQMusicOpenApiQueueReader {
            val getPlayerApi = classLoader.loadClass(OPEN_API_SDK_CLASS).getMethod(GET_PLAYER_API)
            require(Modifier.isStatic(getPlayerApi.modifiers)) {
                "$OPEN_API_SDK_CLASS.$GET_PLAYER_API is not static"
            }
            return QQMusicOpenApiQueueReader {
                try {
                    getPlayerApi.invoke(null)
                } catch (error: InvocationTargetException) {
                    // SDK 未初始化/已销毁（SDKException）：本轮没有快照，下轮再取
                    null
                }
            }
        }
    }
}
