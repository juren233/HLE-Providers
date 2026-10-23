/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHost

/**
 * Provider-owned copy of the host build facts the Hook layer used to read from core BuildConfig.
 *
 * Values are captured once at [attach]. Nothing here may be read before
 * [AppleMusicProviderOrchestrator.install] runs.
 */
internal object AppleProviderEnvironment {
    @Volatile
    var diagnosticsEnabled: Boolean = false
        private set

    @Volatile
    var coreVersionCode: Int = 0
        private set

    @Volatile
    var pluginVersionCode: Int = 0
        private set

    /** Cache/diagnostic version stamp: core and plugin versions move independently. */
    val versionKey: String
        get() = "$coreVersionCode-$pluginVersionCode"

    @Volatile
    private var host: OfficialProviderHost? = null

    fun attach(host: OfficialProviderHost, pluginVersionCode: Int = 0) {
        this.host = host
        diagnosticsEnabled = host.isDiagnosticEnabled()
        coreVersionCode = host.coreVersionCode
        this.pluginVersionCode = pluginVersionCode
    }

    fun reportDiagnostic(message: String) {
        if (!diagnosticsEnabled) return
        runCatching { host?.reportDiagnostic("AppleMusicProvider", message) }
    }

    fun reportDiagnostic(tag: String, message: String) {
        if (!diagnosticsEnabled) return
        runCatching { host?.reportDiagnostic(tag, message) }
    }
}
