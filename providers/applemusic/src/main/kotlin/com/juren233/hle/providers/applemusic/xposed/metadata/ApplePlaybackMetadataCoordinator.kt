/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import android.os.Handler
import android.os.Looper


internal interface ApplePlaybackMetadataCoordinatorHost {
    fun activePlayer(): Any?

    fun configuredContentUiLanguage(): Int

    fun shouldOverrideAccountLanguage(selection: Int): Boolean

    fun shouldRestoreCjkOriginalMetadata(metadata: MediaMetadataCache.Metadata): Boolean

    fun ensureContentItemMetadataHooks(contentItemClass: Class<*>)

    fun setMetadataPlaybackMediaId(mediaId: String)

    fun onCurrentPlaybackItem(mediaId: String, playbackItem: Any, queueId: Long)

    fun effectiveMetadataAlias(mediaId: String): Alias?

    fun applyPlaybackMetadataOverride(
        mediaId: String,
        alias: Alias,
        rememberLocalizedArtist: Boolean = true,
        originalMetadata: Boolean = false,
        originalMetadataConfirmed: Boolean = false,
    )

    fun logMetadataIdentity(event: String, details: String)

    fun validatedOriginalSongAlias(
        alias: Alias?,
        localizedTitle: String?,
        localizedArtist: String?,
    ): Alias?

    fun shouldShareOriginalSongLanguage(
        localizedTitle: String?,
        localizedArtist: String?,
        alias: Alias?,
    ): Boolean

    fun rememberOriginalLanguageForArtist(mediaId: String, language: String)

    fun isRestoreOriginalMetadataEnabled(): Boolean
}

/**
 * 选择补充歌词链使用的当前歌曲 ID。
 * 已由队列发布流程确认的 ID 优先，避免播放器 getter 在切歌边界返回上一首歌曲。
 */
internal fun selectCurrentPlaybackMediaId(
    publishedMediaId: String?,
    observedQueueMediaId: String?,
): String? = publishedMediaId?.takeIf(String::isNotBlank)
    ?: observedQueueMediaId?.takeIf(String::isNotBlank)

internal fun metadataWithEffectiveDisplayAlias(
    metadata: MediaMetadataCache.Metadata,
    alias: Alias?,
): MediaMetadataCache.Metadata = metadata.copy(
    title = alias?.title?.takeIf(String::isNotBlank) ?: metadata.title,
    artist = alias?.artist?.takeIf(String::isNotBlank) ?: metadata.artist,
)

/**
 * Owns the current Apple Music queue identity and the configured/original catalog resolution
 * lifecycle. UI model mutation remains in the host so this component cannot bypass surface,
 * generation, or visibility guards.
 */
internal class ApplePlaybackMetadataCoordinator(
    private val hookResolver: AppleMusicHookResolver,
    private val catalogResolver: AppleInternalCatalogResolver,
    private val metadataStore: AppleMetadataOverrideStore,
    private val host: ApplePlaybackMetadataCoordinatorHost,
) {
    private val playbackTarget by lazy {
        hookResolver.resolveClass(AppleMusicHookPoint.LOCAL_MEDIA_PLAYER_CONTROLLER_STATE).target
    }
    private var currentRefresh: PlaybackMetadataRefresh? = null
    private var currentMediaId: String? = null
    private val idleQueueItem = AppleIdleQueueItem()

    fun currentMetadataId(): String? = currentMediaId

    fun currentRefreshMediaId(): String? = currentRefresh?.mediaId

    fun invokeCurrentRefresh(mediaId: String) {
        currentRefresh
            ?.takeIf { it.mediaId == mediaId }
            ?.refresh
            ?.invoke()
    }

    fun refreshCurrentQueueItemIfActive(mediaPlayer: Any?, source: String) {
        if (!isActivePlaybackCallback(mediaPlayer, host.activePlayer())) {
            // 还没有任何播放器播放过时（冷启动恢复到暂停歌曲），队列项不能当作当前歌曲发布，
            // 只记住它；歌词页确认可见歌曲与它一致后才发布。
            rememberIdleQueueItem(mediaPlayer, source)
            ProviderLogger.debug(
                "忽略非活动播放器的歌曲元数据：source=$source, " +
                    "callback=${mediaPlayer?.let(System::identityHashCode)}, " +
                    "active=${host.activePlayer()?.let(System::identityHashCode)}"
            )
            return
        }
        refreshCurrentQueueItem(mediaPlayer, source)
    }

    fun refreshCurrentQueueItem(mediaPlayer: Any?, source: String) {
        if (mediaPlayer == null) {
            ProviderLogger.debug("歌曲元数据刷新失败：$source 的 MediaPlayer 为空")
            return
        }
        runCatching {
            handleQueueItem(currentQueueItem(mediaPlayer), source)
        }.onFailure {
            ProviderLogger.error("歌曲元数据刷新异常：source=$source", it)
        }
    }

    fun handleQueueItem(
        queueItem: Any?,
        source: String,
        publishAsCurrent: Boolean = true,
        refreshPlaybackMetadata: (() -> Unit)? = null,
    ) {
        if (queueItem == null) {
            ProviderLogger.debug("歌曲元数据更新失败：$source 的 PlayerQueueItem 为空")
            return
        }
        runCatching {
            val mediaItem = AppleReflection.call(
                queueItem,
                playbackMember(AppleMusicRuntimeMember.PLAYBACK_QUEUE_ITEM_ITEM_METHOD),
            ) ?: return@runCatching
            val mediaId = mediaItemId(mediaItem) ?: return@runCatching
            val languageSelection = host.configuredContentUiLanguage()
            val overrideAccountLanguage = host.shouldOverrideAccountLanguage(languageSelection)

            val metadata = MediaMetadataCache.Metadata(
                id = mediaId,
                title = AppleReflection.call(
                    mediaItem,
                    playbackMember(AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_TITLE_METHOD),
                ) as? String,
                artist = AppleReflection.call(
                    mediaItem,
                    playbackMember(
                        AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_ARTIST_NAME_METHOD
                    ),
                ) as? String,
                genre = AppleReflection.call(
                    mediaItem,
                    playbackMember(AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_GENRE_NAME_METHOD),
                ) as? String,
                duration = AppleReflection.call(
                    mediaItem,
                    playbackMember(AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_DURATION_METHOD),
                ) as? Long ?: 0L,
                queueId = AppleReflection.call(
                    queueItem,
                    playbackMember(AppleMusicRuntimeMember.PLAYBACK_QUEUE_ITEM_ID_METHOD),
                ) as? Long ?: 0L
            )
            val restoreCjkOriginalMetadata = host.shouldRestoreCjkOriginalMetadata(metadata)
            if (overrideAccountLanguage || restoreCjkOriginalMetadata) {
                host.ensureContentItemMetadataHooks(mediaItem.javaClass)
            }
            val previousMetadata = MediaMetadataCache.getMetadataById(mediaId)
            if (publishAsCurrent) {
                val previousCurrentId = currentMediaId
                currentMediaId = mediaId
                idleQueueItem.clear()
                host.setMetadataPlaybackMediaId(mediaId)
                host.onCurrentPlaybackItem(
                    mediaId = mediaId,
                    playbackItem = mediaItem,
                    queueId = metadata.queueId,
                )
                val effectiveAlias = host.effectiveMetadataAlias(mediaId)
                metadataStore.updateCurrentPlaybackOverride(effectiveAlias)
                val publishedMetadata = metadataWithEffectiveDisplayAlias(metadata, effectiveAlias)
                MediaMetadataCache.put(publishedMetadata)
                ProviderLogger.debug(
                    "歌曲元数据已更新：source=$source, id=${publishedMetadata.id}, " +
                        "queueId=${publishedMetadata.queueId}, 标题=${publishedMetadata.title}"
                )
                refreshPlaybackMetadata?.let { callback ->
                    currentRefresh = PlaybackMetadataRefresh(mediaId, callback)
                }
                PlaybackManager.onSongChanged(publishedMetadata.id)
                if (AppleProviderEnvironment.diagnosticsEnabled) host.logMetadataIdentity(
                    event = "queue_current_published",
                    details = "trigger=$source, previousId=$previousCurrentId, " +
                        "publishedId=$mediaId, title=${publishedMetadata.title}, " +
                        "artist=${publishedMetadata.artist}, baseTitle=${metadata.title}, " +
                        "baseArtist=${metadata.artist}, queueId=${metadata.queueId}, " +
                        "overrideEnabled=$overrideAccountLanguage",
                )
                if (
                    previousMetadata != null &&
                    (previousMetadata.title != publishedMetadata.title ||
                        previousMetadata.artist != publishedMetadata.artist)
                ) {
                    PlaybackManager.onCatalogMetadataResolved(publishedMetadata.id)
                }
                resolveCatalogMetadata(
                    metadata = metadata,
                    languageSelection = languageSelection,
                    overrideAccountLanguage = overrideAccountLanguage,
                    restoreCjkOriginalMetadata = restoreCjkOriginalMetadata,
                )
            } else {
                MediaMetadataCache.put(metadata)
                ProviderLogger.debug(
                    "歌曲元数据已更新：source=$source, id=${metadata.id}, " +
                        "queueId=${metadata.queueId}, 标题=${metadata.title}"
                )
            }
        }.onFailure {
            ProviderLogger.error("歌曲元数据解析异常：source=$source", it)
        }
    }

    fun resolveOriginalMetadataOnDemand(mediaId: String) {
        val metadata = MediaMetadataCache.getMetadataById(mediaId)
        if (metadata == null) {
            ProviderLogger.info("Apple 原名按需查询忽略: id=$mediaId, reason=metadata_missing")
            return
        }
        if (
            metadata.originalMetadataResolved ||
            !metadata.originalTitle.isNullOrBlank() ||
            !metadata.originalArtist.isNullOrBlank()
        ) {
            PlaybackManager.onCatalogMetadataResolved(mediaId)
            return
        }
        resolveOriginalMetadata(
            metadata = metadata,
            applyToPlayback = host.shouldRestoreCjkOriginalMetadata(metadata),
            reason = "online_source_requested",
        )
    }

    fun isCurrentQueueItem(candidate: Any?, current: Any?): Boolean {
        if (candidate == null || current == null) return false
        if (candidate === current) return true
        val queueIdMethod = playbackMember(AppleMusicRuntimeMember.PLAYBACK_QUEUE_ITEM_ID_METHOD)
        val candidateQueueId = AppleReflection.call(candidate, queueIdMethod) as? Long ?: 0L
        val currentQueueId = AppleReflection.call(current, queueIdMethod) as? Long ?: 0L
        if (candidateQueueId > 0L && currentQueueId > 0L) {
            return candidateQueueId == currentQueueId
        }
        val candidateMediaId = queueItemMediaId(candidate)
        val currentMediaId = queueItemMediaId(current)
        return candidateMediaId != null && candidateMediaId == currentMediaId
    }

    fun queueItemMediaId(queueItem: Any): String? {
        val mediaItem = AppleReflection.call(
            queueItem,
            playbackMember(AppleMusicRuntimeMember.PLAYBACK_QUEUE_ITEM_ITEM_METHOD),
        ) ?: return null
        return mediaItemId(mediaItem)
    }

    fun currentPlaybackQueueMediaId(): String? {
        val publishedMediaId = currentMediaId
        val observedQueueMediaId = if (publishedMediaId.isNullOrBlank()) {
            host.activePlayer()?.let { player ->
                runCatching { currentQueueItem(player) }.getOrNull()
            }?.let { queueItem ->
                runCatching { queueItemMediaId(queueItem) }.getOrNull()
            }
        } else {
            null
        }
        return selectCurrentPlaybackMediaId(
            publishedMediaId = publishedMediaId,
            observedQueueMediaId = observedQueueMediaId,
        )
    }

    /**
     * 歌词页刚确认可见歌曲后，取播放歌曲 ID 并交给 [action]（在主线程执行）。没有活动播放器、而可见歌曲
     * 正是播放器回调所观察到的当前队列项时，先按正常的队列项路径发布它（带标题、歌手等元数据），
     * 发布完成后才调用 [action]，所以 [currentPlaybackQueueMediaId] 此时已返回该 ID。
     * 发布在记录队列项时所在线程的 Looper 上执行，与正常切歌路径是同一线程；活动播放器仍只在真正播放时
     * 确立，这里不改变它，也不发布播放状态。
     */
    fun withPlaybackSongIdForVisibleLyrics(visibleSongId: String?, action: (String?) -> Unit) {
        if (!shouldAdoptIdleQueueItem(
                activePlayerPresent = host.activePlayer() != null,
                idleMediaId = idleQueueItem.mediaId(),
                visibleSongId = visibleSongId,
                publishedMediaId = currentMediaId,
            )
        ) {
            action(currentPlaybackQueueMediaId())
            return
        }
        val taken = idleQueueItem.take() ?: run {
            action(currentPlaybackQueueMediaId())
            return
        }
        ProviderLogger.info(
            "歌词页可见歌曲与无活动播放器时的当前队列项一致，按当前歌曲发布：id=$visibleSongId"
        )
        val publish = {
            handleQueueItem(taken.queueItem, "visible_lyrics_idle_queue_item")
        }
        val looper = taken.looper
        if (looper == null || looper == Looper.myLooper()) {
            publish()
            action(currentPlaybackQueueMediaId())
            return
        }
        // 发布完成后再回到主线程继续，保证 onLyricsBuilt 看到的播放歌曲 ID 已就绪。
        val posted = Handler(looper).post {
            publish()
            Handler(Looper.getMainLooper()).post { action(currentPlaybackQueueMediaId()) }
        }
        if (!posted) {
            // 该线程已退出：在当前线程发布，仍保持先发布后继续的顺序。
            publish()
            action(currentPlaybackQueueMediaId())
        }
    }

    private fun rememberIdleQueueItem(mediaPlayer: Any?, source: String) {
        if (mediaPlayer == null || host.activePlayer() != null) return
        runCatching {
            val queueItem = currentQueueItem(mediaPlayer) ?: return@runCatching
            val mediaId = queueItemMediaId(queueItem) ?: return@runCatching
            idleQueueItem.remember(mediaId, queueItem, Looper.myLooper())
            ProviderLogger.debug("已记录无活动播放器时的当前队列项：source=$source, id=$mediaId")
        }.onFailure {
            ProviderLogger.error("记录无活动播放器时的当前队列项失败：source=$source", it)
        }
    }

    private fun resolveCatalogMetadata(
        metadata: MediaMetadataCache.Metadata,
        languageSelection: Int,
        overrideAccountLanguage: Boolean,
        restoreCjkOriginalMetadata: Boolean,
    ) {
        val resolutionPlan = AppleMetadataResolutionEngine.catalogMetadataResolutionPlan(
            overrideAccountLanguage = overrideAccountLanguage,
            restoreCjkOriginalMetadata = restoreCjkOriginalMetadata,
        )
        if (resolutionPlan.resolveConfiguredRegion) {
            resolveConfiguredCatalogMetadata(metadata, languageSelection)
        }

        if (resolutionPlan.resolveOriginalRegion) {
            if (metadata.originalMetadataResolved) {
                val cachedAlias = cachedOriginalMetadataAlias(metadata)
                if (cachedAlias != null) {
                    host.applyPlaybackMetadataOverride(
                        mediaId = metadata.id,
                        alias = cachedAlias,
                        rememberLocalizedArtist = false,
                        originalMetadata = true,
                        originalMetadataConfirmed = true,
                    )
                } else {
                    PlaybackManager.onCatalogMetadataResolved(metadata.id)
                }
                return
            }
            resolveOriginalMetadata(
                metadata = metadata,
                applyToPlayback = true,
                reason = "setting_enabled",
            )
            return
        }
        if (!resolutionPlan.resolveConfiguredRegion) {
            if (AppleProviderEnvironment.diagnosticsEnabled) host.logMetadataIdentity(
                event = "current_catalog_resolve_skipped",
                details = "requestedId=${metadata.id}, selection=$languageSelection, reason=disabled",
            )
        }
    }

    private fun resolveConfiguredCatalogMetadata(
        metadata: MediaMetadataCache.Metadata,
        languageSelection: Int,
    ) {
        if (AppleProviderEnvironment.diagnosticsEnabled) host.logMetadataIdentity(
            event = "current_catalog_resolve_started",
            details = "requestedId=${metadata.id}, selection=$languageSelection, " +
                "title=${metadata.title}, artist=${metadata.artist}",
        )

        catalogResolver.resolveForContentUiLanguage(
            mediaId = metadata.id,
            selection = languageSelection,
        ) { alias ->
            if (AppleProviderEnvironment.diagnosticsEnabled) host.logMetadataIdentity(
                event = "current_catalog_resolve_finished",
                details = "requestedId=${metadata.id}, selection=$languageSelection, " +
                    "hit=${alias != null}, resolved=${alias?.title}/${alias?.artist}/${alias?.album}",
            )
            if (
                alias != null &&
                host.shouldOverrideAccountLanguage(languageSelection) &&
                host.configuredContentUiLanguage() == languageSelection
            ) {
                host.applyPlaybackMetadataOverride(metadata.id, alias)
            }
        }
    }

    private fun resolveOriginalMetadata(
        metadata: MediaMetadataCache.Metadata,
        applyToPlayback: Boolean,
        reason: String,
    ) {
        ProviderLogger.info(
            "Apple 原名查询开始: id=${metadata.id}, title=${metadata.title}, " +
                "artist=${metadata.artist}, reason=$reason"
        )
        catalogResolver.resolveOriginalMetadata(
            metadata = metadata,
            onCandidate = candidate@{ candidate ->
                if (!applyToPlayback ||
                    !host.isRestoreOriginalMetadataEnabled() ||
                    currentMediaId != metadata.id
                ) return@candidate
                val safeCandidate = host.validatedOriginalSongAlias(
                    alias = candidate,
                    localizedTitle = metadata.title,
                    localizedArtist = metadata.artist,
                ) ?: return@candidate
                host.applyPlaybackMetadataOverride(
                    mediaId = metadata.id,
                    alias = safeCandidate,
                    rememberLocalizedArtist = false,
                )
            },
            onResolved = { resolution ->
                val alias = host.validatedOriginalSongAlias(
                    alias = resolution.alias,
                    localizedTitle = metadata.title,
                    localizedArtist = metadata.artist,
                )
                metadataStore.markOriginalResolved(metadata.id)
                resolution.language?.takeIf {
                    host.shouldShareOriginalSongLanguage(
                        localizedTitle = metadata.title,
                        localizedArtist = metadata.artist,
                        alias = alias,
                    )
                }?.let { language ->
                    host.rememberOriginalLanguageForArtist(metadata.id, language)
                }
                MediaMetadataCache.updateOriginalMetadata(
                    mediaId = metadata.id,
                    title = alias?.title,
                    artist = alias?.artist,
                    album = resolution.album ?: alias?.album,
                    resolved = true,
                )
                if (alias == null) {
                    ProviderLogger.info(
                        "Apple 原名查询未命中: id=${metadata.id}, reason=$reason"
                    )
                    metadataStore.configuredMetadata(metadata.id)?.let { localizedAlias ->
                        host.applyPlaybackMetadataOverride(metadata.id, localizedAlias)
                    } ?: PlaybackManager.onCatalogMetadataResolved(metadata.id)
                    return@resolveOriginalMetadata
                }
                if (
                    applyToPlayback &&
                    host.isRestoreOriginalMetadataEnabled() &&
                    currentMediaId == metadata.id
                ) {
                    host.applyPlaybackMetadataOverride(
                        mediaId = metadata.id,
                        alias = alias,
                        rememberLocalizedArtist = false,
                        originalMetadata = true,
                        originalMetadataConfirmed = true,
                    )
                } else {
                    PlaybackManager.onCatalogMetadataResolved(metadata.id)
                }
            },
        )
    }

    private fun cachedOriginalMetadataAlias(
        metadata: MediaMetadataCache.Metadata,
    ): Alias? {
        val title = metadata.originalTitle?.takeIf(String::isNotBlank)
        val artist = metadata.originalArtist?.takeIf(String::isNotBlank)
        if (title == null && artist == null) return null
        return Alias(
            title = title ?: metadata.title.orEmpty(),
            artist = artist ?: metadata.artist.orEmpty(),
            language = "original",
        )
    }

    private fun mediaItemId(mediaItem: Any): String? {
        val subscriptionStoreId =
            AppleReflection.call(
                mediaItem,
                playbackMember(
                    AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_SUBSCRIPTION_STORE_ID_METHOD
                ),
            ) as? String
        if (!subscriptionStoreId.isNullOrBlank()) return subscriptionStoreId
        val persistentId = AppleReflection.call(
            mediaItem,
            playbackMember(AppleMusicRuntimeMember.PLAYBACK_MEDIA_ITEM_PERSISTENT_ID_METHOD),
        ) as? Long ?: 0L
        return persistentId.takeIf { it > 0L }?.toString()
    }

    fun currentQueueItem(mediaPlayer: Any): Any? = AppleReflection.call(
        mediaPlayer,
        playbackMember(AppleMusicRuntimeMember.PLAYBACK_PLAYER_CURRENT_ITEM_METHOD),
    )

    private fun playbackMember(member: AppleMusicRuntimeMember): String =
        playbackTarget.runtimeMemberName(member)
}

/**
 * 根编排的默认宿主实现：依赖以 supplier 显式注入，保持原匿名实现"调用期解析"
 * 单例字段的语义（部分 Hook 在宿主构造之后才初始化）。
 */
internal class DefaultApplePlaybackMetadataCoordinatorHost(
    private val activePlayerFn: () -> Any?,
    private val configuredContentUiLanguageFn: () -> Int,
    private val shouldOverrideAccountLanguageFn: (Int) -> Boolean,
    private val shouldRestoreCjkOriginalMetadataFn: (MediaMetadataCache.Metadata) -> Boolean,
    private val ensureContentItemMetadataHooksFn: (Class<*>) -> Unit,
    private val setMetadataPlaybackMediaIdFn: (String) -> Unit,
    private val onCurrentPlaybackItemFn: (String, Any, Long) -> Unit,
    private val effectiveMetadataAliasFn: (String) -> Alias?,
    private val applyPlaybackMetadataOverrideFn: (
        String, Alias, Boolean, Boolean, Boolean,
    ) -> Unit,
    private val logMetadataIdentityFn: (String, String) -> Unit,
    private val shouldShareOriginalSongLanguageFn: (String?, String?, Alias?) -> Boolean,
    private val rememberOriginalLanguageForArtistFn: (String, String) -> Unit,
    private val isRestoreOriginalMetadataEnabledFn: () -> Boolean,
) : ApplePlaybackMetadataCoordinatorHost {
    override fun activePlayer(): Any? = activePlayerFn()

    override fun configuredContentUiLanguage(): Int = configuredContentUiLanguageFn()

    override fun shouldOverrideAccountLanguage(selection: Int): Boolean =
        shouldOverrideAccountLanguageFn(selection)

    override fun shouldRestoreCjkOriginalMetadata(
        metadata: MediaMetadataCache.Metadata,
    ): Boolean = shouldRestoreCjkOriginalMetadataFn(metadata)

    override fun ensureContentItemMetadataHooks(contentItemClass: Class<*>) {
        ensureContentItemMetadataHooksFn(contentItemClass)
    }

    override fun setMetadataPlaybackMediaId(mediaId: String) {
        setMetadataPlaybackMediaIdFn(mediaId)
    }

    override fun onCurrentPlaybackItem(mediaId: String, playbackItem: Any, queueId: Long) {
        onCurrentPlaybackItemFn(mediaId, playbackItem, queueId)
    }

    override fun effectiveMetadataAlias(mediaId: String): Alias? =
        effectiveMetadataAliasFn(mediaId)

    override fun applyPlaybackMetadataOverride(
        mediaId: String,
        alias: Alias,
        rememberLocalizedArtist: Boolean,
        originalMetadata: Boolean,
        originalMetadataConfirmed: Boolean,
    ) {
        applyPlaybackMetadataOverrideFn(
            mediaId, alias, rememberLocalizedArtist, originalMetadata, originalMetadataConfirmed,
        )
    }

    override fun logMetadataIdentity(event: String, details: String) {
        logMetadataIdentityFn(event, details)
    }

    override fun validatedOriginalSongAlias(
        alias: Alias?,
        localizedTitle: String?,
        localizedArtist: String?,
    ): Alias? =
        // 成员与同名顶层策略函数重名，Kotlin 成员优先；无限定调用会解析到自身形成无限递归。
        com.juren233.hle.providers.applemusic.xposed.validatedOriginalSongAlias(
            alias = alias,
            localizedTitle = localizedTitle,
            localizedArtist = localizedArtist,
        )

    override fun shouldShareOriginalSongLanguage(
        localizedTitle: String?,
        localizedArtist: String?,
        alias: Alias?,
    ): Boolean = shouldShareOriginalSongLanguageFn(localizedTitle, localizedArtist, alias)

    override fun rememberOriginalLanguageForArtist(mediaId: String, language: String) {
        rememberOriginalLanguageForArtistFn(mediaId, language)
    }

    override fun isRestoreOriginalMetadataEnabled(): Boolean =
        isRestoreOriginalMetadataEnabledFn()
}
