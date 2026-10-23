/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 */
package com.juren233.hle.providers.applemusic.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Original 1613 DEX descriptors, not JADX display names; APK checks run in AppleMusicProfileBinaryTest. */
class AppleMusic7001613ProfileRegressionTest {
    private val version = AppleMusicVersion("7.0.0-beta", 1613L)
    private val previous = AppleMusicVersion("7.0.0-beta", 1611L)
    private fun target(point: AppleMusicHookPoint, version: AppleMusicVersion = this.version) =
        AppleMusicHookProfiles.exactTargets(version, point).single()

    @Test
    fun `1613 has an isolated profile based on the verified 1611 map`() {
        assertEquals("am-7.0.0-beta-1613", AppleMusicHookProfiles.profileFor(version)?.id)
        assertEquals("am-7.0.0-beta-1611", AppleMusicHookProfiles.profileFor(previous)?.id)
        assertNull(AppleMusicHookProfiles.profileFor(AppleMusicVersion("7.0.0-beta", 1612L)))
        AppleMusicHookPoint.entries.forEach { point ->
            val previousTargets = AppleMusicHookProfiles.exactTargets(previous, point)
            val currentTargets = AppleMusicHookProfiles.exactTargets(version, point)
            if (point == AppleMusicHookPoint.CONTENT_ITEM_METADATA_CLASSES) {
                assertEquals(
                    "Lost targets: $point",
                    previousTargets.map { it.className },
                    currentTargets.take(previousTargets.size).map { it.className },
                )
            } else {
                assertEquals("Lost targets: $point", previousTargets.size, currentTargets.size)
            }
        }
    }

    @Test
    fun `1613 signature changes do not overwrite older exact targets`() {
        assertEquals(
            "q8.q7",
            target(AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING, previous).className,
        )
        assertEquals(
            "z0.v0",
            target(AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY, previous).className,
        )
        assertEquals(
            "com.apple.android.music.E\$a",
            target(AppleMusicHookPoint.THEME_MODE_EMIT, previous).className,
        )
        // R8 reused q8.r7 from 1609 and z0.r0 from 1607; older profiles keep their own values.
        val older = AppleMusicVersion("7.0.0-beta", 1607L)
        assertEquals(
            "q8.r7",
            target(AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING,
                AppleMusicVersion("7.0.0-beta", 1609L)).className,
        )
        assertEquals(
            "q8.t7",
            target(AppleMusicHookPoint.IN_APP_ACTION_SHEET_BINDING, older).className,
        )
        assertEquals(
            "z0.r0",
            target(AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY, older).className,
        )
    }

    @Test
    fun `lyrics owners and binding fields use the 1613 binary names`() {
        val menu = target(AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER)
        // b0 only toggles the action row; a0 builds popup_menu_lyrics_translations.
        assertEquals("com.apple.android.music.player.fragment.a0", menu.className)
        listOf(
            AppleMusicHookPoint.LYRICS_TRANSLATION_PREFERENCE to "n",
            AppleMusicHookPoint.LYRICS_PRONUNCIATION_PREFERENCE to "m",
        ).forEach { (point, method) ->
            val preference = target(point)
            assertEquals("ja.i0", preference.className)
            assertEquals(method, preference.methodName)
            assertEquals(listOf("boolean"), preference.parameterTypeNames)
            assertEquals(true, preference.isStatic)
        }
        val ui = target(AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW)
        assertEquals("l0", ui.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_UI_BINDING_FIELD))
        assertEquals("d0", ui.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_UI_BINDING_RECYCLER_FIELD))
        assertEquals("o0", ui.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_UI_ADAPTER_FIELD))
        assertEquals("m1", ui.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_UI_VIEW_MODEL_FIELD))
        val song = target(AppleMusicHookPoint.PLAYER_SONG_BINDING_EXECUTE)
        assertEquals("l0", song.runtimeMemberName(AppleMusicRuntimeMember.PLAYER_SONG_BINDING_PLAYBACK_ITEM_FIELD))
        assertEquals("d0", song.runtimeMemberName(AppleMusicRuntimeMember.PLAYER_SONG_BINDING_LYRICS_BUTTON_FIELD))
        assertEquals(
            listOf("com.apple.android.music.player.x"),
            AppleMusicHookProfiles.exactTargets(version, AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER)
                .map { it.className },
        )
        // The word adapter and the active-line adapter are siblings under player.g1 in 1613.
        assertEquals(
            listOf("com.apple.android.music.player.x", "com.apple.android.music.player.S0"),
            AppleMusicHookProfiles.exactTargets(version, AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER)
                .map { it.className },
        )
    }

    @Test
    fun `catalogue executors and compose page hooks follow the 1613 signatures`() {
        val executors = AppleMusicHookProfiles.exactTargets(
            version,
            AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR,
        ).map { it.className to it.methodName }
        assertTrue(executors.containsAll(listOf("Fe.b" to "b", "Fe.b" to "a", "I.h1" to "g", "I.h1" to "h")))
        listOf(
            AppleMusicHookPoint.ALBUM_COMPOSE_CONTENT,
            AppleMusicHookPoint.ARTIST_COMPOSE_CONTENT,
            AppleMusicHookPoint.LIBRARY_COMPOSE_CONTENT,
        ).forEach { point ->
            assertEquals(listOf("z0.m", "int"), target(point).parameterTypeNames)
        }
        assertEquals("PageContent", target(AppleMusicHookPoint.ALBUM_COMPOSE_CONTENT).methodName)
        assertEquals("PageContent", target(AppleMusicHookPoint.ARTIST_COMPOSE_CONTENT).methodName)
        assertEquals("w1", target(AppleMusicHookPoint.LIBRARY_COMPOSE_CONTENT).methodName)
        assertEquals(
            listOf("kotlin.Pair", "kotlin.Pair"),
            target(AppleMusicHookPoint.ALBUM_COMPOSE_TRACK_COMPARATOR).parameterTypeNames,
        )
        assertEquals("H0", target(AppleMusicHookPoint.ACTIVITY_THEME_CREATE)
            .runtimeMemberName(AppleMusicRuntimeMember.ACTIVITY_THEME_MODE_FIELD))
        assertEquals(
            listOf(
                "O0.i", "D7.h", "D7.u", "Rb.D5", "Vb.s", "Rb.C5", "Rb.E5", "int",
                "boolean", "D7.q", "D7.r", "pi.l", "pi.p", "pi.p", "z0.m", "int",
            ),
            target(AppleMusicHookPoint.ALBUM_COMPOSE_ROW).parameterTypeNames,
        )
    }
}
