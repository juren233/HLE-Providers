/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import com.juren233.hyperlyricsenhanced.contract.AppleMusicPreferenceKeys

/**
 * R6 跨领域生命周期接线：远程内容语言偏好。
 *
 * 偏好对象与监听器强引用归 [AppleOrchestratorCatalogLanguageAssembly]；
 * 本函数只负责“注入偏好 → 赋值监听器 → 注册监听器”的顺序与各组装群的刷新分发，
 * 保持迁移前的解析时机：所有回调都通过组装群的调用期 supplier 读取组件。
 */
internal fun AppleMusicProviderOrchestrator.initializeContentUiLanguage() {
    val prefs = runCatching {
        host.getRemotePreferences(AppleMusicPreferenceKeys.PREFERENCES_NAME)
    }.getOrNull() ?: return
    // 入口门控与各功能共享同一份远程偏好，入口状态变化后功能 Hook 立即让位/恢复。
    AppleMusicOptimizationGate.attach(prefs)
    catalogLanguage.attachPreferences(prefs)
    AppleLyricTextTransform.initialize(application) {
        lyricsPlayback.lyricsHooks.isSimplifyTraditionalLyricsEnabled()
    }
    catalogLanguage.internalCatalogResolver.setPersistentLocalizedCacheEnabled(
        prefs.getBoolean(
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_LOCALIZED_METADATA_CACHE,
            AppleMusicPreferenceKeys.DEFAULT_HOOK_APPLE_MUSIC_LOCALIZED_METADATA_CACHE,
        )
    )
    catalogLanguage.applyConfiguredContentUiLanguage(prefs)
    val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
        when (key) {
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE -> {
                catalogLanguage.applyConfiguredContentUiLanguage(changed)
                inAppMetadata.metadataConfigurationDispatcher.dispatch()
            }
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_OVERRIDE_ACCOUNT_LANGUAGE ->
                inAppMetadata.metadataConfigurationDispatcher.dispatch()
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_RESTORE_CJK_ORIGINAL_METADATA ->
                inAppMetadata.metadataConfigurationDispatcher.dispatch()
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_LOCALIZED_METADATA_CACHE ->
                catalogLanguage.applyConfiguredContentUiLanguage(changed)
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_SIMPLIFY_TRADITIONAL_CONTENT -> {
                lyricsPlayback.lyricsHooks.onAppleLyricsDisplayPreferenceChanged(AppleLyricsDisplayPreference.TEXT)
                // 该开关同时覆盖歌名/歌手名/专辑名，必须重新分发元数据，展示值才会立即改写。
                inAppMetadata.metadataConfigurationDispatcher.dispatch()
            }
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_HIDE_MANDARIN_PINYIN -> {
                lyricsPlayback.lyricsHooks.onAppleLyricsDisplayPreferenceChanged(AppleLyricsDisplayPreference.PRONUNCIATION)
            }
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_LYRICS_BLUR_EFFECT,
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_LYRICS_BLUR_ANIMATION,
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MIN_RADIUS_DP,
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_NATIVE_LYRICS_BLUR_MAX_RADIUS_DP,
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MIN_RADIUS_PX,
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_ADVANCED_LYRICS_BLUR_MAX_RADIUS_PX ->
                lyricsPlayback.lyricsHooks.onAppleLyricsDisplayPreferenceChanged(AppleLyricsDisplayPreference.BLUR)
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT,
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_FOLLOW_SYSTEM_FONT_WEIGHT ->
                lyricsPlayback.lyricsHooks.onAppleLyricsDisplayPreferenceChanged(AppleLyricsDisplayPreference.FONT)
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_VOLUME_BALANCE -> {
                val enabled = changed.getBoolean(
                    key,
                    AppleMusicPreferenceKeys.DEFAULT_HOOK_APPLE_MUSIC_VOLUME_BALANCE,
                )
                ProviderLogger.info(
                    "[AtmosVolumeDiag] event=preference_changed," +
                        "elapsedMs=${android.os.SystemClock.elapsedRealtime()}," +
                        "enabled=$enabled"
                )
                lyricsPlayback.playbackHooks.onVolumeBalancePreferenceChanged()
            }
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_MATCH_ONLINE_TRANSLATION,
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_NATIVE_ONLINE_TRANSLATION -> {
                if (!lyricsPlayback.lyricsHooks.isNativeOnlineTranslationEnabled()) {
                    lyricsPlayback.lyricsHooks.nativeOnlineTranslationStore.clear()
                    lyricsPlayback.lyricsHooks.refreshAppleLyricsSupplementPresentation()
                }
            }
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_FILL_MISSING_LYRICS,
            AppleMusicPreferenceKeys.KEY_HOOK_APPLE_MUSIC_LUNABEAT_WORD_LYRICS ->
                lyricsPlayback.missingLyricsHooks.onPreferenceChanged()
            AppleMusicPreferenceKeys.KEY_HOOK_ENABLE_AOD_LYRICS -> {
                lyricsPlayback.playbackHooks.onAodPreferenceChanged()
            }
            else -> {
                if (com.juren233.hyperlyricsenhanced.online.OnlineTranslationSourcePreferences.isSourcePreference(key)) {
                    currentLyricsMenuSongId(
                        playbackSongId = lyricsPlayback.playbackMetadataCoordinator.currentPlaybackQueueMediaId(),
                        visibleLyricsSongId = lyricsPlayback.lyricsHooks.currentSongId(),
                    )?.let { songId ->
                        lyricsPlayback.onlineSourceMenuHooks.refreshActiveMenu(songId)
                    }
                }
            }
        }
    }
    catalogLanguage.attachPreferenceListener(listener)
    prefs.registerOnSharedPreferenceChangeListener(listener)
    ProviderLogger.diagnostic(
        "[AtmosVolumeDiag] event=preference_listener_registered," +
            "listener=${System.identityHashCode(listener)}"
    )
}
