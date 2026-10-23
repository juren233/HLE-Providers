/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import org.junit.Assert.assertEquals
import org.junit.Test

class HolderItemViewFieldCandidatesTest {
    // Stand-ins for the 1611 shapes: Number plays View, Long plays ViewGroup/RecyclerView.
    @Suppress("unused")
    private open class BaseHolder {
        val itemView: Number? = 1
        val ownerRecycler: Long? = 2L
    }

    @Suppress("unused")
    private class EpoxyHolder : BaseHolder() {
        val viewParent: Long? = null
    }

    @Suppress("unused")
    private class SubtypeOnlyHolder {
        val first: Long? = null
        val second: Long? = 3L
    }

    private fun hierarchy(clazz: Class<*>) =
        generateSequence(clazz) { it.superclass }.flatMap { it.declaredFields.asSequence() }.toList()

    @Test
    fun exactViewTypedFieldWinsOverSubtypeFieldsDeclaredFirst() {
        val names = holderItemViewFieldCandidates(hierarchy(EpoxyHolder::class.java), Number::class.java)
            .map { it.name }
        assertEquals(listOf("itemView"), names)
    }

    @Test
    fun withoutExactFieldKeepsOnlyFirstSubtypeField() {
        val names = holderItemViewFieldCandidates(hierarchy(SubtypeOnlyHolder::class.java), Number::class.java)
            .map { it.name }
        assertEquals(listOf("first"), names)
    }
}
