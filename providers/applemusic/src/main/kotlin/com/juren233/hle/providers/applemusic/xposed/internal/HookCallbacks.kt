/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.internal

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCall
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderInterceptor
import com.juren233.hle.providers.applemusic.xposed.AppleMusicOptimizationGate
import com.juren233.hle.providers.applemusic.xposed.ProviderLogger

/**
 * 入口门控：Apple Music 体验优化入口关闭时，Hook 回调直接走原生实现。
 */
internal class EntryGatedHooker(
    private val delegate: OfficialProviderInterceptor,
) : OfficialProviderInterceptor {
    override fun intercept(chain: OfficialProviderCall): Any? =
        if (AppleMusicOptimizationGate.isEnabled()) {
            delegate.intercept(chain)
        } else {
            chain.proceed()
        }
}

internal class CallbackHook(
    private val before: ((OfficialProviderCall) -> Unit)?,
    private val after: ((OfficialProviderCall, Any?) -> Unit)?,
) : OfficialProviderInterceptor {
    override fun intercept(chain: OfficialProviderCall): Any? {
        runCatching { before?.invoke(chain) }
            .onFailure { ProviderLogger.error("Apple Music Hook 前置回调失败", it) }
        val result = chain.proceed()
        runCatching { after?.invoke(chain, result) }
            .onFailure { ProviderLogger.error("Apple Music Hook 后置回调失败", it) }
        return result
    }
}

internal class ScopedCallbackHook(
    private val enter: (OfficialProviderCall) -> Boolean,
    private val after: (OfficialProviderCall, Any?) -> Unit,
    private val exit: () -> Unit,
) : OfficialProviderInterceptor {
    override fun intercept(chain: OfficialProviderCall): Any? {
        val entered = runCatching { enter(chain) }
            .onFailure { ProviderLogger.error("Apple Music Hook 作用域进入失败", it) }
            .getOrDefault(false)
        return try {
            val result = chain.proceed()
            if (entered) {
                runCatching { after(chain, result) }
                    .onFailure { ProviderLogger.error("Apple Music Hook 作用域回调失败", it) }
            }
            result
        } finally {
            if (entered) {
                runCatching(exit)
                    .onFailure { ProviderLogger.error("Apple Music Hook 作用域清理失败", it) }
            }
        }
    }
}

internal class ConditionalVoidSkipHook(
    private val shouldSkip: (OfficialProviderCall) -> Boolean,
) : OfficialProviderInterceptor {
    override fun intercept(chain: OfficialProviderCall): Any? {
        val skip = runCatching { shouldSkip(chain) }
            .onFailure {
                ProviderLogger.error("Apple Music Hook 条件跳过判断失败，继续原始调用", it)
            }
            .getOrDefault(false)
        return if (skip) null else chain.proceed()
    }
}

/**
 * 非 void 方法的条件跳过：命中时返回 [skippedResult] 构造的值（调用方仍拿到原方法语义的
 * 返回值，例如 fluent 接口的 thisObject），否则执行原始调用。
 */
internal class ConditionalSkipHook(
    private val shouldSkip: (OfficialProviderCall) -> Boolean,
    private val skippedResult: (OfficialProviderCall) -> Any?,
) : OfficialProviderInterceptor {
    override fun intercept(chain: OfficialProviderCall): Any? {
        val skip = runCatching { shouldSkip(chain) }
            .onFailure {
                ProviderLogger.error("Apple Music Hook 条件跳过判断失败，继续原始调用", it)
            }
            .getOrDefault(false)
        if (!skip) return chain.proceed()
        return runCatching { skippedResult(chain) }
            .onFailure {
                ProviderLogger.error("Apple Music Hook 跳过返回值构造失败，回退原始调用", it)
            }
            .getOrElse { chain.proceed() }
    }
}

internal class ResultOverrideHook(
    private val override: (OfficialProviderCall, Any?) -> Any?,
) : OfficialProviderInterceptor {
    override fun intercept(chain: OfficialProviderCall): Any? {
        val original = chain.proceed()
        return runCatching { override(chain, original) }
            .onFailure { ProviderLogger.error("Apple Music Hook 结果覆盖失败", it) }
            .getOrDefault(original)
    }
}

internal class ArgumentRewriteHook(
    private val rewrite: (OfficialProviderCall) -> Array<Any?>?,
) : OfficialProviderInterceptor {
    override fun intercept(chain: OfficialProviderCall): Any? {
        val rewritten = runCatching { rewrite(chain) }
            .onFailure { ProviderLogger.error("Apple Music Hook 参数改写失败", it) }
            .getOrNull()
        return if (rewritten == null) {
            chain.proceed()
        } else {
            chain.proceed(rewritten)
        }
    }
}
