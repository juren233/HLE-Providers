/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.provider

import android.app.Application
import android.content.pm.PackageManager
import android.media.session.PlaybackState
import android.util.Log
import dalvik.system.BaseDexClassLoader
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Provider Pack 仅供官方 HyperLyrics Enhanced 加载。
 *
 * 两道自检，全部软失败（绝不向宿主音乐软件进程抛出异常）：
 * 1. install 时：根据加载宿主回调实现类的模块 APK 标准安装路径（/data/app/）
 *    判定宿主包名，判定为复刻模块时不注册任何 Hook；
 * 2. 每次开始播放时（播放状态从未播放变为 PLAYING 的沿上，持续播放期间不复检）：
 *    复核模块 APK 路径，并补充 install 阶段拿不到的官方包安装证据。
 * 任一道判定为复刻模块后，插件在本次进程内停用（媒体回调不再处理，不恢复）。
 * 无法判定时放行，由 Pack 签名体系继续兜底内容完整性。
 */
object OfficialCoreHostGuard {
    private const val TAG = "HLEProvider/CoreGuard"
    const val OFFICIAL_CORE_PACKAGE = "com.juren233.hyperlyricsenhanced"
    private const val APP_INSTALL_PATH_PREFIX = "/data/app/"

    @Volatile
    private var deactivated = false

    @Volatile
    private var installedHost: Any? = null

    private val playing = AtomicBoolean(false)

    /**
     * install 时调用。返回 true 表示已确认宿主不是官方 HyperLyrics Enhanced，
     * 调用方应直接返回、不注册任何 Hook。
     */
    fun isForeignCoreHost(host: Any): Boolean {
        installedHost = host
        val foreign = isForeignCoreHostInternal(host)
        if (foreign) {
            deactivated = true
            Log.w(TAG, "HLE插件仅限官方HLE使用，当前宿主模块: " + firstModuleApkPath(host))
        }
        return foreign
    }

    /**
     * 播放状态回调第一行调用：只在“未播放 → PLAYING”的沿上复检一次，
     * 持续播放期间的状态刷新不触发任何检测。
     */
    fun onPlaybackStateChanged(state: PlaybackState?) {
        if (deactivated) return
        if (state?.state != PlaybackState.STATE_PLAYING) {
            playing.set(false)
            return
        }
        if (!playing.compareAndSet(false, true)) return
        recheckOnPlaybackStarted()
    }

    /** 复检确认宿主为复刻模块后返回 true；媒体回调应直接忽略后续更新。 */
    fun isDeactivated(): Boolean = deactivated

    private fun recheckOnPlaybackStarted() {
        val host = installedHost ?: return
        if (isForeignCoreHostInternal(host)) {
            deactivate(host, "（播放期复检）")
            return
        }
        val application = currentApplication() ?: return
        val officialInstalled = runCatching {
            application.packageManager.getPackageInfo(OFFICIAL_CORE_PACKAGE, 0)
            true
        }.recoverCatching { error ->
            if (error is PackageManager.NameNotFoundException) false else throw error
        }.getOrNull()
        if (officialInstalled == false) {
            deactivate(host, "（播放期复检：设备上未安装官方包）")
        }
    }

    private fun deactivate(host: Any, reason: String) {
        deactivated = true
        Log.w(
            TAG,
            "HLE插件仅限官方HLE使用$reason，当前宿主模块: " + firstModuleApkPath(host),
        )
    }

    private fun isForeignCoreHostInternal(host: Any): Boolean = runCatching {
        val installPaths = moduleApkPaths(host.javaClass.classLoader)
            .filter { it.startsWith(APP_INSTALL_PATH_PREFIX) }
        if (installPaths.isEmpty()) return@runCatching false
        !installPaths.any(::looksLikeOfficialCoreApkPath)
    }.getOrDefault(false)

    private fun currentApplication(): Application? = runCatching {
        val activityThread = Class.forName(
            "android.app.ActivityThread",
            false,
            OfficialCoreHostGuard::class.java.classLoader,
        )
        activityThread.getDeclaredMethod("currentApplication").invoke(null) as? Application
    }.getOrNull()

    private fun firstModuleApkPath(host: Any): String = runCatching {
        moduleApkPaths(host.javaClass.classLoader)
            .filter { it.startsWith(APP_INSTALL_PATH_PREFIX) }
            .firstOrNull()
    }.getOrNull() ?: "unknown"

    private fun moduleApkPaths(loader: ClassLoader?): List<String> {
        if (loader !is BaseDexClassLoader) return emptyList()
        runCatching {
            (loader.javaClass.getMethod("getPath").invoke(loader) as? String)
                ?.split(File.pathSeparator)
                ?.filter { it.isNotBlank() }
                ?.takeIf(List<String>::isNotEmpty)
                ?.let { return it }
        }
        return runCatching {
            val pathList = BaseDexClassLoader::class.java
                .getDeclaredField("pathList")
                .apply { isAccessible = true }
                .get(loader)
            val elements = pathList.javaClass
                .getDeclaredField("dexElements")
                .apply { isAccessible = true }
                .get(pathList) as Array<*>
            elements.mapNotNull { element ->
                runCatching {
                    element?.javaClass
                        ?.getDeclaredField("path")
                        ?.apply { isAccessible = true }
                        ?.get(element) as? String
                }.getOrNull()
            }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /**
     * 官方包名必须以“/目录段”形式出现，后随目录结束、子目录或 LSPosed 的
     * “包名-随机后缀”分隔符；前缀污染（evil.com.juren233…）与后缀污染
     * （….hyperlyricsenhanced.fork）都不视为官方。
     */
    internal fun looksLikeOfficialCoreApkPath(path: String): Boolean {
        val marker = "/$OFFICIAL_CORE_PACKAGE"
        var index = path.indexOf(marker)
        while (index >= 0) {
            when (path.getOrNull(index + marker.length)) {
                null, '/', '-' -> return true
            }
            index = path.indexOf(marker, index + 1)
        }
        return false
    }
}
