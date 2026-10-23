package com.juren233.hle.providers.applemusic.xposed

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.contract.AppleMusicPreferenceKeys
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class AppleInternalCatalogResolver(
    context: Context,
    internal val classLoader: ClassLoader,
    internal val hookResolver: AppleMusicHookResolver,
    internal val mainHandler: Handler
) {
    internal val dispatch = AppleInternalCatalogDispatch()
    /** Apple 自身（无模块标记）经 amp-api 拦截器的请求在途数，含等待拦截器锁的请求。 */
    internal val nativeAmpRequestsInFlight = AtomicInteger()
    /** 模块全部目录请求共用的单许可门，Apple 自身请求在途时让行，见 [AppleCatalogRequestGate]。 */
    internal val catalogRequestGate = AppleCatalogRequestGate(
        dispatch = { mainHandler.post(it) },
        dispatchDelayed = { runnable, delayMs -> mainHandler.postDelayed(runnable, delayMs) },
        shouldYield = { nativeAmpRequestsInFlight.get() > 0 },
        clock = SystemClock::uptimeMillis,
    )
    internal val caches = AppleInternalCatalogCaches()
    /** 网络查询结果的回调按预算分批在主线程执行；缓存命中仍同步回调。 */
    internal val resultDelivery = AppleBudgetedResultQueue(post = { mainHandler.post(it) })
    internal val persistentLocalizedCache = AppleLocalizedMetadataCache(context, mainHandler)
    internal val persistentOriginalCache = AppleOriginalMetadataCache(context, mainHandler)
    internal val resolvedCatalogHolder by lazy {
        hookResolver.resolveClass(AppleMusicHookPoint.MEDIA_API_REPOSITORY_HOLDER_CLASS)
    }
    internal var catalogAccess: CatalogAccess? = null
    @Volatile
    internal var accountStorefront: String? = null
    @Volatile
    internal var accountStorefrontCaptured = false
    @Volatile
    internal var lastAppliedConfiguredStorefront: String? = null
    internal val activeCatalogRequest = ThreadLocal<CatalogRequestLocalization?>()
    internal val pendingCatalogRequests = ConcurrentHashMap<String, CatalogRequestLocalization>()
    internal val catalogRequestSequence = AtomicLong()
    internal val catalogDiagnosticSequence = AtomicLong()
    @Volatile
    internal var contentUiLanguageSelection =
        AppleMusicPreferenceKeys.DEFAULT_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE
    // 6.5.3 起 Application.onCreate 阶段 MediaApi 尚未就绪（applicationConnector 未初始化），
    // storefront 应用需要延迟重试；门控与尝试计数供重试调度跨线程防重入。
    internal val storefrontApplyRetryGate = AtomicBoolean()
    internal val storefrontApplyRetryAttempts = AtomicInteger()
}
