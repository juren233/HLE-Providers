/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hle.providers.applemusic.xposed

import com.juren233.hle.providers.applemusic.xposed.internal.WeakIdentityMap

/** Constructed only in Debug: inspect the first native input and retained UI row independently. */
internal class AppleAlbumComposeDiagnostics(private val host: AppleArtistSurfaceHost) {
    private val seen = WeakIdentityMap<Any, MutableSet<String>>()

    @Synchronized
    fun once(owner: Any, key: String, event: String, details: () -> String) {
        if (!AppleProviderEnvironment.diagnosticsEnabled) return
        val keys = seen[owner] ?: mutableSetOf<String>().also { seen[owner] = it }
        if (keys.size >= 16 || !keys.add(key)) return
        runCatching { host.logMetadataIdentity(event, details()) }
            .onFailure { ProviderLogger.diagnostic("Apple Music 专辑链路诊断失败: event=$event, error=${it.javaClass.simpleName}") }
    }

    private val seenDecisions = LinkedHashSet<String>()

    /** 不受单 owner 16 条上限约束的去重记录；用于每个 revision 都可能变化的判定。 */
    @Synchronized
    fun decision(key: String, event: String, details: () -> String) {
        if (!AppleProviderEnvironment.diagnosticsEnabled) return
        if (!seenDecisions.add(key)) return
        if (seenDecisions.size > 512) seenDecisions.remove(seenDecisions.first())
        runCatching { host.logMetadataIdentity(event, details()) }
            .onFailure { ProviderLogger.diagnostic("Apple Music 专辑链路诊断失败: event=$event, error=${it.javaClass.simpleName}") }
    }
}
