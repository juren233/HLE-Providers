/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.View
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

internal interface AppleMetadataSurfaceHost {
    fun catalogResolver(): AppleInternalCatalogResolver?

    fun associatedArtistIds(mediaId: String): Collection<String>

    /** 关联艺人 ID 的变更计数；返回 null 表示无法判断，每次都重新展开请求范围。 */
    fun associatedArtistIdsRevision(): Long? = null

    fun hasVisibleExactConsumer(mediaId: String): Boolean

    fun hasGenericRecyclerConsumer(mediaId: String): Boolean

    fun detachController(owner: Any): Int

    fun logMetadataIdentity(event: String, details: String)

    fun describeView(view: View): String
}

internal class AppleMetadataSurfaceRuntime(
    private val runtime: AppleMusicProviderRuntime,
    private val host: AppleMetadataSurfaceHost,
) {
    private val coordinator = AppleMetadataSurfaceCoordinator(
        clock = SystemClock::elapsedRealtime,
    )
    private val visibleResolutionLeases = AppleVisibleMetadataResolutionLeases(
        clock = SystemClock::elapsedRealtime,
    )
    private val syncLock = Any()
    private val dispatchRevision = AtomicLong(0L)
    private var lastSyncedSignature: MetadataSurfaceSignature? = null
    private var lastSyncedScopeRevision = -1L
    private var lastSyncedArtistRevision: Long? = null

    @Volatile
    private var activePageOwner = WeakReference<Any>(null)

    /** 只在主线程读写。 */
    private var visibleScopeSyncScheduled = false

    fun installLifecycleHooks() {
        val activityInstalled = runCatching {
            val activityResume = Activity::class.java.getDeclaredMethod("onResume")
                .apply { isAccessible = true }
            val activityPause = Activity::class.java.getDeclaredMethod("onPause")
                .apply { isAccessible = true }
            runtime.hookRegistrar.installHook(activityResume, after = { chain, _ ->
                chain.thisObject?.let(::onSurfaceResumed)
            })
            runtime.hookRegistrar.installHook(activityPause, before = { chain ->
                chain.thisObject?.let(::onSurfacePaused)
            })
        }.onFailure {
            ProviderLogger.error("Apple Music Activity 元数据生命周期 Hook 安装失败", it)
        }.isSuccess
        val fragmentInstalled = runCatching {
            val fragmentClass = runtime.classLoader.loadClass("androidx.fragment.app.Fragment")
            val fragmentResume = AppleReflection.findMethod(
                fragmentClass,
                "onResume",
                parameterCount = 0,
            )
            val fragmentPause = AppleReflection.findMethod(
                fragmentClass,
                "onPause",
                parameterCount = 0,
            )
            runtime.hookRegistrar.installHook(fragmentResume, after = { chain, _ ->
                chain.thisObject?.let(::onSurfaceResumed)
            })
            runtime.hookRegistrar.installHook(fragmentPause, before = { chain ->
                chain.thisObject?.let(::onSurfacePaused)
            })
        }.onFailure {
            ProviderLogger.info(
                "Apple Music 未提供标准 Fragment 生命周期类，改用页面控制器边界"
            )
        }.isSuccess
        ProviderLogger.info(
            "Apple Music 元数据页面生命周期 Hook 已安装: " +
                "activity=$activityInstalled, fragment=$fragmentInstalled"
        )
    }

    fun activePageOwner(): Any? = activePageOwner.get()

    fun onPageAttached(owner: Any, recycler: View) {
        activePageOwner = WeakReference(owner)
        onSurfaceResumed(owner)
        if (AppleProviderEnvironment.diagnosticsEnabled) {
            host.logMetadataIdentity(
                event = "metadata_page_controller_attached",
                details = "controller=${owner.javaClass.name}@${System.identityHashCode(owner)}, " +
                    "recycler=${host.describeView(recycler)}",
            )
        }
    }

    fun onPageDetached(owner: Any) {
        val removedControllerRefs = host.detachController(owner)
        if (AppleProviderEnvironment.diagnosticsEnabled) {
            host.logMetadataIdentity(
                event = "metadata_page_controller_detached",
                details = "controller=${owner.javaClass.name}@${System.identityHashCode(owner)}, " +
                    "removedControllerRefs=$removedControllerRefs",
            )
        }
        if (activePageOwner.get() === owner) activePageOwner = WeakReference(null)
        onSurfacePaused(owner)
    }

    fun markVisible(
        mediaIds: Collection<String>,
    ): AppleMetadataSurfaceCoordinator.SurfaceSnapshot {
        val snapshot = AppleMainThreadCost.section("scope_mark") {
            visibleResolutionLeases.mark(mediaIds)
            coordinator.markCurrentPage(mediaIds)
            coordinator.markVisible(mediaIds)
        }
        scheduleVisibleScopeSync(snapshot)
        return snapshot
    }

    /**
     * 列表一帧内会逐行绑定、逐行标记可见；每行都把请求范围整体展开并重排待发请求。
     * 优先级判断直接读协调器，已即时生效；目录请求范围只用于重排待发请求，主线程上合并到
     * 下一条消息同步一次。其他线程照旧当场同步。
     */
    private fun scheduleVisibleScopeSync(snapshot: AppleMetadataSurfaceCoordinator.SurfaceSnapshot) {
        if (Looper.myLooper() !== Looper.getMainLooper()) {
            syncRequestScope(snapshot)
            return
        }
        if (visibleScopeSyncScheduled) return
        visibleScopeSyncScheduled = true
        runtime.mainHandler.post {
            visibleScopeSyncScheduled = false
            AppleMainThreadCost.section("scope_sync") { syncRequestScope() }
        }
    }

    fun setPlaybackMediaId(
        mediaId: String?,
    ): AppleMetadataSurfaceCoordinator.SurfaceSnapshot {
        val snapshot = coordinator.setPlaybackMediaId(mediaId)
        syncRequestScope(snapshot)
        return snapshot
    }

    fun requestContext(mediaId: String): AppleMetadataSurfaceCoordinator.RequestContext =
        coordinator.requestContext(mediaId)

    fun isCurrentMediaId(mediaId: String): Boolean =
        requestContext(mediaId).priority !=
            RequestPriority.BACKGROUND

    fun hasVisibleConsumer(mediaId: String): Boolean =
        host.hasVisibleExactConsumer(mediaId) || host.hasGenericRecyclerConsumer(mediaId)

    fun isRefreshable(mediaId: String): Boolean = shouldRefreshInAppSurface(
        surfaceRelevant = isCurrentMediaId(mediaId),
        hasVisibleExactConsumer = hasVisibleConsumer(mediaId),
        hasActiveVisibleLease = visibleResolutionLeases.contains(mediaId),
    )

    fun hasVisibleResolutionLease(mediaId: String): Boolean =
        visibleResolutionLeases.contains(mediaId)

    fun refreshRequestScope() {
        syncRequestScope()
    }

    private fun onSurfaceResumed(owner: Any) {
        syncRequestScope(coordinator.onSurfaceResumed(owner))
    }

    private fun onSurfacePaused(owner: Any) {
        syncRequestScope(coordinator.onSurfacePaused(owner))
    }

    private fun syncRequestScope(
        snapshot: AppleMetadataSurfaceCoordinator.SurfaceSnapshot = coordinator.snapshot(),
    ) {
        val resolver = host.catalogResolver() ?: return
        // 展开请求范围要把每个可见 ID 的关联艺人逐个并入；行绑定时反复标记已可见的 ID，
        // 范围与关联艺人都没变时结果必然相同，直接跳过。
        val artistRevision = host.associatedArtistIdsRevision()
        if (artistRevision != null) {
            synchronized(syncLock) {
                if (
                    lastSyncedSignature != null &&
                    snapshot.scopeRevision == lastSyncedScopeRevision &&
                    artistRevision == lastSyncedArtistRevision
                ) return
            }
        }
        val visible = expandScopeIds(snapshot.visibleMediaIds)
        val activePage = expandScopeIds(snapshot.activePageMediaIds) - visible
        val signature = MetadataSurfaceSignature(
            coordinatorRevision = snapshot.scopeRevision,
            visibleMediaIds = visible,
            activePageMediaIds = activePage,
        )
        val revision = synchronized(syncLock) {
            lastSyncedScopeRevision = snapshot.scopeRevision
            lastSyncedArtistRevision = artistRevision
            if (signature == lastSyncedSignature) return
            lastSyncedSignature = signature
            dispatchRevision.incrementAndGet()
        }
        resolver.updateRequestScope(
            revision = revision,
            visibleMediaIds = visible,
            activePageMediaIds = activePage,
        )
    }

    private fun expandScopeIds(mediaIds: Collection<String>): Set<String> = buildSet {
        mediaIds.forEach { mediaId ->
            add(mediaId)
            addAll(host.associatedArtistIds(mediaId))
        }
    }
}

/** 依赖显式注入的默认宿主；store 为根单例的构造期值，可安全直捕，其余调用期解析。 */
internal class DefaultAppleMetadataSurfaceHost(
    private val catalogResolverFn: () -> AppleInternalCatalogResolver?,
    private val overrideStore: AppleMetadataOverrideStore,
    private val hasVisibleExactConsumerFn: (String) -> Boolean,
    private val hasGenericRecyclerConsumerFn: (String) -> Boolean,
    private val detachControllerFn: (Any) -> Int,
    private val logMetadataIdentityFn: (String, String) -> Unit,
    private val describeViewFn: (View) -> String,
) : AppleMetadataSurfaceHost {
    override fun catalogResolver(): AppleInternalCatalogResolver? = catalogResolverFn()

    override fun associatedArtistIds(mediaId: String): Collection<String> =
        overrideStore.associatedArtistIds(mediaId).orEmpty()

    override fun associatedArtistIdsRevision(): Long =
        overrideStore.associatedArtistIdsRevision()

    override fun hasVisibleExactConsumer(mediaId: String): Boolean =
        hasVisibleExactConsumerFn(mediaId)

    override fun hasGenericRecyclerConsumer(mediaId: String): Boolean =
        hasGenericRecyclerConsumerFn(mediaId)

    override fun detachController(owner: Any): Int = detachControllerFn(owner)

    override fun logMetadataIdentity(event: String, details: String) {
        logMetadataIdentityFn(event, details)
    }

    override fun describeView(view: View): String = describeViewFn(view)
}
