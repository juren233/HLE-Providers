/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

import android.os.Handler
import android.os.SystemClock
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface SpotifyLyricsFallbackCancellation {
    fun cancel()
}

internal interface SpotifyLyricsFallbackScheduler {
    fun nowMs(): Long

    fun schedule(
        delayMs: Long,
        action: () -> Unit,
    ): SpotifyLyricsFallbackCancellation
}

internal fun interface SpotifyLyricsFallbackRequestStarter<Client> {
    fun start(
        client: Client,
        trackUri: String,
        onSuccess: (Any) -> Unit,
        onError: (Throwable) -> Unit,
    ): SpotifyLyricsFallbackCancellation
}

/**
 * 每首歌最多发起一次的 Spotify 官方歌词主动请求兜底。
 *
 * 该状态机不猜测 Spotify 对象图：客户端只能来自已验证的 am80/lg80 构造实例，
 * 并由 Spotify 自己的 enable_v3_lyrics_endpoint 选择结果决定使用哪一个。
 * 所有调用均应在同一调度线程执行；生产环境使用 Spotify 主线程 Handler。
 */
internal class SpotifyLyricsFallbackCoordinator<Client>(
    private val scheduler: SpotifyLyricsFallbackScheduler,
    private val requestDelayMs: Long,
    private val requestStarter: SpotifyLyricsFallbackRequestStarter<Client>,
    private val onSuccess: (trackUri: String, value: Any) -> Unit,
    private val onFailure: (trackUri: String, error: Throwable) -> Unit = { _, _ -> },
    private val onScheduled: (trackUri: String, delayMs: Long) -> Unit = { _, _ -> },
    private val onRequestStarted: (trackUri: String) -> Unit = {},
) {
    private var generation = 0L
    private var hasTrack = false
    private var currentTrackUri: String? = null
    private var firstSeenAtMs = 0L
    private var client: Client? = null
    private var attempted = false
    private var lyricsAvailable = false
    private var scheduledRequest: SpotifyLyricsFallbackCancellation? = null
    private var activeRequest: SpotifyLyricsFallbackCancellation? = null
    private var activeRequestGeneration: Long? = null

    init {
        require(requestDelayMs >= 0L)
    }

    fun onTrackChanged(trackUri: String?) {
        generation += 1
        cancelPendingAndActiveRequest()
        hasTrack = true
        currentTrackUri = SpotifyTrackIdentity.requestUri(trackUri)
        firstSeenAtMs = scheduler.nowMs()
        attempted = false
        lyricsAvailable = false
        scheduleIfNeeded()
    }

    fun onTrackMetadataUpdated(trackUri: String?) {
        if (!hasTrack) return
        SpotifyTrackIdentity.requestUri(trackUri)?.let { currentTrackUri = it }
        scheduleIfNeeded()
    }

    fun clearTrack() {
        generation += 1
        cancelPendingAndActiveRequest()
        hasTrack = false
        currentTrackUri = null
        attempted = false
        lyricsAvailable = false
    }

    fun onClientAvailable(value: Client) {
        client = value
        scheduleIfNeeded()
    }

    fun onLyricsAvailable(trackUri: String) {
        val normalized = SpotifyTrackIdentity.requestUri(trackUri) ?: return
        if (!hasTrack || normalized != currentTrackUri) return
        lyricsAvailable = true
        cancelPendingAndActiveRequest()
    }

    private fun scheduleIfNeeded() {
        if (!hasTrack || attempted || lyricsAvailable) return
        if (scheduledRequest != null || activeRequestGeneration != null) return
        val trackUri = currentTrackUri ?: return
        client ?: return
        val requestGeneration = generation
        val elapsed = (scheduler.nowMs() - firstSeenAtMs).coerceAtLeast(0L)
        val remainingDelay = (requestDelayMs - elapsed).coerceAtLeast(0L)
        scheduledRequest = scheduler.schedule(remainingDelay) {
            startRequest(requestGeneration, trackUri)
        }
        onScheduled(trackUri, remainingDelay)
    }

    private fun startRequest(
        requestGeneration: Long,
        trackUri: String,
    ) {
        scheduledRequest = null
        if (
            requestGeneration != generation ||
            !hasTrack ||
            attempted ||
            lyricsAvailable ||
            trackUri != currentTrackUri
        ) {
            return
        }
        val activeClient = client ?: return
        attempted = true
        activeRequestGeneration = requestGeneration
        onRequestStarted(trackUri)
        val cancellation = runCatching {
            requestStarter.start(
                client = activeClient,
                trackUri = trackUri,
                onSuccess = { value -> onRequestSuccess(requestGeneration, trackUri, value) },
                onError = { error -> onRequestFailure(requestGeneration, trackUri, error) },
            )
        }.getOrElse { error ->
            activeRequestGeneration = null
            onFailure(trackUri, error)
            return
        }
        if (activeRequestGeneration == requestGeneration) {
            activeRequest = cancellation
        } else {
            cancellation.cancel()
        }
    }

    private fun onRequestSuccess(
        requestGeneration: Long,
        trackUri: String,
        value: Any,
    ) {
        if (
            activeRequestGeneration != requestGeneration ||
            requestGeneration != generation ||
            trackUri != currentTrackUri
        ) {
            return
        }
        activeRequestGeneration = null
        activeRequest = null
        onSuccess(trackUri, value)
    }

    private fun onRequestFailure(
        requestGeneration: Long,
        trackUri: String,
        error: Throwable,
    ) {
        if (
            activeRequestGeneration != requestGeneration ||
            requestGeneration != generation ||
            trackUri != currentTrackUri
        ) {
            return
        }
        activeRequestGeneration = null
        activeRequest = null
        onFailure(trackUri, error)
    }

    private fun cancelPendingAndActiveRequest() {
        scheduledRequest?.cancel()
        scheduledRequest = null
        activeRequestGeneration = null
        activeRequest?.cancel()
        activeRequest = null
    }
}

internal data class SpotifySelectedLyricsClient<Client>(
    val endpoint: SpotifyLyricsEndpoint,
    val client: Client,
)

/**
 * 合并两个独立到达的运行时事实：Spotify 已构造的 v2/v3 客户端，以及
 * enable_v3_lyrics_endpoint 的真实选择结果。只有二者对齐后才交给请求状态机。
 */
internal class SpotifyLyricsClientSelector<Client> {
    private val clients = mutableMapOf<SpotifyLyricsEndpoint, Client>()
    private var selectedEndpoint: SpotifyLyricsEndpoint? = null
    private var deliveredEndpoint: SpotifyLyricsEndpoint? = null
    private var deliveredClient: Client? = null

    @Synchronized
    fun onClientAvailable(
        endpoint: SpotifyLyricsEndpoint,
        client: Client,
    ): SpotifySelectedLyricsClient<Client>? {
        clients[endpoint] = client
        return selectionIfChanged()
    }

    @Synchronized
    fun onEndpointSelected(
        endpoint: SpotifyLyricsEndpoint,
    ): SpotifySelectedLyricsClient<Client>? {
        selectedEndpoint = endpoint
        return selectionIfChanged()
    }

    private fun selectionIfChanged(): SpotifySelectedLyricsClient<Client>? {
        val endpoint = selectedEndpoint ?: return null
        val client = clients[endpoint] ?: return null
        if (endpoint == deliveredEndpoint && client === deliveredClient) return null
        deliveredEndpoint = endpoint
        deliveredClient = client
        return SpotifySelectedLyricsClient(endpoint, client)
    }
}

internal class SpotifyHandlerLyricsFallbackScheduler(
    private val handler: Handler,
) : SpotifyLyricsFallbackScheduler {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()

    override fun schedule(
        delayMs: Long,
        action: () -> Unit,
    ): SpotifyLyricsFallbackCancellation {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMs)
        return SpotifyLyricsFallbackCancellation { handler.removeCallbacks(runnable) }
    }
}

/** 使用 Spotify 进程自身的 RxJava 类型主动订阅所选 am80/lg80 的 kg80.b。 */
internal object SpotifyLyricsClientRequester {
    private const val SINGLE_CLASS_NAME = "io.reactivex.rxjava3.core.Single"
    private const val CONSUMER_CLASS_NAME = "io.reactivex.rxjava3.functions.Consumer"
    private const val DISPOSABLE_CLASS_NAME = "io.reactivex.rxjava3.disposables.Disposable"

    fun start(
        client: Any,
        trackUri: String,
        onSuccess: (Any) -> Unit,
        onError: (Throwable) -> Unit,
    ): SpotifyLyricsFallbackCancellation {
        val classLoader = client.javaClass.classLoader
            ?: Thread.currentThread().contextClassLoader
        val clientInterface = Class.forName(
            SpotifyHookProfiles.LYRICS_CLIENT_INTERFACE,
            false,
            classLoader,
        )
        check(clientInterface.isInstance(client)) {
            "Captured object does not implement ${SpotifyHookProfiles.LYRICS_CLIENT_INTERFACE}"
        }
        val requestMethod = clientInterface.getMethod(
            "b",
            String::class.java,
            String::class.java,
        )
        check(requestMethod.returnType.name == SINGLE_CLASS_NAME) {
            "Unexpected kg80.b return type: ${requestMethod.returnType.name}"
        }
        val single = requestMethod.invoke(client, trackUri, null)
            ?: error("kg80.b returned null")
        val rxClassLoader = single.javaClass.classLoader ?: classLoader
        val consumerClass = Class.forName(CONSUMER_CLASS_NAME, false, rxClassLoader)
        val disposableClass = Class.forName(DISPOSABLE_CLASS_NAME, false, rxClassLoader)
        val subscribeMethod = single.javaClass.getMethod(
            "subscribe",
            consumerClass,
            consumerClass,
        )
        check(subscribeMethod.returnType.name == DISPOSABLE_CLASS_NAME) {
            "Unexpected Single.subscribe return type: ${subscribeMethod.returnType.name}"
        }
        val successConsumer = createConsumer(consumerClass, "Success") { value ->
            value?.let(onSuccess)
        }
        val errorConsumer = createConsumer(consumerClass, "Error") { value ->
            onError(value as? Throwable ?: IllegalStateException("RxJava error is not Throwable"))
        }
        val disposable = subscribeMethod.invoke(single, successConsumer, errorConsumer)
            ?: error("Single.subscribe returned null")
        check(disposableClass.isInstance(disposable)) {
            "Single.subscribe did not return Disposable"
        }
        val cancelled = AtomicBoolean(false)
        return SpotifyLyricsFallbackCancellation {
            if (cancelled.compareAndSet(false, true)) {
                runCatching { disposableClass.getMethod("dispose").invoke(disposable) }
            }
        }
    }

    private fun createConsumer(
        consumerClass: Class<*>,
        label: String,
        accept: (Any?) -> Unit,
    ): Any = Proxy.newProxyInstance(
        consumerClass.classLoader,
        arrayOf(consumerClass),
    ) { proxy, method, arguments ->
        when {
            method.name == "accept" && method.parameterCount == 1 -> {
                accept(arguments?.getOrNull(0))
                null
            }
            method.name == "toString" && method.parameterCount == 0 ->
                "HLESpotifyLyrics$label"
            method.name == "hashCode" && method.parameterCount == 0 ->
                System.identityHashCode(proxy)
            method.name == "equals" && method.parameterCount == 1 ->
                proxy === arguments?.getOrNull(0)
            else -> null
        }
    }
}
