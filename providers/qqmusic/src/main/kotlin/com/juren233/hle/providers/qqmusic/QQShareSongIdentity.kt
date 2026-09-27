/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

/**
 * 小米音乐 4.44.0.9 真机证伪（2026-09-27）：MediaSession MEDIA_ID 不是 QQ songmid
 * （三首不同歌曲的 MEDIA_ID 在单曲接口一律 code=0 且 data 为空数组），而媒体通知
 * extras 的 mediaFocusParam JSON 携带权威分享数据：干净歌名/歌手 + 真实 songmid。
 * Exhale 会话实测：MEDIA_ID=001Q5MKR47b4JH 查无此歌，shareData 的
 * songmid=004OZHd728dJgm 返回 Exhale / Sabrina Carpenter / id=234724468。
 *
 * 数据源与承载位置（MiuiSystemUI 反编译确认）：
 * SystemUI 的 LegacyMediaDataManagerImpl 从「媒体通知 extras」读取
 * key="miui.focus.param.media" 填进 MediaData.mediaFocusParam，再由
 * MiuiIslandMediaControllerImpl 解析 param_v2.param_island.shareData——
 * 即该 JSON 由小米音乐放进媒体通知的 Notification.extras，pack 侧从
 * NotificationManager.notify hook 读取（不在 MediaMetadata 里）。
 *
 * 提取不依赖 JSON 全文结构：扫描字符串值，优先包含 shareData / param_island
 * 标记的值，取 URL 的 songmid= 段与 shareData 的 title/content 字段
 * （content 即歌手）；无标记值时退化为全值扫描。
 */
internal data class QQShareSongIdentity(
    val songMid: String,
    val title: String?,
    val artist: String?,
)

internal fun extractShareSongIdentity(values: Collection<String?>): QQShareSongIdentity? {
    val candidates = values.filterNotNull()
    val preferred = candidates.firstOrNull {
        it.contains("shareData") || it.contains("param_island")
    }
    val scanOrder = listOfNotNull(preferred) + candidates.filter { it !== preferred }
    var songMid: String? = null
    var title: String? = null
    var artist: String? = null
    for (value in scanOrder) {
        if (songMid == null) {
            songMid = SONG_MID_REGEX.find(value)?.groupValues?.get(1)
        }
        if (title == null) {
            title = TITLE_REGEX.find(value)?.let { unescapeJson(it.groupValues[1]) }
                ?.takeIf(String::isNotBlank)
        }
        if (artist == null) {
            artist = CONTENT_REGEX.find(value)?.let { unescapeJson(it.groupValues[1]) }
                ?.takeIf(String::isNotBlank)
        }
        if (songMid != null && title != null && artist != null) break
    }
    return songMid?.let { QQShareSongIdentity(it, title, artist) }
}

private val SONG_MID_REGEX = Regex("""(?i)songmid=([0-9A-Za-z]+)""")
private val TITLE_REGEX = Regex(""""title"\s*:\s*"((?:[^"\\]|\\.)*)"""")
private val CONTENT_REGEX = Regex(""""content"\s*:\s*"((?:[^"\\]|\\.)*)"""")

private fun unescapeJson(value: String): String = value
    .replace("\\/", "/")
    .replace("\\\"", "\"")
    .replace("\\\\", "\\")
