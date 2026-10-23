/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.contract.AppleMusicPreferenceKeys
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal fun AppleInternalCatalogResolver.resolveCatalogIdentity(
    mediaId: String,
    languages: List<String>,
    onResult: (CatalogIdentity) -> Unit
) {
    caches.catalogIdentity(mediaId)?.takeIf { isUsefulCatalogIdentity(it) }?.let {
        onResult(it)
        return
    }
    caches.removeCatalogIdentity(mediaId)
    val ownsRequest = dispatch.attachCatalogIdentityCallback(mediaId, onResult)
    if (!ownsRequest) return

    queryIdentityBase(mediaId) { currentSong ->
        currentSong?.isrc?.let { isrc ->
            ProviderLogger.info("Apple 内部歌曲 ISRC: id=$mediaId, isrc=$isrc")
            finishCatalogIdentity(
                mediaId,
                CatalogIdentity(
                    isrc = isrc,
                    fallbackAliases = listOfNotNull(currentSong.alias),
                    genres = currentSong.genres,
                    artistIds = currentSong.artistIds,
                ),
            )
            return@queryIdentityBase
        }

        val fallbackAliases = mutableListOf<Alias>().apply {
            currentSong?.alias?.let(::add)
        }
        val fallbackGenres = mutableListOf<String>().apply {
            currentSong?.genres?.let(::addAll)
        }
        fun queryNext(index: Int) {
            if (index >= languages.size) {
                finishCatalogIdentity(
                    mediaId,
                    CatalogIdentity(
                        isrc = null,
                        fallbackAliases = fallbackAliases,
                        genres = fallbackGenres,
                        artistIds = currentSong?.artistIds.orEmpty(),
                    ),
                )
                return
            }
            val language = languages[index]
            queryById(mediaId, language) { song ->
                song?.alias?.let(fallbackAliases::add)
                song?.genres?.let(fallbackGenres::addAll)
                val isrc = song?.isrc
                if (isrc != null) {
                    ProviderLogger.info(
                        "Apple 内部歌曲 ISRC: id=$mediaId, language=$language, isrc=$isrc"
                    )
                    finishCatalogIdentity(
                        mediaId,
                        CatalogIdentity(
                            isrc = isrc,
                            fallbackAliases = fallbackAliases,
                            genres = fallbackGenres.distinct(),
                            artistIds = (
                                currentSong?.artistIds.orEmpty() + song.artistIds
                            ).distinct(),
                        ),
                    )
                } else {
                    queryNext(index + 1)
                }
            }
        }
        queryNext(0)
    }
}

internal fun AppleInternalCatalogResolver.rememberCatalogIdentity(mediaId: String, song: CatalogSong) {
    val isrc = song.isrc ?: return
    val identity = CatalogIdentity(
        isrc = isrc,
        fallbackAliases = listOfNotNull(song.alias),
        genres = song.genres,
        artistIds = song.artistIds,
    )
    finishCatalogIdentity(mediaId, identity)
}

internal fun AppleInternalCatalogResolver.finishCatalogIdentity(mediaId: String, identity: CatalogIdentity) {
    val previous = caches.catalogIdentity(mediaId)
    val merged = if (previous == null) identity else {
        CatalogIdentity(
            isrc = previous.isrc ?: identity.isrc,
            fallbackAliases = (previous.fallbackAliases + identity.fallbackAliases).distinct(),
            genres = (previous.genres + identity.genres).distinct(),
            artistIds = (previous.artistIds + identity.artistIds).distinct(),
        )
    }
    val cacheable = isUsefulCatalogIdentity(merged)
    if (cacheable) {
        caches.putCatalogIdentity(mediaId, merged)
    } else {
        caches.removeCatalogIdentity(mediaId)
    }
    MediaMetadataCache.updateCatalogGenres(mediaId, merged.genres)
    val callbacks = dispatch.drainCatalogIdentityCallbacks(mediaId)
    if (callbacks.isNotEmpty()) {
        ProviderLogger.info(
            "Apple 内部歌曲身份已就绪: id=$mediaId, isrc=${merged.isrc}, " +
                "genres=${merged.genres}, " +
                "artistIds=${merged.artistIds}, candidates=${merged.fallbackAliases.size}, " +
                "cached=$cacheable"
        )
        callbacks.forEach { callback -> callback(merged) }
    }
}

internal fun AppleInternalCatalogResolver.isUsefulCatalogIdentity(identity: CatalogIdentity): Boolean =
    shouldCacheCatalogIdentity(identity.isrc, identity.genres)

internal fun AppleInternalCatalogResolver.queryById(
    mediaId: String,
    language: String?,
    onResult: (CatalogSong?) -> Unit
) {
    val queryParams = linkedMapOf(
        "ids" to mediaId,
        "platform" to "android",
        "include[songs]" to "artists"
    )
    language?.let { queryParams["l"] = it }
    query(
        storefront = language?.let { storefrontForLanguage(it) },
        language = language,
        description = "id=$mediaId",
        path = "songs",
        queryParams = queryParams,
        onResult = onResult
    )
}

/**
 * 身份链路的首个无语言查询（只为取 ISRC、流派和歌手 ID）。1611 的 amp-api 拦截器 `y9.d.a`
 * 持锁执行 `proceed`，同一客户端的请求全部串行；逐首单 ID 查询会把 Apple 页面请求挤在后面，
 * 新页面长时间白屏。请求先在 [IDENTITY_BASE_BATCH_WINDOW_MS] 内收集，拿到全局目录许可时才取批
 * （最多 [IDENTITY_BASE_BATCH_SIZE] 个 ID），等待许可期间到达的 ID 并入同一批，再按 ID 分发结果。
 */
internal fun AppleInternalCatalogResolver.queryIdentityBase(
    mediaId: String,
    onResult: (CatalogSong?) -> Unit,
) {
    if (dispatch.submitIdentityBaseQuery(mediaId, onResult)) {
        mainHandler.postDelayed(
            { catalogRequestGate.submit { release -> runIdentityBaseBatch(release) } },
            IDENTITY_BASE_BATCH_WINDOW_MS,
        )
    }
}

private fun AppleInternalCatalogResolver.runIdentityBaseBatch(release: () -> Unit) {
    val (batch, hasMore) = dispatch.takeIdentityBaseQueryBatch(IDENTITY_BASE_BATCH_SIZE)
    if (hasMore) catalogRequestGate.submit { next -> runIdentityBaseBatch(next) }
    if (batch.isEmpty()) {
        release()
        return
    }
    val ids = batch.keys.toList()
    val description = "identity-ids=${ids.size}"
    val queryParams = linkedMapOf(
        "ids" to ids.joinToString(","),
        "platform" to "android",
        "include[songs]" to "artists",
    )
    performQueryResponse(
        storefront = null,
        language = null,
        description = description,
        path = "songs",
        queryParams = queryParams,
        diagnosticRequestId = logCatalogRequestQueued(description, null, null),
        queuedAtMs = SystemClock.uptimeMillis(),
        release = release,
    ) { response ->
        val songs = runCatching {
            response?.let { parseCatalogSongs(it, CURRENT_LANGUAGE) }
        }.onFailure { error ->
            ProviderLogger.error("Apple 内部歌曲身份批量响应解析失败: ids=${ids.size}", error)
        }.getOrNull()
        ProviderLogger.info(
            "Apple 内部歌曲身份批量查询: requested=${ids.size}, " +
                "resolved=${songs?.size ?: 0}, responded=${songs != null}"
        )
        // 一批约 50 首的身份结果逐首同步续接原名查询，单条消息可达 33 ms（221047）。
        batch.forEach { (id, callbacks) ->
            val song = selectIdentityBaseSong(id, ids.size, songs)
            callbacks.forEach { callback -> resultDelivery.enqueue { callback(song) } }
        }
    }
}

internal fun AppleInternalCatalogResolver.queryByConfiguredRegion(
    mediaIds: List<String>,
    entityType: LocalizedEntityType,
    storefront: String,
    language: String,
    onResult: (Map<String, CatalogSong>) -> Unit,
) = queryByConfiguredRegionResult(mediaIds, entityType, storefront, language) { songs ->
    onResult(songs.orEmpty())
}

/**
 * 同 [queryByConfiguredRegion]，但请求失败、超时或响应解析失败时回调 null，以区分目录空结果。
 * 传入 [release] 表示调用方已持有全局目录许可，直接发出请求并在结束时归还。
 */
internal fun AppleInternalCatalogResolver.queryByConfiguredRegionResult(
    mediaIds: List<String>,
    entityType: LocalizedEntityType,
    storefront: String,
    language: String,
    release: (() -> Unit)? = null,
    onResult: (Map<String, CatalogSong>?) -> Unit,
) {
    val queryParams = linkedMapOf(
        "ids" to mediaIds.joinToString(","),
        "l" to language,
        "platform" to "android",
    )
    if (entityType != LocalizedEntityType.ARTIST) {
        queryParams["include[${entityType.path}]"] = "artists"
    }
    val description = "localized-${entityType.path}-ids=${mediaIds.size}"
    val handleResponse: (Any?) -> Unit = { response ->
        val songs = runCatching {
            response?.let { parseCatalogEntities(it, language, entityType) }
        }.onFailure { error ->
            ProviderLogger.error(
                "Apple 地区批量元数据响应解析失败: entityType=$entityType, " +
                    "ids=${mediaIds.size}, storefront=$storefront, language=$language",
                error,
            )
        }.getOrNull()
        val byId = songs.orEmpty().mapNotNull { song ->
            song.id?.let { it to song }
        }.toMap()
        byId.forEach { (id, song) -> rememberCatalogIdentity(id, song) }
        ProviderLogger.info(
            "Apple 地区批量元数据候选: entityType=$entityType, " +
                "requested=${mediaIds.size}, resolved=${byId.size}, " +
                "storefront=$storefront, language=$language, responded=${songs != null}"
        )
        onResult(byId.takeIf { songs != null })
    }
    if (release == null) {
        queryResponse(
            storefront = storefront,
            language = language,
            description = description,
            path = entityType.path,
            queryParams = queryParams,
            onResult = handleResponse,
        )
    } else {
        performQueryResponse(
            storefront = storefront,
            language = language,
            description = description,
            path = entityType.path,
            queryParams = queryParams,
            diagnosticRequestId = logCatalogRequestQueued(description, storefront, language),
            queuedAtMs = SystemClock.uptimeMillis(),
            release = release,
            onResult = handleResponse,
        )
    }
}

/**
 * ISRC 查原名。同一语言的请求在拿到全局目录许可时合并为 `filter[isrc]=a,b,...`
 * （最多 [ISRC_BATCH_SIZE] 个），减少经 amp-api 串行通道的请求数。
 */
internal fun AppleInternalCatalogResolver.queryByIsrc(
    isrc: String,
    language: String,
    onResult: (CatalogSong?) -> Unit
) {
    if (dispatch.submitIsrcQuery(language, isrc, onResult)) {
        mainHandler.postDelayed(
            { catalogRequestGate.submit { release -> runIsrcBatch(release) } },
            IDENTITY_BASE_BATCH_WINDOW_MS,
        )
    }
}

private fun AppleInternalCatalogResolver.runIsrcBatch(release: () -> Unit) {
    val (language, batch, hasMore) = dispatch.takeIsrcQueryBatch(ISRC_BATCH_SIZE)
    if (hasMore) catalogRequestGate.submit { next -> runIsrcBatch(next) }
    if (language == null || batch.isEmpty()) {
        release()
        return
    }
    val isrcs = batch.keys.toList()
    val single = isrcs.size == 1
    val description = if (single) "isrc=${isrcs.first()}" else "isrc-batch=${isrcs.size}"
    val queryParams = linkedMapOf(
        "filter[isrc]" to isrcs.joinToString(","),
        "l" to language,
        "platform" to "android",
        "include[songs]" to "artists",
    )
    if (single) queryParams["limit"] = "1"
    val storefront = runCatching { storefrontForLanguage(language) }.getOrElse { error ->
        release()
        ProviderLogger.error("Apple ISRC 批量查询地区不支持: language=$language", error)
        batch.values.flatten().forEach { callback -> callback(null) }
        return
    }
    performQueryResponse(
        storefront = storefront,
        language = language,
        description = description,
        path = "songs",
        queryParams = queryParams,
        diagnosticRequestId = logCatalogRequestQueued(description, storefront, language),
        queuedAtMs = SystemClock.uptimeMillis(),
        release = release,
    ) { response ->
        val songs = runCatching {
            response?.let { parseCatalogSongs(it, language) }
        }.onFailure { error ->
            ProviderLogger.error(
                "Apple ISRC 批量响应解析失败: isrcs=${isrcs.size}, language=$language",
                error,
            )
        }.getOrNull()
        ProviderLogger.info(
            "Apple 内部原名 ISRC 查询: requested=${isrcs.size}, resolved=${songs?.size ?: 0}, " +
                "language=$language, responded=${songs != null}"
        )
        batch.forEach { (isrc, callbacks) ->
            val song = selectIsrcBatchSong(isrc, isrcs.size, songs)
            if (song == null && songs != null && !single) {
                // 批量结果可能被分页截断：缺失项回退原单条查询，不当作无结果。
                callbacks.forEach { callback -> queryByIsrcSingle(isrc, language, callback) }
            } else {
                callbacks.forEach { callback -> callback(song) }
            }
        }
    }
}

/** 原单条 ISRC 查询（`limit=1`、取第一条），用于批量结果缺失时回退。 */
internal fun AppleInternalCatalogResolver.queryByIsrcSingle(
    isrc: String,
    language: String,
    onResult: (CatalogSong?) -> Unit
) {
    val queryParams = linkedMapOf(
        "filter[isrc]" to isrc,
        "l" to language,
        "platform" to "android",
        "include[songs]" to "artists",
        "limit" to "1"
    )
    query(
        storefront = storefrontForLanguage(language),
        language = language,
        description = "isrc=$isrc",
        path = "songs",
        queryParams = queryParams,
        onResult = onResult
    )
}

internal fun AppleInternalCatalogResolver.query(
    storefront: String?,
    language: String?,
    description: String,
    path: String,
    queryParams: Map<String, String>,
    onResult: (CatalogSong?) -> Unit
) {
    queryResponse(
        storefront = storefront,
        language = language,
        description = description,
        path = path,
        queryParams = queryParams,
    ) { response ->
        val song = runCatching {
            response?.let {
                parseCatalogSong(it, language ?: CURRENT_LANGUAGE)
            }
        }.onFailure { error ->
            ProviderLogger.error(
                "Apple 内部原名响应解析失败: $description, language=$language",
                error,
            )
        }.getOrNull()
        ProviderLogger.info(
            "Apple 内部原名候选: $description, storefront=$storefront, " +
                "language=$language, value=${song?.alias?.title}/${song?.alias?.artist}, " +
                "isrc=${song?.isrc}"
        )
        onResult(song)
    }
}

internal fun AppleInternalCatalogResolver.queryResponse(
    storefront: String?,
    language: String?,
    description: String,
    path: String,
    queryParams: Map<String, String>,
    onResult: (Any?) -> Unit,
) {
    val diagnosticRequestId = logCatalogRequestQueued(description, storefront, language)
    val queuedAtMs = SystemClock.uptimeMillis()
    catalogRequestGate.submit { release ->
        performQueryResponse(
            storefront = storefront,
            language = language,
            description = description,
            path = path,
            queryParams = queryParams,
            diagnosticRequestId = diagnosticRequestId,
            queuedAtMs = queuedAtMs,
            release = release,
            onResult = onResult,
        )
    }
}

internal fun AppleInternalCatalogResolver.logCatalogRequestQueued(
    description: String,
    storefront: String?,
    language: String?,
): String {
    val diagnosticRequestId = catalogDiagnosticSequence.incrementAndGet().toString(36)
    logCatalogRequestDiagnostic(
        requestId = diagnosticRequestId,
        event = "queued",
        description = description,
        storefront = storefront,
        language = language,
        elapsedMs = 0L,
        detail = if (AppleProviderEnvironment.diagnosticsEnabled) "gateWaiting=${catalogRequestGate.waitingCount}" else null,
    )
    return diagnosticRequestId
}

/**
 * 在已持有 [AppleInternalCatalogResolver.catalogRequestGate] 许可的主线程任务中发出请求；
 * 完成、失败或超时（先到者）调用 [release] 归还许可。超时从真正发出时开始计算。
 */
internal fun AppleInternalCatalogResolver.performQueryResponse(
    storefront: String?,
    language: String?,
    description: String,
    path: String,
    queryParams: Map<String, String>,
    diagnosticRequestId: String,
    queuedAtMs: Long,
    release: () -> Unit,
    onResult: (Any?) -> Unit,
) {
    run {
        var requestToken: String? = null
        val completed = AtomicBoolean(false)
        var slowResponse: Runnable? = null
        var timeout: Runnable? = null

        fun finish(response: Any?, event: String = "response") {
            release()
            if (!completed.compareAndSet(false, true)) {
                logCatalogRequestDiagnostic(
                    requestId = diagnosticRequestId,
                    event = "late_$event",
                    description = description,
                    storefront = storefront,
                    language = language,
                    requestToken = requestToken,
                    elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                    detail = if (AppleProviderEnvironment.diagnosticsEnabled) catalogResponseDiagnostic(response) else null,
                )
                return
            }
            slowResponse?.let(mainHandler::removeCallbacks)
            timeout?.let(mainHandler::removeCallbacks)
            requestToken?.let(pendingCatalogRequests::remove)
            logCatalogRequestDiagnostic(
                requestId = diagnosticRequestId,
                event = event,
                description = description,
                storefront = storefront,
                language = language,
                requestToken = requestToken,
                elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                detail = if (AppleProviderEnvironment.diagnosticsEnabled) catalogResponseDiagnostic(response) else null,
            )
            onResult(response)
        }

        fun fail(event: String, error: Throwable) {
            release()
            if (!completed.compareAndSet(false, true)) {
                logCatalogRequestDiagnostic(
                    requestId = diagnosticRequestId,
                    event = "late_$event",
                    description = description,
                    storefront = storefront,
                    language = language,
                    requestToken = requestToken,
                    elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                    detail = "error=${error.javaClass.name}:${error.message}",
                )
                return
            }
            slowResponse?.let(mainHandler::removeCallbacks)
            timeout?.let(mainHandler::removeCallbacks)
            requestToken?.let(pendingCatalogRequests::remove)
            ProviderLogger.error(
                "Apple 内部目录直连查询失败: $description, language=$language",
                error,
            )
            logCatalogRequestDiagnostic(
                requestId = diagnosticRequestId,
                event = event,
                description = description,
                storefront = storefront,
                language = language,
                requestToken = requestToken,
                elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                detail = "error=${error.javaClass.name}:${error.message}",
            )
            onResult(null)
        }

        runCatching {
            val access = catalogAccess ?: createCatalogAccess().also { catalogAccess = it }
            val localization = if (storefront != null && language != null) {
                CatalogRequestLocalization(storefront, language)
            } else {
                null
            }
            if (localization != null) {
                captureAccountStorefront(access)
                requestToken = catalogRequestSequence.incrementAndGet().toString(36)
                pendingCatalogRequests[requestToken] = localization
            }
            val directQueryParams = LinkedHashMap(queryParams)
            requestToken?.let { token ->
                directQueryParams[CATALOG_REQUEST_TOKEN_PARAM] = token
            }
            slowResponse = Runnable {
                if (completed.get()) return@Runnable
                logCatalogRequestDiagnostic(
                    requestId = diagnosticRequestId,
                    event = "slow_response",
                    description = description,
                    storefront = storefront,
                    language = language,
                    requestToken = requestToken,
                    elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                    detail = "mode=direct-network, slowMs=$QUERY_SLOW_RESPONSE_MS",
                )
            }.also { mainHandler.postDelayed(it, QUERY_SLOW_RESPONSE_MS) }
            timeout = Runnable {
                release()
                if (!completed.compareAndSet(false, true)) return@Runnable
                requestToken?.let(pendingCatalogRequests::remove)
                logCatalogRequestDiagnostic(
                    requestId = diagnosticRequestId,
                    event = "timeout",
                    description = description,
                    storefront = storefront,
                    language = language,
                    requestToken = requestToken,
                    elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                    detail = "mode=direct-network, timeoutMs=$QUERY_TIMEOUT_MS",
                )
                onResult(null)
            }.also { mainHandler.postDelayed(it, QUERY_TIMEOUT_MS) }

            val continuation = createDirectCatalogContinuation(
                access = access,
                onSuccess = { response -> finish(response) },
                onFailure = { error -> fail("request_failed", error) },
            )
            val previousStorefront = access.storefrontField.get(access.mediaApi) as? String
            val directResult = try {
                activeCatalogRequest.set(localization)
                localization?.let {
                    access.storefrontField.set(access.mediaApi, it.storefront)
                }
                access.directQueryMethod.invoke(
                    access.mediaApi,
                    path,
                    directQueryParams,
                    continuation,
                )
            } finally {
                activeCatalogRequest.remove()
                if (localization != null) {
                    access.storefrontField.set(access.mediaApi, previousStorefront)
                }
            }
            logCatalogRequestDiagnostic(
                requestId = diagnosticRequestId,
                event = "observing",
                description = description,
                storefront = storefront,
                language = language,
                requestToken = requestToken,
                elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                detail = "mode=direct-network, path=$path, " +
                    "suspended=${isCoroutineSuspended(directResult)}",
            )
            if (!isCoroutineSuspended(directResult)) {
                finish(directResult)
            }
        }.onFailure { error ->
            activeCatalogRequest.remove()
            fail("start_failed", error)
        }
    }
}

internal fun AppleInternalCatalogResolver.createDirectCatalogContinuation(
    access: CatalogAccess,
    onSuccess: (Any?) -> Unit,
    onFailure: (Throwable) -> Unit,
): Any = Proxy.newProxyInstance(
    access.continuationType.classLoader ?: classLoader,
    arrayOf(access.continuationType),
) { proxy, method, args ->
    when (method.name) {
        "getContext" -> access.emptyCoroutineContext
        "resumeWith" -> {
            val result = args?.firstOrNull()
            mainHandler.post {
                AppleMainThreadCost.sectionWithLog("catalog_response", "catalog_response_log") {
                    val failure = coroutineResultFailure(result)
                    if (failure != null) onFailure(failure) else onSuccess(result)
                }
            }
            null
        }
        "equals" -> proxy === args?.firstOrNull()
        "hashCode" -> System.identityHashCode(proxy)
        "toString" -> "AppleCatalogContinuation"
        else -> null
    }
}

internal fun AppleInternalCatalogResolver.coroutineResultFailure(result: Any?): Throwable? {
    if (result is Throwable) return result
    val value = result ?: return null
    val fields = value.javaClass.declaredFields.filterNot { field ->
        Modifier.isStatic(field.modifiers)
    }
    if (fields.size != 1) return null
    val field = fields.single()
    if (!Throwable::class.java.isAssignableFrom(field.type)) return null
    field.isAccessible = true
    return field.get(value) as? Throwable
}

/**
 * 单 ID 批次保持原单条查询语义（取响应第一条）；多 ID 批次按实体 ID 精确匹配，
 * 未返回的 ID 视为该首歌查询无结果。
 */
internal fun selectIdentityBaseSong(
    mediaId: String,
    requestedCount: Int,
    songs: List<CatalogSong>?,
): CatalogSong? {
    songs ?: return null
    return if (requestedCount == 1) songs.firstOrNull() else songs.firstOrNull { it.id == mediaId }
}

internal const val IDENTITY_BASE_BATCH_SIZE = 50
internal const val IDENTITY_BASE_BATCH_WINDOW_MS = 50L

/** 单个 ISRC 的批次保持原语义（取第一条）；多个时按 ISRC 精确匹配首条。 */
internal fun selectIsrcBatchSong(
    isrc: String,
    requestedCount: Int,
    songs: List<CatalogSong>?,
): CatalogSong? {
    songs ?: return null
    return if (requestedCount == 1) {
        songs.firstOrNull()
    } else {
        songs.firstOrNull { it.isrc.equals(isrc, ignoreCase = true) }
    }
}

internal const val ISRC_BATCH_SIZE = 25
