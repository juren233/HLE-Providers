/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseNextTrackProfilesTest {
    @Test
    fun `uses 9 4 25 playback process and original DEX targets`() {
        val profile = NeteaseNextTrackProfiles.resolve("9.4.25", 9_004_025L)
        assertEquals("com.netease.cloudmusic.service.PlayService", profile.serviceClassName)
        assertEquals("cq0.y", profile.playerManagerClassName)
        assertEquals("W1", profile.playerManagerAccessorName)
        assertEquals("g", profile.nextMusicMethodName)
        assertEquals("com.netease.cloudmusic.meta.MusicInfo", profile.musicInfoClassName)
        assertEquals("com.netease.cloudmusic.meta.virtual.SimpleMusicInfo", profile.simpleMusicInfoClassName)
        assertEquals(":play", profile.nextTrackProcessSuffix)
        assertEquals(
            "com.netease.cloudmusic:play",
            NeteaseNextTrackProfiles.nextTrackProcessName(
                "com.netease.cloudmusic", "9.4.25", 9_004_025L,
            ),
        )
        assertEquals(
            "com.netease.cloudmusic",
            NeteaseNextTrackProfiles.nextTrackProcessName(
                "com.netease.cloudmusic", "9.6.0", 9_006_000L,
            ),
        )

        val queries = NeteaseNextTrackResolver.queries(profile)
        assertEquals("W1", queries[0].preferredTarget?.methodName)
        assertEquals("cq0.y", queries[0].preferredTarget?.returnTypeName)
        assertTrue(queries[0].preferredTarget?.isStatic == true)
        assertEquals("cq0.y", queries[1].preferredTarget?.className)
        assertEquals("g", queries[1].preferredTarget?.methodName)
        assertTrue(queries[0].requiredCallerMethodNames.isEmpty())
        assertTrue(queries[1].requiredCallerMethodNames.isEmpty())
    }

    @Test
    fun `uses exact original DEX identifiers for NetEase 9 5 61`() {
        val profile = NeteaseNextTrackProfiles.resolve("9.5.61", 9_005_061L)

        assertEquals("com.netease.cloudmusic.service.MainProcessPlayService", profile.serviceClassName)
        assertEquals("tr0.z", profile.playerManagerClassName)
        assertEquals("com.netease.cloudmusic.meta.MusicInfo", profile.musicInfoClassName)
        assertEquals(
            "com.netease.cloudmusic.meta.virtual.SimpleMusicInfo",
            profile.simpleMusicInfoClassName,
        )
        assertEquals("E1", profile.playerManagerAccessorName)
        assertEquals("g", profile.nextMusicMethodName)
        assertEquals("toSimpleMusicInfo", profile.toSimpleMusicInfoMethodName)
        assertEquals("getId", profile.idMethodName)
        assertEquals("getMusicName", profile.titleMethodName)
        assertEquals("getSingerName", profile.artistMethodName)
        assertEquals("getAlbumName", profile.albumMethodName)
        assertEquals("getDuration", profile.durationMethodName)
    }

    @Test
    fun `uses exact original DEX identifiers for NetEase 9 5 70`() {
        val profile = NeteaseNextTrackProfiles.resolve("9.5.70", 9_005_070L)

        assertEquals("com.netease.cloudmusic.service.MainProcessPlayService", profile.serviceClassName)
        assertEquals("vr0.z", profile.playerManagerClassName)
        assertEquals("com.netease.cloudmusic.meta.MusicInfo", profile.musicInfoClassName)
        assertEquals(
            "com.netease.cloudmusic.meta.virtual.SimpleMusicInfo",
            profile.simpleMusicInfoClassName,
        )
        assertEquals("E1", profile.playerManagerAccessorName)
        assertEquals("g", profile.nextMusicMethodName)
        assertEquals("toSimpleMusicInfo", profile.toSimpleMusicInfoMethodName)
        assertEquals("getId", profile.idMethodName)
        assertEquals("getMusicName", profile.titleMethodName)
        assertEquals("getSingerName", profile.artistMethodName)
        assertEquals("getAlbumName", profile.albumMethodName)
        assertEquals("getDuration", profile.durationMethodName)
    }

    @Test
    fun `uses the verified template for an unknown NetEase version`() {
        assertEquals(
            NeteaseNextTrackProfiles.V9_5_70,
            NeteaseNextTrackProfiles.resolve("9.5.80", 9_005_080L),
        )
    }

    @Test
    fun `uses original 9 6 0 DEX methods and direct resolver targets`() {
        val profile = NeteaseNextTrackProfiles.resolve("9.6.0", 9_006_000L)
        assertEquals("com.netease.cloudmusic.service.MainProcessPlayService", profile.serviceClassName)
        assertEquals("np0.z", profile.playerManagerClassName)
        assertEquals("w1", profile.playerManagerAccessorName)
        assertFalse(profile.playerManagerAccessorName == "E1")
        assertEquals("g", profile.nextMusicMethodName)
        assertEquals("com.netease.cloudmusic.meta.MusicInfo", profile.musicInfoClassName)
        assertEquals("toSimpleMusicInfo", profile.toSimpleMusicInfoMethodName)
        assertEquals("com.netease.cloudmusic.meta.virtual.SimpleMusicInfo", profile.simpleMusicInfoClassName)
        assertTrue(profile.useVerifiedDirectTargets)

        val queries = NeteaseNextTrackResolver.queries(profile)
        val accessor = queries[0]
        val next = queries[1]
        assertEquals("w1", accessor.preferredTarget?.methodName)
        assertEquals("np0.z", accessor.preferredTarget?.returnTypeName)
        assertTrue(accessor.preferredTarget?.isStatic == true)
        assertEquals("g", next.preferredTarget?.methodName)
        assertEquals("np0.z", next.preferredTarget?.className)
        assertEquals("com.netease.cloudmusic.meta.MusicInfo", next.preferredTarget?.returnTypeName)
        assertTrue(accessor.requiredCallerMethodNames.isEmpty())
        assertTrue(next.requiredCallerMethodNames.isEmpty())
        assertEquals(accessor.cacheKey, next.declaringClassReference?.queryCacheKey)
    }

    @Test
    fun `anchors NetEase recovery to original DEX call paths`() {
        val queries = NeteaseNextTrackResolver.queries(NeteaseNextTrackProfiles.V9_5_61)
        val accessor = queries[0]
        val next = queries[1]

        assertEquals("netease-player-manager-accessor-v4", accessor.cacheKey)
        org.junit.Assert.assertNull(accessor.returnTypeName)
        assertEquals(listOf("getRealNextMusic"), accessor.requiredCallerMethodNames)
        assertEquals(listOf("getRealNextMusic"), next.requiredCallerMethodNames)
        assertNull(accessor.preferredTarget)
        assertNull(next.preferredTarget)
        assertEquals(accessor.cacheKey, next.declaringClassReference?.queryCacheKey)
    }

    @Test
    fun `rejects repeated NetEase current-song candidates before repair`() {
        val current = NeteaseNextTrackSnapshot(
            id = "123",
            title = "Current",
            artist = "Artist",
            album = "Album",
            durationMs = 10_000L,
        )
        assertTrue(
            NeteaseNextTrackCandidatePolicy.isCurrent(
                currentId = 123L,
                currentTitle = "Current",
                currentArtist = "Artist",
                candidate = current,
            ),
        )

        val tracker = NeteaseNextTrackValidationTracker(invalidThreshold = 3)
        assertFalse(tracker.record(candidateMatchesCurrent = true))
        assertFalse(tracker.record(candidateMatchesCurrent = true))
        assertTrue(tracker.record(candidateMatchesCurrent = true))
        assertFalse(tracker.record(candidateMatchesCurrent = false))
    }
}
