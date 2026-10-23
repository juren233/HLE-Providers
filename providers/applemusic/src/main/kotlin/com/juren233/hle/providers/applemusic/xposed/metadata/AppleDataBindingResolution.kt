/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.juren233.hle.providers.applemusic.xposed.internal.ThreadLocalStack
import com.juren233.hle.providers.applemusic.xposed.internal.WeakIdentityMap
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

internal fun AppleDataBindingMetadataHooks.clearPendingRefresh(binding: Any, expected: PendingDataBindingRefresh) {
    synchronized(pendingRefreshes) {
        if (pendingRefreshes[binding] == expected) pendingRefreshes.remove(binding)
    }
}

internal fun AppleDataBindingMetadataHooks.captureBoundRoot(
    root: View,
    visible: Boolean = false,
    alreadyMarkedVisible: Boolean = false,
): String? {
    val binding = bindingsByRoot[root]?.get() ?: return null
    val mediaId = resolvedMediaId(binding) ?: return null
    if (visible && isRootVisible(root)) {
        resolveVisible(binding, mediaId, alreadyMarkedVisible = alreadyMarkedVisible)
    }
    return mediaId
}

internal fun AppleDataBindingMetadataHooks.postVisibleResolution(
    binding: Any,
    mediaId: String,
    originalResolutionMode: InAppOriginalResolutionMode = host.originalResolutionMode(binding),
) {
    val root = bindingRootViews[binding]?.get() ?: return
    val pending = PendingVisibleDataBindingResolution(
        mediaId = mediaId,
        bindGeneration = generation(binding),
        originalResolutionMode = originalResolutionMode,
    )
    val shouldPost = synchronized(visibleResolutionPosts) {
        if (visibleResolutionPosts[binding] == pending) false else {
            visibleResolutionPosts[binding] = pending
            true
        }
    }
    if (!shouldPost) return
    root.postOnAnimation {
        val current = synchronized(visibleResolutionPosts) { visibleResolutionPosts[binding] }
        if (current != pending) return@postOnAnimation
        synchronized(visibleResolutionPosts) { visibleResolutionPosts.remove(binding) }
        if (
            bindingMediaIds[binding] != mediaId ||
            generation(binding) != pending.bindGeneration ||
            !isRootVisible(root)
        ) return@postOnAnimation
        resolveVisible(binding, mediaId, pending.originalResolutionMode)
    }
}

internal fun AppleDataBindingMetadataHooks.invalidateOverwrittenAlias(binding: Any, mediaId: String, root: View) {
    val appliedAlias = appliedAliases[binding] ?: return
    val pendingAlias = pendingRefreshes[binding]?.alias
    val renderedTexts = dataBindingRenderedTexts(root)
    if (!host.shouldInvalidateAppliedAlias(
            binding,
            mediaId,
            appliedAlias,
            pendingAlias,
            renderedTexts,
        )
    ) return
    appliedAliases.remove(binding)
    if (AppleProviderEnvironment.diagnosticsEnabled) {
        ProviderLogger.info(
            "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                "event=data_binding_alias_invalidated, contentId=$mediaId, " +
                "rendered=$renderedTexts"
        )
    }
}

internal fun AppleDataBindingMetadataHooks.resolveVisible(
    binding: Any,
    mediaId: String,
    originalResolutionMode: InAppOriginalResolutionMode = host.originalResolutionMode(binding),
    alreadyMarkedVisible: Boolean = false,
) {
    if (!alreadyMarkedVisible) {
        host.enrichEntitiesForResolution(listOf(mediaId))
        host.markMetadataVisible(listOf(mediaId))
    }
    host.effectiveAlias(mediaId)?.let { refreshDataBindings(mediaId, it) }
    host.scheduleMetadataResolution(
        listOf(mediaId),
        RequestPriority.VISIBLE,
        originalResolutionMode,
    )
}

internal fun AppleDataBindingMetadataHooks.resolvedMediaId(binding: Any): String? {
    bindingMediaIds[binding]?.let { return it }
    val fields = bindingContentFields.computeIfAbsent(binding.javaClass) {
        discoverBindingContentFields(it)
    }
    val candidates = fields.mapNotNull { field ->
        val value = runCatching { field.get(binding) }.getOrNull() ?: return@mapNotNull null
        host.bindingCandidateMediaId(value)
    }.distinct()
    val mediaId = candidates.singleOrNull()
    if (mediaId == null) {
        if (AppleProviderEnvironment.diagnosticsEnabled) {
            ProviderLogger.info(
                "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                    "event=data_binding_resolve_miss, " +
                    "binding=${binding.javaClass.name}@${System.identityHashCode(binding)}, " +
                    "candidates=$candidates, fieldCount=${fields.size}, " +
                    "texts=${bindingRootViews[binding]?.get()?.let(::debugTextSnapshot)}"
            )
        }
        return null
    }
    register(mediaId, binding)
    return mediaId
}

internal fun AppleDataBindingMetadataHooks.registerGenericRecyclerBinding(capture: RecyclerBindCapture, root: View) {
    val mediaIds = normalizedRecyclerBindingMediaIds(capture.mediaIds)
    if (mediaIds.isEmpty()) return
    recyclerRootMediaIds[root] = mediaIds
    val adapter = capture.adapter?.get() ?: return
    if (capture.position < 0) return
    val dataBinding = bindingsByRoot[root]?.get()
    val dataBindingMediaId = dataBinding?.let(::resolvedMediaId)
        ?: mediaIds.singleOrNull()?.also { mediaId ->
            dataBinding?.let { register(mediaId, it) }
        }
    if (host.isQueueAdapter(adapter)) return
    val blockMultiItemStructuralRefresh =
        mediaIds.size > 1 && host.isArtistProfileRecyclerAdapter(adapter)
    if (!shouldRegisterGenericRecyclerRefresh(
            mediaIds,
            dataBindingMediaId,
            blockMultiItemStructuralRefresh,
        )
    ) {
        if (AppleProviderEnvironment.diagnosticsEnabled && blockMultiItemStructuralRefresh) {
            ProviderLogger.info(
                "Apple Music 元数据链路: seq=${host.nextMetadataTraceSequence()}, " +
                    "event=generic_recycler_structural_refresh_blocked, " +
                    "contentIds=$mediaIds, root=${root.javaClass.name}, " +
                    "position=${capture.position}, adapter=${adapter.javaClass.name}"
            )
        }
        return
    }
    mediaIds.filterNot { it == dataBindingMediaId }.forEach { mediaId ->
        val refs = genericRecyclerItemRefs.computeIfAbsent(mediaId) {
            ConcurrentLinkedQueue()
        }
        var registered = false
        refs.forEach { ref ->
            val targetAdapter = ref.adapter.get()
            val targetRoot = ref.root.get()
            if (targetAdapter == null || targetRoot == null) {
                refs.remove(ref)
            } else if (
                targetAdapter === adapter && targetRoot === root &&
                ref.position == capture.position
            ) {
                registered = true
            }
        }
        if (!registered) {
            refs.add(
                InAppRecyclerItemRef(
                    adapter = WeakReference(adapter),
                    root = WeakReference(root),
                    position = capture.position,
                )
            )
        }
    }
}

internal fun AppleDataBindingMetadataHooks.boundRootContainsMediaId(root: View, mediaId: String): Boolean =
    mediaId in recyclerRootMediaIds[root].orEmpty()

/**
 * 取 ViewHolder 的 itemView。宿主 R8 混淆后字段名不可用：1611 的 Epoxy holder `J` 自身带一个
 * 常为空的 `ViewGroup` 字段，基类 `RecyclerView$D` 另有指向所属 RecyclerView 的字段，
 * 只有 itemView 的声明类型恰好是 `View`（221024 `[PlaylistRowDiag]` 实测）。
 */
internal fun AppleDataBindingMetadataHooks.recyclerViewHolderItemView(holder: Any): View? {
    val candidates = holderItemViewFieldCandidates(
        AppleReflection.hierarchyFields(holder.javaClass),
        View::class.java,
    )
    return candidates.firstNotNullOfOrNull { field ->
        runCatching {
            field.isAccessible = true
            field.get(holder) as? View
        }.getOrNull()
    }
}

/**
 * 声明类型恰为 [viewType] 的字段优先；没有时沿用原先“第一个子类字段”的行为。不能退到其他
 * 非空子类字段，否则会把所属 RecyclerView 当成行视图。
 */
internal fun holderItemViewFieldCandidates(
    fields: List<java.lang.reflect.Field>,
    viewType: Class<*>,
): List<java.lang.reflect.Field> {
    val viewFields = fields.filter { field -> viewType.isAssignableFrom(field.type) }
    return viewFields.filter { it.type == viewType }.ifEmpty { viewFields.take(1) }
}

internal fun AppleDataBindingMetadataHooks.recyclerViewFromRecycler(recycler: Any?): Any? {
    recycler ?: return null
    val recyclerClass = recyclerViewClass ?: return null
    return AppleReflection.hierarchyFields(recycler.javaClass)
        .asSequence()
        .firstOrNull { field -> recyclerClass.isAssignableFrom(field.type) }
        ?.let { field ->
            runCatching {
                field.isAccessible = true
                field.get(recycler)
            }.getOrNull()
        }
}

internal fun identityCandidateSet(candidates: List<Any>): Set<Any> =
    Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).apply { addAll(candidates) }

internal fun AppleDataBindingMetadataHooks.bindingReferencesAny(binding: Any, candidateSet: Set<Any>): Boolean {
    val fields = bindingContentFields.computeIfAbsent(binding.javaClass) {
        discoverBindingContentFields(it)
    }
    return fields.any { field ->
        runCatching { field.get(binding) }.getOrNull()?.let(candidateSet::contains) == true
    }
}

internal fun AppleDataBindingMetadataHooks.discoverBindingContentFields(bindingClass: Class<*>): List<Field> {
    val baseClass = bindingBaseClass ?: return emptyList()
    return generateSequence(bindingClass) { current ->
        current.superclass?.takeUnless { it == baseClass }
    }.flatMap { current -> current.declaredFields.asSequence() }
        .filter { field -> !Modifier.isStatic(field.modifiers) && !field.type.isPrimitive }
        .onEach { field -> field.isAccessible = true }
        .toList()
}

internal fun AppleDataBindingMetadataHooks.dataBindingRenderedTexts(root: View): List<String> {
    val texts = mutableListOf<String>()
    val pending = ArrayDeque<View>()
    pending.add(root)
    var visited = 0
    while (pending.isNotEmpty() && visited < 32 && texts.size < 8) {
        val view = pending.removeFirst()
        visited += 1
        if (view is TextView) {
            view.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(texts::add)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                view.getChildAt(index)?.let(pending::addLast)
            }
        }
    }
    return texts
}

internal fun AppleDataBindingMetadataHooks.debugTextSnapshot(root: View): String =
    dataBindingRenderedTexts(root).joinToString(prefix = "[", postfix = "]")
