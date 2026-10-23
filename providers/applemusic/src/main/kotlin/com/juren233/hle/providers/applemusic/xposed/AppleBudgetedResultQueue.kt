/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

/**
 * 按提交顺序在主线程执行任务，每条消息只占用约 [budgetNs]，剩余的由下一条消息继续。
 *
 * 一批名称查询结果在同一条消息里逐个发布时，每个结果都会同步套用覆盖并传播艺人别名，
 * 单条消息可达 30–60 ms（221046）。缓存写入仍在发布处同步完成，这里只延后回调本身。
 * 批量发起名称请求（每个 ID 约 1 ms）同样经此分批（221048）。
 */
internal class AppleBudgetedResultQueue(
    private val post: (Runnable) -> Unit,
    private val clock: () -> Long = System::nanoTime,
    private val budgetNs: Long = DEFAULT_BUDGET_NS,
    private val failureMessage: String = "Apple 名称查询结果回调失败",
) {
    private val pending = ArrayDeque<() -> Unit>()
    private var drainScheduled = false

    fun enqueue(task: () -> Unit) {
        val schedule = synchronized(pending) {
            pending.addLast(task)
            (!drainScheduled).also { drainScheduled = true }
        }
        if (schedule) post(Runnable(::drain))
    }

    private fun drain() {
        val startNs = clock()
        var executed = 0
        while (true) {
            if (executed > 0 && clock() - startNs >= budgetNs) break
            val task = synchronized(pending) { pending.removeFirstOrNull() } ?: break
            executed += 1
            runCatching(task).onFailure {
                ProviderLogger.error(failureMessage, it)
            }
        }
        val reschedule = synchronized(pending) {
            drainScheduled = pending.isNotEmpty()
            drainScheduled
        }
        if (reschedule) post(Runnable(::drain))
    }

    companion object {
        const val DEFAULT_BUDGET_NS = 6_000_000L
    }
}
