/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.hooks

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderCall
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderInterceptor
import java.lang.reflect.Executable
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class AppleHookRegistrarTest {

    @Test
    fun `callback tracer deduplicates each module and executable pair`() {
        val firstCallbacks = mutableListOf<String>()
        val scopes = mutableListOf<String>()
        var delegateCalls = 0
        val tracer = AppleHookCallbackTracer(
            withModule = { moduleId, block ->
                scopes += "enter:$moduleId"
                try {
                    block()
                } finally {
                    scopes += "exit:$moduleId"
                }
            },
            onFirstCallback = { moduleId, executable ->
                firstCallbacks += "$moduleId:${executable.name}"
            },
        )
        val delegate = object : OfficialProviderInterceptor {
            override fun intercept(chain: OfficialProviderCall): Any? {
                delegateCalls += 1
                return chain.proceed()
            }
        }
        val firstModuleHook = tracer.wrap("module-a", TEST_EXECUTABLE, delegate)
        val secondModuleHook = tracer.wrap("module-b", TEST_EXECUTABLE, delegate)

        assertEquals("original", firstModuleHook.intercept(chain()))
        assertEquals("original", firstModuleHook.intercept(chain()))
        assertEquals("original", secondModuleHook.intercept(chain()))

        assertEquals(listOf("module-a:toString", "module-b:toString"), firstCallbacks)
        assertEquals(3, delegateCalls)
        assertEquals(
            listOf(
                "enter:module-a",
                "exit:module-a",
                "enter:module-a",
                "exit:module-a",
                "enter:module-b",
                "exit:module-b",
            ),
            scopes,
        )
    }

    @Test
    fun `callbacks in the same module are tracked separately for each executable`() {
        val hits = mutableListOf<String>()
        val tracer = AppleHookCallbackTracer(
            withModule = { _, block -> block() },
            onFirstCallback = { _, executable -> hits += executable.name },
        )
        val delegate = object : OfficialProviderInterceptor {
            override fun intercept(chain: OfficialProviderCall): Any? = chain.proceed()
        }
        val first = tracer.wrap("queue", TEST_EXECUTABLE, delegate)
        val second = tracer.wrap("queue", Any::class.java.getDeclaredMethod("hashCode"), delegate)
        first.intercept(chain())
        second.intercept(chain())
        first.intercept(chain())
        second.intercept(chain())
        assertEquals(listOf("toString", "hashCode"), hits)
    }

    private fun chain(): OfficialProviderCall = Proxy.newProxyInstance(
        OfficialProviderCall::class.java.classLoader,
        arrayOf(OfficialProviderCall::class.java),
    ) { _, method, arguments ->
        when (method.name) {
            "getExecutable" -> TEST_EXECUTABLE
            "getThisObject" -> null
            "getArgs" -> emptyList<Any?>()
            "getArg" -> null
            "proceed", "proceedWith" -> "original"
            "toString" -> "TestChain"
            "hashCode" -> System.identityHashCode(this)
            "equals" -> arguments?.firstOrNull() === this
            else -> error("Unexpected OfficialProviderCall method: ${method.name}")
        }
    } as OfficialProviderCall

    private companion object {
        val TEST_EXECUTABLE: Executable = Any::class.java.getDeclaredMethod("toString")
    }
}
