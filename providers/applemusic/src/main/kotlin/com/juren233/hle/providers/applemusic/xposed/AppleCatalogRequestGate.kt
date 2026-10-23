/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模块目录请求的全局许可门。Apple Music 1611 的 amp-api 拦截器 `y9.d.a` 持锁执行请求，
 * 同一客户端上的请求本就逐个串行；模块多发在途请求没有吞吐收益，只会让页面请求排在后面。
 * 同一时刻最多 [maxInFlight] 个任务持有许可，其余按提交顺序等待。任务通过传入的 release
 * 归还许可，重复调用无副作用；任务启动时抛异常也会归还。
 *
 * 取得许可后若 [shouldYield] 为真（Apple 自身 amp-api 请求在途），每 [yieldPollMs] 复查一次，
 * 最多让行 [maxYieldMs] 后照常启动，让页面请求先过串行通道又不让模块请求无限推迟。
 */
internal class AppleCatalogRequestGate(
    private val maxInFlight: Int = 1,
    private val dispatch: (Runnable) -> Unit,
    private val dispatchDelayed: (Runnable, Long) -> Unit = { runnable, _ -> dispatch(runnable) },
    private val shouldYield: () -> Boolean = { false },
    private val clock: () -> Long = { 0L },
    private val maxYieldMs: Long = DEFAULT_MAX_YIELD_MS,
    private val yieldPollMs: Long = DEFAULT_YIELD_POLL_MS,
) {
    private val waiting = ArrayDeque<(release: () -> Unit) -> Unit>()
    private var inFlight = 0

    val waitingCount: Int get() = synchronized(this) { waiting.size }

    fun submit(task: (release: () -> Unit) -> Unit) {
        val startNow = synchronized(this) {
            if (inFlight < maxInFlight) {
                inFlight += 1
                true
            } else {
                waiting.addLast(task)
                false
            }
        }
        if (startNow) start(task)
    }

    private fun start(task: (release: () -> Unit) -> Unit) {
        val released = AtomicBoolean(false)
        val release = { if (released.compareAndSet(false, true)) onReleased() }
        val acquiredAt = clock()
        lateinit var attempt: Runnable
        attempt = Runnable {
            if (shouldYield() && clock() - acquiredAt < maxYieldMs) {
                dispatchDelayed(attempt, yieldPollMs)
                return@Runnable
            }
            try {
                task(release)
            } catch (error: Throwable) {
                release()
                throw error
            }
        }
        dispatch(attempt)
    }

    private fun onReleased() {
        val next = synchronized(this) {
            waiting.removeFirstOrNull() ?: run {
                inFlight -= 1
                null
            }
        }
        next?.let(::start)
    }

    private companion object {
        const val DEFAULT_MAX_YIELD_MS = 1_000L
        const val DEFAULT_YIELD_POLL_MS = 50L
    }
}
