/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderControlProtocol

internal data class BodianTrackBean(
    val rid: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
)

/**
 * 波点 :service 进程的下一首帧合成器（纯逻辑，便于 JVM 单测）。
 *
 * 当前曲来自 PlayManager.play(Music, int, boolean) 的入参，下一首来自
 * PlayManager.prefetch(Music) 的入参（均为未混淆的稳定精确描述符）。波点切换
 * 当前曲时 play() 内部会先 cancelPrefetch()，因此当前曲变更即代表既有预取作废，
 * 必须改发清除帧，避免岛上残留上一首的"下一首"。
 */
internal class BodianNextTrackResolver(
    private val nowMs: () -> Long,
) {
    private var current: BodianTrackBean? = null
    private var next: BodianTrackBean? = null
    private var lastFrame: String? = null
    private var lastFrameAtMs = 0L

    @Synchronized
    fun onCurrentMusic(bean: BodianTrackBean?): String? {
        if (bean == null || bean.rid.isBlank()) return null
        val previous = current
        current = bean
        if (previous == null || previous.rid != bean.rid) {
            next = null
        }
        return buildFrame()
    }

    @Synchronized
    fun onPrefetchMusic(bean: BodianTrackBean?): String? {
        if (bean == null || bean.rid.isBlank()) return null
        if (current == null) return null
        if (bean.rid == current?.rid) return null
        next = bean
        return buildFrame()
    }

    /** 重连后核心只回放最后一种内容，靠心跳周期性重发控制帧。 */
    @Synchronized
    fun heartbeat(): String? = buildFrame()

    private fun buildFrame(): String? {
        val currentBean = current ?: return null
        val nextBean = next
        val frame = if (nextBean == null || nextBean.title.isNullOrBlank()) {
            OfficialProviderControlProtocol.encodeNextTrackClear(
                currentId = currentBean.rid,
                currentTitle = currentBean.title.orEmpty(),
                currentArtist = currentBean.artist.orEmpty(),
            )
        } else {
            OfficialProviderControlProtocol.encodeNextTrack(
                currentId = currentBean.rid,
                currentTitle = currentBean.title.orEmpty(),
                currentArtist = currentBean.artist.orEmpty(),
                nextId = nextBean.rid,
                nextTitle = nextBean.title.orEmpty(),
                nextArtist = nextBean.artist.orEmpty(),
                nextAlbum = nextBean.album.orEmpty(),
                nextDurationMs = nextBean.durationMs,
            )
        }
        val now = nowMs()
        if (frame == lastFrame && now - lastFrameAtMs < HEARTBEAT_MS) return null
        lastFrame = frame
        lastFrameAtMs = now
        return frame
    }

    companion object {
        const val HEARTBEAT_MS = 5_000L
    }
}
