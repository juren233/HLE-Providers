package com.juren233.hle.providers.applemusic.xposed

import android.content.Context
import com.juren233.hle.providers.applemusic.xposed.util.ChineseUtils

internal object AppleLyricTextTransform {
    private const val MAX_SIMPLIFIED_CACHE_ENTRIES = 1024
    private const val DICTIONARY_WARMUP_TEXT = "繁"

    private val rawReadDepth = ThreadLocal.withInitial { 0 }

    /**
     * 繁转简结果缓存：词组最长匹配每个字都要截取多段子串查表，而同一批歌名在 getter、
     * 列表刷新与通知里会被反复转换。字典加载后不变，结果只取决于输入文本。
     */
    private val simplifiedCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_SIMPLIFIED_CACHE_ENTRIES
    }

    @Volatile
    private var context: Context? = null

    @Volatile
    private var enabled: (() -> Boolean)? = null

    fun initialize(context: Context, enabled: () -> Boolean) {
        val appContext = context.applicationContext
        this.context = appContext
        this.enabled = enabled
        // 字典首次使用时才从模块 APK 读取并解析，若首次转换发生在主线程会整段阻塞
        // （221046 冷启动首页卡片预处理出现一次 66 ms，疑为此）；开关已开启时提前在后台加载。
        if (enabled()) {
            Thread({
                val startNs = System.nanoTime()
                ChineseUtils.toSimplified(appContext, DICTIONARY_WARMUP_TEXT)
                if (AppleProviderEnvironment.diagnosticsEnabled) {
                    ProviderLogger.info(
                        "[ZhDict] 后台预加载完成: ms=${(System.nanoTime() - startNs) / 1_000_000}"
                    )
                }
            }, "HLE-ZhDict").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }.start()
        }
    }

    fun transform(text: String?): String? {
        text ?: return null
        if (isRawReadActive() || enabled?.invoke() != true) return text
        val currentContext = context ?: return text
        synchronized(simplifiedCache) { simplifiedCache[text] }?.let { return it }
        val simplified = ChineseUtils.toSimplified(currentContext, text)
        synchronized(simplifiedCache) { simplifiedCache[text] = simplified }
        return simplified
    }

    /**
     * 展示边界使用：开启繁转简时把解析到的繁体歌名/歌手名/专辑名转成简体。
     * 只用于写入 App UI、通知与框架元数据；持久化原名缓存必须保留原值，
     * 否则关闭开关无法恢复，也会改变在线检索使用的原名键。
     */
    fun displayAlias(alias: Alias): Alias = alias.copy(
        title = transform(alias.title).orEmpty(),
        artist = transform(alias.artist).orEmpty(),
        album = transform(alias.album).orEmpty(),
    )

    fun isTransformEnabled(): Boolean = enabled?.invoke() == true

    fun isRawReadActive(): Boolean = (rawReadDepth.get() ?: 0) > 0

    fun <T> withRawReads(block: () -> T): T {
        rawReadDepth.set((rawReadDepth.get() ?: 0) + 1)
        return try {
            block()
        } finally {
            val nextDepth = (rawReadDepth.get() ?: 1) - 1
            if (nextDepth == 0) rawReadDepth.remove() else rawReadDepth.set(nextDepth)
        }
    }
}
