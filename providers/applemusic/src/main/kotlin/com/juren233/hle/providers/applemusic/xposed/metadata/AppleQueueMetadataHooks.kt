/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import android.os.Bundle
import com.juren233.hle.providers.applemusic.xposed.internal.WeakIdentityMap
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

internal interface AppleQueueMetadataHost {
    fun activePlaybackIdentity(): ActivePlaybackMediaIdentity

    fun logMetadataIdentity(
        event: String,
        identity: ActivePlaybackMediaIdentity,
        details: String,
    )

    fun media3MetadataId(metadata: Any, fallback: String?, trustedFallback: Boolean): String?

    fun media3MetadataDetails(metadata: Any): String

    fun registerMetadata(
        mediaId: String,
        metadata: Any,
        requestResolution: Boolean,
        preBind: Boolean,
        priority: RequestPriority,
    )

    fun markPlaybackItemHistory(playbackItem: Any)

    fun registerPlaybackItem(
        mediaId: String,
        playbackItem: Any,
        notifyChange: Boolean,
        analyzeMetadata: Boolean,
    )

    fun contentItemMediaId(contentItem: Any, refresh: Boolean): String?

    fun effectiveAlias(mediaId: String): Alias?

    fun applyAliasToPlaybackItem(
        playbackItem: Any,
        alias: Alias,
        notifyChange: Boolean,
    )

    fun shouldRequestOverride(mediaId: String): Boolean

    fun ensureOverride(
        mediaId: String,
        preBind: Boolean,
        priority: RequestPriority,
    )

    fun ensureOverrides(
        mediaIds: Collection<String>,
        preBind: Boolean,
        originalResolutionLimit: Int,
    )

    fun readPlaybackItemValue(
        playbackItem: Any,
        field: InAppPlaybackItemField,
        contract: InAppPlaybackItemContract,
    ): String?

    fun markMetadataVisible(mediaIds: Collection<String>)

    fun isCurrentMetadataSurfaceMediaId(mediaId: String): Boolean

    fun hasLivePlaybackItem(mediaId: String): Boolean
}

internal class AppleQueueMetadataHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val metadataStore: AppleMetadataOverrideStore,
    private val host: AppleQueueMetadataHost,
) {
    @Volatile
    private var currentNowPlayingRefresh: InAppNowPlayingRefresh? = null

    @Volatile
    private var currentDispatcherRefresh: InAppMetadataDispatcherRefresh? = null

    @Volatile
    private var queueRefresh: InAppQueueRefresh? = null

    @Volatile
    private var historyRefresh: InAppQueueRefresh? = null

    private val queueAdapterRefs = ConcurrentLinkedQueue<WeakReference<Any>>()

    /**
     * 每个队列适配器上绑定过的“位置 → ID”。刷新只核对这些行：221033 历史列表 254 行，滚动时
     * 每秒刷新 10–15 次，每次整表反射读取条目导致明显卡顿。位置可能因列表更新而过期，
     * 刷新前逐行重新读取条目核对 ID，不一致的行不通知并移除记录。
     */
    private val boundQueuePositions = WeakIdentityMap<Any, MutableMap<Int, String>>()

    /**
     * 整表登记时已完整分析过的条目对象 → ID。Adapter 提交在每次队列更新时都会把同一批
     * 历史条目（最多 128 条）重新分析一遍（221035：每次 47–105 ms，5 s 内 3 次，其中艺人 ID
     * 合并只占 5–11 ms，其余是逐条反射读取与登记）。同一对象再次出现在整表里时只取回 ID 并
     * 重新套用别名；可见行的绑定 Hook 仍走完整登记，不受影响。
     */
    private val analyzedQueueItems = WeakIdentityMap<Any, AnalyzedQueueItem>()

    /** [appliedAlias] 为最近一次写入该条目对象的别名；别名未变时复用路径不再重复读写字段。 */
    private class AnalyzedQueueItem(val mediaId: String, var appliedAlias: Alias?)

    /**
     * 整表登记中排在前 [MAX_QUEUE_PREBIND_ENTRIES] 条之后、尚未分析过的历史条目，延后到后续
     * 主线程消息分批完整登记。221038：首次打开队列时历史记录 Hook 登记 128 条耗时 151 ms，
     * 其中逐条分析（`registerPlaybackItem`）123 ms、取 ID 只要 2 ms。屏幕上的行由绑定 Hook
     * 当场完整登记，不受延后影响。
     */
    private class DeferredQueueEntry(
        val entry: WeakReference<Any>,
        val preBind: Boolean,
        val historyEntry: Boolean,
    )

    private val deferredQueueEntries = ArrayDeque<DeferredQueueEntry>()
    private val deferredQueueItems = WeakIdentityMap<Any, Boolean>()
    private var deferredQueueFlushScheduled = false

    private fun recordBoundPosition(adapter: Any, position: Int, mediaId: String?) {
        boundQueuePositions.putIfAbsent(adapter, HashMap())
        val positions = boundQueuePositions[adapter] ?: return
        synchronized(positions) {
            if (mediaId == null) positions.remove(position) else positions[position] = mediaId
        }
    }

    /**
     * 宿主自带一份经 R8 改名的 androidx，模块自带的 `RecyclerView.Adapter` 与它不是同一个类，
     * 不能用 `as?` 判断或直接调用。类与方法名取自版本档案；缺任一项时不登记适配器，
     * 队列行继续由通用列表刷新处理（AM-META-CLASS-IDENTITY-001）。
     */
    private class HostAdapterAccess(
        val adapterClass: Class<*>,
        val itemCount: java.lang.reflect.Method,
        val notifyItemChanged: java.lang.reflect.Method,
    )

    private val hostAdapterAccess: HostAdapterAccess? by lazy {
        val point = AppleMusicHookPoint.RECYCLER_NOTIFY_ITEM_CHANGED
        if (AppleMusicHookProfiles.exactTargets(runtime.hookResolver.version, point).isEmpty()) {
            return@lazy null
        }
        runCatching {
            val notify = runtime.hookResolver.resolveMethodOrNull(point) ?: return@runCatching null
            val itemCountName = notify.target.runtimeMemberNameOrNull(
                AppleMusicRuntimeMember.RECYCLER_ADAPTER_ITEM_COUNT_METHOD,
            ) ?: return@runCatching null
            val adapterClass = notify.method.declaringClass
            val itemCount = AppleReflection.findMethod(
                adapterClass,
                itemCountName,
                parameterTypes = emptyList(),
            ).takeIf { it.returnType == Int::class.javaPrimitiveType } ?: return@runCatching null
            HostAdapterAccess(adapterClass, itemCount, notify.method)
        }.onFailure {
            ProviderLogger.error("Apple Music 播放列表 Adapter 访问解析失败", it)
        }.getOrNull()
    }
    private val debugQueueBindTraceKeys = ConcurrentHashMap.newKeySet<String>()
    // 可空降级：目标缺失（如 6.5.3 队列适配器被混淆重排）时对应捕获子功能跳过安装，
    // 成员名读取返回 null，不影响其余 Hook 与提供器初始化。
    private var queueAdapterTarget: AppleMusicHookTarget? = null
    private var historyTarget: AppleMusicHookTarget? = null

    fun installHooks() {
        val history = runtime.hookResolver.resolveMethodOrNull(
            AppleMusicHookPoint.IN_APP_HISTORY_UPDATE,
        )
        historyTarget = history?.target
        queueAdapterTarget = runtime.hookResolver.resolveMethodOrNull(
            AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_SUBMIT,
        )?.target

        runtime.hookResolver.resolveMethodOrNull(
            AppleMusicHookPoint.IN_APP_GLOBAL_METADATA_DISPATCHER,
        )?.let(::installGlobalMetadataCapture)
            ?: ProviderLogger.info(
                "Apple Music App 全局元数据捕获 Hook 未安装: 目标解析失败，已跳过",
            )
        runtime.hookResolver.resolveMethodOrNull(
            AppleMusicHookPoint.IN_APP_NOW_PLAYING_METADATA_LISTENER,
        )?.let(::installNowPlayingMetadata)
            ?: ProviderLogger.info(
                "Apple Music App 播放页元数据 Hook 未安装: 目标解析失败，已跳过",
            )
        val queue = runtime.hookResolver.resolveMethodOrNull(AppleMusicHookPoint.IN_APP_QUEUE_UPDATE)
        if (queue != null && history != null) {
            installQueueAndHistory(queue, history)
        } else {
            ProviderLogger.info(
                "Apple Music App 播放列表/历史记录元数据 Hook 未安装: 目标解析失败，已跳过",
            )
        }
        val adapterSubmit = runtime.hookResolver.resolveMethodOrNull(
            AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_SUBMIT,
        )
        val adapterBind = runtime.hookResolver.resolveMethodOrNull(
            AppleMusicHookPoint.IN_APP_QUEUE_ADAPTER_BIND,
        )
        if (adapterSubmit != null && adapterBind != null) {
            installQueueAdapter(adapterSubmit, adapterBind)
        } else {
            ProviderLogger.info(
                "Apple Music App 播放列表 Adapter 捕获 Hook 未安装: 目标解析失败，已跳过",
            )
        }
    }

    /** 待合并刷新的 mediaId；与 [adapterRefreshScheduled] 一起由该集合加锁保护。 */
    private val pendingAdapterRefreshIds = LinkedHashSet<String>()
    private var adapterRefreshScheduled = false

    fun currentNowPlayingRefresh(): InAppNowPlayingRefresh? = currentNowPlayingRefresh

    fun currentDispatcherRefresh(): InAppMetadataDispatcherRefresh? = currentDispatcherRefresh

    fun isQueueAdapter(adapter: Any): Boolean = queueAdapterRefs.any { it.get() === adapter }

    fun hasLiveAdapter(): Boolean = queueAdapterRefs.any { it.get() != null }

    fun hasCapturedMediaId(mediaId: String): Boolean =
        queueRefresh?.mediaIds?.contains(mediaId) == true ||
            historyRefresh?.mediaIds?.contains(mediaId) == true

    /**
     * 别名成批到达时，每个别名都整表扫描一次队列会变成 O(n²)。同一批到达的 mediaId 合并到
     * 一次主线程扫描：每个条目只登记一次，命中任一待刷新 mediaId 的位置统一 notify。
     * 返回值只用于日志，表示已排入刷新的存活 Adapter 数。
     */
    fun refreshAdapters(mediaId: String): Int {
        if (!host.isCurrentMetadataSurfaceMediaId(mediaId)) return 0
        val isCaptured = hasCapturedMediaId(mediaId) || host.hasLivePlaybackItem(mediaId)
        if (!isCaptured) return 0
        val liveAdapters = queueAdapterRefs.count { it.get() != null }
        if (liveAdapters == 0) return 0
        val schedule = synchronized(pendingAdapterRefreshIds) {
            pendingAdapterRefreshIds.add(mediaId)
            (!adapterRefreshScheduled).also { adapterRefreshScheduled = true }
        }
        if (schedule) runtime.mainHandler.post(::flushAdapterRefreshes)
        return liveAdapters
    }

    private fun flushAdapterRefreshes() {
        val mediaIds = synchronized(pendingAdapterRefreshIds) {
            adapterRefreshScheduled = false
            pendingAdapterRefreshIds.toList().also { pendingAdapterRefreshIds.clear() }
        }.filterTo(HashSet(), host::isCurrentMetadataSurfaceMediaId)
        if (mediaIds.isEmpty()) return
        val access = hostAdapterAccess ?: return
        val startNs = if (AppleProviderEnvironment.diagnosticsEnabled) System.nanoTime() else 0L
        queueAdapterRefs.forEach { ref ->
            val adapter = ref.get()
            if (adapter == null) {
                queueAdapterRefs.remove(ref)
                return@forEach
            }
            val itemCount = runCatching { access.itemCount.invoke(adapter) as Int }.getOrDefault(0)
            val positions = boundQueuePositions[adapter] ?: return@forEach
            val candidates = synchronized(positions) {
                positions.filter { (position, mediaId) -> position < itemCount && mediaId in mediaIds }.keys
            }
            val matchingPositions = candidates.filter { position ->
                val entry = queueEntryAt(adapter, position).entry
                val current = registerQueueEntry(
                    entry = entry,
                    requestResolution = false,
                    preBind = true,
                )
                (current in mediaIds).also { matched ->
                    if (!matched) recordBoundPosition(adapter, position, current)
                }
            }.sorted()
            matchingPositions.forEach { position ->
                runCatching { access.notifyItemChanged.invoke(adapter, position) }
            }
            if (AppleProviderEnvironment.diagnosticsEnabled) {
                ProviderLogger.info(
                    "[QueueAdapterRefresh] adapter=${System.identityHashCode(adapter)}, " +
                        "itemCount=$itemCount, ids=${mediaIds.size}, candidates=${candidates.size}, " +
                        "positions=$matchingPositions, " +
                        "ms=${"%.2f".format((System.nanoTime() - startNs) / 1_000_000.0)}"
                )
            }
        }
    }

    private fun installGlobalMetadataCapture(resolved: ResolvedAppleMusicHookMethod) {
        runtime.hookRegistrar.installHook(resolved.method, before = { chain ->
            val dispatcher = chain.thisObject ?: return@installHook
            val metadata = chain.args.firstOrNull() ?: return@installHook
            val identityBefore = if (AppleProviderEnvironment.diagnosticsEnabled) host.activePlaybackIdentity() else null
            val mediaId = host.media3MetadataId(metadata, null, false)
            if (mediaId == null) {
                if (AppleProviderEnvironment.diagnosticsEnabled) host.logMetadataIdentity(
                    event = "in_app_global_unresolved",
                    identity = checkNotNull(identityBefore),
                    details = host.media3MetadataDetails(metadata),
                )
                return@installHook
            }
            currentDispatcherRefresh = InAppMetadataDispatcherRefresh(
                mediaId = mediaId,
                dispatcher = WeakReference(dispatcher),
                method = resolved.method,
                metadata = WeakReference(metadata),
            )
            if (AppleProviderEnvironment.diagnosticsEnabled) host.logMetadataIdentity(
                event = "in_app_global_capture",
                identity = checkNotNull(identityBefore),
                details = "resolvedId=$mediaId, ${host.media3MetadataDetails(metadata)}",
            )
            host.registerMetadata(
                mediaId = mediaId,
                metadata = metadata,
                requestResolution = true,
                preBind = false,
                priority = RequestPriority.VISIBLE,
            )
        })
        ProviderLogger.info(
            "Apple Music App 全局元数据捕获 Hook 已安装: " +
                "${resolved.target.className}#${resolved.target.methodName}, " +
                "fallback=${resolved.compatibilityFallback}"
        )
    }

    private fun installNowPlayingMetadata(resolved: ResolvedAppleMusicHookMethod) {
        runtime.hookRegistrar.installHook(resolved.method, before = { chain ->
            val listener = chain.thisObject ?: return@installHook
            val metadata = chain.args.firstOrNull() ?: return@installHook
            val identityBefore = if (AppleProviderEnvironment.diagnosticsEnabled) host.activePlaybackIdentity() else null
            val mediaId = host.media3MetadataId(metadata, null, false)
            if (mediaId == null) {
                if (AppleProviderEnvironment.diagnosticsEnabled) host.logMetadataIdentity(
                    event = "in_app_now_playing_unresolved",
                    identity = checkNotNull(identityBefore),
                    details = host.media3MetadataDetails(metadata),
                )
                return@installHook
            }
            currentNowPlayingRefresh = InAppNowPlayingRefresh(
                mediaId = mediaId,
                listener = WeakReference(listener),
                method = resolved.method,
                metadata = WeakReference(metadata),
            )
            if (AppleProviderEnvironment.diagnosticsEnabled) host.logMetadataIdentity(
                event = "in_app_now_playing_capture",
                identity = host.activePlaybackIdentity(),
                details = "resolvedId=$mediaId, ${host.media3MetadataDetails(metadata)}, " +
                    "aliasHit=${metadataStore.hasConfiguredMetadata(mediaId)}",
            )
            host.registerMetadata(
                mediaId = mediaId,
                metadata = metadata,
                requestResolution = true,
                preBind = false,
                priority = RequestPriority.VISIBLE,
            )
        })
        ProviderLogger.info(
            "Apple Music App 播放页元数据 Hook 已安装: " +
                "${resolved.target.className}#${resolved.target.methodName}, " +
                "fallback=${resolved.compatibilityFallback}"
        )
    }

    private fun installQueueAndHistory(
        queue: ResolvedAppleMusicHookMethod,
        history: ResolvedAppleMusicHookMethod,
    ) {
        runtime.hookRegistrar.installHook(queue.method, before = { chain ->
            chain.thisObject ?: return@installHook
            val items = chain.args.firstOrNull() as? Iterable<*> ?: return@installHook
            val mediaIds = measureQueueOpen("queue") { registerQueueEntries(items, preBind = true) }
            val changed = queueRefresh?.mediaIds != mediaIds
            if (changed) queueRefresh = InAppQueueRefresh(mediaIds)
            if (AppleProviderEnvironment.diagnosticsEnabled && changed) {
                ProviderLogger.info(
                    "Apple Music 继续播放捕获: ids=${mediaIds.size}, " +
                        "unresolved=${mediaIds.count { !metadataStore.hasConfiguredMetadata(it) }}"
                )
            }
        })
        runtime.hookRegistrar.installHook(history.method, before = { chain ->
            chain.thisObject ?: return@installHook
            val items = chain.args.firstOrNull() as? Iterable<*> ?: return@installHook
            val mediaIds = measureQueueOpen("history") {
                registerQueueEntries(
                    items = items,
                    preBind = true,
                    maxEntries = MAX_QUEUE_LOCALIZED_PREFETCH_ENTRIES,
                    originalResolutionLimit = MAX_QUEUE_PREBIND_ENTRIES,
                    historyEntries = true,
                )
            }
            val changed = historyRefresh?.mediaIds != mediaIds
            if (changed) historyRefresh = InAppQueueRefresh(mediaIds)
            if (AppleProviderEnvironment.diagnosticsEnabled && changed) {
                ProviderLogger.info(
                    "Apple Music 历史记录捕获: ids=${mediaIds.size}, " +
                        "unresolved=${mediaIds.count { !metadataStore.hasConfiguredMetadata(it) }}"
                )
            }
        })
        ProviderLogger.info(
            "Apple Music App 播放列表/历史记录元数据 Hook 已安装: " +
                "queue=${queue.target.className}#${queue.target.methodName}, " +
                "history=${history.target.className}#${history.target.methodName}"
        )
    }

    private fun installQueueAdapter(
        submit: ResolvedAppleMusicHookMethod,
        bind: ResolvedAppleMusicHookMethod,
    ) {
        runtime.hookRegistrar.installHook(submit.method, before = { chain ->
            val adapterObject = chain.thisObject ?: return@installHook
            registerQueueAdapter(adapterObject)
            val items = chain.args.firstOrNull() as? Iterable<*> ?: return@installHook
            measureQueueOpen("adapter_submit") {
                registerQueueEntries(
                    items = items,
                    preBind = true,
                    maxEntries = MAX_QUEUE_LOCALIZED_PREFETCH_ENTRIES,
                    originalResolutionLimit = MAX_QUEUE_PREBIND_ENTRIES,
                )
            }
            if (AppleProviderEnvironment.diagnosticsEnabled) {
                debugQueueBindTraceKeys.clear()
                AppleQueueOpenCost.reset()
                val startNs = System.nanoTime()
                // 只追踪当场登记的前几条；其余条目已排入延后登记，追踪时再完整分析会抬高 debug 测量。
                items.take(MAX_QUEUE_PREBIND_ENTRIES).forEachIndexed {
                        position, entry ->
                    captureQueueEntry(
                        position = position,
                        entry = entry,
                        entrySource = "adapter_submit",
                        requestResolution = false,
                        preBind = true,
                    )
                }
                ProviderLogger.info(
                    "[QueueOpenCost] hook=adapter_submit_debug_trace, " +
                        "ms=${"%.1f".format((System.nanoTime() - startNs) / 1_000_000.0)}, " +
                        AppleQueueOpenCost.summary()
                )
            }
        })
        runtime.hookRegistrar.installHook(bind.method, before = { chain ->
            val adapter = chain.thisObject ?: return@installHook
            registerQueueAdapter(adapter)
            val position = chain.args.getOrNull(1) as? Int ?: return@installHook
            val lookup = queueEntryAt(adapter, position)
            val mediaId = captureQueueEntry(
                position = position,
                entry = lookup.entry,
                entrySource = lookup.source,
                requestResolution = true,
                preBind = true,
                priority = RequestPriority.VISIBLE,
                // 打开队列时已登记过的条目，绑定时只补套用新别名与可见请求，不重做整套分析。
                reuseAnalyzedItem = true,
            )
            if (hostAdapterAccess?.adapterClass?.isInstance(adapter) == true) {
                recordBoundPosition(adapter, position, mediaId)
            }
        })
        ProviderLogger.info(
            "Apple Music App 播放列表 Adapter 捕获 Hook 已安装: " +
                "submit=${submit.target.className}#${submit.target.methodName}, " +
                "bind=${bind.target.className}#${bind.target.methodName}"
        )
    }

    private fun registerQueueAdapter(adapter: Any) {
        if (hostAdapterAccess?.adapterClass?.isInstance(adapter) != true) return
        var registered = false
        queueAdapterRefs.forEach { ref ->
            val target = ref.get()
            if (target == null) {
                queueAdapterRefs.remove(ref)
            } else if (target === adapter) {
                registered = true
            }
        }
        if (!registered) {
            queueAdapterRefs.add(WeakReference(adapter))
            if (AppleProviderEnvironment.diagnosticsEnabled) {
                ProviderLogger.info(
                    "[QueueAdapterRefresh] registered adapter=${adapter.javaClass.name}@" +
                        "${System.identityHashCode(adapter)}"
                )
            }
        }
    }

    /** 队列适配器目标缺失时成员名返回 null，调用方据此跳过条目读取。 */
    private fun queueAdapterMemberName(member: AppleMusicRuntimeMember): String? =
        queueAdapterTarget?.runtimeMemberNameOrNull(member)

    private fun queueEntryAt(adapter: Any, position: Int): InAppQueueEntryLookup {
        val displayedEntry = queueAdapterMemberName(
            AppleMusicRuntimeMember.QUEUE_ADAPTER_DISPLAYED_ENTRY_METHOD,
        )?.let { methodName ->
            runCatching { AppleReflection.call(adapter, methodName, position) }.getOrNull()
        }
        if (displayedEntry != null) {
            return InAppQueueEntryLookup(displayedEntry, "displayed_list")
        }
        val submittedEntry = queueAdapterMemberName(
            AppleMusicRuntimeMember.QUEUE_ADAPTER_SUBMITTED_ENTRIES_FIELD,
        )?.let { fieldName ->
            runCatching {
                (AppleReflection.field(adapter, fieldName) as? List<*>)?.getOrNull(position)
            }.getOrNull()
        }
        return InAppQueueEntryLookup(submittedEntry, "submitted_list_fallback")
    }

    private fun registerQueueEntries(
        items: Iterable<*>,
        preBind: Boolean = false,
        maxEntries: Int = Int.MAX_VALUE,
        originalResolutionLimit: Int = MAX_QUEUE_PREBIND_ENTRIES,
        historyEntries: Boolean = false,
    ): Set<String> = buildSet {
        val iterator = items.iterator()
        var processed = 0
        val deferredIds = mutableSetOf<String>()
        while (iterator.hasNext() && processed < maxEntries) {
            val entry = iterator.next()
            if (processed >= MAX_QUEUE_PREBIND_ENTRIES) {
                deferQueueEntry(entry, preBind, historyEntries)?.let { mediaId ->
                    add(mediaId)
                    deferredIds += mediaId
                    processed += 1
                    continue
                }
            }
            AppleQueueOpenCost.time(AppleQueueOpenCost.Kind.REGISTER_ENTRY) {
                registerQueueEntry(
                    entry = entry,
                    requestResolution = false,
                    preBind = preBind,
                    historyEntry = historyEntries || isHistoryQueueEntry(entry),
                    reuseAnalyzedItem = true,
                )
            }?.let(::add)
            processed += 1
        }
        AppleQueueOpenCost.time(AppleQueueOpenCost.Kind.ENSURE_OVERRIDES) {
            // 延后登记的条目在分批登记后再请求，届时已有查询所需的 ID 与艺人信息。
            host.ensureOverrides(
                mediaIds = this - deferredIds,
                preBind = preBind,
                originalResolutionLimit = originalResolutionLimit,
            )
        }
    }

    /**
     * 只处理尚未分析过的内容项条目：取出 ID 并排入延后登记，返回 ID；其余情况返回 null，
     * 由调用方照常当场登记（含 Media3 元数据条目与已分析过的条目）。
     */
    private fun deferQueueEntry(entry: Any?, preBind: Boolean, historyEntries: Boolean): String? {
        entry ?: return null
        val item = queueAdapterMemberName(AppleMusicRuntimeMember.QUEUE_ENTRY_ITEM_FIELD)
            ?.let { fieldName ->
                runCatching { AppleReflection.field(entry, fieldName) }.getOrNull()
            } ?: return null
        if (analyzedQueueItems[item] != null) return null
        val hasMetadata = queueAdapterMemberName(AppleMusicRuntimeMember.QUEUE_ITEM_METADATA_FIELD)
            ?.let { fieldName ->
                runCatching { AppleReflection.field(item, fieldName) }.getOrNull()
            } != null
        if (hasMetadata) return null
        val historyEntry = historyEntries || isHistoryQueueEntry(entry)
        val mediaId = AppleQueueOpenCost.time(AppleQueueOpenCost.Kind.MEDIA_ID) {
            host.contentItemMediaId(item, historyEntry)
        } ?: return null
        val schedule = synchronized(deferredQueueEntries) {
            if (!deferredQueueItems.putIfAbsent(item, true)) return@synchronized false
            deferredQueueEntries.addLast(
                DeferredQueueEntry(WeakReference(entry), preBind, historyEntry),
            )
            (!deferredQueueFlushScheduled).also { deferredQueueFlushScheduled = true }
        }
        AppleQueueOpenCost.record(AppleQueueOpenCost.Kind.DEFERRED_ENTRY, 0L)
        if (schedule) runtime.mainHandler.post(::flushDeferredQueueEntries)
        return mediaId
    }

    private fun flushDeferredQueueEntries() {
        val startNs = System.nanoTime()
        val registered = mutableSetOf<String>()
        var processed = 0
        var preBind = true
        while (true) {
            if (processed > 0 && System.nanoTime() - startNs >= DEFERRED_QUEUE_BUDGET_NS) break
            val deferred = synchronized(deferredQueueEntries) {
                deferredQueueEntries.removeFirstOrNull()
            } ?: break
            val entry = deferred.entry.get() ?: continue
            processed += 1
            preBind = preBind && deferred.preBind
            registerQueueEntry(
                entry = entry,
                requestResolution = false,
                preBind = deferred.preBind,
                historyEntry = deferred.historyEntry,
                reuseAnalyzedItem = true,
            )?.let(registered::add)
        }
        if (registered.isNotEmpty()) {
            host.ensureOverrides(mediaIds = registered, preBind = preBind, originalResolutionLimit = 0)
        }
        val remaining = synchronized(deferredQueueEntries) {
            deferredQueueFlushScheduled = deferredQueueEntries.isNotEmpty()
            if (!deferredQueueFlushScheduled) deferredQueueItems.clear()
            deferredQueueEntries.size
        }
        if (remaining > 0) runtime.mainHandler.post(::flushDeferredQueueEntries)
        if (AppleProviderEnvironment.diagnosticsEnabled) {
            ProviderLogger.info(
                "[QueueOpenCost] hook=deferred_flush, entries=$processed, ids=${registered.size}, " +
                    "remaining=$remaining, " +
                    "ms=${"%.1f".format((System.nanoTime() - startNs) / 1_000_000.0)}"
            )
        }
    }

    /** debug 包记录整表 Hook 的主线程耗时与各环节拆分（`[QueueOpenCost]`）。 */
    private inline fun measureQueueOpen(hook: String, block: () -> Set<String>): Set<String> {
        if (!AppleProviderEnvironment.diagnosticsEnabled) return block()
        AppleQueueOpenCost.reset()
        val startNs = System.nanoTime()
        val mediaIds = block()
        ProviderLogger.info(
            "[QueueOpenCost] hook=$hook, ids=${mediaIds.size}, " +
                "ms=${"%.1f".format((System.nanoTime() - startNs) / 1_000_000.0)}, " +
                AppleQueueOpenCost.summary()
        )
        return mediaIds
    }

    private fun isHistoryQueueEntry(entry: Any?): Boolean {
        val historyEntryClassName = historyTarget?.runtimeMemberNameOrNull(
            AppleMusicRuntimeMember.QUEUE_HISTORY_ENTRY_CLASS_NAME,
        ) ?: return false
        return entry != null && isInAppHistoryQueueEntryClassName(
            className = entry.javaClass.name,
            historyEntryClassName = historyEntryClassName,
        )
    }

    private fun registerQueueEntry(
        entry: Any?,
        requestResolution: Boolean = true,
        preBind: Boolean = false,
        historyEntry: Boolean = entry?.let(::isHistoryQueueEntry) == true,
        priority: RequestPriority =
            RequestPriority.ACTIVE_PAGE,
        reuseAnalyzedItem: Boolean = false,
    ): String? {
        entry ?: return null
        val item = queueAdapterMemberName(AppleMusicRuntimeMember.QUEUE_ENTRY_ITEM_FIELD)
            ?.let { fieldName ->
                runCatching { AppleReflection.field(entry, fieldName) }.getOrNull()
            } ?: return null
        if (historyEntry) host.markPlaybackItemHistory(item)
        val metadata = queueAdapterMemberName(AppleMusicRuntimeMember.QUEUE_ITEM_METADATA_FIELD)
            ?.let { fieldName ->
                runCatching { AppleReflection.field(item, fieldName) }.getOrNull()
            }
        if (metadata != null) {
            val itemId = queueAdapterMemberName(AppleMusicRuntimeMember.QUEUE_ITEM_ID_FIELD)
                ?.let { fieldName ->
                    runCatching { AppleReflection.field(item, fieldName) }.getOrNull() as? String
                }
            val mediaId = host.media3MetadataId(metadata, itemId, true) ?: return null
            host.registerMetadata(
                mediaId = mediaId,
                metadata = metadata,
                requestResolution = requestResolution,
                preBind = preBind,
                priority = priority,
            )
            return mediaId
        }

        if (reuseAnalyzedItem) {
            analyzedQueueItems[item]?.let { analyzed ->
                AppleQueueOpenCost.time(AppleQueueOpenCost.Kind.REUSED_ENTRY) {
                    val alias = host.effectiveAlias(analyzed.mediaId)
                    if (alias != null && alias != analyzed.appliedAlias) {
                        host.applyAliasToPlaybackItem(item, alias, notifyChange = !preBind)
                        analyzed.appliedAlias = alias
                    }
                }
                if (requestResolution && host.shouldRequestOverride(analyzed.mediaId)) {
                    host.ensureOverride(analyzed.mediaId, preBind, priority)
                }
                return analyzed.mediaId
            }
        }
        val mediaId = AppleQueueOpenCost.time(AppleQueueOpenCost.Kind.MEDIA_ID) {
            host.contentItemMediaId(item, historyEntry)
        } ?: return null
        val analyzed = AnalyzedQueueItem(mediaId, appliedAlias = null)
        analyzedQueueItems[item] = analyzed
        val existingAlias = host.effectiveAlias(mediaId)
        existingAlias?.let { alias ->
            AppleQueueOpenCost.time(AppleQueueOpenCost.Kind.REGISTER_PLAYBACK_ITEM) {
                host.registerPlaybackItem(
                    mediaId = mediaId,
                    playbackItem = item,
                    notifyChange = false,
                    analyzeMetadata = host.shouldRequestOverride(mediaId),
                )
            }
            host.applyAliasToPlaybackItem(item, alias, notifyChange = !preBind)
            analyzed.appliedAlias = alias
            if (requestResolution && host.shouldRequestOverride(mediaId)) {
                host.ensureOverride(mediaId, preBind, priority)
            }
            return mediaId
        }
        AppleQueueOpenCost.time(AppleQueueOpenCost.Kind.REGISTER_PLAYBACK_ITEM) {
            host.registerPlaybackItem(
                mediaId = mediaId,
                playbackItem = item,
                notifyChange = !preBind,
                analyzeMetadata = true,
            )
        }
        host.effectiveAlias(mediaId)?.let { alias ->
            host.applyAliasToPlaybackItem(item, alias, notifyChange = !preBind)
            analyzed.appliedAlias = alias
        }
        if (requestResolution && host.shouldRequestOverride(mediaId)) {
            host.ensureOverride(mediaId, preBind, priority)
        }
        return mediaId
    }

    private fun captureQueueEntry(
        position: Int,
        entry: Any?,
        entrySource: String,
        requestResolution: Boolean,
        preBind: Boolean,
        priority: RequestPriority =
            RequestPriority.BACKGROUND,
        reuseAnalyzedItem: Boolean = false,
    ): String? {
        val historyEntry = isHistoryQueueEntry(entry)
        // 绑定前的原始字段只用于 debug 日志；release 不做这些反射读取。
        val debugSnapshot = if (AppleProviderEnvironment.diagnosticsEnabled) {
            AppleMainThreadCost.section("queue_bind_debug") {
                queueBindDebugSnapshot(entry, historyEntry)
            }
        } else {
            null
        }
        val mediaId = AppleMainThreadCost.section("queue_bind_register") {
            registerQueueEntry(
                entry = entry,
                requestResolution = requestResolution,
                preBind = preBind,
                historyEntry = historyEntry,
                priority = priority,
                reuseAnalyzedItem = reuseAnalyzedItem,
            )
        }
        if (mediaId != null && priority == RequestPriority.VISIBLE) {
            AppleMainThreadCost.section("queue_bind_visible") {
                host.markMetadataVisible(listOf(mediaId))
            }
        }
        if (debugSnapshot != null) {
            AppleMainThreadCost.section("queue_bind_debug") {
                logQueueBind(
                    position = position,
                    entry = entry,
                    entrySource = entrySource,
                    snapshot = debugSnapshot,
                    mediaId = mediaId,
                    finalTitle = debugSnapshot.text(title = true),
                    finalSubtitle = debugSnapshot.text(title = false),
                    historyEntry = historyEntry,
                    resolutionRequested = requestResolution,
                )
            }
        }
        return mediaId
    }

    private fun playbackItemText(
        item: Any?,
        metadata: Any?,
        historyEntry: Boolean,
        contract: InAppPlaybackItemContract,
        title: Boolean,
    ): String? = when {
        item == null -> null
        historyEntry -> host.readPlaybackItemValue(
            item,
            if (title) InAppPlaybackItemField.TITLE else InAppPlaybackItemField.ARTIST,
            contract,
        )
        else -> metadata?.let { nonNullMetadata ->
            queueAdapterMemberName(
                if (title) {
                    AppleMusicRuntimeMember.MEDIA3_METADATA_TITLE_FIELD
                } else {
                    AppleMusicRuntimeMember.MEDIA3_METADATA_ARTIST_FIELD
                },
            )?.let { fieldName ->
                runCatching { AppleReflection.field(nonNullMetadata, fieldName) }
                    .getOrNull()?.toString()
            }
        }
    }

    /** debug 包专用：绑定前读取的原始字段，供 [logQueueBind] 对比替换前后的文本。 */
    private inner class QueueBindDebugSnapshot(
        val item: Any?,
        val metadata: Any?,
        val historyEntry: Boolean,
        val itemId: String?,
        val bundleId: String?,
    ) {
        private val contract = if (historyEntry) {
            InAppPlaybackItemContract.HISTORY
        } else {
            InAppPlaybackItemContract.STANDARD
        }
        val rawTitle = text(title = true)
        val rawSubtitle = text(title = false)

        fun text(title: Boolean): String? =
            playbackItemText(item, metadata, historyEntry, contract, title)
    }

    private fun queueBindDebugSnapshot(entry: Any?, historyEntry: Boolean): QueueBindDebugSnapshot {
        val item = entry?.let { nonNullEntry ->
            queueAdapterMemberName(AppleMusicRuntimeMember.QUEUE_ENTRY_ITEM_FIELD)?.let {
                    fieldName ->
                runCatching { AppleReflection.field(nonNullEntry, fieldName) }.getOrNull()
            }
        }
        val metadata = item?.takeUnless { historyEntry }?.let { nonNullItem ->
            queueAdapterMemberName(AppleMusicRuntimeMember.QUEUE_ITEM_METADATA_FIELD)?.let {
                    fieldName ->
                runCatching { AppleReflection.field(nonNullItem, fieldName) }.getOrNull()
            }
        }
        val itemId = when {
            item == null -> null
            historyEntry -> host.contentItemMediaId(item, true)
            else -> queueAdapterMemberName(AppleMusicRuntimeMember.QUEUE_ITEM_ID_FIELD)?.let {
                    fieldName ->
                runCatching { AppleReflection.field(item, fieldName) }.getOrNull() as? String
            }
        }
        val bundleId = metadata?.let { nonNullMetadata ->
            queueAdapterMemberName(AppleMusicRuntimeMember.MEDIA3_METADATA_BUNDLE_FIELD)?.let {
                    fieldName ->
                runCatching {
                    (AppleReflection.field(nonNullMetadata, fieldName) as? Bundle)?.getString(
                        MEDIA3_METADATA_ID_KEY,
                    )
                }.getOrNull()
            }
        }
        return QueueBindDebugSnapshot(item, metadata, historyEntry, itemId, bundleId)
    }

    private fun logQueueBind(
        position: Int,
        entry: Any?,
        entrySource: String,
        snapshot: QueueBindDebugSnapshot,
        mediaId: String?,
        finalTitle: String?,
        finalSubtitle: String?,
        historyEntry: Boolean,
        resolutionRequested: Boolean,
    ) {
        val item = snapshot.item
        val metadata = snapshot.metadata
        val itemId = snapshot.itemId
        val bundleId = snapshot.bundleId
        val rawTitle = snapshot.rawTitle
        val rawSubtitle = snapshot.rawSubtitle
        val event = if (historyEntry) "history_bind" else "queue_bind"
        val traceKey =
            "$event:$entrySource:$position:$mediaId:$rawTitle:$rawSubtitle:$finalTitle:$finalSubtitle"
        if (
            traceKey !in debugQueueBindTraceKeys &&
            debugQueueBindTraceKeys.size >= MAX_DEBUG_QUEUE_BIND_TRACE_KEYS
        ) return
        if (!debugQueueBindTraceKeys.add(traceKey)) return
        val alias = mediaId?.let(host::effectiveAlias)
        val shouldRequest = mediaId?.let(host::shouldRequestOverride)
        ProviderLogger.info(
            "Apple Music 队列绑定: event=$event, source=$entrySource, position=$position, " +
                "entryClass=${entry?.javaClass?.name}, itemClass=${item?.javaClass?.name}, " +
                "metadataClass=${metadata?.javaClass?.name}, itemId=$itemId, bundleId=$bundleId, " +
                "registeredId=$mediaId, rawTitle=$rawTitle, rawSubtitle=$rawSubtitle, " +
                "alias=${alias?.title}/${alias?.artist}/${alias?.album}, " +
                "resolutionRequested=$resolutionRequested, shouldRequest=$shouldRequest, " +
                "originalResolved=${mediaId != null && metadataStore.isOriginalResolved(mediaId)}, " +
                "originalPending=${mediaId != null && metadataStore.isOriginalPending(mediaId)}, " +
                "localizedResolved=${mediaId != null &&
                    metadataStore.hasConfiguredMetadata(mediaId)}, " +
                "writeTarget=${metadata?.javaClass?.name ?: item?.javaClass?.name}, " +
                "finalTitle=$finalTitle, finalSubtitle=$finalSubtitle"
        )
    }

    private companion object {
        const val MAX_QUEUE_PREBIND_ENTRIES = 24
        const val DEFERRED_QUEUE_BUDGET_NS = 6_000_000L
        const val MAX_QUEUE_LOCALIZED_PREFETCH_ENTRIES = 128
        const val MAX_DEBUG_QUEUE_BIND_TRACE_KEYS = 512
        const val MEDIA3_METADATA_ID_KEY = Constants.APPLE_MEDIA3_METADATA_ID_KEY
    }
}

/**
 * 队列元数据 Hook 的默认宿主实现：orchestrator 依赖以 supplier 显式注入，保持原匿名实现
 * "调用期解析"的语义；inAppMetadataRegistry 为根单例构造期值可直捕。
 */
internal class DefaultAppleQueueMetadataHost(
    private val activePlaybackIdentityFn: () -> ActivePlaybackMediaIdentity,
    private val logMetadataIdentityFn: (String, ActivePlaybackMediaIdentity, String) -> Unit,
    private val media3MetadataIdFn: (Any, String?, Boolean) -> String?,
    private val media3MetadataDetailsFn: (Any) -> String,
    private val registerMetadataFn: (String, Any, Boolean, Boolean, RequestPriority) -> Unit,
    private val registerPlaybackItemFn: (String, Any, Boolean, Boolean) -> Unit,
    private val contentItemMediaIdFn: (Any, Boolean) -> String?,
    private val effectiveAliasFn: (String) -> Alias?,
    private val applyAliasToPlaybackItemFn: (Any, Alias, Boolean) -> Unit,
    private val shouldRequestOverrideFn: (String) -> Boolean,
    private val ensureOverrideFn: (String, Boolean, RequestPriority) -> Unit,
    private val ensureOverridesFn: (Collection<String>, Boolean, Int) -> Unit,
    private val readPlaybackItemValueFn: (
        Any, InAppPlaybackItemField, InAppPlaybackItemContract,
    ) -> String?,
    private val markMetadataVisibleFn: (Collection<String>) -> Unit,
    private val isCurrentMetadataSurfaceMediaIdFn: (String) -> Boolean,
    private val registry: AppleInAppMetadataRegistry,
) : AppleQueueMetadataHost {
    override fun activePlaybackIdentity(): ActivePlaybackMediaIdentity =
        activePlaybackIdentityFn()

    override fun logMetadataIdentity(
        event: String,
        identity: ActivePlaybackMediaIdentity,
        details: String,
    ) {
        logMetadataIdentityFn(event, identity, details)
    }

    override fun media3MetadataId(metadata: Any, fallback: String?, trustedFallback: Boolean): String? =
        media3MetadataIdFn(metadata, fallback, trustedFallback)

    override fun media3MetadataDetails(metadata: Any): String =
        media3MetadataDetailsFn(metadata)

    override fun registerMetadata(
        mediaId: String,
        metadata: Any,
        requestResolution: Boolean,
        preBind: Boolean,
        priority: RequestPriority,
    ) {
        registerMetadataFn(mediaId, metadata, requestResolution, preBind, priority)
    }

    override fun registerPlaybackItem(
        mediaId: String,
        playbackItem: Any,
        notifyChange: Boolean,
        analyzeMetadata: Boolean,
    ) {
        registerPlaybackItemFn(mediaId, playbackItem, notifyChange, analyzeMetadata)
    }

    override fun contentItemMediaId(contentItem: Any, refresh: Boolean): String? =
        contentItemMediaIdFn(contentItem, refresh)

    override fun effectiveAlias(mediaId: String): Alias? =
        effectiveAliasFn(mediaId)

    override fun applyAliasToPlaybackItem(
        playbackItem: Any,
        alias: Alias,
        notifyChange: Boolean,
    ) {
        applyAliasToPlaybackItemFn(playbackItem, alias, notifyChange)
    }

    override fun shouldRequestOverride(mediaId: String): Boolean =
        shouldRequestOverrideFn(mediaId)

    override fun ensureOverride(
        mediaId: String,
        preBind: Boolean,
        priority: RequestPriority,
    ) {
        ensureOverrideFn(mediaId, preBind, priority)
    }

    override fun ensureOverrides(
        mediaIds: Collection<String>,
        preBind: Boolean,
        originalResolutionLimit: Int,
    ) {
        ensureOverridesFn(mediaIds, preBind, originalResolutionLimit)
    }

    override fun readPlaybackItemValue(
        playbackItem: Any,
        field: InAppPlaybackItemField,
        contract: InAppPlaybackItemContract,
    ): String? = readPlaybackItemValueFn(playbackItem, field, contract)

    override fun markMetadataVisible(mediaIds: Collection<String>) {
        markMetadataVisibleFn(mediaIds)
    }

    override fun isCurrentMetadataSurfaceMediaId(mediaId: String): Boolean =
        isCurrentMetadataSurfaceMediaIdFn(mediaId)

    override fun markPlaybackItemHistory(playbackItem: Any) {
        registry.markPlaybackItemContract(
            playbackItem,
            InAppPlaybackItemContract.HISTORY,
        )
    }

    override fun hasLivePlaybackItem(mediaId: String): Boolean =
        registry.hasLivePlaybackItem(mediaId)
}
