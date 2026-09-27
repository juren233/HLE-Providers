/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import android.os.SystemClock
import android.util.Log

/**
 * release 构建里成功路径完全静默导致真机排障失明（1.0.24 教训），关键新路径
 * 统一用节流 INFO 日志：同一 key 至少间隔 minIntervalMs 才输出一条。
 */
internal class ThrottledLogger {
    private val lastAt = HashMap<String, Long>()

    @Synchronized
    fun log(tag: String, key: String, minIntervalMs: Long, message: () -> String) {
        val now = SystemClock.elapsedRealtime()
        if (now - (lastAt[key] ?: 0L) < minIntervalMs) return
        lastAt[key] = now
        Log.i(tag, message())
    }
}
