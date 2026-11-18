/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 */
package com.juren233.hle.providers.applemusic.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Original 1622 DEX descriptors, not JADX display names; APK checks run in AppleMusicProfileBinaryTest. */
class AppleMusic7011622ProfileRegressionTest {
    private val version = AppleMusicVersion("7.0.1", 1622L)
    private val previous = AppleMusicVersion("7.0.0", 1614L)
    private val music = "com.apple.android.music"
    private fun target(point: AppleMusicHookPoint, version: AppleMusicVersion = this.version) =
        AppleMusicHookProfiles.exactTargets(version, point).single()
    private fun targets(point: AppleMusicHookPoint, version: AppleMusicVersion = this.version) =
        AppleMusicHookProfiles.exactTargets(version, point)

    @Test
    fun `1622 has an isolated profile with every 1614 group`() {
        assertEquals("am-7.0.1-1622", AppleMusicHookProfiles.profileFor(version)?.id)
        assertEquals("am-7.0.0-beta-1614", AppleMusicHookProfiles.profileFor(previous)?.id)
        assertNull(AppleMusicHookProfiles.profileFor(AppleMusicVersion("7.0.1", 1623L)))
        assertTrue(AppleMusicHookProfiles.profileFor(version)!!.settingsDataCategoryHasNoSimGate)
        AppleMusicHookPoint.entries.forEach { point ->
            assertEquals("Lost targets: $point", targets(point, previous).size, targets(point).size)
        }
    }

    @Test
    fun `1622 renames do not overwrite the 1614 targets`() {
        assertEquals("com.airbnb.epoxy.K", target(AppleMusicHookPoint.EPOXY_FINAL_BIND, previous).className)
        assertEquals("q3.b", target(AppleMusicHookPoint.COMPOSE_OBSERVE_AS_STATE, previous).className)
        assertEquals("$music.q", targets(AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES, previous)[4].className)
        assertEquals(
            "$music.common.actionsheet.U",
            target(AppleMusicHookPoint.LYRICS_SHARE_DESTINATION_LIST, previous).className,
        )
        assertEquals(
            "l0",
            target(AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW, previous)
                .runtimeMemberName(AppleMusicRuntimeMember.LYRICS_UI_BINDING_FIELD),
        )
    }

    @Test
    fun `inherited targets carry the renamed obfuscated types`() {
        val localization = target(AppleMusicHookPoint.CONTENT_HTTP_LOCALIZATION)
        assertEquals("sb.a", localization.className)
        assertEquals(listOf("ok.f"), localization.parameterTypeNames)
        assertEquals("jk.D", localization.returnTypeName)
        assertEquals(listOf("jk.u"), target(AppleMusicHookPoint.LYRICS_COOKIE_JAR).parameterTypeNames)
        assertEquals("com.airbnb.epoxy.J", target(AppleMusicHookPoint.EPOXY_FINAL_BIND).className)
        assertEquals(
            listOf("O0.i", "D7.h", "D7.u", "Sb.B5", "Wb.s", "Sb.A5", "Sb.C5", "int",
                "boolean", "D7.q", "D7.r", "oi.l", "oi.p", "oi.p", "z0.m", "int"),
            target(AppleMusicHookPoint.ALBUM_COMPOSE_ROW).parameterTypeNames,
        )
        assertEquals("y7.n", target(AppleMusicHookPoint.ALBUM_COMPOSE_ROW).className)
        assertEquals(
            listOf("Zb.f", "O9.d", "oa.f", "oa.l", "Zb.n"),
            targets(AppleMusicHookPoint.BROWSE_COMPOSE_ITEM).map { it.className },
        )
        assertEquals("Nj.G", target(AppleMusicHookPoint.RADIO_SEARCH_SESSION)
            .runtimeMemberName(AppleMusicRuntimeMember.RADIO_SEARCH_SCOPE_CLASS))
        assertEquals("$music.C\$a", target(AppleMusicHookPoint.THEME_MODE_EMIT).className)
        assertEquals("$music.p", targets(AppleMusicHookPoint.COLLECTION_SURFACE_CLASSES)[4].className)
    }

    @Test
    fun `lyrics UI members follow the shifted 1622 fields`() {
        val ui = target(AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW)
        assertEquals("m0", ui.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_UI_BINDING_FIELD))
        assertEquals("e0", ui.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_UI_BINDING_RECYCLER_FIELD))
        assertEquals("o0", ui.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_UI_ADAPTER_FIELD))
        assertEquals("n1", ui.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_UI_VIEW_MODEL_FIELD))
        assertEquals(
            listOf("$music.player.A", "$music.player.V0"),
            targets(AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER).map { it.className },
        )
        assertEquals("$music.player.A", target(AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER).className)
        assertEquals("$music.player.fragment.Z", target(AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER).className)
        assertEquals("x", target(AppleMusicHookPoint.LYRICS_NETWORK_REQUEST).methodName)
        val song = target(AppleMusicHookPoint.PLAYER_SONG_BINDING_EXECUTE)
        assertEquals("m0", song.runtimeMemberName(AppleMusicRuntimeMember.PLAYER_SONG_BINDING_PLAYBACK_ITEM_FIELD))
        assertEquals("e0", song.runtimeMemberName(AppleMusicRuntimeMember.PLAYER_SONG_BINDING_LYRICS_BUTTON_FIELD))
        assertEquals("I0", target(AppleMusicHookPoint.ACTIVITY_THEME_CREATE)
            .runtimeMemberName(AppleMusicRuntimeMember.ACTIVITY_THEME_MODE_FIELD))
    }

    @Test
    fun `compose and catalogue entry points use the 1622 binary names`() {
        val observe = target(AppleMusicHookPoint.COMPOSE_OBSERVE_AS_STATE)
        assertEquals("$music.library3.librarypin.j", observe.className)
        assertEquals("m", observe.methodName)
        assertEquals(listOf("androidx.lifecycle.I", "z0.m"), observe.parameterTypeNames)
        assertEquals("z0.t0", observe.returnTypeName)
        assertEquals("w", target(AppleMusicHookPoint.BROWSE_COMPOSER_SCOPE).methodName)
        assertEquals("z0.T0", target(AppleMusicHookPoint.BROWSE_COMPOSER_SCOPE).returnTypeName)
        assertEquals("o", target(AppleMusicHookPoint.BROWSE_COMPOSER_USE_SCOPE).methodName)
        assertEquals(listOf("z0.R0"), target(AppleMusicHookPoint.BROWSE_COMPOSER_USE_SCOPE).parameterTypeNames)
        assertEquals("z0.w0", target(AppleMusicHookPoint.COMPOSE_NEVER_EQUAL_POLICY).className)
        assertEquals(
            listOf("x9.D#b", "x9.D#e", "L3.p#b", "L3.p#a", "pe.c#d", "pe.c#e"),
            targets(AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR).map { "${it.className}#${it.methodName}" },
        )
        assertEquals("G", target(AppleMusicHookPoint.MEDIA_API_REPOSITORY_HOLDER_CLASS)
            .runtimeMemberName(AppleMusicRuntimeMember.MEDIA_API_DIRECT_QUERY_METHOD))
        val binding = targets(AppleMusicHookPoint.DATA_BINDING_RUNTIME_CLASSES)
            .single { it.className == "androidx.databinding.ViewDataBinding" }
        assertEquals(
            listOf("n0", "x", "n", "j0"),
            listOf(
                AppleMusicRuntimeMember.DATA_BINDING_REGISTRATION_METHOD,
                AppleMusicRuntimeMember.DATA_BINDING_INVALIDATE_METHOD,
                AppleMusicRuntimeMember.DATA_BINDING_EXECUTE_METHOD,
                AppleMusicRuntimeMember.DATA_BINDING_SET_VARIABLE_METHOD,
            ).map(binding::runtimeMemberName),
        )
    }

    @Test
    fun `artist top song model and lyric share classes use the 1622 names`() {
        val topSong = targets(AppleMusicHookPoint.ARTIST_SURFACE_CLASSES).single {
            it.runtimeMemberName(AppleMusicRuntimeMember.ARTIST_RUNTIME_ROLE) == "top_song_model"
        }
        assertEquals("$music.s1", topSong.className)
        assertEquals(
            listOf("b", "L", "Q", "H"),
            listOf(
                AppleMusicRuntimeMember.ARTIST_MODEL_BIND_METHOD,
                AppleMusicRuntimeMember.ARTIST_TOP_SONG_TITLE_FIELD,
                AppleMusicRuntimeMember.ARTIST_TOP_SONG_SUBTITLE_FIELD,
                AppleMusicRuntimeMember.ARTIST_TOP_SONG_CAPTION_FIELD,
            ).map(topSong::runtimeMemberName),
        )
        val list = target(AppleMusicHookPoint.LYRICS_SHARE_DESTINATION_LIST)
        assertEquals("$music.common.actionsheet.V", list.className)
        assertEquals(
            "$music.common.actionsheet.V\$a",
            list.runtimeMemberName(AppleMusicRuntimeMember.LYRICS_SHARE_DESTINATION_ITEM_CLASS),
        )
        assertEquals("$music.common.actionsheet.V\$a", target(AppleMusicHookPoint.LYRICS_SHARE_DESTINATION_ICON).className)
        assertEquals("$music.common.actionsheet.S", target(AppleMusicHookPoint.LYRICS_SHARE_PROGRESS_CREATE).className)
        assertEquals("$music.common.actionsheet.S", target(AppleMusicHookPoint.LYRICS_SHARE_PROGRESS_CREATE_VIEW).className)
        assertEquals("$music.utils.h1", target(AppleMusicHookPoint.LYRICS_SHARE_RENDERED_IMAGE).className)
    }
}
