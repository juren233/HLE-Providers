/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

/**
 * 最近一次成功下载的歌词。小米音乐切歌时快照常先于元数据到达：插件先按快照起一次任务（还没有
 * 会话 ID），元数据随后绑上会话 ID 又起一次，两次是同一首歌、同一个在线 ID。第二次直接复用第一次
 * 的下载结果，不再重复下载（2026-11-09 真机：同一首歌下载两次、各约 6 s）。
 *
 * 只记一条：键是取词任务的曲目（ID + 歌名 + 歌手，不含会话 ID）与请求的在线歌曲 ID，两者都相同
 * 才复用。只在取词执行器线程上读写。
 */
internal class QQMusicRecentDownload<T : Any> {
    private var trackKey: String? = null
    private var songId: String? = null
    private var value: T? = null

    fun find(trackKey: String, songId: String): T? =
        value?.takeIf { this.trackKey == trackKey && this.songId == songId }

    fun remember(trackKey: String, songId: String, value: T) {
        this.trackKey = trackKey
        this.songId = songId
        this.value = value
    }
}
