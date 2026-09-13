/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.netease

import com.juren233.hyperlyricsenhanced.provider.OfficialCoreHostGuard.looksLikeOfficialCoreApkPath
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialCoreHostGuardTest {
    @Test
    fun acceptsOfficialInstallPathLayouts() {
        assertTrue(
            looksLikeOfficialCoreApkPath(
                "/data/app/~~aBcD1234==/com.juren233.hyperlyricsenhanced-xYzAbCd/base.apk",
            ),
        )
        assertTrue(
            looksLikeOfficialCoreApkPath(
                "/data/app/com.juren233.hyperlyricsenhanced-1/base.apk",
            ),
        )
        assertTrue(
            looksLikeOfficialCoreApkPath(
                "/data/app/com.juren233.hyperlyricsenhanced/base.apk",
            ),
        )
    }

    @Test
    fun rejectsForeignInstallPaths() {
        assertFalse(
            looksLikeOfficialCoreApkPath("/data/app/~~aBcD1234==/com.fork.app-xYzAbCd/base.apk"),
        )
        assertFalse(looksLikeOfficialCoreApkPath("/data/app/com.fork.app-1/base.apk"))
    }

    @Test
    fun rejectsNamesThatOnlyEmbedTheOfficialPackageAsSubstring() {
        assertFalse(
            looksLikeOfficialCoreApkPath(
                "/data/app/evil.com.juren233.hyperlyricsenhanced-1/base.apk",
            ),
        )
        assertFalse(
            looksLikeOfficialCoreApkPath(
                "/data/app/com.juren233.hyperlyricsenhanced.fork-1/base.apk",
            ),
        )
        assertFalse(
            looksLikeOfficialCoreApkPath(
                "/data/app/~~aBcD==/com.juren233.hyperlyricsenhancedfake-xYz/base.apk",
            ),
        )
    }
}
