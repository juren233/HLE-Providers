/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import android.util.Log
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

internal object ProviderLogger {
    private const val TAG = "AppleMusicProvider"

    fun debug(message: String) {
        if (!AppleProviderEnvironment.diagnosticsEnabled) return
        AppleMainThreadCost.timeLog {
            runCatching { Log.d(TAG, message) }
            AppleProviderEnvironment.reportDiagnostic(TAG, message)
        }
    }

    fun diagnostic(message: String) {
        if (!AppleProviderEnvironment.diagnosticsEnabled) return
        AppleMainThreadCost.timeLog {
            runCatching { Log.i(TAG, "[debug] $message") }
            AppleProviderEnvironment.reportDiagnostic(TAG, "[debug] $message")
        }
    }

    fun info(message: String) {
        if (!AppleProviderEnvironment.diagnosticsEnabled) return
        AppleMainThreadCost.timeLog {
            runCatching { Log.i(TAG, message) }
            AppleProviderEnvironment.reportDiagnostic(TAG, message)
        }
    }

    fun error(message: String, throwable: Throwable? = null) {
        runCatching { Log.e(TAG, message, throwable) }
    }
}

internal object AppleReflection {
    private val methodsByClass =
        ConcurrentHashMap<Class<*>, Map<String, List<Method>>>()
    private val fieldsByClass =
        ConcurrentHashMap<Class<*>, Map<String, Field>>()
    private val hierarchyFieldsByClass =
        ConcurrentHashMap<Class<*>, List<Field>>()
    private val zeroArgMethodsByClass =
        ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, Method>>()

    /**
     * 类及其父类声明的全部字段，顺序同自身到父类逐级 `declaredFields`。
     * 运行期回调按类型找字段时用它代替每次重新反射整条继承链。
     */
    fun hierarchyFields(clazz: Class<*>): List<Field> =
        hierarchyFieldsByClass.computeIfAbsent(clazz) { owner ->
            generateSequence(owner) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .toList()
        }

    fun findMethod(
        clazz: Class<*>,
        name: String,
        parameterCount: Int? = null,
        parameterTypes: List<Class<*>>? = null
    ): Method {
        val method = methodsNamed(clazz, name).firstOrNull { candidate ->
            candidate.name == name &&
                (parameterCount == null || candidate.parameterCount == parameterCount) &&
                (parameterTypes == null || candidate.parameterTypes.contentEquals(parameterTypes.toTypedArray()))
        } ?: throw NoSuchMethodException(
            "${clazz.name}#$name/${parameterCount ?: parameterTypes?.size ?: "*"}"
        )
        method.isAccessible = true
        return method
    }

    fun call(instance: Any, name: String, vararg args: Any?): Any? {
        if (args.isEmpty()) return zeroArgMethod(instance.javaClass, name).invoke(instance)
        val method = methodsNamed(instance.javaClass, name).firstOrNull { candidate ->
            parametersMatch(candidate.parameterTypes, args)
        } ?: throw NoSuchMethodException("${instance.javaClass.name}#$name/${args.size}")
        method.isAccessible = true
        return method.invoke(instance, *args)
    }

    fun callStatic(clazz: Class<*>, name: String, vararg args: Any?): Any? {
        val method = methodsNamed(clazz, name).firstOrNull { candidate ->
            Modifier.isStatic(candidate.modifiers) &&
                parametersMatch(candidate.parameterTypes, args)
        } ?: throw NoSuchMethodException("${clazz.name}#$name/${args.size}")
        method.isAccessible = true
        return method.invoke(null, *args)
    }

    fun newInstance(clazz: Class<*>, vararg args: Any?): Any {
        val constructor = clazz.declaredConstructors.firstOrNull { candidate ->
            parametersMatch(candidate.parameterTypes, args)
        } ?: throw NoSuchMethodException("${clazz.name}<init>/${args.size}")
        @Suppress("UNCHECKED_CAST")
        (constructor as Constructor<Any>).isAccessible = true
        return constructor.newInstance(*args)
    }

    fun field(instance: Any, name: String): Any? {
        val field = findField(instance.javaClass, name)
        field.isAccessible = true
        return field.get(instance)
    }

    fun setField(instance: Any, name: String, value: Any?) {
        val field = findField(instance.javaClass, name)
        field.isAccessible = true
        field.set(instance, value)
    }

    fun intField(instance: Any, name: String): Int {
        val field = findField(instance.javaClass, name)
        field.isAccessible = true
        return field.getInt(instance)
    }

    private fun findField(clazz: Class<*>, name: String): Field {
        return fieldsByClass.computeIfAbsent(clazz, ::indexFields)[name]
            ?: throw NoSuchFieldException("${clazz.name}#$name")
    }

    /** 无参调用的匹配只看参数个数，结果按类与名称缓存，并只设置一次可访问。 */
    private fun zeroArgMethod(clazz: Class<*>, name: String): Method =
        zeroArgMethodsByClass.computeIfAbsent(clazz) { ConcurrentHashMap() }[name]
            ?: (methodsNamed(clazz, name).firstOrNull { it.parameterCount == 0 }
                ?: throw NoSuchMethodException("${clazz.name}#$name/0"))
                .also { method ->
                    method.isAccessible = true
                    zeroArgMethodsByClass.getValue(clazz)[name] = method
                }

    private fun methodsNamed(clazz: Class<*>, name: String): List<Method> =
        methodsByClass.computeIfAbsent(clazz) { owner ->
            AppleStartupResolveCost.time(AppleStartupResolveCost.Kind.REFLECTION_INDEX) { indexMethods(owner) }
        }[name].orEmpty()

    private fun indexMethods(clazz: Class<*>): Map<String, List<Method>> {
        val methods = LinkedHashMap<String, MutableList<Method>>()
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredMethods.forEach { method ->
                methods.getOrPut(method.name) { ArrayList() }.add(method)
            }
            current = current.superclass
        }
        return methods
    }

    private fun indexFields(clazz: Class<*>): Map<String, Field> {
        val fields = LinkedHashMap<String, Field>()
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredFields.forEach { field -> fields.putIfAbsent(field.name, field) }
            current = current.superclass
        }
        return fields
    }

    private fun parametersMatch(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (types.size != args.size) return false
        return types.indices.all { index ->
            val argument = args[index] ?: return@all !types[index].isPrimitive
            boxed(types[index]).isAssignableFrom(argument.javaClass)
        }
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> Boolean::class.javaObjectType
        java.lang.Byte.TYPE -> Byte::class.javaObjectType
        java.lang.Character.TYPE -> Char::class.javaObjectType
        java.lang.Short.TYPE -> Short::class.javaObjectType
        java.lang.Integer.TYPE -> Int::class.javaObjectType
        java.lang.Long.TYPE -> Long::class.javaObjectType
        java.lang.Float.TYPE -> Float::class.javaObjectType
        java.lang.Double.TYPE -> Double::class.javaObjectType
        else -> type
    }
}
