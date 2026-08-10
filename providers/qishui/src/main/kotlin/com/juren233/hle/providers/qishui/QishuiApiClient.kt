/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qishui

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object QishuiTrackIdentity {
    private val numericTrackId = Regex("""\d{5,}""")

    fun candidates(value: String?): Set<String> {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return emptySet()
        return buildSet {
            add(raw)
            raw.substringAfterLast('/').substringBefore('?')
                .takeIf(String::isNotBlank)
                ?.let(::add)
            numericTrackId.findAll(raw).forEach { add(it.value) }
        }
    }

    fun apiTrackId(value: String?): String? = candidates(value)
        .firstOrNull { numericTrackId.matches(it) }
}

internal object QishuiApiClient {
    const val ENDPOINT = "https://api.qishui.com/luna/pc/track_v2"

    fun buildRequestUrl(trackId: String): String {
        require(trackId.isNotBlank()) { "汽水 trackId 不能为空" }
        val parameters = listOf(
            "track_id" to trackId,
            "media_type" to "track",
            "aid" to "386088",
            "device_platform" to "web",
            "channel" to "pc_web",
        ).joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
        }
        return "$ENDPOINT?$parameters"
    }

    fun fetch(trackId: String): QishuiLyricPayload {
        val connection = (URI(buildRequestUrl(trackId)).toURL().openConnection()
            as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 " +
                    "Chrome/138.0 Mobile Safari/537.36",
            )
        }
        return try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "汽水歌词 HTTP ${connection.responseCode}"
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseResponse(trackId, JSONObject(body))
                ?: error("汽水歌词响应不包含有效 lyric")
        } finally {
            connection.disconnect()
        }
    }

    fun parseResponse(trackId: String, root: JSONObject): QishuiLyricPayload? {
        val lyric = root.optJSONObject("lyric") ?: return null
        val content = lyric.optString("content").trim()
        if (content.isBlank()) return null
        val type = lyric.optString("type").ifBlank { inferType(content) }
        val translations = buildList {
            appendTranslations(lyric.opt("translations"), this)
            appendTranslations(lyric.opt("lang_translations"), this)
        }
        return QishuiLyricPayload(
            trackId = trackId,
            type = type,
            content = content,
            translations = translations,
        )
    }

    private fun appendTranslations(
        raw: Any?,
        destination: MutableList<QishuiTranslationPayload>,
    ) {
        when (raw) {
            is JSONObject -> raw.keys().forEach { language ->
                decodeTranslation(language, raw.opt(language))?.let(destination::add)
            }
            is JSONArray -> repeat(raw.length()) { index ->
                val value = raw.opt(index)
                val language = (value as? JSONObject)
                    ?.optString("lang")
                    ?.takeIf(String::isNotBlank)
                    ?: index.toString()
                decodeTranslation(language, value)?.let(destination::add)
            }
        }
    }

    private fun decodeTranslation(
        languageKey: String,
        value: Any?,
    ): QishuiTranslationPayload? {
        val language: String
        val type: String
        val content: String
        when (value) {
            is String -> {
                language = languageKey
                content = value.trim()
                type = inferType(content)
            }
            is JSONObject -> {
                language = value.optString("lang").ifBlank { languageKey }
                content = value.optString("content").trim()
                type = value.optString("type").ifBlank { inferType(content) }
            }
            else -> return null
        }
        if (content.isBlank()) return null
        return QishuiTranslationPayload(
            language = language,
            type = type,
            content = content,
        )
    }

    private fun inferType(content: String): String =
        if (Regex("""^\[\d+,\d+]""").containsMatchIn(content.trimStart())) "krc" else "lrc"
}
