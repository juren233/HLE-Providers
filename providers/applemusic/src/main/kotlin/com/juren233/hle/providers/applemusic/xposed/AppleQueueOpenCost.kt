/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed


/**
 * debug 包专用：拆分打开播放队列时三个整表 Hook（继续播放、历史记录、Adapter 提交）的主线程耗时。
 * 环节之间有嵌套（登记条目内部会合并艺人 ID），按原值记录；每次 Hook 开始时清零。
 */
internal object AppleQueueOpenCost {
    enum class Kind { REGISTER_ENTRY, REUSED_ENTRY, DEFERRED_ENTRY, MEDIA_ID, REGISTER_PLAYBACK_ITEM, MERGE_ARTIST_IDS, SCOPE, HYDRATE, APPLY_REFS, ENSURE_OVERRIDES, DEBUG_TRACE }

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
    fun reset() {
        nanos.fill(0)
        counts.fill(0)
    }

    @Synchronized
    fun summary(): String = Kind.entries.filter { counts[it.ordinal] > 0 }.joinToString { kind ->
        "${kind.name.lowercase()}=${"%.1f".format(nanos[kind.ordinal] / 1_000_000.0)}ms/n${counts[kind.ordinal]}"
    }
}
