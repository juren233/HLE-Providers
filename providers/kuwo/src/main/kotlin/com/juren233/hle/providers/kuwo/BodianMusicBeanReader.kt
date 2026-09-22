/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

/**
 * 波点 `cn.kuwo.player.bean.Music` 的反射读取器（pack 不能编译期依赖宿主类）。
 *
 * 已对照波点 5.8.7（472）原始 DEX 验证：`rid` 为 @JvmField public String；
 * name/artist/album 为私有 Kotlin 属性，暴露 getName()/getArtist()/getAlbum()；
 * `getDur()` 为 PlayerBridge 写入 METADATA_KEY_DURATION 的同一取值（毫秒）。
 */
internal object BodianMusicBeanReader {
    fun read(music: Any?): BodianTrackBean? {
        if (music == null) return null
        return runCatching {
            val rid = readStringField(music, "rid")?.trim()?.takeIf(String::isNotEmpty)
                ?: return@runCatching null
            BodianTrackBean(
                rid = rid,
                title = readStringGetter(music, "getName"),
                artist = readStringGetter(music, "getArtist"),
                album = readStringGetter(music, "getAlbum"),
                durationMs = runCatching {
                    (music.javaClass.getMethod("getDur").invoke(music) as? Int)?.toLong() ?: 0L
                }.getOrDefault(0L),
            )
        }.getOrNull()
    }

    private fun readStringField(target: Any, name: String): String? = runCatching {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            val field = runCatching { clazz.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return@runCatching field.get(target) as? String
            }
            clazz = clazz.superclass
        }
        null
    }.getOrNull()

    private fun readStringGetter(target: Any, getter: String): String? = runCatching {
        target.javaClass.getMethod(getter).invoke(target) as? String
    }.getOrNull()
}
