/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qishui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QishuiApiClientTest {
    @Test
    fun `builds the verified PC track endpoint request`() {
        val url = QishuiApiClient.buildRequestUrl("7592139829673068587")

        assertTrue(url.startsWith("https://api.qishui.com/luna/pc/track_v2?"))
        assertTrue(url.contains("track_id=7592139829673068587"))
        assertTrue(url.contains("media_type=track"))
        assertTrue(url.contains("aid=386088"))
        assertTrue(url.contains("device_platform=web"))
        assertTrue(url.contains("channel=pc_web"))
    }

    @Test
    fun `parses PC translations without dropping their language key`() {
        val payload = requireNotNull(
            QishuiApiClient.parseResponse(
                "7592139829673068587",
                JSONObject(
                    """
                    {
                      "lyric": {
                        "type": "krc",
                        "content": "[22010,1000]<0,1000,0>Hello",
                        "translations": {
                          "cn": "[00:22.01]你好"
                        }
                      }
                    }
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals("krc", payload.type)
        assertEquals(1, payload.translations.size)
        assertEquals("cn", payload.translations.single().language)
        assertEquals("[00:22.01]你好", payload.translations.single().content)
    }

    @Test
    fun `parses Android lang translations with their own lyric type`() {
        val payload = requireNotNull(
            QishuiApiClient.parseResponse(
                "7234441209933596674",
                JSONObject(
                    """
                    {
                      "lyric": {
                        "type": "krc",
                        "content": "[22010,1000]<0,1000,0>Hello",
                        "lang_translations": {
                          "ZH-HANS-CN": {
                            "id": "7316704686453737473",
                            "lang": "ZH-HANS-CN",
                            "type": "lrc",
                            "content": "[00:22.01]你好"
                          }
                        }
                      }
                    }
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals(1, payload.translations.size)
        assertEquals("ZH-HANS-CN", payload.translations.single().language)
        assertEquals("lrc", payload.translations.single().type)
        assertEquals("[00:22.01]你好", payload.translations.single().content)
    }

    @Test
    fun `extracts numeric track id from system media URI`() {
        assertEquals(
            "7592139829673068587",
            QishuiTrackIdentity.apiTrackId(
                "luna://track/7592139829673068587?scene=player",
            ),
        )
    }
}
