/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed


/**
 * debug 包专用：Hook 目标解析各环节的累计耗时，用于拆分冷启动 `[StartupCost]` 中
 * “去掉 `hook()` 后剩下的解析开销”。环节之间可能嵌套（契约校验内部也会加载类），按原值记录。
 */
internal object AppleStartupResolveCost {
    enum class Kind { CLASS_LOOKUP, METHOD_SCAN, CONTRACT, BASELINE, REFLECTION_INDEX }

    private val nanos = LongArray(Kind.entries.size)
    private val counts = IntArray(Kind.entries.size)

    inline fun <T> time(kind: Kind, block: () -> T): T {
        if (!AppleProviderEnvironment.diagnosticsEnabled) return block()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(kind, System.nanoTime() - start)
        }
    }

    @Synchronized
    fun record(kind: Kind, ns: Long) {
        nanos[kind.ordinal] += ns
        counts[kind.ordinal] += 1
    }

    @Synchronized
    fun summary(): String = Kind.entries.joinToString { kind ->
        "${kind.name.lowercase()}=${"%.1f".format(nanos[kind.ordinal] / 1_000_000.0)}ms/n${counts[kind.ordinal]}"
    }
}
