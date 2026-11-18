/* Copyright 2026 juren233 */
package com.juren233.hle.providers.applemusic.xposed

import com.juren233.hle.providers.applemusic.xposed.hooks.FunctionalAppleMusicHookModule
import org.junit.Assert.assertEquals
import org.junit.Test

/** 1622 未适配时，一个模块找不到目标就中止了其后全部模块的安装。 */
class AppleHookModuleIsolationTest {
    @Test fun `a failing module is skipped and later modules still install`() {
        val installed = mutableListOf<String>()
        fun module(id: String, block: () -> Unit) = FunctionalAppleMusicHookModule(id, installer = block)
        val failed = installHookModulesIsolated(
            sequenceOf(
                module("first") { installed += "first" },
                module("missingTarget") { throw NoSuchMethodException("unresolved") },
                module("linkage") { throw NoClassDefFoundError("gone") },
                module("last") { installed += "last" },
            ),
        ) { it.installHooks() }
        assertEquals(listOf("first", "last"), installed)
        assertEquals(listOf("missingTarget", "linkage"), failed)
    }
}
