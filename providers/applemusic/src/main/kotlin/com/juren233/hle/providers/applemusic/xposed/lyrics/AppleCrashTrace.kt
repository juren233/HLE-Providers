/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Diagnostics only. Remembers the module's recent lyric-page actions and, when the host process
 * dies from an Apple lyric adapter exception, writes them out first so the crash can be tied to
 * what the module did just before.
 */
internal object AppleCrashTrace {
    private const val CAPACITY = 60
    private val events = ArrayDeque<String>(CAPACITY)
    private val installed = AtomicBoolean(false)

    fun record(event: () -> String) {
        if (!AppleProviderEnvironment.diagnosticsEnabled) return
        val line = "${SystemClock.uptimeMillis()} ${event()}"
        synchronized(events) {
            if (events.size >= CAPACITY) events.removeFirst()
            events.addLast(line)
        }
    }

    fun installOnce() {
        if (!AppleProviderEnvironment.diagnosticsEnabled || !installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { dump(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun dump(thread: Thread, error: Throwable) {
        val frames = error.stackTrace.take(8).joinToString(" <- ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
        val recent = synchronized(events) { events.toList() }
        ProviderLogger.diagnostic(
            "宿主崩溃前模块动作: thread=${thread.name}, error=${error.javaClass.name}: " +
                "${error.message}, frames=$frames, now=${SystemClock.uptimeMillis()}, " +
                "recent=${recent.size}"
        )
        recent.forEach { ProviderLogger.diagnostic("宿主崩溃前模块动作: $it") }
    }
}
