/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import android.os.Looper
import android.os.SystemClock
import android.util.Printer

/**
 * debug 包专用：统计模块在主线程上实际占用的时间，按秒汇总输出 `[MainCost]`。
 *
 * - Hook 回调只计模块自身耗时：扣除 `proceed` 执行原方法的时间，以及回调里再触发的其他
 *   Hook 回调（后者单独计入自己的条目）。
 * - 模块投递到主线程的消息（类名在模块包下的 Runnable）按消息耗时计入。
 * - 主线程上写日志的耗时单独累计（`logMs`），用于区分调试日志本身的开销。
 */
internal object AppleMainThreadCost {
    private const val WINDOW_MS = 1_000L
    private const val TOP_ENTRIES = 20
    private const val MODULE_PACKAGE = "io.github.proify.lyricon"
    private const val PROFILER_LOG_PREFIX = "[MainCost]"

    /** 单元测试环境没有主 Looper，此时统计恒为关闭。 */
    private val mainThread: Thread? by lazy { runCatching { Looper.getMainLooper()?.thread }.getOrNull() }

    private class Frame {
        var proceedNs = 0L
        var childNs = 0L
        var inProceed = false
    }

    private class Entry {
        var ns = 0L
        var count = 0
        var maxNs = 0L
    }

    private val frames = ArrayDeque<Frame>()
    private val entries = HashMap<String, Entry>()
    private var windowStartMs = 0L
    private var hookNs = 0L
    private var postedNs = 0L
    private var logNs = 0L
    private var messageStartNs = 0L
    private var messageKey: String? = null

    val isEnabled: Boolean get() = AppleProviderEnvironment.diagnosticsEnabled && Thread.currentThread() === mainThread

    /** 在主线程调用一次：开始统计模块投递的主线程消息。 */
    fun installLooperObserver() {
        if (!AppleProviderEnvironment.diagnosticsEnabled) return
        Looper.getMainLooper().setMessageLogging(Printer { line ->
            if (line.startsWith(">>>>>")) {
                messageKey = moduleRunnableKey(line)
                messageStartNs = if (messageKey != null) System.nanoTime() else 0L
            } else if (line.startsWith("<<<<<")) {
                val key = messageKey ?: return@Printer
                messageKey = null
                val ns = System.nanoTime() - messageStartNs
                postedNs += ns
                record("post:$key", ns)
            }
        })
    }

    /** 主线程 Hook 回调入口；返回 null 表示不在主线程或未开启。 */
    fun enterHook(): Any? {
        if (!isEnabled) return null
        val frame = Frame()
        frames.addLast(frame)
        return frame
    }

    fun proceedStarted(token: Any?) {
        (token as? Frame)?.inProceed = true
    }

    fun proceedFinished(token: Any?, ns: Long) {
        val frame = token as? Frame ?: return
        frame.inProceed = false
        frame.proceedNs += ns
    }

    fun exitHook(token: Any?, key: String, totalNs: Long) {
        val frame = token as? Frame ?: return
        frames.removeLastOrNull()
        val selfNs = (totalNs - frame.proceedNs - frame.childNs).coerceAtLeast(0L)
        // 在父回调的 proceed 之外触发的 Hook，其总耗时不应再算进父回调自身。
        frames.lastOrNull()?.let { parent ->
            if (!parent.inProceed) parent.childNs += totalNs - frame.proceedNs
        }
        hookNs += selfNs
        record("hook:$key", selfNs)
    }

    /** 主线程上某段代码的含内部耗时，记为 `sec:<key>`，不参与 Hook 自身耗时的扣除。 */
    inline fun <T> section(key: String, block: () -> T): T {
        if (!isEnabled) return block()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            recordSection(key, System.nanoTime() - start)
        }
    }

    fun recordSection(key: String, ns: Long) {
        record("sec:$key", ns)
    }

    /**
     * 同 [section]，另把其间主线程写日志的耗时记为 `sec:<key>_log`，用于估算 release 开销。
     * 嵌套的 Hook 回调不受影响。
     */
    inline fun <T> sectionWithLog(key: String, logKey: String, block: () -> T): T {
        if (!isEnabled) return block()
        val start = System.nanoTime()
        val logStart = currentLogNs()
        try {
            return block()
        } finally {
            recordSection(logKey, currentLogNs() - logStart)
            recordSection(key, System.nanoTime() - start)
        }
    }

    fun currentLogNs(): Long = logNs

    /** ProviderLogger 写日志时调用。 */
    inline fun <T> timeLog(block: () -> T): T {
        if (!isEnabled) return block()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            addLogNs(System.nanoTime() - start)
        }
    }

    fun addLogNs(ns: Long) {
        logNs += ns
    }

    private fun record(key: String, ns: Long) {
        val now = SystemClock.uptimeMillis()
        if (windowStartMs == 0L) windowStartMs = now
        entries.getOrPut(key) { Entry() }.apply {
            this.ns += ns
            count += 1
            if (ns > maxNs) maxNs = ns
        }
        if (now - windowStartMs >= WINDOW_MS) flush(now)
    }

    private fun flush(now: Long) {
        val top = entries.entries.sortedByDescending { it.value.ns }.take(TOP_ENTRIES)
            .joinToString(prefix = "[", postfix = "]") { (key, entry) ->
                "$key=${ms(entry.ns)}/n${entry.count}/max${ms(entry.maxNs)}"
            }
        val windowMs = now - windowStartMs
        val hookMs = ms(hookNs)
        val postedMs = ms(postedNs)
        val logMs = ms(logNs)
        entries.clear()
        hookNs = 0L
        postedNs = 0L
        logNs = 0L
        windowStartMs = now
        // 汇总本身也是日志，写在清零之后，不计入下一窗口以外的统计。
        ProviderLogger.info(
            "$PROFILER_LOG_PREFIX windowMs=$windowMs, hookSelfMs=$hookMs, postedMs=$postedMs, " +
                "logMs=$logMs, top=$top"
        )
    }

    private fun ms(ns: Long) = "%.1f".format(ns / 1_000_000.0)

    /** `>>>>> Dispatching to Handler (h) {..} <callback>: what`；只保留模块包下的回调类。 */
    private fun moduleRunnableKey(line: String): String? {
        val index = line.indexOf(MODULE_PACKAGE)
        if (index < 0) return null
        val end = line.indexOfAny(charArrayOf('@', ':', ' '), index).let { if (it < 0) line.length else it }
        return line.substring(index + MODULE_PACKAGE.length + 1, end)
    }
}
