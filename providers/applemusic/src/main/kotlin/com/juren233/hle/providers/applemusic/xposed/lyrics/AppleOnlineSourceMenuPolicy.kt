/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import com.juren233.hyperlyricsenhanced.common.lyric.AppleMissingLyricsSourceStatus
import com.juren233.hyperlyricsenhanced.common.lyric.OnlineSourceContentState
import com.juren233.hyperlyricsenhanced.online.model.Source

internal fun effectiveOnlineSourceSelection(
    storedSource: String?,
    confirmedSource: String?,
    onlineContentConsumed: Boolean,
    isLyricsSource: Boolean = false,
    presentedLyricsSource: String? = null,
): String? = when {
    // A successful request or stored candidate does not identify the displayed lyrics.
    isLyricsSource -> presentedLyricsSource
    onlineContentConsumed -> confirmedSource ?: storedSource
    else -> null
}

/** Classify the same-song visible model, without treating a cached candidate as a binding. */
internal fun presentedLyricsSource(
    requestedSongId: String?,
    modelSongId: String?,
    hasLines: Boolean,
    pointer: Any?,
    knownSupplementPointers: List<Any>,
    currentSupplementPointer: Any?,
    supplementSource: String?,
    nativeAddress: (Any) -> Long?,
): String? {
    if (requestedSongId.isNullOrBlank() || requestedSongId != modelSongId || !hasLines || pointer == null) {
        return null
    }
    if (!isKnownSupplementPointer(pointer, knownSupplementPointers, nativeAddress)) return "APPLE"
    // Retained models can still be visible while a new model is being built. Do not
    // attribute the new Store source to an old pointer, or guess that it is native.
    if (!isKnownSupplementPointer(pointer, listOfNotNull(currentSupplementPointer), nativeAddress)) {
        return null
    }
    return supplementSource?.takeIf { it.isNotBlank() && it != "APPLE" }
}

internal fun sourceMenuPresentation(
    actualSource: String?,
    pendingTargetSource: String?,
    failedSource: String?,
): OnlineSourceMenuPresentation? = when {
    pendingTargetSource != null -> OnlineSourceMenuPresentation(
        source = pendingTargetSource,
        status = OnlineSourceMenuStatus.SWITCHING,
    )
    failedSource != null -> OnlineSourceMenuPresentation(
        source = failedSource,
        status = OnlineSourceMenuStatus.FAILED,
    )
    actualSource != null -> OnlineSourceMenuPresentation(
        source = actualSource,
        status = OnlineSourceMenuStatus.STABLE,
    )
    else -> null
}

internal fun sourceMenuLabel(
    source: String,
    contentType: String,
    status: OnlineSourceMenuStatus = OnlineSourceMenuStatus.STABLE,
): String {
    val sourceLabel = when (source) {
        "QM" -> "QQ"
        "KUWO" -> AppleMenuText.pick("酷我", "Kuwo")
        "KUGOU" -> AppleMenuText.pick("酷狗", "Kugou")
        "LB" -> "LB"
        "AMLL" -> "AMLL"
        "APPLE" -> AppleMenuText.pick("原生", "Native")
        else -> AppleMenuText.pick("网易", "NE")
    }
    val contentLabel = when (contentType) {
        "pronunciation" -> AppleMenuText.pick("发音", "Pronunciation")
        "lyrics" -> AppleMenuText.pick("歌词", "Lyrics")
        else -> AppleMenuText.pick("翻译", "Translation")
    }
    return when (status) {
        OnlineSourceMenuStatus.STABLE -> AppleMenuText.join(sourceLabel, contentLabel)
        OnlineSourceMenuStatus.SWITCHING -> AppleMenuText.pick("切换中", "Switching")
        OnlineSourceMenuStatus.FAILED -> AppleMenuText.pick("切换失败", "Switch failed")
    }
}

/**
 * 旧版补充歌词缓存只保存了正文和时间轴，无法可靠反推出当初采用的平台。
 * 在来源状态重新检索完成前，必须使用中性标题，不能假装它来自默认的网易。
 */
internal fun missingLyricsSourceMenuLabel(source: String?): String =
    source?.let { sourceMenuLabel(it, "lyrics") } ?: AppleMenuText.pick("歌词来源", "Lyrics Source")

/**
 * `searched=true, found=false` 只表示没有匹配到可用歌词，并不等于请求失败。
 * 当前跨进程状态没有携带网络/解析异常原因，因此 UI 不得伪造“检索失败”。
 */
internal fun missingLyricsSourceStatusLabel(status: AppleMissingLyricsSourceStatus): String =
    when {
        status.source == "APPLE" -> AppleMenuText.pick("Apple Music 原生歌词", "Apple Music native lyrics")
        status.searched && status.found ->
            "${if (status.wordTimed) AppleMenuText.pick("逐字歌词", "Word-synced") else AppleMenuText.pick("逐行歌词", "Line-synced")} · " +
                AppleMenuText.pick(
                    "${status.lineCount} 句",
                    if (status.lineCount == 1) "1 line" else "${status.lineCount} lines",
                )
        status.searched -> AppleMenuText.pick("未找到匹配歌词", "No matching lyrics found")
        else -> AppleMenuText.pick("正在获取来源信息", "Fetching source info…")
    }

internal fun isMissingLyricsSourceSelectable(
    status: AppleMissingLyricsSourceStatus,
    selected: Boolean,
): Boolean = !selected && (!status.searched || status.found)

internal fun currentLyricsMenuSongId(
    playbackSongId: String?,
    visibleLyricsSongId: String?,
): String? = playbackSongId?.trim()?.takeIf(String::isNotEmpty)
    ?: visibleLyricsSongId?.trim()?.takeIf(String::isNotEmpty)

internal fun sourceMenuWidth(vararg candidates: Int): Int =
    candidates.asSequence().filter { it > 0 }.maxOrNull() ?: 1

internal fun shouldDeferNativeTranslationPresentationRefresh(
    activeMenuSongId: String?,
    popupShowing: Boolean,
    expectedSongId: String?,
): Boolean =
    popupShowing &&
        !activeMenuSongId.isNullOrBlank() &&
        (expectedSongId.isNullOrBlank() || activeMenuSongId == expectedSongId)

/** What a translation/pronunciation source row can offer, from what the core searched. */
internal enum class OnlineSourceRowKind {
    /** Has content (or is the current source): shows its match percentage. */
    AVAILABLE,

    /** Searched and returned none, or cannot supply this content at all: dimmed, not clickable. */
    NO_CONTENT,

    /** Searched but returned nothing usable (miss, timeout): clickable to search again. */
    SEARCH_FAILED,

    /** The core has not reported this source yet. */
    SEARCHING,
}

/**
 * [state] is null while the core has not reported the source. Every source, TTML catalog sources
 * included, is judged by the content the core actually found in it.
 */
internal fun onlineSourceRowKind(
    contentType: String,
    source: String,
    isSelected: Boolean,
    state: OnlineSourceContentState?,
    hasMatchStat: Boolean,
): OnlineSourceRowKind = when {
    isSelected -> OnlineSourceRowKind.AVAILABLE
    state == null -> if (hasMatchStat) OnlineSourceRowKind.AVAILABLE else OnlineSourceRowKind.SEARCHING
    state.searchFailed -> OnlineSourceRowKind.SEARCH_FAILED
    (if (contentType == "pronunciation") state.hasPronunciation else state.hasTranslation) ->
        OnlineSourceRowKind.AVAILABLE
    else -> OnlineSourceRowKind.NO_CONTENT
}
