/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.hooks

import com.juren233.hle.providers.applemusic.xposed.AppleProviderEnvironment

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCall
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderHost
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderInterceptor
import com.juren233.hle.providers.applemusic.xposed.ProviderLogger
import com.juren233.hle.providers.applemusic.xposed.AppleMusicDexKitWatchdog
import com.juren233.hle.providers.applemusic.xposed.AppleMainThreadCost
import com.juren233.hle.providers.applemusic.xposed.internal.ArgumentRewriteHook
import com.juren233.hle.providers.applemusic.xposed.internal.CallbackHook
import com.juren233.hle.providers.applemusic.xposed.internal.ConditionalSkipHook
import com.juren233.hle.providers.applemusic.xposed.internal.ConditionalVoidSkipHook
import com.juren233.hle.providers.applemusic.xposed.internal.EntryGatedHooker
import com.juren233.hle.providers.applemusic.xposed.internal.ResultOverrideHook
import com.juren233.hle.providers.applemusic.xposed.internal.ScopedCallbackHook
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal class AppleHookRegistrar(
    private val host: OfficialProviderHost,
) {
    private val activeModuleId = ThreadLocal<String?>()

    /** debug 包专用：按模块累计 `hook()` 本身与安装日志的耗时，用于拆分冷启动开销。 */
    val installCost = AppleHookInstallCost()
    private val callbackTracer = AppleHookCallbackTracer(
        withModule = { moduleId, block -> withModule(moduleId, block) },
        onFirstCallback = { moduleId, executable ->
            ProviderLogger.diagnostic(
                "Apple Music Hook 首次回调: module=$moduleId, target=$executable"
            )
        },
    )

    fun <T> withModule(moduleId: String, block: () -> T): T {
        val previous = activeModuleId.get()
        activeModuleId.set(moduleId)
        return try {
            block()
        } finally {
            if (previous == null) activeModuleId.remove() else activeModuleId.set(previous)
        }
    }

    fun installHook(
        executable: Executable,
        before: ((OfficialProviderCall) -> Unit)? = null,
        after: ((OfficialProviderCall, Any?) -> Unit)? = null,
        gated: Boolean = true,
    ) = installHooker(executable, CallbackHook(before, after), gated = gated)

    fun installScopedHook(
        executable: Executable,
        enter: (OfficialProviderCall) -> Boolean,
        after: (OfficialProviderCall, Any?) -> Unit,
        exit: () -> Unit,
    ) = installHooker(executable, ScopedCallbackHook(enter, after, exit))

    fun installConditionalVoidSkipHook(
        executable: Executable,
        shouldSkip: (OfficialProviderCall) -> Boolean,
    ) {
        require(executable is Method && executable.returnType == Void.TYPE) {
            "Conditional skip hooks require a void method: $executable"
        }
        installHooker(executable, ConditionalVoidSkipHook(shouldSkip))
    }

    /** 非 void 方法的条件跳过；[skippedResult] 给出命中时返回给调用方的值。 */
    fun installConditionalSkipHook(
        executable: Executable,
        shouldSkip: (OfficialProviderCall) -> Boolean,
        skippedResult: (OfficialProviderCall) -> Any?,
    ) = installHooker(executable, ConditionalSkipHook(shouldSkip, skippedResult))

    fun installResultOverrideHook(
        executable: Executable,
        override: (OfficialProviderCall, Any?) -> Any?,
    ) = installHooker(executable, ResultOverrideHook(override))

    fun installArgumentRewriteHook(
        executable: Executable,
        gated: Boolean = true,
        rewrite: (OfficialProviderCall) -> Array<Any?>?,
    ) = installHooker(executable, ArgumentRewriteHook(rewrite), gated = gated)

    private fun installHooker(executable: Executable, hooker: OfficialProviderInterceptor, gated: Boolean = true) {
        val moduleId = activeModuleId.get() ?: "unscoped"
        // 入口关闭时体验优化 Hook 让位；歌词/播放数据路径显式 gated=false 保持运行。
        val effectiveHooker = if (gated) EntryGatedHooker(hooker) else hooker
        var current = executable
        var retried = false
        while (true) {
            try {
                // 不对 Hook 目标本身 deoptimize：LSPlant 会把其备份（原方法体）固定为解释执行，
                // 却挡不住目标被内联进调用方。内联绕过 Hook 时应像 AppleMediaLaunchHooks 那样
                // 只对已核实的调用方 deoptimize。
                val hookStartNs = if (AppleProviderEnvironment.diagnosticsEnabled) System.nanoTime() else 0L
                host.intercept(
                    current,
                    if (AppleProviderEnvironment.diagnosticsEnabled) {
                        callbackTracer.wrap(moduleId, current, effectiveHooker)
                    } else {
                        effectiveHooker
                    },
                )
                AppleMusicDexKitWatchdog.hookInstalled(current)
                if (AppleProviderEnvironment.diagnosticsEnabled) {
                    val logStartNs = System.nanoTime()
                    ProviderLogger.diagnostic(
                        "Apple Music Hook 已安装: module=$moduleId, target=$current"
                    )
                    installCost.record(
                        moduleId,
                        hookNs = logStartNs - hookStartNs,
                        logNs = System.nanoTime() - logStartNs,
                    )
                }
                return
            } catch (failure: Throwable) {
                val replacement = if (!retried) {
                    AppleMusicDexKitWatchdog.hookInstallFailed(current, failure)
                } else {
                    null
                }
                if (replacement == null) throw failure
                ProviderLogger.info(
                    "Apple Music DexKit 方法安装失败后重试: " +
                        "old=${current.toGenericString()}, new=${replacement.toGenericString()}",
                )
                current = replacement
                retried = true
            }
        }
    }
}

internal class AppleHookInstallCost {
    class Entry(var hookNs: Long = 0L, var logNs: Long = 0L, var hooks: Int = 0)

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun record(moduleId: String, hookNs: Long, logNs: Long) {
        val entry = entries.getOrPut(moduleId) { Entry() }
        entry.hookNs += hookNs
        entry.logNs += logNs
        entry.hooks += 1
    }

    @Synchronized
    fun snapshot(moduleId: String): Entry =
        entries[moduleId]?.let { Entry(it.hookNs, it.logNs, it.hooks) } ?: Entry()
}

/** 记录 `proceed` 执行原方法的耗时，供 [AppleMainThreadCost] 扣除。 */
private class TimedChain(
    private val delegate: OfficialProviderCall,
    private val token: Any,
) : OfficialProviderCall by delegate {
    override fun proceed(): Any? = timed { delegate.proceed() }

    override fun proceed(args: Array<out Any?>): Any? = timed { delegate.proceed(args) }

    override fun proceedWith(thisObject: Any): Any? = timed { delegate.proceedWith(thisObject) }

    override fun proceedWith(thisObject: Any, args: Array<out Any?>): Any? =
        timed { delegate.proceedWith(thisObject, args) }

    private inline fun timed(block: () -> Any?): Any? {
        AppleMainThreadCost.proceedStarted(token)
        val startNs = System.nanoTime()
        try {
            return block()
        } finally {
            AppleMainThreadCost.proceedFinished(token, System.nanoTime() - startNs)
        }
    }
}

internal class AppleHookCallbackTracer(
    private val withModule: (String, () -> Any?) -> Any?,
    private val onFirstCallback: (String, Executable) -> Unit,
) {
    private val firstCallbackTargets = ConcurrentHashMap.newKeySet<Pair<String, Executable>>()

    fun wrap(
        moduleId: String,
        executable: Executable,
        delegate: OfficialProviderInterceptor,
    ): OfficialProviderInterceptor = object : OfficialProviderInterceptor {
        override fun intercept(chain: OfficialProviderCall): Any? = withModule(moduleId) {
            AppleMusicDexKitWatchdog.callback(executable)
            if (firstCallbackTargets.add(moduleId to executable)) {
                onFirstCallback(moduleId, executable)
            }
            val costToken = AppleMainThreadCost.enterHook()
            val startNs = if (costToken != null) System.nanoTime() else 0L
            val timedChain = if (costToken != null) TimedChain(chain, costToken) else chain
            runCatching { delegate.intercept(timedChain) }
                .also {
                    if (costToken != null) {
                        AppleMainThreadCost.exitHook(
                            costToken,
                            "$moduleId#${executable.declaringClass.simpleName}.${executable.name}",
                            System.nanoTime() - startNs,
                        )
                    }
                }
                .onSuccess {
                    AppleMusicDexKitWatchdog.validation(
                        executable = executable,
                        valid = true,
                        detail = "module=$moduleId callback_completed",
                    )
                }
                .onFailure { error ->
                    AppleMusicDexKitWatchdog.validation(
                        executable = executable,
                        valid = false,
                        detail = "module=$moduleId ${error::class.java.simpleName}: ${error.message}",
                    )
                }
                .getOrThrow()
        }
    }
}
