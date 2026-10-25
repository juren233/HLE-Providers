/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 入口门控边界守卫：A（体验优化入口）只停优化 Hook；
 * 播放、元数据与歌词数据路径必须显式绕过门控，保证 B 开启时超级岛/AOD 仍有内容。
 */
class AppleMusicOptimizationGateScopeTest {

    @Test
    fun `lyrics and playback data hooks bypass the optimization gate`() {
        assertEquals(7, gateBypassCount("hooks/ApplePlaybackHooks.kt"))
        assertEquals(2, gateBypassCount("metadata/ApplePlaybackMetadataHooks.kt"))
        assertEquals(2, gateBypassCount("lyrics/AppleSupplementPlaybackHooks.kt"))
    }

    @Test
    fun `playback metadata display overrides stay behind the optimization gate`() {
        val source = source("AppleOrchestratorLyricsPlaybackAssembly.kt")
        assertEquals(
            10,
            Regex("AppleMusicOptimizationGate\\.isEnabled\\(\\)").findAll(source).count(),
        )
    }

    private fun gateBypassCount(relativePath: String): Int =
        Regex("gated = false").findAll(source(relativePath)).count()

    private fun source(relativePath: String): String =
        mainSourceRoot().resolve(relativePath).readText()

    private fun mainSourceRoot(): File =
        listOf(
            File("app/src/main/kotlin/com/juren233/hle/providers/applemusic/xposed"),
            File("src/main/kotlin/com/juren233/hle/providers/applemusic/xposed"),
        ).first(File::isDirectory)
}
