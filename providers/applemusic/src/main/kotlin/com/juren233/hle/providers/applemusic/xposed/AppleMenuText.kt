/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import java.util.Locale

/**
 * Single source for the language of the lyrics/translation/pronunciation menu shown inside
 * Apple Music. Chinese when the host process locale is Chinese, English otherwise, matching the
 * main app's default (Chinese) and `values-en` split.
 */
internal object AppleMenuText {
    /** Tests pin the language here; production follows the host process locale. */
    @Volatile
    internal var chineseOverride: Boolean? = null

    private val isChinese: Boolean
        get() = chineseOverride ?: (Locale.getDefault().language == Locale.CHINESE.language)

    fun pick(zh: String, en: String): String = if (isChinese) zh else en

    /** Chinese joins the source and content words directly; English separates them. */
    fun join(source: String, content: String): String = if (isChinese) source + content else "$source $content"
}
