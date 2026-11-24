/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 */
package com.juren233.hle.providers.applemusic.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1626 与 1624 的目标逐项相同；原包校验在 AppleMusicProfileBinaryTest（HLE_APPLE_MUSIC_1626_APK）。 */
class AppleMusic7031626ProfileRegressionTest {
    private val version = AppleMusicVersion("7.0.3", 1626L)
    private val previous = AppleMusicVersion("7.0.2", 1624L)

    @Test
    fun `1626 has its own exact profile and neighbours stay unmatched`() {
        assertEquals("am-7.0.3-1626", AppleMusicHookProfiles.profileFor(version)?.id)
        assertEquals("am-7.0.2-1624", AppleMusicHookProfiles.profileFor(previous)?.id)
        assertNull(AppleMusicHookProfiles.profileFor(AppleMusicVersion("7.0.3", 1625L)))
        assertNull(AppleMusicHookProfiles.profileFor(AppleMusicVersion("7.0.3", 1627L)))
        assertTrue(AppleMusicHookProfiles.profileFor(version)!!.settingsDataCategoryHasNoSimGate)
    }

    @Test
    fun `1626 targets equal the 1624 targets in every group`() {
        AppleMusicHookPoint.entries.forEach { point ->
            assertEquals(
                "Targets differ: $point",
                AppleMusicHookProfiles.exactTargets(previous, point).map(::describe),
                AppleMusicHookProfiles.exactTargets(version, point).map(::describe),
            )
        }
    }

    // 契约对象没有值相等，按其余全部字段比较（toString 里带契约的对象标识，要去掉）。
    private fun describe(target: AppleMusicHookTarget) =
        target.toString().replace(Regex("contract=.*\\)$"), ")")

    @Test
    fun `1624 does not borrow from the newer 1626 profile and 1626 tries itself first`() {
        val point = AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY
        assertEquals("z0.v0", AppleMusicHookProfiles.candidates(version, point).first().className)
        assertEquals("z0.v0", AppleMusicHookProfiles.candidates(previous, point).first().className)
        assertEquals(
            AppleMusicHookProfiles.candidates(previous, point),
            AppleMusicHookProfiles.candidates(version, point),
        )
    }
}
