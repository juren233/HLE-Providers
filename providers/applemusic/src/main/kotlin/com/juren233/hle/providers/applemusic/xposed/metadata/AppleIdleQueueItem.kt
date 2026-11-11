/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import android.os.Looper

/**
 * 还没有任何播放器真正播放过（没有活动播放器）时，播放器回调所观察到的当前队列项。
 *
 * 冷启动恢复到暂停歌曲时，Apple Music 会先回调切歌，此时活动播放器尚未确立，队列项不能直接
 * 当作当前歌曲发布（活动播放器规则不变）。这里只记住它；歌词页随后确认可见歌曲与它一致，
 * 才由 [ApplePlaybackMetadataCoordinator] 走正常的队列项发布路径。
 */
internal class AppleIdleQueueItem {
    /** 取出的队列项，以及记录它时所在线程的 Looper（正常切歌路径所用的同一回调线程）。 */
    class Taken(val queueItem: Any, val looper: Looper?)

    private var mediaId: String? = null
    private var queueItem: Any? = null
    private var looper: Looper? = null

    @Synchronized
    fun remember(mediaId: String, queueItem: Any, looper: Looper?) {
        this.mediaId = mediaId
        this.queueItem = queueItem
        this.looper = looper
    }

    @Synchronized
    fun mediaId(): String? = mediaId

    /** 取出并清空记住的队列项；没有记录时返回 null。 */
    @Synchronized
    fun take(): Taken? {
        val item = queueItem ?: return null
        val taken = Taken(item, looper)
        clear()
        return taken
    }

    @Synchronized
    fun clear() {
        mediaId = null
        queueItem = null
        looper = null
    }
}

/**
 * 歌词页的可见歌曲是否应按「无活动播放器时记住的队列项」发布为当前歌曲。
 *
 * 只在没有活动播放器、记住的队列项与歌词页可见歌曲是同一首，且该歌曲尚未作为当前歌曲
 * 发布时成立；有活动播放器时一律走原有「仅活动播放器的当前队列项更新当前歌曲」规则。
 */
internal fun shouldAdoptIdleQueueItem(
    activePlayerPresent: Boolean,
    idleMediaId: String?,
    visibleSongId: String?,
    publishedMediaId: String?,
): Boolean {
    if (activePlayerPresent) return false
    val visible = visibleSongId?.takeIf(String::isNotBlank) ?: return false
    return idleMediaId == visible && publishedMediaId != visible
}
