/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

/**
 * 同一酷我歌词后端下，按宿主与进程裁剪运行面。
 *
 * cn.kuwo.player（酷我音乐本体，主进程）：
 * 播放队列与当前曲目藏在 playcontrol 混淆类后（KuwoHookProfiles 按版本固化），
 * 下一首依赖该 hook，随 LYRICS 一起在主进程采集。
 *
 * cn.wenyu.bodian（波点音乐，酷我系）：
 * 已对照波点 5.8.7（versionCode 472）原始 DEX 验证：
 * - MediaSession MEDIA_ID 即酷我 rid（cn.kuwo.mediasession.PlayerBridge 写入，
 *   兜底 localId），歌词主链路无需任何 hook；
 * - MediaSession 在主进程；:service 进程（cn.wenyu.bodian:service）只承载解码
 *   播放（真机音频焦点日志证实 cn.kuwo.service.remote.kwplayer.PlayManager 在
 *   该进程），播放队列在 Flutter/Dart 侧，Java 仅经 PlayManager.prefetch(Music)
 *   预取下一首、PlayManager.play(Music, int, boolean) 开始当前曲；
 * - 酷我本体的 playcontrol hook 目标类（cn.kuwo.mod.playcontrol.*、
 *   cn.kuwo.base.bean.*）在波点 DEX 中不存在，绝不能在波点安装。
 */
internal enum class KuwoFeature {
    /** MediaSession 歌词主链路（rid 直取 + 搜索兜底）。 */
    LYRICS,

    /**
     * 缓冲边界合成：波点的 MediaSession 不发布缓冲态，卡顿期间岛内进度会按旧
     * PLAYING 锚点继续外推，歌词随之漂移；经 AIDL 缓冲边界冻结/恢复进度。
     */
    BUFFERING_STATE,

    /** 下一首预告。酷我本体走 playcontrol hook；波点走 :service 预取 hook。 */
    NEXT_TRACK,
}

internal object KuwoHostPlan {
    const val KUWO_PACKAGE = "cn.kuwo.player"
    const val BODIAN_PACKAGE = "cn.wenyu.bodian"
    const val BODIAN_SERVICE_PROCESS = "$BODIAN_PACKAGE:service"

    fun supports(packageName: String): Boolean =
        packageName == KUWO_PACKAGE || packageName == BODIAN_PACKAGE

    fun resolve(packageName: String, processName: String): Set<KuwoFeature> = when {
        packageName == KUWO_PACKAGE && processName == packageName ->
            setOf(KuwoFeature.LYRICS, KuwoFeature.NEXT_TRACK)
        packageName == BODIAN_PACKAGE && processName == packageName ->
            setOf(KuwoFeature.LYRICS, KuwoFeature.BUFFERING_STATE)
        packageName == BODIAN_PACKAGE && processName == BODIAN_SERVICE_PROCESS ->
            setOf(KuwoFeature.NEXT_TRACK)
        else -> emptySet()
    }
}
