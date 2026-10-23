/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.internal

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 按键分桶的弱引用登记表。同一对象在一个桶内只登记一次；每 [sweepInterval] 次登记清扫一次
 * 全部桶，移除已回收的引用和空桶，避免再也不会访问的键永久残留。
 */
internal class WeakReferenceBuckets<K : Any, T : Any>(
    private val sweepInterval: Int = DEFAULT_SWEEP_INTERVAL,
) {
    private val buckets = ConcurrentHashMap<K, ConcurrentLinkedQueue<WeakReference<T>>>()
    private val registrations = AtomicInteger()

    operator fun get(key: K): ConcurrentLinkedQueue<WeakReference<T>>? = buckets[key]

    val keyCount: Int get() = buckets.size

    /** 登记 [value]；返回是否新加入。在 compute 内完成，不会写进刚被清扫移除的空桶。 */
    fun add(key: K, value: T): Boolean {
        var added = false
        buckets.compute(key) { _, existing ->
            val refs = existing ?: ConcurrentLinkedQueue()
            var registered = false
            refs.forEach { ref ->
                val target = ref.get()
                if (target == null) refs.remove(ref) else if (target === value) registered = true
            }
            if (!registered) {
                refs.add(WeakReference(value))
                added = true
            }
            refs
        }
        if (registrations.incrementAndGet() % sweepInterval == 0) sweep()
        return added
    }

    fun sweep() {
        buckets.keys.forEach { key ->
            buckets.computeIfPresent(key) { _, refs ->
                refs.removeIf { it.get() == null }
                refs.takeIf { it.isNotEmpty() }
            }
        }
    }

    private companion object {
        const val DEFAULT_SWEEP_INTERVAL = 256
    }
}
