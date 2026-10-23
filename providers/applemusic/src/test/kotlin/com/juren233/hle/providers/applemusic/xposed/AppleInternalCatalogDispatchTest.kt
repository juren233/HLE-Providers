/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4 调度所有者回归：重复请求合并、回调语义、批处理名额的
 * 完成/失败/同步异常/失效四条释放路径。行为基线取自拆分前的
 * AppleInternalCatalogResolver 内联实现。
 */
class AppleInternalCatalogDispatchTest {

    private fun originalRequest(
        key: String,
        mediaId: String = key,
        priority: RequestPriority = RequestPriority.BACKGROUND,
        language: String = "ja-JP",
        callbacks: List<(Alias?) -> Unit> = emptyList(),
    ) = OriginalEntityRequest(
        requestKey = key,
        mediaId = mediaId,
        lookupIds = listOf(mediaId),
        entityType = LocalizedEntityType.SONG,
        language = language,
        storefront = "jp",
        directCacheKey = "V2:ENTITY_SONG:$mediaId",
        priority = priority,
        callbacks = callbacks,
    )

    private fun localizedRequest(
        key: String,
        priority: RequestPriority = RequestPriority.BACKGROUND,
        language: String = "ja-JP",
    ) = LocalizedRequest(
        cacheKey = "1:SONG:$language:$key",
        requestKey = "rk-$key",
        mediaId = key,
        lookupIds = listOf(key),
        entityType = LocalizedEntityType.SONG,
        selection = 1,
        storefront = "jp",
        language = language,
        priority = priority,
    )

    @Test
    fun `duplicate original entity submissions merge callbacks and never lower priority`() {
        val dispatch = AppleInternalCatalogDispatch()
        val received = mutableListOf<String>()

        val first = dispatch.submitOriginalEntityRequest(
            originalRequest("k1", priority = RequestPriority.BACKGROUND)
                .copy(callbacks = listOf { received += "a" }),
        )
        val second = dispatch.submitOriginalEntityRequest(
            originalRequest("k1", priority = RequestPriority.VISIBLE)
                .copy(callbacks = listOf { received += "b" }),
        )

        assertTrue(first)
        assertFalse(second)
        val queued = dispatch.pendingOriginalEntityRequests().single()
        assertEquals(RequestPriority.VISIBLE, queued.priority)
        assertEquals(2, queued.callbacks.size)
    }

    @Test
    fun `batch selection groups same shape requests and acquires one running slot`() {
        val dispatch = AppleInternalCatalogDispatch()
        dispatch.submitOriginalEntityRequest(originalRequest("a"))
        dispatch.submitOriginalEntityRequest(originalRequest("b"))
        dispatch.submitOriginalEntityRequest(originalRequest("c", language = "ko-KR"))

        val taken = dispatch.takeOriginalEntityBatch()

        assertEquals(listOf("a", "b"), taken.map { it.mediaId })
        val lanes = dispatch.laneSnapshot()
        assertEquals(1, lanes.originalPending)
        assertEquals(1, lanes.originalRunning)
        assertEquals(1, dispatch.batchFlags().originalBackgroundRunning)
    }

    @Test
    fun `completion releases the slot so the next background batch can start`() {
        val dispatch = AppleInternalCatalogDispatch()
        dispatch.submitOriginalEntityRequest(originalRequest("a"))
        val first = dispatch.takeOriginalEntityBatch()
        assertEquals(1, first.size)

        // 后台名额为 1：已有后台批次在途时新后台请求只排队，不要求调度。
        assertFalse(dispatch.submitOriginalEntityRequest(originalRequest("b")))
        assertTrue(dispatch.takeOriginalEntityBatch().isEmpty())
        dispatch.endOriginalEntityBatch(first.first().priority)
        assertEquals(0, dispatch.laneSnapshot().originalRunning)
        assertEquals(0, dispatch.batchFlags().originalBackgroundRunning)
        assertTrue(dispatch.shouldScheduleOriginalEntityBatch())

        val second = dispatch.takeOriginalEntityBatch()
        assertEquals(listOf("b"), second.map { it.mediaId })
    }

    @Test
    fun `background cap holds queued work while visible requests keep a slot`() {
        val dispatch = AppleInternalCatalogDispatch()
        // Occupies the background lane up to its cap using real submissions, then confirms the
        // cap holds queued background work while a visible request still gets a slot.
        dispatch.submitOriginalEntityRequest(originalRequest("warm-1"))
        dispatch.submitOriginalEntityRequest(originalRequest("warm-2"))
        dispatch.takeOriginalEntityBatch()
        dispatch.submitOriginalEntityRequest(originalRequest("warm-3"))
        dispatch.submitOriginalEntityRequest(originalRequest("warm-4"))
        dispatch.takeOriginalEntityBatch()

        assertFalse(dispatch.submitOriginalEntityRequest(originalRequest("bg")))
        assertTrue(dispatch.pendingOriginalEntityRequests().any { it.mediaId == "bg" })
        assertTrue(dispatch.submitOriginalEntityRequest(originalRequest("v", priority = RequestPriority.VISIBLE)))

        val taken = dispatch.takeOriginalEntityBatch()
        assertEquals(listOf("v"), taken.map { it.mediaId })
        assertTrue(dispatch.takeOriginalEntityBatch().isEmpty())
    }

    @Test
    fun `take clears the scheduled flag even when capacity vanished so later release re-arms`() {
        val dispatch = AppleInternalCatalogDispatch()
        dispatch.submitOriginalEntityRequest(originalRequest("a"))
        // Fill every running slot with real batches so the next take cannot acquire one.
        repeat(MAX_ORIGINAL_ENTITY_BATCHES_RUNNING) { index ->
            dispatch.submitOriginalEntityRequest(
                originalRequest("fill-$index", priority = RequestPriority.VISIBLE),
            )
            dispatch.takeOriginalEntityBatch()
        }

        assertTrue(dispatch.takeOriginalEntityBatch().isEmpty())
        assertFalse(dispatch.batchFlags().originalScheduled)

        dispatch.endOriginalEntityBatch(RequestPriority.VISIBLE)
        assertTrue(dispatch.shouldScheduleOriginalEntityBatch())
    }

    @Test
    fun `original song lane merges duplicates and finish drains each callback once`() {
        val dispatch = AppleInternalCatalogDispatch()
        val results = mutableListOf<Int>()

        assertTrue(dispatch.attachOriginalSongCallback("a") { results += 1 })
        assertFalse(dispatch.attachOriginalSongCallback("a") { results += 2 })
        assertTrue(dispatch.attachOriginalSongCallback("b") { results += 3 })

        val drained = dispatch.drainOriginalSongCallbacks("a")
        assertEquals(2, drained.size)
        drained.forEach { callback ->
            callback(OriginalResolution(alias = null, language = null, originKnown = false, artistIds = emptyList()))
        }
        assertEquals(listOf(1, 2), results)
        assertTrue(dispatch.drainOriginalSongCallbacks("a").isEmpty())
        assertTrue(dispatch.attachOriginalSongCallback("a") { })
    }

    @Test
    fun `catalog identity lane merges concurrent lookups and drains exactly once`() {
        val dispatch = AppleInternalCatalogDispatch()
        var calls = 0

        assertTrue(dispatch.attachCatalogIdentityCallback("a") { calls += 1 })
        assertFalse(dispatch.attachCatalogIdentityCallback("a") { calls += 1 })

        val drained = dispatch.drainCatalogIdentityCallbacks("a")
        assertEquals(2, drained.size)
        drained.forEach { callback ->
            callback(CatalogIdentity(isrc = null, fallbackAliases = emptyList(), genres = emptyList(), artistIds = emptyList()))
        }
        assertEquals(2, calls)
        assertTrue(dispatch.drainCatalogIdentityCallbacks("a").isEmpty())
    }

    @Test
    fun `candidate publication skips blank aliases and invalidation drops pending callbacks`() {
        val dispatch = AppleInternalCatalogDispatch()
        val seen = mutableListOf<Alias>()
        val valid = Alias("タイトル", "アーティスト", "ja-JP")

        dispatch.addOriginalCandidateCallback("a") { seen += it }
        dispatch.publishOriginalCandidate("a", Alias("", "", "ja-JP"))
        assertTrue(seen.isEmpty())
        dispatch.publishOriginalCandidate("a", valid)
        assertEquals(listOf(valid), seen)
        dispatch.publishOriginalCandidate("a", valid)
        assertEquals(listOf(valid), seen)

        dispatch.addOriginalCandidateCallback("b") { seen += it }
        dispatch.discardOriginalCandidates("b")
        dispatch.publishOriginalCandidate("b", valid)
        assertEquals(listOf(valid), seen)
    }

    @Test
    fun `request scope installs once per revision and clears stale priorities`() {
        val dispatch = AppleInternalCatalogDispatch()
        dispatch.rememberRequestPriority("1", RequestPriority.VISIBLE)
        assertEquals(RequestPriority.VISIBLE, dispatch.currentRequestPriority("1", RequestPriority.BACKGROUND))

        assertTrue(dispatch.applyRequestScope(1, visible = setOf("2"), activePage = setOf("3")))
        assertEquals(RequestPriority.BACKGROUND, dispatch.currentScopedPriority("1"))
        assertEquals(RequestPriority.VISIBLE, dispatch.currentScopedPriority("2"))
        assertEquals(RequestPriority.ACTIVE_PAGE, dispatch.currentScopedPriority("3"))

        assertFalse(dispatch.applyRequestScope(1, visible = setOf("2"), activePage = setOf("3")))
        assertTrue(dispatch.applyRequestScope(2, visible = emptySet(), activePage = emptySet()))
        assertEquals(RequestPriority.BACKGROUND, dispatch.currentRequestPriority("1", RequestPriority.VISIBLE))
    }

    @Test
    fun `scoped remember fills absent ids as background and keeps scoped winners`() {
        val dispatch = AppleInternalCatalogDispatch()
        assertTrue(dispatch.applyRequestScope(1, visible = setOf("2"), activePage = emptySet()))

        dispatch.rememberRequestPriority("9", RequestPriority.VISIBLE)
        assertEquals(RequestPriority.BACKGROUND, dispatch.currentScopedPriority("9"))
        dispatch.rememberRequestPriority("2", RequestPriority.BACKGROUND)
        assertEquals(RequestPriority.VISIBLE, dispatch.currentScopedPriority("2"))
    }

    @Test
    fun `pending priority sync demotes out of scope lanes and honors the id filter`() {
        val dispatch = AppleInternalCatalogDispatch()
        dispatch.submitLocalizedRequest(localizedRequest("1", priority = RequestPriority.ACTIVE_PAGE))
        dispatch.submitOriginalEntityRequest(originalRequest("2", priority = RequestPriority.ACTIVE_PAGE))

        val changed = dispatch.updatePendingRequestPriorities(
            scopedPriorities = mapOf("1" to RequestPriority.VISIBLE),
        )
        assertEquals(2, changed)
        assertEquals(RequestPriority.VISIBLE, dispatch.pendingLocalizedRequests().single().priority)
        assertEquals(
            RequestPriority.BACKGROUND,
            dispatch.pendingOriginalEntityRequests().single().priority,
        )

        val unchanged = dispatch.updatePendingRequestPriorities(
            scopedPriorities = mapOf("1" to RequestPriority.VISIBLE),
            onlyMediaIds = setOf("1"),
        )
        assertEquals(0, unchanged)
    }

    @Test
    fun `localized submissions merge by request key without stacking callbacks`() {
        val dispatch = AppleInternalCatalogDispatch()

        assertTrue(dispatch.submitLocalizedRequest(localizedRequest("a", priority = RequestPriority.BACKGROUND)))
        assertFalse(dispatch.submitLocalizedRequest(localizedRequest("a", priority = RequestPriority.VISIBLE)))

        val queued = dispatch.pendingLocalizedRequests().single()
        assertEquals(RequestPriority.VISIBLE, queued.priority)
    }

    @Test
    fun `localized completion releases the background slot for the next batch`() {
        val dispatch = AppleInternalCatalogDispatch()
        dispatch.submitLocalizedRequest(localizedRequest("a", language = "ja-JP"))
        dispatch.submitLocalizedRequest(localizedRequest("b", language = "ja-JP"))
        dispatch.submitLocalizedRequest(localizedRequest("c", language = "ko-KR"))

        val taken = dispatch.takeLocalizedBatch()
        assertEquals(2, taken.size)
        assertEquals(1, dispatch.laneSnapshot().localizedPending)
        assertEquals(1, dispatch.laneSnapshot().localizedRunning)

        dispatch.endLocalizedBatch(taken.first().priority)
        assertEquals(0, dispatch.laneSnapshot().localizedRunning)
        assertEquals(0, dispatch.batchFlags().localizedBackgroundRunning)
    }

    @Test
    fun `localized batch fills spare capacity with lower priority requests of the same group`() {
        val dispatch = AppleInternalCatalogDispatch()
        dispatch.submitLocalizedRequest(localizedRequest("bg", priority = RequestPriority.BACKGROUND))
        dispatch.submitLocalizedRequest(localizedRequest("visible", priority = RequestPriority.VISIBLE))
        dispatch.submitLocalizedRequest(localizedRequest("active", priority = RequestPriority.ACTIVE_PAGE))
        dispatch.submitLocalizedRequest(
            localizedRequest("ko", priority = RequestPriority.ACTIVE_PAGE, language = "ko-KR"),
        )

        val taken = dispatch.takeLocalizedBatch()

        assertEquals(listOf("visible", "active", "bg"), taken.map(LocalizedRequest::mediaId))
        assertEquals(listOf("ko"), dispatch.pendingLocalizedRequests().map(LocalizedRequest::mediaId))
        assertEquals(0, dispatch.batchFlags().localizedBackgroundRunning)
    }

    @Test
    fun `localized finish drains in flight callbacks so late arrivals re-own the key`() {
        val dispatch = AppleInternalCatalogDispatch()
        val aliases = mutableListOf<Alias?>()

        assertTrue(dispatch.attachLocalizedCallback("rk-a", { aliases += it }, onMerged = {}))
        // A second attach merges into the same lane without querying again.
        assertFalse(
            dispatch.attachLocalizedCallback("rk-a", { alias -> aliases += alias }, onMerged = {}),
        )
        val drained = dispatch.drainLocalizedCallbacks("rk-a")
        assertEquals(2, drained.size)
        drained.forEach { callback -> callback(Alias("t", "a", "ja-JP")) }
        assertEquals(2, aliases.size)
        assertNull(dispatch.drainLocalizedCallbacks("rk-a").firstOrNull())
    }

    @Test
    fun `identity base queries schedule once and take ordered batches`() {
        val dispatch = AppleInternalCatalogDispatch()
        val results = mutableListOf<String>()

        assertTrue(dispatch.submitIdentityBaseQuery("1") { results += "1:${it?.id}" })
        assertFalse(dispatch.submitIdentityBaseQuery("2") { results += "2:${it?.id}" })
        assertFalse(dispatch.submitIdentityBaseQuery("1") { results += "1b:${it?.id}" })
        assertFalse(dispatch.submitIdentityBaseQuery("3") { results += "3:${it?.id}" })

        val (first, firstHasMore) = dispatch.takeIdentityBaseQueryBatch(batchSize = 2)
        assertEquals(listOf("1", "2"), first.keys.toList())
        assertTrue(firstHasMore)
        assertEquals(2, first.getValue("1").size)
        // 仍有剩余时调度权在调用方，新提交不应再次要求调度。
        assertFalse(dispatch.submitIdentityBaseQuery("4") {})

        val (second, secondHasMore) = dispatch.takeIdentityBaseQueryBatch(batchSize = 2)
        assertEquals(listOf("3", "4"), second.keys.toList())
        assertFalse(secondHasMore)
        (first.values + second.values).flatten().forEach { it(null) }
        assertEquals(listOf("1:null", "1b:null", "2:null", "3:null"), results)

        assertTrue(dispatch.takeIdentityBaseQueryBatch(batchSize = 2).first.isEmpty())
        assertTrue(dispatch.submitIdentityBaseQuery("5") {})
    }

    @Test
    fun `identity base song selection keeps single id semantics and matches ids in batches`() {
        val one = catalogSong("1")
        val two = catalogSong("2")

        assertEquals(one, selectIdentityBaseSong("9", 1, listOf(one)))
        assertEquals(two, selectIdentityBaseSong("2", 3, listOf(one, two)))
        assertNull(selectIdentityBaseSong("3", 3, listOf(one, two)))
        assertNull(selectIdentityBaseSong("1", 1, null))
        assertNull(selectIdentityBaseSong("1", 2, emptyList()))
    }

    @Test
    fun `isrc queries group by language and take ordered batches`() {
        val dispatch = AppleInternalCatalogDispatch()
        assertTrue(dispatch.submitIsrcQuery("ja-JP", "A") {})
        assertFalse(dispatch.submitIsrcQuery("ja-JP", "B") {})
        assertFalse(dispatch.submitIsrcQuery("ko-KR", "C") {})
        assertFalse(dispatch.submitIsrcQuery("ja-JP", "A") {})

        val (firstLanguage, first, firstHasMore) = dispatch.takeIsrcQueryBatch(batchSize = 25)
        assertEquals("ja-JP", firstLanguage)
        assertEquals(listOf("A", "B"), first.keys.toList())
        assertEquals(2, first.getValue("A").size)
        assertTrue(firstHasMore)

        val (secondLanguage, second, secondHasMore) = dispatch.takeIsrcQueryBatch(batchSize = 25)
        assertEquals("ko-KR", secondLanguage)
        assertEquals(listOf("C"), second.keys.toList())
        assertFalse(secondHasMore)
        assertNull(dispatch.takeIsrcQueryBatch(batchSize = 25).first)
        assertTrue(dispatch.submitIsrcQuery("ja-JP", "D") {})
    }

    @Test
    fun `isrc batch selection keeps single semantics and matches isrc case-insensitively`() {
        val one = catalogSong("1")
        val two = catalogSong("2")

        assertEquals(one, selectIsrcBatchSong("other", 1, listOf(one, two)))
        assertEquals(two, selectIsrcBatchSong("isrc2", 2, listOf(one, two)))
        assertNull(selectIsrcBatchSong("ISRC3", 2, listOf(one, two)))
        assertNull(selectIsrcBatchSong("ISRC1", 2, null))
    }

    private fun catalogSong(id: String) = CatalogSong(
        id = id,
        alias = Alias("t$id", "a", "ja-JP"),
        isrc = "ISRC$id",
        genres = emptyList(),
        artistIds = emptyList(),
    )
}
