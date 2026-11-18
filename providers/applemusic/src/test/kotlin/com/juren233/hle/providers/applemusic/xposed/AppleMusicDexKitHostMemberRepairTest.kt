/* Copyright 2026 juren233 */
package com.juren233.hle.providers.applemusic.xposed

import com.juren233.hle.providers.applemusic.xposed.AppleMusicDexKitResolver.MemberDescriptor
import com.juren233.hle.providers.applemusic.xposed.AppleMusicDexKitResolver.MemberKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1622 的 `player.O` 变成枚举后，按基线序号改名曾沿继承链选中 `Object.identityHashCodeNative`，
 * Hook 后主线程无限重入卡死。改名只能落在宿主自己声明的成员上。
 */
class AppleMusicDexKitHostMemberRepairTest {
    class Item

    /** 记录基线时的宿主类：静态单参、参数与返回都是混淆类型。 */
    class OldUtil {
        companion object {
            @JvmStatic fun b(item: Item): Item = item
        }
    }

    /** 新版同名类没有对应成员；父类 Thread 有多个静态单参方法（sleep、holdsLock…）。 */
    class Unrelated : Thread()

    /** 新版改名后的宿主类：静态单参里一个返回 int（稳定类型），一个返回混淆类型。 */
    class RenamedUtil {
        companion object {
            @JvmStatic fun c(item: Item): Int = item.hashCode()
            @JvmStatic fun d(item: Item): Item = item
        }
    }

    private fun baseline(): MemberDescriptor = MemberDescriptor.from(
        OldUtil::class.java.getDeclaredMethod("b", Item::class.java),
        OldUtil::class.java,
    )

    @Test fun `repair never picks a method declared by a framework superclass`() {
        assertNull(baseline().resolveName(Unrelated::class.java, MemberKind.METHOD))
    }

    @Test fun `repair ordinal uses the same group as the recorded baseline`() {
        assertEquals("d", baseline().resolveName(RenamedUtil::class.java, MemberKind.METHOD))
    }

    @Test fun `original name is kept when it still matches`() {
        assertEquals("b", baseline().resolveName(OldUtil::class.java, MemberKind.METHOD))
    }

    @Test fun `framework members are not host declared`() {
        assertFalse(isHostDeclared(Any::class.java.getDeclaredMethod("hashCode"), Unrelated::class.java))
        assertFalse(
            isHostDeclared(
                Thread::class.java.getDeclaredMethod("holdsLock", Any::class.java),
                Unrelated::class.java,
            ),
        )
        assertTrue(
            isHostDeclared(
                RenamedUtil::class.java.getDeclaredMethod("d", Item::class.java),
                RenamedUtil::class.java,
            ),
        )
    }
}
