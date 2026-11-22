/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 */
package com.juren233.hle.providers.applemusic.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Original 1624 DEX descriptors, not JADX display names; APK checks run in AppleMusicProfileBinaryTest. */
class AppleMusic7021624ProfileRegressionTest {
    private val version = AppleMusicVersion("7.0.2", 1624L)
    private val previous = AppleMusicVersion("7.0.1", 1622L)
    private val music = "com.apple.android.music"
    private fun target(point: AppleMusicHookPoint, version: AppleMusicVersion = this.version) =
        AppleMusicHookProfiles.exactTargets(version, point).single()
    private fun targets(point: AppleMusicHookPoint, version: AppleMusicVersion = this.version) =
        AppleMusicHookProfiles.exactTargets(version, point)

    @Test
    fun `1624 has an isolated profile with every 1622 group`() {
        assertEquals("am-7.0.2-1624", AppleMusicHookProfiles.profileFor(version)?.id)
        assertEquals("am-7.0.1-1622", AppleMusicHookProfiles.profileFor(previous)?.id)
        assertNull(AppleMusicHookProfiles.profileFor(AppleMusicVersion("7.0.2", 1623L)))
        assertNull(AppleMusicHookProfiles.profileFor(AppleMusicVersion("7.0.2", 1625L)))
        assertTrue(AppleMusicHookProfiles.profileFor(version)!!.settingsDataCategoryHasNoSimGate)
        AppleMusicHookPoint.entries.forEach { point ->
            val old = targets(point, previous).size
            val expected = if (point == AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR) old + 1 else old
            assertEquals("Lost targets: $point", expected, targets(point).size)
        }
    }

    @Test
    fun `1624 renames do not overwrite the 1622 targets`() {
        assertEquals("z0.m", target(AppleMusicHookPoint.BROWSE_COMPOSER_SCOPE, previous).className)
        assertEquals("z0.w0", target(AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY, previous).className)
        assertEquals("$music.utils.l1", target(AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS, previous).className)
        assertEquals("$music.utils.h1", target(AppleMusicHookPoint.LYRICS_SHARE_RENDERED_IMAGE, previous).className)
        assertEquals(
            "o0",
            target(AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW, previous)
                .runtimeMemberName(AppleMusicRuntimeMember.LYRICS_UI_ADAPTER_FIELD),
        )
        assertEquals("I0", target(AppleMusicHookPoint.ACTIVITY_THEME_CREATE, previous)
            .runtimeMemberName(AppleMusicRuntimeMember.ACTIVITY_THEME_MODE_FIELD))
    }

    @Test
    fun `compose runtime and LiveData types use the 1624 names`() {
        val observe = target(AppleMusicHookPoint.COMPOSE_OBSERVE_AS_STATE)
        assertEquals("$music.library3.librarypin.j", observe.className)
        assertEquals("g", observe.methodName)
        assertEquals(listOf("androidx.lifecycle.H", "z0.n"), observe.parameterTypeNames)
        assertEquals("z0.t0", observe.returnTypeName)
        assertEquals("z0.n", target(AppleMusicHookPoint.BROWSE_COMPOSER_SCOPE).className)
        assertEquals("w", target(AppleMusicHookPoint.BROWSE_COMPOSER_SCOPE).methodName)
        assertEquals("z0.S0", target(AppleMusicHookPoint.BROWSE_COMPOSER_SCOPE).returnTypeName)
        assertEquals("o", target(AppleMusicHookPoint.BROWSE_COMPOSER_USE_SCOPE).methodName)
        assertEquals(listOf("z0.Q0"), target(AppleMusicHookPoint.BROWSE_COMPOSER_USE_SCOPE).parameterTypeNames)
        assertEquals("z0.S0", target(AppleMusicHookPoint.BROWSE_SCOPE_INVALIDATE).className)
        assertEquals("z0.v0", target(AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY).className)
        assertEquals(listOf("z0.n", "int"), target(AppleMusicHookPoint.LIBRARY_COMPOSE_CONTENT).parameterTypeNames)
        assertEquals(listOf("z0.n", "int"), target(AppleMusicHookPoint.ALBUM_COMPOSE_CONTENT).parameterTypeNames)
        assertEquals(listOf("z0.n", "int"), target(AppleMusicHookPoint.ARTIST_COMPOSE_CONTENT).parameterTypeNames)
        assertEquals(
            listOf("A1.D", "A1.q"),
            targets(AppleMusicHookPoint.COMPOSE_TEXT_LAYOUT).map { it.className },
        )
    }

    @Test
    fun `browse rows and the album row follow the 1624 classes`() {
        assertEquals(
            listOf("Zb.f", "O9.d", "oa.f", "oa.n", "Zb.n"),
            targets(AppleMusicHookPoint.BROWSE_COMPOSE_ITEM).map { it.className },
        )
        assertEquals("y7.m", target(AppleMusicHookPoint.ALBUM_COMPOSE_ROW).className)
        assertEquals(
            listOf("O0.i", "D7.h", "D7.u", "Sb.A5", "Wb.s", "Sb.z5", "Sb.B5", "int",
                "boolean", "D7.q", "D7.r", "oi.l", "oi.p", "oi.p", "z0.n", "int"),
            target(AppleMusicHookPoint.ALBUM_COMPOSE_ROW).parameterTypeNames,
        )
    }

    @Test
    fun `catalogue executors cover every search and editorial channel`() {
        val searchHost = "com.google.android.gms.internal.cast.f0"
        assertEquals(
            listOf(
                "x9.D#b", "x9.D#e", "$searchHost#b", "$searchHost#a", "E8.g#d", "E8.g#e", "L3.p#b",
            ),
            targets(AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR).map { "${it.className}#${it.methodName}" },
        )
        // /search carries the storefront in a 7-parameter signature; both /search/query channels have 6.
        assertEquals(
            listOf(7, 7, 7, 6, 7, 7, 6),
            targets(AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR).map { it.parameterCount },
        )
    }

    @Test
    fun `lyrics UI and song binding members follow the 1624 fields`() {
        val ui = target(AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW)
        assertEquals("l0", ui.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_UI_BINDING_FIELD))
        assertEquals("d0", ui.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_UI_BINDING_RECYCLER_FIELD))
        assertEquals("n0", ui.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_UI_ADAPTER_FIELD))
        assertEquals("m1", ui.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_UI_VIEW_MODEL_FIELD))
        val song = target(AppleMusicHookPoint.PLAYER_SONG_BINDING_EXECUTE)
        assertEquals("l0", song.runtimeMemberName(AppleMusicRuntimeMember.PLAYER_SONG_BINDING_PLAYBACK_ITEM_FIELD))
        assertEquals("d0", song.runtimeMemberName(AppleMusicRuntimeMember.PLAYER_SONG_BINDING_LYRICS_BUTTON_FIELD))
        assertEquals("H0", target(AppleMusicHookPoint.ACTIVITY_THEME_CREATE)
            .runtimeMemberName(AppleMusicRuntimeMember.ACTIVITY_THEME_MODE_FIELD))
        assertEquals("$music.utils.i1", target(AppleMusicHookPoint.APPLE_TEXT_STYLE_UTILS).className)
        assertEquals("$music.utils.e1", target(AppleMusicHookPoint.LYRICS_SHARE_RENDERED_IMAGE).className)
    }
}
