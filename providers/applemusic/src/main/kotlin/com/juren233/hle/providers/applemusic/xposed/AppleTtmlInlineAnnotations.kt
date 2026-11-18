/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

/**
 * 去掉目录源 TTML 里的行内翻译与音译注释（`ttm:role="x-translation"` / `"x-roman"`）。
 *
 * AMLL TTML DB 把翻译、音译写成 `<p>` 里的无时间 span；Apple 原生解析器不把它们当翻译，
 * 而是当作正文拼进这一行（Issue #77：一行带 10 种语言翻译时全部首尾相连显示）。
 * 翻译与发音由模块覆盖层提供，交给 Apple 解析的 TTML 只需保留正文与背景声。
 * Apple 格式的 `<head>` 内 `<translations>`/`<transliterations>` 不受影响。
 */
internal object AppleTtmlInlineAnnotations {
    private val ANNOTATION_START = Regex(
        """<(?:[\w.-]+:)?span\b[^>]*?\s[\w.-]+:role\s*=\s*(["'])x-(?:translation|roman)\1[^>]*>"""
    )
    private val SPAN_TAG = Regex("""<(/?)(?:[\w.-]+:)?span\b[^>]*?(/?)>""")

    /** 没有行内注释时原样返回；注释 span 找不到闭合标签（文档残缺）时也原样返回。 */
    fun strip(ttml: String): String {
        var match = ANNOTATION_START.find(ttml) ?: return ttml
        val out = StringBuilder(ttml.length)
        var cursor = 0
        while (true) {
            out.append(ttml, cursor, match.range.first)
            cursor = if (match.value.endsWith("/>")) {
                match.range.last + 1
            } else {
                closingEnd(ttml, match.range.last + 1) ?: return ttml
            }
            match = ANNOTATION_START.find(ttml, cursor) ?: break
        }
        out.append(ttml, cursor, ttml.length)
        return out.toString()
    }

    private fun closingEnd(ttml: String, from: Int): Int? {
        var depth = 1
        var tag = SPAN_TAG.find(ttml, from)
        while (tag != null) {
            when {
                tag.groupValues[1] == "/" -> {
                    depth -= 1
                    if (depth == 0) return tag.range.last + 1
                }
                tag.groupValues[2] != "/" -> depth += 1
            }
            tag = tag.next()
        }
        return null
    }
}
