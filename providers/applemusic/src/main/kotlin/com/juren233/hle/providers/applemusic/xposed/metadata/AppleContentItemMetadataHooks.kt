/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import com.juren233.hle.providers.applemusic.xposed.internal.ThreadLocalReentryGuard
import com.juren233.hle.providers.applemusic.xposed.internal.WeakIdentityMap
import java.lang.reflect.Executable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal interface AppleContentItemMetadataHost {
    fun containerNavigationBinding(contentItem: Any): InAppContainerNavigationRef?

    fun effectiveAlias(mediaId: String): Alias?

    fun registerContainerItem(mediaId: String, contentItem: Any, kind: InAppContainerKind)

    fun localizedEntityType(
        contentItem: Any,
    ): LocalizedEntityType?

    fun recordComposeMediaId(mediaId: String)

    fun recordCurrentRecyclerMediaId(mediaId: String)

    fun requestPriority(mediaId: String): RequestPriority

    fun shouldResolveFromGetter(
        priority: RequestPriority,
    ): Boolean

    fun registerPlaybackItem(
        mediaId: String,
        playbackItem: Any,
        notifyChange: Boolean,
        analyzeMetadata: Boolean,
    )

    fun shouldRequestOverride(mediaId: String): Boolean

    fun applyAliasToPlaybackItem(
        playbackItem: Any,
        alias: Alias,
        notifyChange: Boolean,
    )

    fun metadataOverride(
        entityType: LocalizedEntityType,
        getter: AppleContentItemGetter,
        alias: Alias,
        original: String?,
    ): String?
}

/** Owns ContentItem identity caching and the lazily installed metadata getter Hooks. */
internal class AppleContentItemMetadataHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val host: AppleContentItemMetadataHost,
) {
    private val hookedMethods = ConcurrentHashMap.newKeySet<Executable>()
    /** 构造 Hook 每次实例化都会调用 [ensureHooks]；同一具体类只需处理一次。 */
    private val processedClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val mediaIds = WeakIdentityMap<Any, String>()
    private val getterGuard = ThreadLocalReentryGuard()

    private class GetterItemState(val mediaId: String) {
        @Volatile var analyzed = false
        @Volatile var appliedAlias: Alias? = null
    }

    /** getter 每次调用都会走登记与套用别名；按对象记住已做过的部分，避免每帧重复。 */
    private val getterItemStates = WeakIdentityMap<Any, GetterItemState>()

    /**
     * 档案外的子类在首个实例构造时补装 getter Hook，放到后台线程执行：安装 Hook 需进入
     * GC 临界区，在主线程上遇到宿主后台 GC 会整段阻塞（PERF-LAZY-HOOK-GC-001）。代价是该子类
     * 在 Hook 装好之前读取的 getter 返回原值；常见子类已在版本档案里启动预装。
     */
    private val lateHookExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HLE-ContentItemHook").apply { isDaemon = true }
    }
    private val baseContentItemRuntimeTarget by lazy {
        runtime.hookResolver.resolveClasses(AppleMusicHookPoint.CONTENT_ITEM_METADATA_CLASSES)
            .first { resolved ->
                resolved.target.runtimeMemberNameOrNull(AppleMusicRuntimeMember.CONTENT_ITEM_ROLE) ==
                    "base"
            }
    }

    fun installHooks() {
        runCatching {
            val resolvedClasses = runtime.hookResolver.resolveClasses(
                AppleMusicHookPoint.CONTENT_ITEM_METADATA_CLASSES,
            )
            val baseContentItemClass = baseContentItemRuntimeTarget.clazz
            baseContentItemClass.declaredConstructors.forEach { constructor ->
                constructor.isAccessible = true
                runtime.hookRegistrar.installHook(constructor, after = { chain, _ ->
                    val contentItemClass = chain.thisObject?.javaClass ?: return@installHook
                    if (contentItemClass in processedClasses) return@installHook
                    lateHookExecutor.execute {
                        runCatching { ensureHooks(contentItemClass) }.onFailure {
                            ProviderLogger.error("Apple Music 内容项 getter Hook 补装失败", it)
                        }
                    }
                })
            }
            resolvedClasses.forEach { resolved -> ensureHooks(resolved.clazz) }
            ProviderLogger.info(
                "Apple Music 内容项 title/artist/album 模型 Hook 已安装"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 内容项元数据 Hook 安装失败", it)
        }
    }

    fun ensureHooks(contentItemClass: Class<*>) {
        if (!processedClasses.add(contentItemClass)) return
        listOf(
            AppleContentItemGetter.TITLE to AppleMusicRuntimeMember.CONTENT_ITEM_TITLE_GETTER,
            AppleContentItemGetter.NOW_PLAYING_TITLE to
                AppleMusicRuntimeMember.CONTENT_ITEM_NOW_PLAYING_TITLE_GETTER,
            AppleContentItemGetter.ARTIST to AppleMusicRuntimeMember.CONTENT_ITEM_ARTIST_GETTER,
            AppleContentItemGetter.NOW_PLAYING_SUBTITLE to
                AppleMusicRuntimeMember.CONTENT_ITEM_NOW_PLAYING_SUBTITLE_GETTER,
            AppleContentItemGetter.SUBTITLE to AppleMusicRuntimeMember.CONTENT_ITEM_SUBTITLE_GETTER,
            AppleContentItemGetter.COLLECTION to
                AppleMusicRuntimeMember.CONTENT_ITEM_COLLECTION_GETTER,
        ).forEach { (getter, runtimeMember) ->
            val methodName = baseContentItemRuntimeTarget.target.runtimeMemberName(runtimeMember)
            val method = runCatching {
                AppleReflection.findMethod(contentItemClass, methodName, parameterCount = 0)
            }.getOrNull() ?: return@forEach
            if (method.returnType != String::class.java || !hookedMethods.add(method)) {
                return@forEach
            }
            runtime.hookRegistrar.installResultOverrideHook(method) { chain, original ->
                if (getterGuard.isActive) {
                    return@installResultOverrideHook original
                }
                val contentItem = chain.thisObject ?: return@installResultOverrideHook original
                val containerBinding = AppleMainThreadCost.section("getter_container") {
                    host.containerNavigationBinding(contentItem)
                }
                if (containerBinding != null) {
                    val mediaId = containerBinding.mediaId
                    val containerKind = containerBinding.kind
                    val alias = host.effectiveAlias(mediaId)
                    host.registerContainerItem(mediaId, contentItem, containerKind)
                    if (getter != AppleContentItemGetter.TITLE || alias == null) {
                        return@installResultOverrideHook original
                    }
                    return@installResultOverrideHook when (containerKind) {
                        InAppContainerKind.ARTIST ->
                            alias.artist.takeIf(String::isNotBlank) ?: original
                        InAppContainerKind.ALBUM ->
                            alias.album.takeIf(String::isNotBlank) ?: original
                    }
                }
                val entityType = AppleMainThreadCost.section("getter_entity_type") {
                    host.localizedEntityType(contentItem)
                } ?: return@installResultOverrideHook original
                val mediaId = AppleMainThreadCost.section("getter_media_id") {
                    runCatching { mediaId(contentItem) }.getOrNull()
                } ?: return@installResultOverrideHook original
                AppleMainThreadCost.section("getter_record") {
                    host.recordComposeMediaId(mediaId)
                    host.recordCurrentRecyclerMediaId(mediaId)
                }
                val surfaceRelevant = AppleMainThreadCost.section("getter_priority") {
                    host.shouldResolveFromGetter(host.requestPriority(mediaId))
                }
                val resolvedAlias = AppleMainThreadCost.section("getter_alias") {
                    host.effectiveAlias(mediaId)
                }
                val itemState = getterItemState(contentItem, mediaId)
                // 完整分析只读取对象自身字段并合并键，不发请求；同一对象分析过一次后结果不变，
                // 而套用别名后再分析还会把写入的本地化名当成原名读取。
                val analyzeMetadata = !itemState.analyzed && surfaceRelevant && (
                    resolvedAlias == null || host.shouldRequestOverride(mediaId)
                    )
                if (analyzeMetadata) itemState.analyzed = true
                AppleMainThreadCost.section(
                    if (analyzeMetadata) "getter_register_analyze" else "getter_register",
                ) {
                    host.registerPlaybackItem(
                        mediaId = mediaId,
                        playbackItem = contentItem,
                        notifyChange = false,
                        analyzeMetadata = analyzeMetadata,
                    )
                }
                // 同一对象已写入相同别名时不再逐字段反射比对；getter 返回值仍按别名覆盖。
                if (resolvedAlias != null && resolvedAlias != itemState.appliedAlias) {
                    AppleMainThreadCost.section("getter_apply_alias") {
                        host.applyAliasToPlaybackItem(
                            playbackItem = contentItem,
                            alias = resolvedAlias,
                            notifyChange = false,
                        )
                    }
                    itemState.appliedAlias = resolvedAlias
                }
                val alias = resolvedAlias ?: host.effectiveAlias(mediaId)
                if (alias == null) {
                    original
                } else {
                    AppleMainThreadCost.section("getter_override") {
                        host.metadataOverride(
                            entityType = entityType,
                            getter = getter,
                            alias = alias,
                            original = original as? String,
                        )
                    } ?: original
                }
            }
            ProviderLogger.info(
                "Apple 内容项元数据 getter Hook 已安装: " +
                    "class=${method.declaringClass.name}, method=$methodName"
            )
        }
    }

    /** 对象换了 mediaId（宿主复用对象）时重新开始记录。 */
    private fun getterItemState(contentItem: Any, mediaId: String): GetterItemState =
        synchronized(getterItemStates) {
            getterItemStates[contentItem]?.takeIf { it.mediaId == mediaId }
                ?: GetterItemState(mediaId).also { getterItemStates[contentItem] = it }
        }

    fun mediaId(contentItem: Any, refresh: Boolean = false): String? {
        if (!refresh) {
            synchronized(mediaIds) {
                mediaIds[contentItem]?.let { return it }
            }
        }
        val subscriptionStoreId = runCatching {
            AppleReflection.call(
                contentItem,
                baseContentItemRuntimeTarget.target.runtimeMemberName(
                    AppleMusicRuntimeMember.CONTENT_ITEM_SUBSCRIPTION_STORE_ID_GETTER
                ),
            ) as? String
        }.getOrNull()
        val id = runCatching {
            AppleReflection.call(
                contentItem,
                baseContentItemRuntimeTarget.target.runtimeMemberName(
                    AppleMusicRuntimeMember.CONTENT_ITEM_ID_GETTER
                ),
            ) as? String
        }.getOrNull()
        val persistentId = runCatching {
            AppleReflection.call(
                contentItem,
                baseContentItemRuntimeTarget.target.runtimeMemberName(
                    AppleMusicRuntimeMember.CONTENT_ITEM_PERSISTENT_ID_GETTER
                ),
            ) as? Long
        }.getOrNull()?.takeIf { it > 0L }?.toString()
        val resolvedMediaId = sequenceOf(subscriptionStoreId, id, persistentId)
            .filterNotNull()
            .firstOrNull { candidate -> candidate.isNotBlank() && candidate.all(Char::isDigit) }
        synchronized(mediaIds) {
            if (resolvedMediaId == null && refresh) {
                mediaIds.remove(contentItem)
            } else if (resolvedMediaId != null) {
                mediaIds[contentItem] = resolvedMediaId
            }
        }
        return resolvedMediaId
    }

    fun <T> withOriginalGetters(block: () -> T): T = getterGuard.run(block)
}

/**
 * ContentItem 元数据 Hook 的默认宿主实现：orchestrator 依赖以 supplier 显式注入，保持原匿名
 * 实现"调用期解析"的语义。
 */
internal class DefaultAppleContentItemMetadataHost(
    private val containerNavigationBindingFn: (Any) -> InAppContainerNavigationRef?,
    private val effectiveAliasFn: (String) -> Alias?,
    private val registerContainerItemFn: (String, Any, InAppContainerKind) -> Unit,
    private val localizedEntityTypeFn: (Any) -> LocalizedEntityType?,
    private val recordComposeMediaIdFn: (String) -> Unit,
    private val recordCurrentRecyclerMediaIdFn: (String) -> Unit,
    private val requestPriorityFn: (String) -> RequestPriority,
    private val shouldResolveFromGetterFn: (RequestPriority) -> Boolean,
    private val registerPlaybackItemFn: (String, Any, Boolean, Boolean) -> Unit,
    private val shouldRequestOverrideFn: (String) -> Boolean,
    private val applyAliasToPlaybackItemFn: (Any, Alias, Boolean) -> Unit,
    private val metadataOverrideFn: (
        LocalizedEntityType, AppleContentItemGetter, Alias, String?,
    ) -> String?,
) : AppleContentItemMetadataHost {
    override fun containerNavigationBinding(contentItem: Any): InAppContainerNavigationRef? =
        containerNavigationBindingFn(contentItem)

    override fun effectiveAlias(mediaId: String): Alias? =
        effectiveAliasFn(mediaId)

    override fun registerContainerItem(mediaId: String, contentItem: Any, kind: InAppContainerKind) {
        registerContainerItemFn(mediaId, contentItem, kind)
    }

    override fun localizedEntityType(contentItem: Any): LocalizedEntityType? =
        localizedEntityTypeFn(contentItem)

    override fun recordComposeMediaId(mediaId: String) {
        recordComposeMediaIdFn(mediaId)
    }

    override fun recordCurrentRecyclerMediaId(mediaId: String) {
        recordCurrentRecyclerMediaIdFn(mediaId)
    }

    override fun requestPriority(mediaId: String): RequestPriority =
        requestPriorityFn(mediaId)

    override fun shouldResolveFromGetter(priority: RequestPriority): Boolean =
        shouldResolveFromGetterFn(priority)

    override fun registerPlaybackItem(
        mediaId: String,
        playbackItem: Any,
        notifyChange: Boolean,
        analyzeMetadata: Boolean,
    ) {
        registerPlaybackItemFn(mediaId, playbackItem, notifyChange, analyzeMetadata)
    }

    override fun shouldRequestOverride(mediaId: String): Boolean =
        shouldRequestOverrideFn(mediaId)

    override fun applyAliasToPlaybackItem(
        playbackItem: Any,
        alias: Alias,
        notifyChange: Boolean,
    ) {
        applyAliasToPlaybackItemFn(playbackItem, alias, notifyChange)
    }

    override fun metadataOverride(
        entityType: LocalizedEntityType,
        getter: AppleContentItemGetter,
        alias: Alias,
        original: String?,
    ): String? = metadataOverrideFn(entityType, getter, alias, original)
}
