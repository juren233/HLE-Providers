/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuwoHostPlanTest {
    @Test
    fun `supports kuwo and bodian hosts only`() {
        assertTrue(KuwoHostPlan.supports(KuwoHostPlan.KUWO_PACKAGE))
        assertTrue(KuwoHostPlan.supports(KuwoHostPlan.BODIAN_PACKAGE))
        assertFalse(KuwoHostPlan.supports("com.tencent.qqmusic"))
        assertFalse(KuwoHostPlan.supports("cn.wenyu.bodianx"))
        assertFalse(KuwoHostPlan.supports(""))
    }

    @Test
    fun `kuwo main process runs lyrics plus playcontrol next track`() {
        val features = KuwoHostPlan.resolve(KuwoHostPlan.KUWO_PACKAGE, KuwoHostPlan.KUWO_PACKAGE)
        assertEquals(setOf(KuwoFeature.LYRICS, KuwoFeature.NEXT_TRACK), features)
    }

    @Test
    fun `bodian main process runs lyrics plus buffering but never kuwo next track`() {
        val features = KuwoHostPlan.resolve(KuwoHostPlan.BODIAN_PACKAGE, KuwoHostPlan.BODIAN_PACKAGE)
        assertEquals(setOf(KuwoFeature.LYRICS, KuwoFeature.BUFFERING_STATE), features)
        assertFalse(features.contains(KuwoFeature.NEXT_TRACK))
    }

    @Test
    fun `bodian service process only collects next track`() {
        val features = KuwoHostPlan.resolve(
            KuwoHostPlan.BODIAN_PACKAGE,
            KuwoHostPlan.BODIAN_SERVICE_PROCESS,
        )
        assertEquals(setOf(KuwoFeature.NEXT_TRACK), features)
    }

    @Test
    fun `unknown processes resolve to no features`() {
        assertTrue(KuwoHostPlan.resolve(KuwoHostPlan.KUWO_PACKAGE, "cn.kuwo.player:push").isEmpty())
        assertTrue(KuwoHostPlan.resolve(KuwoHostPlan.BODIAN_PACKAGE, "cn.wenyu.bodian:push").isEmpty())
        assertTrue(
            KuwoHostPlan.resolve(KuwoHostPlan.BODIAN_PACKAGE, KuwoHostPlan.BODIAN_PACKAGE + "x")
                .isEmpty(),
        )
    }

    @Test
    fun `bodian media id rides the existing direct rid path`() {
        // 波点 PlayerBridge 写入的 MEDIA_ID 是纯数字酷我 rid，必须走 directRid 主路径
        assertEquals(81_466_699L, KuwoTrackIdResolver.directRid("81466699"))
    }
}
