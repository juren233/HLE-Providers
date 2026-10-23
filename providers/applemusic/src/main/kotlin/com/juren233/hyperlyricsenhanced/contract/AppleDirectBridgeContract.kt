/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.juren233.hyperlyricsenhanced.contract

/**
 * Broadcast contract between the Apple Music Provider and the SystemUI side.
 *
 * Both ends are compiled independently, so action strings and extras are frozen.
 */
object AppleDirectBridgeContract {
    const val ACTION_REQUEST =
        "com.juren233.hyperlyricsenhanced.applemusic.REQUEST_DIRECT_BRIDGE"
    const val ACTION_REGISTER =
        "com.juren233.hyperlyricsenhanced.applemusic.REGISTER_DIRECT_BRIDGE"
    const val ACTION_RESOLVE_ORIGINAL_METADATA =
        "com.juren233.hyperlyricsenhanced.applemusic.RESOLVE_ORIGINAL_METADATA"
    const val EXTRA_BINDER = "bridge"
    const val EXTRA_MEDIA_ID = "media_id"
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
}
