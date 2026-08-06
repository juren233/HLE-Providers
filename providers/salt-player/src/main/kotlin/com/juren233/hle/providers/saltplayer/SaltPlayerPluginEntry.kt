/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import android.util.Log
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderApplicationCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHost
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMetadataCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlaybackStateCallback
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderPlugin
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import java.util.concurrent.atomic.AtomicBoolean

object SaltPlayerPluginEntry : OfficialProviderPlugin {
    private const val TAG = "HLEProvider/SaltPlayer"
    private const val TARGET_PACKAGE = "com.salt.music"
    private const val PROVIDER_PACKAGE =
        "com.juren233.hyperlyricsenhanced.provider.salt-player"

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
                Log.i(TAG, "椒盐音乐 Lyricon Provider 已注册")
            }
        })
        host.hookMediaSession(
            playbackStateCallback = OfficialProviderPlaybackStateCallback { state ->
                provider?.player?.setPlaybackState(state)
            },
            metadataCallback = OfficialProviderMetadataCallback { _ -> Unit },
        )
        host.hookAfterMethod(
            target = SaltPlayerHookProfiles.sendLyric,
            callback = OfficialProviderMethodCallback { _, arguments ->
                val lyric = (arguments.firstOrNull() as? String)?.takeIf(String::isNotBlank)
                provider?.player?.sendText(lyric)
            },
        )
        host.hookAfterMethod(
            target = SaltPlayerHookProfiles.clearLyric,
            callback = OfficialProviderMethodCallback { _, _ ->
                provider?.player?.sendText(null)
            },
        )
        Log.i(
            TAG,
            "椒盐音乐 Provider Hook 已安装: verified=" +
                "${SaltPlayerHookProfiles.VERIFIED_VERSION_NAME}" +
                "(${SaltPlayerHookProfiles.VERIFIED_VERSION_CODE})",
        )
    }
}
