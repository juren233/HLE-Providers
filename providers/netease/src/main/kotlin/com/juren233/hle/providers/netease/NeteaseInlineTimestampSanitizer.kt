/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.netease

/** Removes Enhanced LRC word timestamps from text shown by lyric consumers. */
internal object NeteaseInlineTimestampSanitizer {
    private val wordTimestamp = Regex("<\\d{1,3}:[0-5]\\d(?:\\.\\d{1,3})?>")

    fun strip(text: String): String = wordTimestamp.replace(text, "")
}
