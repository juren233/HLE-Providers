/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hle.providers.applemusic.xposed

import org.junit.Assert.*
import org.junit.Test

class AppleAlbumComposeMetadataTest {
    private val version = AppleMusicVersion("7.0.0-beta", 1606L)
    private fun target(point: AppleMusicHookPoint) = AppleMusicHookProfiles.exactTargets(version, point).single()
    private val original = Alias("太聪明", "陈绮贞", "zh-Hant")
    private val localized = Alias("Too Smart", "Cheer Chen", "en-US")

    @Test fun `album entry and typed mapper match original DEX rather than legacy controller or bridge`() {
        val content = target(AppleMusicHookPoint.ALBUM_COMPOSE_CONTENT)
        assertEquals("com.apple.android.music.collection2.fragment.AlbumPageFragment", content.className)
        assertEquals("r1", content.methodName)
        assertEquals(listOf("z0.m"), content.parameterTypeNames)
        val mapper = target(AppleMusicHookPoint.ALBUM_COMPOSE_TRACK_MAPPER)
        assertEquals("com.apple.android.music.collection2.viewmodel.AlbumViewModel", mapper.className)
        assertEquals("transformToTrackList", mapper.methodName)
        assertEquals(listOf("com.apple.android.music.mediaapi.models.Album"), mapper.parameterTypeNames)
        assertEquals("java.util.List", mapper.returnTypeName)
        assertEquals(false, mapper.isStatic)
        assertFalse(mapper.includeSynthetic)
        assertFalse(mapper.className.contains("Epoxy"))
    }

    @Test fun `row target and raw identity fields preserve exact 1606 binary names`() {
        val row = target(AppleMusicHookPoint.ALBUM_COMPOSE_ROW)
        assertEquals("y7.n", row.className)
        assertEquals("a", row.methodName)
        assertEquals(listOf("O0.j", "D7.i", "D7.t", "Rb.x5", "Vb.t", "Rb.w5", "Rb.y5", "int", "boolean", "D7.o", "D7.p", "pi.l", "pi.p", "pi.p", "z0.m", "int"), row.parameterTypeNames)
        assertEquals(true, row.isStatic)
        assertEquals("a", row.runtimeMemberName(AppleMusicRuntimeMember.ALBUM_COMPOSE_ROW_KEY_FIELD))
        assertEquals("a", row.runtimeMemberName(AppleMusicRuntimeMember.ALBUM_COMPOSE_KEY_ID_FIELD))
        assertNotEquals("c", row.runtimeMemberName(AppleMusicRuntimeMember.ALBUM_COMPOSE_KEY_ID_FIELD))
        val comparator = target(AppleMusicHookPoint.ALBUM_COMPOSE_TRACK_COMPARATOR)
        assertEquals("trackDisplayItems_delegate\$lambda\$14\$lambda\$12", comparator.methodName)
        assertEquals(true, comparator.isStatic)
        assertEquals("boolean", comparator.returnTypeName)
        assertEquals("refreshState", target(AppleMusicHookPoint.ALBUM_COMPOSE_REFRESH).methodName)
        assertNotEquals("refreshData", target(AppleMusicHookPoint.ALBUM_COMPOSE_REFRESH).methodName)
        val owner = target(AppleMusicHookPoint.ALBUM_COMPOSE_PAGE_ID)
        assertEquals("com.apple.android.music.collection2.viewmodel.BaseCollectionViewModel", owner.className)
        assertEquals("getId", owner.methodName)
        assertEquals(emptyList<String>(), owner.parameterTypeNames)
        assertEquals("java.lang.String", owner.returnTypeName)
        assertEquals(false, owner.isStatic)
    }

    @Test fun `album support does not leak to older or unknown versions`() {
        val points = AppleMusicHookPoint.entries.filter { it.name.startsWith("ALBUM_COMPOSE_") }
        assertEquals(11, points.size)
        points.forEach {
            assertEquals(1, AppleMusicHookProfiles.exactTargets(version, it).size)
            assertTrue(AppleMusicHookProfiles.exactTargets(AppleMusicVersion("6.5.3", 1599), it).isEmpty())
            assertTrue(AppleMusicHookProfiles.exactTargets(AppleMusicVersion("7.0.0-beta", 9999), it).isEmpty())
        }
    }

    @Test fun `album header has its own binary verified publication method on both beta builds`() {
        listOf(1606L, 1607L).forEach { versionCode ->
            val header = AppleMusicHookProfiles.exactTargets(
                AppleMusicVersion("7.0.0-beta", versionCode),
                AppleMusicHookPoint.ALBUM_COMPOSE_HEADER_REFRESH,
            ).single()
            assertEquals("com.apple.android.music.collection2.viewmodel.BaseCollectionViewModel", header.className)
            assertEquals("publishHeader", header.methodName)
            assertEquals(emptyList<String>(), header.parameterTypeNames)
            assertEquals("void", header.returnTypeName)
            assertEquals(false, header.isStatic)
            assertFalse(header.includeSynthetic)
            assertNotEquals("refreshData", header.methodName)
            assertNotEquals("refreshState", header.methodName)
        }
    }

    @Test fun `no alias preserves native rows and warm alias is acknowledged by first mapping`() {
        val state = AppleAlbumRowRevision()
        assertFalse(state.dirty())
        assertTrue(state.update("song", original))
        assertTrue(state.dirty())
        state.mapped(state.snapshot())
        assertFalse(state.dirty())
        assertFalse(state.update("song", original))
        assertFalse(state.dirty())
    }

    @Test fun `late primary replacement invalidates rows even after fallback was mapped`() {
        val state = AppleAlbumRowRevision()
        state.update("song", localized)
        state.mapped(state.snapshot())
        assertTrue(state.update("song", original))
        assertTrue(state.dirty())
        state.mapped(state.snapshot())
        assertFalse(state.dirty())
    }

    @Test fun `alias arriving while native mapper copies strings cannot be lost`() {
        val state = AppleAlbumRowRevision()
        state.update("song", localized)
        val started = state.snapshot()
        state.update("song", original)
        state.mapped(started)
        assertTrue(state.dirty())
        state.mapped(state.snapshot())
        state.mapped(started)
        assertFalse(state.dirty())
    }

    @Test fun `separate page revisions and shared song callbacks cannot acknowledge another page`() {
        val first = AppleAlbumRowRevision()
        val second = AppleAlbumRowRevision()
        first.update("shared", original)
        second.update("shared", original)
        first.mapped(first.snapshot())
        assertFalse(first.dirty())
        assertTrue(second.dirty())
        first.update("other", localized)
        second.mapped(second.snapshot())
        assertTrue(first.dirty())
        assertFalse(second.dirty())
    }

    /** Stands in for the host's own `kotlin.Pair`, which the module's `is Pair` can never match. */
    class HostPair(val first: Any, val second: Any?)
    class Entity
    object Comparators {
        @JvmStatic fun hostPairs(a: HostPair, b: HostPair) = true
        @JvmStatic fun entities(a: Entity, b: Entity) = true
    }

    @Test fun `1611 comparator root is read from a host Pair loaded outside the module`() {
        val method = Comparators::class.java.getMethod("hostPairs", HostPair::class.java, HostPair::class.java)
        val getter = hostPairFirstGetter(method)
        assertNotNull(getter)
        val root = Entity()
        val arg = HostPair(root, arrayOf<Any>())
        assertFalse((arg as Any) is Pair<*, *>)
        assertSame(root, albumComparatorRoot(arg, getter))
    }

    @Test fun `entity comparator keeps the argument itself as root`() {
        val method = Comparators::class.java.getMethod("entities", Entity::class.java, Entity::class.java)
        val getter = hostPairFirstGetter(method)
        assertNull(getter)
        val entity = Entity()
        assertSame(entity, albumComparatorRoot(entity, getter))
        assertNull(albumComparatorRoot(null, getter))
    }

    @Test fun `1611 comparator profile declares host Pair parameters`() {
        val comparator = AppleMusicHookProfiles.exactTargets(
            AppleMusicVersion("7.0.0-beta", 1611L),
            AppleMusicHookPoint.ALBUM_COMPOSE_TRACK_COMPARATOR,
        ).single()
        assertEquals(listOf("kotlin.Pair", "kotlin.Pair"), comparator.parameterTypeNames)
        assertEquals("getFirst", AppleMusicHookProfiles.HOST_PAIR_FIRST_GETTER)
    }
}
