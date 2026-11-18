/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleTtmlInlineAnnotationsTest {
    @Test
    fun `strips every inline translation and keeps lyric and background spans`() {
        // Issue #77：AMLL am-lyrics/1891689523.ttml 第 1 行的结构（翻译数量缩减）。
        val raw = "<p begin=\"3.930\" end=\"9.730\" itunes:key=\"L1\" ttm:agent=\"v2\">" +
            "<span begin=\"9.130\" end=\"9.330\">your</span> " +
            "<span begin=\"9.330\" end=\"9.730\">name</span>" +
            "<span ttm:role=\"x-translation\" xml:lang=\"zh-Hans\">转化这痛楚 重写你的名字</span>" +
            "<span ttm:role=\"x-translation\" xml:lang=\"zh-Hant\">轉化這痛楚 重寫你的名字</span>" +
            "<span ttm:role=\"x-translation\" xml:lang=\"fr\">Transformant la douleur</span>" +
            "<span ttm:role=\"x-bg\" begin=\"3.930\" end=\"5.180\">" +
            "<span begin=\"3.930\" end=\"4.030\">(Gi</span><span begin=\"4.140\" end=\"4.350\">ga,</span>" +
            "</span></p>"

        val stripped = AppleTtmlInlineAnnotations.strip(raw)

        assertEquals(
            "<p begin=\"3.930\" end=\"9.730\" itunes:key=\"L1\" ttm:agent=\"v2\">" +
                "<span begin=\"9.130\" end=\"9.330\">your</span> " +
                "<span begin=\"9.330\" end=\"9.730\">name</span>" +
                "<span ttm:role=\"x-bg\" begin=\"3.930\" end=\"5.180\">" +
                "<span begin=\"3.930\" end=\"4.030\">(Gi</span><span begin=\"4.140\" end=\"4.350\">ga,</span>" +
                "</span></p>",
            stripped,
        )
    }

    @Test
    fun `strips romanization and annotations nested in background voice`() {
        val raw = "<p><span begin=\"1.0\" end=\"2.0\">零</span>" +
            "<span ttm:role='x-bg'><span begin=\"2.0\" end=\"3.0\">bg</span>" +
            "<span ttm:role=\"x-translation\" xml:lang=\"en\">bg translation</span></span>" +
            "<span ttm:role=\"x-roman\">&quot;Zero&quot; modori</span>" +
            "<span xml:lang=\"en\" ttm:role=\"x-translation\"/></p>"

        val stripped = AppleTtmlInlineAnnotations.strip(raw)

        assertEquals(
            "<p><span begin=\"1.0\" end=\"2.0\">零</span>" +
                "<span ttm:role='x-bg'><span begin=\"2.0\" end=\"3.0\">bg</span></span></p>",
            stripped,
        )
    }

    @Test
    fun `annotation with nested spans is removed as a whole`() {
        val raw = "<p><span begin=\"1.0\" end=\"2.0\">a</span>" +
            "<span ttm:role=\"x-translation\"><span>x</span><span>y</span></span>" +
            "<span begin=\"2.0\" end=\"3.0\">b</span></p>"

        assertEquals(
            "<p><span begin=\"1.0\" end=\"2.0\">a</span><span begin=\"2.0\" end=\"3.0\">b</span></p>",
            AppleTtmlInlineAnnotations.strip(raw),
        )
    }

    @Test
    fun `head translations and plain lyrics are left untouched`() {
        val raw = "<tt><head><metadata><iTunesMetadata><translations>" +
            "<translation xml:lang=\"zh-Hans\"><text for=\"L1\">译文</text></translation>" +
            "</translations></iTunesMetadata></metadata></head>" +
            "<body><div><p itunes:key=\"L1\"><span begin=\"1.0\" end=\"2.0\">a</span></p></div></body></tt>"

        assertSame(raw, AppleTtmlInlineAnnotations.strip(raw))
    }

    @Test
    fun `malformed annotation without closing tag keeps original text`() {
        val raw = "<p><span begin=\"1.0\" end=\"2.0\">a</span><span ttm:role=\"x-translation\">x</p>"

        assertSame(raw, AppleTtmlInlineAnnotations.strip(raw))
    }

    @Test
    fun `stripped AMLL document still declares lyric content`() {
        val raw = "<tt xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\"><body><div>" +
            "<p begin=\"1.0\" end=\"2.0\"><span begin=\"1.0\" end=\"2.0\">遥</span>" +
            "<span ttm:role=\"x-translation\" xml:lang=\"zh-CN\">遥远</span>" +
            "<span ttm:role=\"x-roman\">ha</span></p></div></body></tt>"

        val stripped = AppleTtmlInlineAnnotations.strip(raw)

        assertTrue(stripped.contains(">遥</span>"))
        assertFalse(stripped.contains("x-translation"))
        assertFalse(stripped.contains("x-roman"))
    }
}
