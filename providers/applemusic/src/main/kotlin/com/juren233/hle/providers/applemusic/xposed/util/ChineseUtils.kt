/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hle.providers.applemusic.xposed.util

import android.content.Context
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * 极轻量级繁简转换工具（仅支持 繁体 -> 简体）
 * 基于正向最大匹配算法，避免了引入完整 opencc4j 导致的体积暴增
 *
 * Provider 自带副本；字典仍从主模块 APK 的 assets 读取，路径由 Host 提供。
 */
object ChineseUtils {
    private const val TAG = "AppleMusicProvider"

    @Volatile
    private var initialized = false

    private val phraseMap = HashMap<String, String>()
    private val charMap = HashMap<Char, Char>()
    private var maxPhraseLength = 1

    @Volatile
    private var moduleApkPath: String? = null

    fun setModuleApkPath(path: String?) {
        if (path.isNullOrBlank()) return
        synchronized(this) {
            if (moduleApkPath == path && (phraseMap.isNotEmpty() || charMap.isNotEmpty())) return
            moduleApkPath = path
            phraseMap.clear()
            charMap.clear()
            maxPhraseLength = 1
            initialized = false
        }
    }

    private fun ensureLoaded(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return

            try {
                openDictionary(context, "dictionary/TSPhrases.txt").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            val key = parts[0]
                            val value = parts[1]
                            phraseMap[key] = value
                            if (key.length > maxPhraseLength) {
                                maxPhraseLength = key.length
                            }
                        }
                    }
                }

                openDictionary(context, "dictionary/TSCharacters.txt").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            val key = parts[0]
                            val value = parts[1]
                            if (key.isNotEmpty() && value.isNotEmpty()) {
                                charMap[key[0]] = value[0]
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "繁简字典加载失败", e)
            }

            initialized = true
        }
    }

    private fun openDictionary(context: Context, path: String): InputStream {
        runCatching { context.assets.open(path) }.getOrNull()?.let { return it }
        moduleApkPath?.let { apkPath ->
            runCatching {
                ZipFile(apkPath).use { apk ->
                    val entry = apk.getEntry("assets/$path")
                        ?: error("Module asset not found: $path")
                    ByteArrayInputStream(apk.getInputStream(entry).use(InputStream::readBytes))
                }
            }.getOrNull()?.let { return it }
        }
        error("繁简字典不可用: $path")
    }

    /**
     * 将繁体中文转换为简体中文 (附带基于上下文词组的转换纠正)
     */
    fun toSimplified(context: Context, text: String): String {
        if (text.isEmpty()) return text
        ensureLoaded(context)

        val result = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            var matched = false
            for (len in maxPhraseLength downTo 2) {
                if (i + len <= text.length) {
                    val sub = text.substring(i, i + len)
                    val mapped = phraseMap[sub]
                    if (mapped != null) {
                        result.append(mapped)
                        i += len
                        matched = true
                        break
                    }
                }
            }
            if (!matched) {
                val c = text[i]
                result.append(charMap[c] ?: c)
                i++
            }
        }
        return result.toString()
    }
}
