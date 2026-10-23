/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把 [block] 推迟到主线程空闲时执行，最迟 [maxDelayMs] 后兜底执行，且只执行一次。
 *
 * 用于原生歌词的首次解析：Apple 构建/呈现歌词后立刻在主线程逐行逐字解析会占用 50–300 ms，
 * 恰好挡在 Apple 歌词页首帧之前（221028：切歌后收到歌词到解析完成中位 208 ms，并伴随掉帧）。
 * 空闲回调在当前消息与随后的布局绘制完成后才会触发，Apple 的歌词页先显示出来。
 */
internal fun postWhenMainIdle(
    handler: Handler,
    maxDelayMs: Long,
    block: (waitedMs: Long, viaIdle: Boolean) -> Unit,
) {
    val scheduledAt = SystemClock.uptimeMillis()
    val once = AppleRunOnce()
    handler.post {
        Looper.myQueue().addIdleHandler {
            once.run { block(SystemClock.uptimeMillis() - scheduledAt, true) }
            false
        }
        handler.postDelayed(
            { once.run { block(SystemClock.uptimeMillis() - scheduledAt, false) } },
            maxDelayMs,
        )
    }
}

/** 多个触发源（空闲回调、超时兜底）竞争时只放行第一次。 */
internal class AppleRunOnce {
    private val done = AtomicBoolean(false)

    fun run(block: () -> Unit): Boolean {
        if (!done.compareAndSet(false, true)) return false
        block()
        return true
    }
}
