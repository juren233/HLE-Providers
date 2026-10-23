/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.internal

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

internal class WeakIdentityMap<K : Any, V> {
    private val queue = ReferenceQueue<K>()
    /** 键都是 [IdentityWeakReference]；查询用 [probe] 匹配，不必每次新建弱引用。 */
    private val values = LinkedHashMap<Any, V>()
    /** 只在持有 `values` 锁时使用，用完立即清空，避免强引用查询对象。 */
    private val probe = IdentityProbe()

    operator fun get(key: K): V? = synchronized(values) {
        removeCollectedKeys()
        probe.lookup(key) { values[it] }
    }

    operator fun set(key: K, value: V) {
        synchronized(values) {
            removeCollectedKeys()
            values[IdentityWeakReference(key, queue)] = value
        }
    }

    /** 仅在键尚未登记时写入；返回是否新写入。 */
    fun putIfAbsent(key: K, value: V): Boolean = synchronized(values) {
        removeCollectedKeys()
        if (probe.lookup(key) { values.containsKey(it) }) return@synchronized false
        values[IdentityWeakReference(key, queue)] = value
        true
    }

    /** 按登记顺序返回仍存活的键快照。 */
    fun keys(): List<K> = synchronized(values) {
        removeCollectedKeys()
        @Suppress("UNCHECKED_CAST")
        values.keys.mapNotNull { (it as IdentityWeakReference<K>).get() }
    }

    fun remove(key: K) {
        synchronized(values) {
            removeCollectedKeys()
            probe.lookup(key) { values.remove(it) }
        }
    }

    fun clear() {
        synchronized(values) {
            values.clear()
            while (queue.poll() != null) Unit
        }
    }

    private fun removeCollectedKeys() {
        while (true) {
            @Suppress("UNCHECKED_CAST")
            val reference = queue.poll() ?: return
            values.remove(reference)
        }
    }
}

private class IdentityWeakReference<T : Any>(
    referent: T,
    queue: ReferenceQueue<T>? = null,
) : WeakReference<T>(referent, queue) {
    private val identityHashCode = System.identityHashCode(referent)

    override fun hashCode(): Int = identityHashCode

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val referent = get() ?: return false
        return when (other) {
            is IdentityWeakReference<*> -> referent === other.get()
            is IdentityProbe -> referent === other.referent
            else -> false
        }
    }
}

/** 与 [IdentityWeakReference] 按身份相等的可复用查询键。 */
private class IdentityProbe {
    var referent: Any? = null
        private set
    private var identityHashCode = 0

    inline fun <R> lookup(key: Any, block: (Any) -> R): R {
        referent = key
        identityHashCode = System.identityHashCode(key)
        try {
            return block(this)
        } finally {
            referent = null
        }
    }

    override fun hashCode(): Int = identityHashCode

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val key = referent ?: return false
        return other is IdentityWeakReference<*> && other.get() === key
    }
}
