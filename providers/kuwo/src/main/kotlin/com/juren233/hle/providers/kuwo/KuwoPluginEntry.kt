/*
 * Copyright 2026 Proify, Tomakino, juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Modified for the HLE Provider Pack runtime.
 */

package com.juren233.hle.providers.kuwo

import android.media.MediaMetadata
import android.util.Log
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderApplicationCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHost
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMetadataCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlaybackStateCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlugin
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import java.util.concurrent.atomic.AtomicBoolean

object KuwoPluginEntry : OfficialProviderPlugin {
    private const val TAG = "HLEProvider/Kuwo"
    private const val TARGET_PACKAGE = "cn.kuwo.player"
    private const val PROVIDER_PACKAGE =
        "com.juren233.hyperlyricsenhanced.provider.kuwo"

    private val initialized = AtomicBoolean(false)

    @Volatile
    private var provider: LyriconProvider? = null

    override fun install(host: OfficialProviderHost) {
        require(host.packageName == TARGET_PACKAGE) {
            "Unexpected target package: ${host.packageName}"
        }
        host.hookApplication(OfficialProviderApplicationCallback { application ->
            if (initialized.compareAndSet(false, true)) {
                provider = LyriconFactory.createProvider(
                    context = application,
                    providerPackageName = PROVIDER_PACKAGE,
                    playerPackageName = host.packageName,
                ).apply {
                    register()
                }
                Log.i(TAG, "酷我音乐 Lyricon Provider 已注册")
            }
        })
        host.hookMediaSession(
            playbackStateCallback = OfficialProviderPlaybackStateCallback { state ->
                provider?.player?.setPlaybackState(state)
            },
            metadataCallback = OfficialProviderMetadataCallback { metadata ->
                val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                provider?.player?.sendText(title?.takeIf(String::isNotBlank))
            },
        )
        Log.i(TAG, "酷我音乐 Provider Hook 已安装")
    }
}
