/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHost
import com.juren233.hle.providers.applemusic.xposed.AppleMusicProviderOrchestrator
import com.juren233.hle.providers.applemusic.xposed.hookModuleIdsForBuild
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlugin

/** Stable Apple Music Provider Pack entry point. */
object AppleMusicPluginEntry : OfficialProviderPlugin {
    override fun install(host: OfficialProviderHost) {
        install(
            host = host,
            classLoader = requireNotNull(host.targetClassLoader) {
                "Host 未提供目标 App 类加载器"
            },
        )
    }

    fun install(host: OfficialProviderHost, classLoader: ClassLoader) {
        AppleMusicProviderOrchestrator.install(host, classLoader)
    }

    internal fun hookModuleIdsForBuild(debug: Boolean): List<String> =
        AppleMusicProviderOrchestrator.hookModuleIdsForBuild(debug)
}
