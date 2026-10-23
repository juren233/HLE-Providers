/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import com.juren233.hle.providers.applemusic.xposed.diagnostics.AppleMetadataFlowDiagnostics
import com.juren233.hle.providers.applemusic.xposed.hooks.AppleFrameworkMetadataHooks
import java.util.concurrent.atomic.AtomicLong

/** Publishes resolved aliases to stores, active playback, and exact in-app consumers. */
internal class AppleMetadataOverrideApplicationCoordinator(
    private val runtime: AppleMusicProviderRuntime,
    private val metadataStore: AppleMetadataOverrideStore,
    private val registry: AppleInAppMetadataRegistry,
    private val resolutionCoordinator: AppleInAppMetadataResolutionCoordinator,
    private val catalogResolver: AppleInternalCatalogResolver,
    private val surfaceRuntime: AppleMetadataSurfaceRuntime,
    private val metadataApplier: AppleInAppMetadataApplier,
    private val librarySurfaceHooks: AppleLibrarySurfaceHooks,
    private val dataBindingHooks: AppleDataBindingMetadataHooks,
    private val listenNowHooks: AppleListenNowHooks,
    private val actionSheetMetadataHooks: AppleActionSheetMetadataHooks,
    private val playbackMetadataCoordinator: ApplePlaybackMetadataCoordinator,
    private val frameworkMetadataHooks: AppleFrameworkMetadataHooks,
    private val visibleMetadataDiagnostics: AppleVisibleMetadataDiagnostics,
    private val media3MetadataCoordinator: AppleMedia3MetadataCoordinator,
    private val configuredContentUiLanguage: () -> Int,
    private val traceSequence: AtomicLong,
) {
    fun rememberOriginalMetadataOverride(
        mediaId: String,
        alias: Alias,
        confirmed: Boolean,
    ) {
        metadataStore.rememberOriginalMetadata(
            mediaId = mediaId,
            alias = alias,
            confirmed = confirmed,
        )
    }

    fun publishCurrentPlaybackAlias(mediaId: String, alias: Alias) {
        if (playbackMetadataCoordinator.currentMetadataId() != mediaId) return
        val displayAlias = AppleLyricTextTransform.displayAlias(alias)
        metadataStore.updateCurrentPlaybackOverride(displayAlias)
        val updated = MediaMetadataCache.updateDisplayMetadata(
            mediaId = mediaId,
            title = displayAlias.title,
            artist = displayAlias.artist,
        ) ?: return
        if (AppleProviderEnvironment.diagnosticsEnabled) AppleMetadataFlowDiagnostics.record("associated_artist_playback_publish") {
            "id=$mediaId title=${AppleMetadataFlowDiagnostics.text(updated.title)} " +
                "artist=${AppleMetadataFlowDiagnostics.text(updated.artist)}"
        }
        PlaybackManager.onCatalogMetadataResolved(mediaId)
    }

    fun apply(
        mediaId: String,
        alias: Alias,
        forceInAppRebind: Boolean = true,
        rememberLocalizedArtist: Boolean = true,
        originalMetadata: Boolean = false,
        originalMetadataConfirmed: Boolean = false,
        artistOnly: Boolean = false,
        propagateArtistEntity: Boolean = true,
    ) {
        if (artistOnly && propagateArtistEntity) {
            val artistId = resolutionCoordinator.sharedAssociatedArtistId(mediaId)
            if (artistId != null) {
                propagateSharedArtistOverride(
                    sourceMediaId = mediaId,
                    artistId = artistId,
                    alias = alias,
                    forceInAppRebind = forceInAppRebind,
                    rememberLocalizedArtist = rememberLocalizedArtist,
                    originalMetadata = originalMetadata,
                    originalMetadataConfirmed = originalMetadataConfirmed,
                )
                return
            }
        }
        val previousEffective = resolutionCoordinator.effectiveAlias(mediaId)
        when {
            artistOnly && originalMetadata -> {
                metadataStore.markOriginalArtistResolved(mediaId)
                metadataStore.rememberOriginalArtist(mediaId, alias)
            }
            artistOnly -> metadataStore.rememberConfiguredArtist(mediaId, alias)
            originalMetadata -> metadataStore.rememberOriginalMetadata(
                mediaId = mediaId,
                alias = alias,
                confirmed = originalMetadataConfirmed,
            )
            else -> metadataStore.rememberConfiguredMetadata(mediaId, alias)
        }
        if (rememberLocalizedArtist && !originalMetadata && artistOnly) {
            catalogResolver.rememberLocalizedArtist(
                selection = configuredContentUiLanguage(),
                artistKeys = localizedArtistCacheKeys(metadataStore.artistKeys(mediaId).orEmpty()),
                localizedArtist = alias.artist,
            )
        }
        val effectiveAlias = resolutionCoordinator.effectiveAlias(mediaId)
        if (effectiveAlias == null) {
            ProviderLogger.info(
                "Apple 设定地区元数据仅缓存: id=$mediaId, reason=original_region_pending"
            )
            return
        }
        val shouldForceInAppRebind = forceInAppRebind || previousEffective != effectiveAlias
        val identity = media3MetadataCoordinator.activePlaybackIdentity()
        val appliesToActivePlayback = identity.mediaId == mediaId
        val allowModelRefresh = allowModelRefresh(mediaId, identity.mediaId)
        if (AppleProviderEnvironment.diagnosticsEnabled) {
            val hasBoundConsumer = metadataApplier.hasLiveModelTarget(mediaId)
            val surfaceRelevant = surfaceRuntime.isCurrentMediaId(mediaId)
            val hasVisibleExactConsumer = surfaceRuntime.hasVisibleConsumer(mediaId)
            val hasActiveVisibleLease = surfaceRuntime.hasVisibleResolutionLease(mediaId)
            media3MetadataCoordinator.logIdentity(
                event = "model_refresh_policy",
                identity = identity,
                details = "overrideId=$mediaId, entityType=${metadataStore.entityType(mediaId)}, " +
                    "forceInAppRebind=$shouldForceInAppRebind, " +
                    "requestedForceInAppRebind=$forceInAppRebind, " +
                    "allowModelRefresh=$allowModelRefresh, surfaceRelevant=$surfaceRelevant, " +
                    "visibleExactConsumer=$hasVisibleExactConsumer, " +
                    "visibleLease=$hasActiveVisibleLease, hasBoundConsumer=$hasBoundConsumer, " +
                    "epoxyRefs=${librarySurfaceHooks.controllerRefCount(mediaId)}, " +
                    "composeRefs=${librarySurfaceHooks.composeStateRefCount(mediaId)}, " +
                    "dataBindingRefs=${dataBindingHooks.refCount(mediaId)}",
            )
        }
        if (playbackMetadataCoordinator.currentMetadataId() == mediaId) {
            metadataStore.updateCurrentPlaybackOverride(effectiveAlias)
        }
        if (AppleProviderEnvironment.diagnosticsEnabled && appliesToActivePlayback) AppleMetadataFlowDiagnostics.record("catalog_display_override") {
            "id=$mediaId active=${playbackMetadataCoordinator.currentMetadataId()} " +
                "title=${AppleMetadataFlowDiagnostics.text(effectiveAlias.title)} " +
                "artist=${AppleMetadataFlowDiagnostics.text(effectiveAlias.artist)} " +
                "original=$originalMetadata"
        }
        MediaMetadataCache.updateDisplayMetadata(mediaId, effectiveAlias.title, effectiveAlias.artist)
        PlaybackManager.onCatalogMetadataResolved(mediaId)
        // 生效别名变化时也合并到后续消息按预算执行：221039 一批名称结果在同一条消息里逐个
        // 同步刷新界面，单条消息最长 220 ms（解析只占约 1 ms，PERF-MAIN-THREAD-001）。
        // 当前播放项仍同步刷新。
        val deferInAppRefresh = !appliesToActivePlayback
        val listenNowDirectBindingTargets = if (deferInAppRefresh) {
            if (deferredInAppRefreshes.offer(mediaId, shouldForceInAppRebind)) {
                runtime.mainHandler.post(::flushDeferredInAppRefreshes)
            }
            -1
        } else {
            deferredInAppRefreshes.cancel(mediaId)
            refreshInAppConsumers(
                mediaId = mediaId,
                alias = effectiveAlias,
                allowModelRefresh = allowModelRefresh,
                forceRebind = shouldForceInAppRebind,
            )
        }
        if (appliesToActivePlayback) {
            actionSheetMetadataHooks.applyAlias(mediaId, effectiveAlias)
            if (AppleProviderEnvironment.diagnosticsEnabled) {
                runtime.mainHandler.post {
                    visibleMetadataDiagnostics.scan("active_alias_applied")
                }
            }
        }
        if (AppleProviderEnvironment.diagnosticsEnabled) {
            val (metadataRefCount, playbackItemRefCount, containerItemRefCount) =
                registry.refCounts(mediaId)
            media3MetadataCoordinator.logIdentity(
                event = "override_applied",
                identity = identity,
                details = "overrideId=$mediaId, active=$appliesToActivePlayback, " +
                    "effective=${effectiveAlias.title}/${effectiveAlias.artist}/${effectiveAlias.album}, " +
                    "original=$originalMetadata, confirmed=$originalMetadataConfirmed, " +
                    "artistOnly=$artistOnly, changed=${previousEffective != effectiveAlias}, " +
                    "inAppRefreshDeferred=$deferInAppRefresh, " +
                    "listenNowDirectBindingTargets=$listenNowDirectBindingTargets, " +
                    "metadataRefs=$metadataRefCount, itemRefs=$playbackItemRefCount, " +
                    "containerRefs=$containerItemRefCount",
            )
        }
        if (previousEffective == effectiveAlias) {
            if (appliesToActivePlayback) {
                metadataApplier.refreshMetadataCallbacks(mediaId, effectiveAlias)
            }
            return
        }
        ProviderLogger.info(
            "Apple 播放元数据已覆盖: id=$mediaId, title=${effectiveAlias.title}, " +
                "artist=${effectiveAlias.artist}, language=${effectiveAlias.language}, " +
                "original=$originalMetadata, confirmed=$originalMetadataConfirmed, " +
                "artistOnly=$artistOnly"
        )
        playbackMetadataCoordinator.invokeCurrentRefresh(mediaId)
        frameworkMetadataHooks.refreshMediaSessionMetadata(mediaId, effectiveAlias)
        frameworkMetadataHooks.refreshMediaSessionQueue(mediaId)
        metadataApplier.refreshMetadataCallbacks(mediaId, effectiveAlias)
    }

    private fun allowModelRefresh(mediaId: String, activeMediaId: String?): Boolean =
        shouldRefreshInAppSurface(
            surfaceRelevant = surfaceRuntime.isCurrentMediaId(mediaId),
            hasVisibleExactConsumer = surfaceRuntime.hasVisibleConsumer(mediaId),
            hasActiveVisibleLease = surfaceRuntime.hasVisibleResolutionLease(mediaId),
        ) && shouldNotifyInAppModelChange(
            mediaId = mediaId,
            activeMediaId = activeMediaId,
            hasBoundConsumer = metadataApplier.hasLiveModelTarget(mediaId),
        )

    /** 刷新 App 内界面消费者；返回“现在就听”直接绑定的刷新数。 */
    private fun refreshInAppConsumers(
        mediaId: String,
        alias: Alias,
        allowModelRefresh: Boolean,
        forceRebind: Boolean,
    ): Int {
        val listenNowDirectBindingTargets = if (
            !allowModelRefresh && listenNowHooks.hasDataBindingRefs(mediaId)
        ) {
            listenNowHooks.refreshDataBindings(mediaId, alias)
        } else {
            0
        }
        if (allowModelRefresh) {
            metadataApplier.applyAliasToMetadataRefs(
                mediaId = mediaId,
                alias = alias,
                forceRebind = forceRebind,
                notifyModelChange = true,
            )
        }
        return listenNowDirectBindingTargets
    }

    /**
     * 页面批量解析时，同一 ID 会因设定地区元数据、两种艺人别名与艺人 ID 传播在同一条消息里
     * 被连续覆盖 2–4 次，生效别名却不变（221018：222 次只涉及 97 个 ID，218 次未变）。
     * 这类刷新合并到下一条主线程消息，每个 ID 按最新别名与刷新策略只执行一次。
     */
    private fun flushDeferredInAppRefreshes() {
        val startNs = System.nanoTime()
        val drained = deferredInAppRefreshes.drain()
        val activeMediaId = media3MetadataCoordinator.activePlaybackIdentity().mediaId
        var refreshed = 0
        val iterator = drained.pending.entries.iterator()
        while (iterator.hasNext()) {
            // 每条消息只占用有限时间，剩余项放回队列由下一条消息继续，避免冷启动时
            // 一次刷新近百个 ID 长时间阻塞主线程（221022：95 个 ID 单次 92 ms）。
            if (refreshed > 0 && System.nanoTime() - startNs >= DEFERRED_REFRESH_BUDGET_NS) break
            val (mediaId, forceRebind) = iterator.next()
            refreshed += 1
            val alias = resolutionCoordinator.effectiveAlias(mediaId) ?: continue
            AppleMainThreadCost.section("override_ui_refresh") {
                refreshInAppConsumers(
                    mediaId = mediaId,
                    alias = alias,
                    allowModelRefresh = allowModelRefresh(mediaId, activeMediaId),
                    forceRebind = forceRebind,
                )
            }
        }
        val remaining = buildMap { iterator.forEachRemaining { put(it.key, it.value) } }
        if (deferredInAppRefreshes.restore(remaining)) {
            runtime.mainHandler.post(::flushDeferredInAppRefreshes)
        }
        if (AppleProviderEnvironment.diagnosticsEnabled) {
            ProviderLogger.diagnostic(
                "[OverrideCoalesce] calls=${drained.offers}, ids=${drained.pending.size}, " +
                    "refreshed=$refreshed, remaining=${remaining.size}, " +
                    "ms=${(System.nanoTime() - startNs) / 1_000_000.0}"
            )
        }
    }

    private val deferredInAppRefreshes = AppleDeferredInAppRefreshQueue()

    private companion object {
        const val DEFERRED_REFRESH_BUDGET_NS = 6_000_000L
    }

    private fun isSharedArtistAliasCurrent(
        mediaId: String,
        alias: Alias,
        originalMetadata: Boolean,
    ): Boolean = if (originalMetadata) {
        metadataStore.isOriginalArtistResolved(mediaId) && metadataStore.originalArtist(mediaId) == alias
    } else {
        metadataStore.configuredArtist(mediaId) == alias
    }

    private fun propagateSharedArtistOverride(
        sourceMediaId: String,
        artistId: String,
        alias: Alias,
        forceInAppRebind: Boolean,
        rememberLocalizedArtist: Boolean,
        originalMetadata: Boolean,
        originalMetadataConfirmed: Boolean,
    ) {
        val previousSharedAlias = if (originalMetadata) {
            metadataStore.rememberSharedOriginalArtist(artistId, alias)
        } else {
            metadataStore.rememberSharedConfiguredArtist(
                selection = configuredContentUiLanguage(),
                artistId = artistId,
                alias = alias,
            )
        }
        val targets = linkedSetOf(sourceMediaId, artistId).apply {
            addAll(metadataStore.associatedMediaIds("id:$artistId").orEmpty())
        }
        targets.forEach { targetMediaId ->
            if (
                targetMediaId != artistId &&
                !shouldShareAssociatedArtistAlias(
                    artistId = artistId,
                    targetArtistIds = metadataStore.associatedArtistIds(targetMediaId).orEmpty(),
                    targetArtistCredit = resolutionCoordinator.associatedArtistCredit(targetMediaId),
                )
            ) return@forEach
            // 同一艺人的歌每解析出一首都会传播一次；其余歌曲若已记着同一个艺人别名，再写一遍
            // 不会改变任何结果（221040：一批 48 首的身份结果引出 157 次覆盖，单条消息 130 ms）。
            if (
                targetMediaId != sourceMediaId &&
                isSharedArtistAliasCurrent(targetMediaId, alias, originalMetadata)
            ) return@forEach
            apply(
                mediaId = targetMediaId,
                alias = alias,
                forceInAppRebind = forceInAppRebind ||
                    previousSharedAlias != alias || targetMediaId != sourceMediaId,
                rememberLocalizedArtist = rememberLocalizedArtist,
                originalMetadata = originalMetadata,
                originalMetadataConfirmed = originalMetadataConfirmed,
                artistOnly = true,
                propagateArtistEntity = false,
            )
        }
        if (AppleProviderEnvironment.diagnosticsEnabled) {
            ProviderLogger.info(
                "Apple Music 元数据链路: seq=${traceSequence.incrementAndGet()}, " +
                    "event=artist_id_alias_propagated, artistId=$artistId, " +
                    "sourceId=$sourceMediaId, targets=$targets, artist=${alias.artist}, " +
                    "original=$originalMetadata"
            )
        }
    }
}

/** 按 ID 合并待执行的 App 内界面刷新；任一次要求强制重绑则合并结果强制重绑。 */
internal class AppleDeferredInAppRefreshQueue {
    data class Drained(val pending: Map<String, Boolean>, val offers: Int)

    private val pending = LinkedHashMap<String, Boolean>()
    private var offers = 0
    private var scheduled = false

    /** 返回 true 表示需要调度一次统一执行。 */
    @Synchronized
    fun offer(mediaId: String, forceRebind: Boolean): Boolean {
        pending[mediaId] = (pending[mediaId] ?: false) || forceRebind
        offers += 1
        if (scheduled) return false
        scheduled = true
        return true
    }

    /** 同步刷新已覆盖该 ID 时撤销其待执行项。 */
    @Synchronized
    fun cancel(mediaId: String) {
        pending.remove(mediaId)
    }

    /**
     * 放回本轮未处理完的项，排在新到项之前；返回 true 表示需要调度下一轮。
     * 放回项不计入合并前调用次数。
     */
    @Synchronized
    fun restore(entries: Map<String, Boolean>): Boolean {
        if (entries.isEmpty()) return false
        val merged = LinkedHashMap<String, Boolean>(entries.size + pending.size)
        entries.forEach { (mediaId, forceRebind) ->
            merged[mediaId] = forceRebind || pending[mediaId] == true
        }
        pending.forEach { (mediaId, forceRebind) ->
            if (mediaId !in merged) merged[mediaId] = forceRebind
        }
        pending.clear()
        pending.putAll(merged)
        if (scheduled) return false
        scheduled = true
        return true
    }

    @Synchronized
    fun drain(): Drained = Drained(LinkedHashMap(pending), offers).also {
        pending.clear()
        offers = 0
        scheduled = false
    }
}
