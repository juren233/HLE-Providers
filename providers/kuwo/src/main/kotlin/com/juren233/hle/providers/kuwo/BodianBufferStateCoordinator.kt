/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

import android.media.session.PlaybackState

internal data class BodianPlaybackSnapshot(
    val state: Int,
    val position: Long,
    val updatedAtMs: Long,
    val speed: Float,
)

internal sealed interface BodianPlaybackDecision {
    data object Forward : BodianPlaybackDecision
    data object Ignore : BodianPlaybackDecision
    data class Publish(val state: BodianPlaybackSnapshot) : BodianPlaybackDecision
}

/**
 * 把波点的 AIDL 缓冲边界转成权威 PlaybackState 边界。
 *
 * 波点的 MediaSession 不发布缓冲态（PlayerBridge 只有播放/暂停两态），卡顿期间
 * 岛内进度按旧 PLAYING 锚点继续外推，歌词随之漂移。AIDL 缓冲开始即冻结外推锚点，
 * 结束后从冻结锚点恢复 PLAYING，卡住的时间不计入歌词进度。
 * 结构移植自 QQMusicBufferStateCoordinator，语义差异：QQ 的会话在缓冲期保持
 * PLAYING，波点是否发布中间态未知，真实状态始终按权威处理——非 PLAYING 真实态
 * 会解除合成冻结。
 */
internal class BodianBufferStateCoordinator {
    private var latestRealState: BodianPlaybackSnapshot? = null
    private var frozenPosition: Long? = null

    @Synchronized
    fun onPlaybackState(state: BodianPlaybackSnapshot?): BodianPlaybackDecision {
        latestRealState = state
        if (state == null) {
            frozenPosition = null
            return BodianPlaybackDecision.Forward
        }

        val frozen = frozenPosition
        if (frozen != null && state.state == PlaybackState.STATE_PLAYING) {
            return BodianPlaybackDecision.Publish(
                BodianPlaybackSnapshot(
                    state = PlaybackState.STATE_BUFFERING,
                    position = frozen,
                    updatedAtMs = state.updatedAtMs,
                    speed = 0f,
                ),
            )
        }

        if (state.state != PlaybackState.STATE_PLAYING) {
            frozenPosition = null
        }
        return BodianPlaybackDecision.Forward
    }

    @Synchronized
    fun onBufferStarted(nowMs: Long): BodianPlaybackDecision {
        if (frozenPosition != null) return BodianPlaybackDecision.Ignore
        val realState = latestRealState ?: return BodianPlaybackDecision.Ignore
        if (realState.state != PlaybackState.STATE_PLAYING) {
            return BodianPlaybackDecision.Ignore
        }

        val position = realState.positionAt(nowMs)
        frozenPosition = position
        return BodianPlaybackDecision.Publish(
            BodianPlaybackSnapshot(
                state = PlaybackState.STATE_BUFFERING,
                position = position,
                updatedAtMs = nowMs,
                speed = 0f,
            ),
        )
    }

    @Synchronized
    fun onBufferEnded(nowMs: Long): BodianPlaybackDecision {
        val position = frozenPosition ?: return BodianPlaybackDecision.Ignore
        frozenPosition = null
        val realState = latestRealState
        if (realState?.state != PlaybackState.STATE_PLAYING) {
            return BodianPlaybackDecision.Ignore
        }

        return BodianPlaybackDecision.Publish(
            BodianPlaybackSnapshot(
                state = PlaybackState.STATE_PLAYING,
                position = position,
                updatedAtMs = nowMs,
                speed = realState.speed.takeIf { it > 0f } ?: 1f,
            ),
        )
    }

    @Synchronized
    fun reset() {
        frozenPosition = null
    }

    private fun BodianPlaybackSnapshot.positionAt(nowMs: Long): Long {
        val base = position.coerceAtLeast(0L)
        if (updatedAtMs <= 0L || speed <= 0f) return base
        val elapsed = (nowMs - updatedAtMs).coerceAtLeast(0L)
        return if (speed == 1f) base + elapsed else base + (elapsed * speed).toLong()
    }
}
