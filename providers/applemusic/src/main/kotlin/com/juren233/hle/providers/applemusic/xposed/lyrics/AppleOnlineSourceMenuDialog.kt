/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.lyrics

import com.juren233.hle.providers.applemusic.xposed.AppleMenuText
import com.juren233.hle.providers.applemusic.xposed.AppleProviderEnvironment

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceInfo
import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceStatus
import com.juren233.hle.providers.applemusic.xposed.ActiveOnlineSourceMenu
import com.juren233.hle.providers.applemusic.xposed.AppleMusicHookPoint
import com.juren233.hle.providers.applemusic.xposed.AppleMusicHookTarget
import com.juren233.hle.providers.applemusic.xposed.AppleMusicProviderRuntime
import com.juren233.hle.providers.applemusic.xposed.AppleMusicRuntimeMember
import com.juren233.hle.providers.applemusic.xposed.AppleNativeOnlineTranslationStore
import com.juren233.hyperlyricsenhanced.contract.AppleSourceSwitchPerformanceDiagnostics
import com.juren233.hle.providers.applemusic.xposed.AppleReflection
import com.juren233.hle.providers.applemusic.xposed.ConfirmedOnlineSourceSelection
import com.juren233.hle.providers.applemusic.xposed.FailedOnlineSourceSwitch
import com.juren233.hle.providers.applemusic.xposed.OnlineSourceMenuPresentation
import com.juren233.hle.providers.applemusic.xposed.OnlineSourceMenuStatus
import com.juren233.hle.providers.applemusic.xposed.OnlineSourceRowKind
import com.juren233.hle.providers.applemusic.xposed.onlineSourceRowKind
import com.juren233.hle.providers.applemusic.xposed.PendingOnlineSourceSwitch
import com.juren233.hle.providers.applemusic.xposed.PreferencesMonitor
import com.juren233.hle.providers.applemusic.xposed.ProviderLogger
import com.juren233.hle.providers.applemusic.xposed.effectiveOnlineSourceSelection
import com.juren233.hle.providers.applemusic.xposed.isMissingLyricsSourceSelectable
import com.juren233.hle.providers.applemusic.xposed.missingLyricsSourceMenuLabel
import com.juren233.hle.providers.applemusic.xposed.missingLyricsSourceStatusLabel
import com.juren233.hle.providers.applemusic.xposed.sourceMenuLabel
import com.juren233.hle.providers.applemusic.xposed.sourceMenuPresentation
import com.juren233.hle.providers.applemusic.xposed.sourceMenuWidth
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

internal fun AppleOnlineSourceMenuHooks.lyricsSourceStatus(
    songId: String,
    source: String,
): AppleMissingLyricsSourceStatus {
    if (source == APPLE_NATIVE_SOURCE) {
        return AppleMissingLyricsSourceStatus(
            source = source,
            searched = true,
            found = true,
        )
    }
    return missingLyricsSourceInfo(songId)
        ?.statuses
        ?.firstOrNull { it.source == source }
        ?: AppleMissingLyricsSourceStatus(source, searched = false, found = false)
}

internal fun AppleOnlineSourceMenuHooks.refreshActiveLyricsSourceDialog(songId: String) {
    val active = activeLyricsSourceDialog ?: return
    if (active.songId != songId) return
    val dialog = active.dialog.get()
    if (dialog == null || !dialog.isShowing) {
        activeLyricsSourceDialog = null
        return
    }
    val isLyricsSourceDialog = active.contentType == "lyrics"
    val selected = currentSource(songId, active.contentType)
    val pending = pendingSwitches[active.contentType]?.takeIf { it.songId == songId }
    val failed = failedSwitches[active.contentType]?.takeIf { it.songId == songId }
    active.rows.forEach { (source, row) ->
        val status = if (isLyricsSourceDialog) lyricsSourceStatus(songId, source) else null
        val isSelected = source == selected
        val isPending = pending?.targetSource == source
        val isFailed = failed?.displayedSource == source
        val contentRow = if (status == null) {
            contentSourceRow(
                songId = songId,
                contentType = active.contentType,
                source = source,
                isSelected = isSelected,
                isPending = isPending,
                isFailedSwitch = isFailed,
                anyPending = pending != null,
            )
        } else {
            null
        }
        val selectable = contentRow?.selectable
            ?: (pending == null && isMissingLyricsSourceSelectable(status!!, isSelected))
        row.container.isClickable = selectable
        row.container.isFocusable = selectable
        row.container.isEnabled = selectable
        row.container.alpha = when {
            contentRow != null -> if (contentRow.dimmed) 0.52f else 1f
            isPending || status!!.found -> 1f
            else -> 0.52f
        }
        row.title.setTextColor(if (isSelected) active.primaryColor else active.onSurfaceColor)
        row.title.typeface = Typeface.create(
            Typeface.DEFAULT,
            if (isSelected) Typeface.BOLD else Typeface.NORMAL,
        )
        row.subtitle.text = contentRow?.subtitle ?: when {
            isPending -> AppleMenuText.pick("获取中…", "Fetching…")
            isFailed -> AppleMenuText.pick("切换失败", "Switch failed")
            else -> missingLyricsSourceStatusLabel(status!!)
        }
        row.subtitle.setTextColor(active.onSurfaceVariantColor)
        row.indicator.text = when {
            isPending -> "…"
            isSelected -> "✓"
            else -> ""
        }
        row.indicator.contentDescription = when {
            isPending -> AppleMenuText.pick("获取中", "Fetching")
            isSelected -> AppleMenuText.pick("已选择", "Selected")
            else -> null
        }
    }
}

/** Row behavior for translation/pronunciation sources; shared by first render and refresh. */
internal data class ContentSourceRow(
    val selectable: Boolean,
    val dimmed: Boolean,
    val subtitle: String,
)

internal fun AppleOnlineSourceMenuHooks.contentSourceRow(
    songId: String,
    contentType: String,
    source: String,
    isSelected: Boolean,
    isPending: Boolean,
    isFailedSwitch: Boolean,
    anyPending: Boolean,
): ContentSourceRow {
    val percentage = contentMatchPercentage(songId, contentType, source)
    val state = nativeTranslationStore.sourceContentState(songId, source)
        ?: missingLyricsSourceContentState(songId, source)
    val kind = onlineSourceRowKind(contentType, source, isSelected, state, percentage != null)
    // The core keeps what a searched source returned, so such a switch only re-matches locally;
    // a source it never got a result from (not reported, or failed) really goes to the network.
    val resultKept = state != null && !state.searchFailed
    val subtitle = when {
        isPending -> if (resultKept) AppleMenuText.pick("切换中…", "Switching…") else AppleMenuText.pick("获取中…", "Fetching…")
        isFailedSwitch -> AppleMenuText.pick("切换失败", "Switch failed")
        kind == OnlineSourceRowKind.NO_CONTENT ->
            if (contentType == "pronunciation") AppleMenuText.pick("暂无发音", "No pronunciation") else AppleMenuText.pick("暂无翻译", "No translation")
        kind == OnlineSourceRowKind.SEARCH_FAILED ->
            if (contentType == "pronunciation") AppleMenuText.pick("上次检索暂无发音 · 点击重试", "No pronunciation last time · Tap to retry") else AppleMenuText.pick("上次检索暂无翻译 · 点击重试", "No translation last time · Tap to retry")
        kind == OnlineSourceRowKind.SEARCHING -> AppleMenuText.pick("检索中…", "Searching…")
        isSelected -> currentSourceSubtitle(contentType, percentage ?: 0)
        else -> sourceMatchSubtitle(contentType, percentage ?: 0)
    }
    return ContentSourceRow(
        selectable = !anyPending && !isSelected && kind != OnlineSourceRowKind.NO_CONTENT,
        dimmed = !isPending && kind == OnlineSourceRowKind.NO_CONTENT,
        subtitle = subtitle,
    )
}

internal fun AppleOnlineSourceMenuHooks.resolveThemeColor(
    context: android.content.Context,
    attr: Int,
    fallback: Int,
): Int {
    val value = TypedValue()
    return if (context.theme.resolveAttribute(attr, value, true)) {
        if (value.resourceId != 0) {
            runCatching { context.getColor(value.resourceId) }.getOrDefault(value.data)
        } else {
            value.data
        }
    } else {
        fallback
    }
}

internal fun AppleOnlineSourceMenuHooks.roundedDrawable(
    context: android.content.Context,
    color: Int,
    radiusDp: Int,
): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(context, radiusDp).toFloat()
}

internal fun AppleOnlineSourceMenuHooks.blendColors(background: Int, foreground: Int, fraction: Float): Int {
    val amount = fraction.coerceIn(0f, 1f)
    fun blend(from: Int, to: Int): Int = (from + (to - from) * amount).roundToInt()
    return Color.rgb(
        blend(Color.red(background), Color.red(foreground)),
        blend(Color.green(background), Color.green(foreground)),
        blend(Color.blue(background), Color.blue(foreground)),
    )
}

internal fun AppleOnlineSourceMenuHooks.dialogWidth(context: android.content.Context): Int = minOf(
    dp(context, 344),
    context.resources.displayMetrics.widthPixels - dp(context, 32),
).coerceAtLeast(dp(context, 280))

internal fun AppleOnlineSourceMenuHooks.hairline(context: android.content.Context): Int =
    (context.resources.displayMetrics.density * 0.5f).roundToInt().coerceAtLeast(1)

internal fun AppleOnlineSourceMenuHooks.requestOnlineSourceSwitch(
    songId: String,
    contentType: String,
    targetSource: String,
) {
    val requestId = ++requestSequence
    val previousSource = currentSource(songId, contentType) ?: targetSource
    val pending = PendingOnlineSourceSwitch(
        requestId = requestId,
        songId = songId,
        contentType = contentType,
        previousSource = previousSource,
        targetSource = targetSource,
    )
    failedSwitches.remove(contentType)
    pendingSwitches[contentType] = pending
    AppleSourceSwitchPerformanceDiagnostics.start(
        mainHandler = runtime.mainHandler,
        requestId = requestId,
        songId = songId,
        previousSource = previousSource,
        targetSource = targetSource,
    )
    ProviderLogger.diagnostic(
        "Apple Music 在线来源菜单点击: requestId=$requestId, " +
            "songId=$songId, contentType=$contentType, " +
            "from=$previousSource, to=$targetSource"
    )
    refreshActiveMenu(songId)
    AppleSourceSwitchPerformanceDiagnostics.stage(
        requestId = requestId,
        songId = songId,
        stage = "menu_refreshed_after_click",
    )
    val accepted = requestOnlineSource(requestId, songId, contentType, targetSource)
    AppleSourceSwitchPerformanceDiagnostics.stage(
        requestId = requestId,
        songId = songId,
        stage = "binder_request_returned",
        details = "accepted=$accepted",
    )
    ProviderLogger.diagnostic(
        "Apple Music 在线来源请求投递: requestId=$requestId, " +
            "contentType=$contentType, accepted=$accepted"
    )
    if (!accepted) {
        markSwitchFailed(pending, previousSource, "binder_unavailable")
        return
    }
    runtime.mainHandler.postDelayed({
        val current = pendingSwitches[contentType]
        if (current?.requestId == requestId) {
            markSwitchFailed(current, currentSource(songId, contentType), "timeout")
        }
    }, ONLINE_SOURCE_SWITCH_TIMEOUT_MS)
}

internal fun AppleOnlineSourceMenuHooks.sourceMenuSourceName(source: String): String = when (source) {
    APPLE_NATIVE_SOURCE -> AppleMenuText.pick("Apple Music 原生歌词", "Apple Music native lyrics")
    "LB" -> "LunaBeat"
    "AMLL" -> "AMLL"
    "NE" -> AppleMenuText.pick("网易云音乐", "NetEase Cloud Music")
    "QM" -> AppleMenuText.pick("QQ音乐", "QQ Music")
    "KUWO" -> AppleMenuText.pick("酷我音乐", "Kuwo Music")
    "KUGOU" -> AppleMenuText.pick("酷狗音乐", "Kugou Music")
    else -> source
}

internal fun AppleOnlineSourceMenuHooks.dp(context: android.content.Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).roundToInt()

internal fun AppleOnlineSourceMenuHooks.markSwitchFailed(
    pending: PendingOnlineSourceSwitch,
    actualSource: String?,
    reason: String,
) {
    val current = pendingSwitches[pending.contentType]
    if (current?.requestId != pending.requestId) return
    pendingSwitches.remove(pending.contentType)
    val displayedSource = actualSource ?: pending.previousSource
    failedSwitches[pending.contentType] = FailedOnlineSourceSwitch(
        requestId = pending.requestId,
        songId = pending.songId,
        contentType = pending.contentType,
        displayedSource = displayedSource,
    )
    ProviderLogger.diagnostic(
        "Apple Music 在线翻译来源菜单切换失败: " +
            "requestId=${pending.requestId}, songId=${pending.songId}, " +
            "contentType=${pending.contentType}, target=${pending.targetSource}, " +
            "actual=$displayedSource, reason=$reason"
    )
    AppleSourceSwitchPerformanceDiagnostics.fail(
        mainHandler = runtime.mainHandler,
        requestId = pending.requestId,
        songId = pending.songId,
        reason = reason,
    )
    refreshActiveMenu(pending.songId)
    runtime.mainHandler.postDelayed(
        {
            val failure = failedSwitches[pending.contentType]
            if (failure?.requestId != pending.requestId) return@postDelayed
            failedSwitches.remove(pending.contentType)
            refreshActiveMenu(pending.songId)
        },
        ONLINE_SOURCE_SWITCH_FAILURE_FEEDBACK_MS,
    )
}

internal fun AppleOnlineSourceMenuHooks.currentSource(songId: String?, contentType: String): String? {
    val confirmedSource = confirmedSelections[contentType]
        ?.takeIf { it.songId == songId }
        ?.source
    return effectiveOnlineSourceSelection(
        storedSource = storedSource(songId, contentType),
        confirmedSource = confirmedSource,
        onlineContentConsumed = hasOnlineContentConsumption(songId, contentType),
        isLyricsSource = contentType == "lyrics",
        presentedLyricsSource = if (contentType == "lyrics") currentPresentedLyricsSource(songId) else null,
    )
}

internal fun AppleOnlineSourceMenuHooks.contentMatchPercentage(
    songId: String?,
    contentType: String,
    source: String,
): Int? = when (contentType) {
    "translation" -> nativeTranslationStore.translationMatchPercentage(songId, source)
        ?: missingLyricsTranslationMatchPercentage(songId, source)
    "pronunciation" -> nativeTranslationStore.pronunciationMatchPercentage(songId, source)
        ?: missingLyricsPronunciationMatchPercentage(songId, source)
    else -> null
}

internal fun AppleOnlineSourceMenuHooks.sourceMatchSubtitle(contentType: String, percentage: Int): String =
    if (contentType == "pronunciation") {
        AppleMenuText.pick("发音匹配度${percentage}%", "Pronunciation match ${percentage}%")
    } else {
        AppleMenuText.pick("翻译匹配度${percentage}%", "Translation match ${percentage}%")
    }

internal fun AppleOnlineSourceMenuHooks.currentSourceSubtitle(contentType: String, percentage: Int): String =
    AppleMenuText.pick("当前使用", "In use") + " · " + sourceMatchSubtitle(contentType, percentage)

internal fun AppleOnlineSourceMenuHooks.storedSource(songId: String?, contentType: String): String? = when (contentType) {
    "pronunciation" -> nativeTranslationStore.pronunciationSource(songId)
        ?: missingLyricsPronunciationSource(songId)
    "translation" -> nativeTranslationStore.translationSource(songId)
        ?: missingLyricsTranslationSource(songId)
    "lyrics" -> missingLyricsSourceInfo(songId)?.selectedSource
    else -> null
}

internal fun AppleOnlineSourceMenuHooks.presentation(
    songId: String,
    contentType: String,
    actualSource: String?,
): OnlineSourceMenuPresentation? = sourceMenuPresentation(
    actualSource = actualSource,
    pendingTargetSource = pendingSwitches[contentType]
        ?.takeIf { it.songId == songId }
        ?.targetSource,
    failedSource = failedSwitches[contentType]
        ?.takeIf { it.songId == songId }
        ?.displayedSource,
)

internal fun AppleOnlineSourceMenuHooks.resolveFragment(clickListener: Any): Any? {
    sourceMenuTarget.runtimeMemberNameOrNull(
        AppleMusicRuntimeMember.LYRICS_SOURCE_MENU_FRAGMENT_FIELD
    )?.let { fieldName ->
        runCatching { AppleReflection.field(clickListener, fieldName) }
            .getOrNull()
            ?.let { return it }
    }
    val fragmentClassName = sourceMenuTarget.runtimeMemberName(
        AppleMusicRuntimeMember.LYRICS_SOURCE_MENU_FRAGMENT_CLASS
    )
    return AppleReflection.hierarchyFields(clickListener.javaClass)
        .asSequence()
        .filter { field -> field.type.name == fragmentClassName }
        .firstNotNullOfOrNull { field ->
            runCatching {
                field.isAccessible = true
                field.get(clickListener)
            }.getOrNull()
        }
}

internal fun AppleOnlineSourceMenuHooks.resolvePopup(fragment: Any): PopupWindow? =
    AppleReflection.hierarchyFields(fragment.javaClass)
        .asSequence()
        .filter { field -> PopupWindow::class.java.isAssignableFrom(field.type) }
        .firstNotNullOfOrNull { field ->
            runCatching {
                field.isAccessible = true
                field.get(fragment) as? PopupWindow
            }.getOrNull()
        }

internal fun AppleOnlineSourceMenuHooks.normalizeTextItems(menu: LinearLayout) {
    for (index in 0 until menu.childCount) {
        val item = menu.getChildAt(index) as? TextView ?: continue
        item.isSingleLine = true
        item.maxLines = 1
        item.ellipsize = null
        item.maxWidth = Int.MAX_VALUE
        item.setHorizontallyScrolling(true)
        item.layoutParams?.let { layoutParams ->
            if (layoutParams.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                item.layoutParams = layoutParams
            }
        }
    }
}

internal fun AppleOnlineSourceMenuHooks.nativeMenuWidth(popup: PopupWindow, menu: LinearLayout): Int {
    val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    var nativeItemWidth = 0
    for (index in 0 until menu.childCount) {
        val item = menu.getChildAt(index)
        item.measure(unspecified, unspecified)
        nativeItemWidth = maxOf(
            nativeItemWidth,
            item.measuredWidth,
            item.minimumWidth,
            item.layoutParams?.width?.takeIf { it > 0 } ?: 0,
        )
    }
    menu.measure(unspecified, unspecified)
    return sourceMenuWidth(
        popup.width,
        popup.contentView.width,
        menu.width,
        menu.measuredWidth,
        menu.minimumWidth,
        menu.layoutParams?.width?.takeIf { it > 0 } ?: 0,
        nativeItemWidth + menu.paddingLeft + menu.paddingRight,
    )
}

internal fun AppleOnlineSourceMenuHooks.updateBounds(
    popup: PopupWindow,
    menu: LinearLayout,
    anchor: View,
    nativeMinimumWidth: Int,
) {
    val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    menu.minimumWidth = nativeMinimumWidth
    menu.measure(unspecified, unspecified)
    val desiredWidth = sourceMenuWidth(nativeMinimumWidth, menu.measuredWidth)
    menu.measure(
        View.MeasureSpec.makeMeasureSpec(desiredWidth, View.MeasureSpec.EXACTLY),
        unspecified,
    )
    val desiredHeight = menu.measuredHeight.coerceAtLeast(1)
    popup.update(anchor, desiredWidth, desiredHeight)
    menu.requestLayout()
    menu.invalidate()
}

internal fun AppleOnlineSourceMenuHooks.reportDiagnostic(
    stage: String,
    clickListener: Any? = null,
    anchor: View? = null,
    fragment: Any? = null,
    popup: PopupWindow? = null,
    menu: LinearLayout? = null,
    songId: String? = currentSongId(),
) {
    if (!AppleProviderEnvironment.diagnosticsEnabled) return
    val resolvedSongId = songId ?: currentSongId()
    val pronunciationSource = currentSource(resolvedSongId, "pronunciation")
    val translationSource = currentSource(resolvedSongId, "translation")
    val lyricsSource = currentSource(resolvedSongId, "lyrics")
    val lyricsInfo = missingLyricsSourceInfo(resolvedSongId)
    ProviderLogger.diagnostic(
        "Apple Music 三方歌词来源菜单诊断: stage=$stage, " +
            "listener=${debugValue(clickListener)}, " +
            "listenerFields=${debugSourceMenuFields(clickListener)}, " +
            "anchor=${debugValue(anchor)}, fragment=${debugValue(fragment)}, " +
            "fragmentFields=${debugSourceMenuFields(fragment)}, popup=${debugValue(popup)}, " +
            "popupShowing=${popup?.isShowing}, content=${debugValue(popup?.contentView)}, " +
            "menu=${debugValue(menu)}, nativeChildren=${menu?.childCount}, " +
            "viewTree=${debugViewTree(popup?.contentView)}, songId=$resolvedSongId, " +
            "visibleLyricsSongId=${visibleLyricsSongId()}, " +
            "storeRevision=${nativeTranslationStore.revision()}, " +
            "translation=[has=${nativeTranslationStore.hasTranslation(resolvedSongId)}," +
            "source=$translationSource,presentation=${presentation(
                resolvedSongId.orEmpty(), "translation", translationSource
            )},selected=${PreferencesMonitor.isTranslationSelected()}], " +
            "pronunciation=[has=${nativeTranslationStore.hasPronunciation(resolvedSongId)}," +
            "source=$pronunciationSource,presentation=${presentation(
                resolvedSongId.orEmpty(), "pronunciation", pronunciationSource
            )},selected=${PreferencesMonitor.isPronunciationSelected()}," +
            "hidden=${shouldHideMandarinPronunciation(resolvedSongId)}], " +
            "lyrics=[hasSupplement=${hasMissingLyricsSupplement(resolvedSongId)}," +
            "source=$lyricsSource,info=$lyricsInfo]"
    )
}

internal fun AppleOnlineSourceMenuHooks.debugSourceMenuFields(instance: Any?): String {
    if (!AppleProviderEnvironment.diagnosticsEnabled || instance == null) return "none"
    val fragmentClassName = sourceMenuTarget.runtimeMemberName(
        AppleMusicRuntimeMember.LYRICS_SOURCE_MENU_FRAGMENT_CLASS
    )
    return AppleReflection.hierarchyFields(instance.javaClass)
        .asSequence()
        .filter { field ->
            field.type.name == fragmentClassName ||
                PopupWindow::class.java.isAssignableFrom(field.type)
        }
        .joinToString(prefix = "[", postfix = "]") { field ->
            "${field.declaringClass.simpleName}.${field.name}:${field.type.simpleName}"
        }
}

internal fun AppleOnlineSourceMenuHooks.debugViewTree(root: View?): String {
    if (!AppleProviderEnvironment.diagnosticsEnabled || root == null) return "none"
    fun describe(view: View, depth: Int): String {
        val text = (view as? TextView)?.text?.toString()?.replace('\n', ' ')?.take(48)
        val label = view.javaClass.simpleName +
            "(id=${view.id},children=${(view as? ViewGroup)?.childCount ?: 0},text=$text)"
        if (depth >= 2 || view !is ViewGroup || view.childCount == 0) return label
        return (0 until view.childCount).joinToString(
            prefix = "$label[",
            postfix = "]",
        ) { index -> describe(view.getChildAt(index), depth + 1) }
    }
    return describe(root, 0)
}

    internal const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
    internal const val ONLINE_SOURCE_MENU_ITEM_TAG = "hyperlyrics_enhanced_online_lyrics_source"
    internal const val ONLINE_SOURCE_SWITCH_TIMEOUT_MS = 15_000L
    internal const val ONLINE_SOURCE_SWITCH_FAILURE_FEEDBACK_MS = 2_000L
    internal const val APPLE_NATIVE_SOURCE = "APPLE"
    internal val DEFAULT_SOURCE_ORDER = listOf("NE", "QM", "KUWO", "KUGOU")
    internal val SOURCE_ORDER = DEFAULT_SOURCE_ORDER
