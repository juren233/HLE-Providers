package com.juren233.hle.providers.applemusic.xposed

import com.juren233.hle.providers.applemusic.xposed.internal.WeakIdentityMap

/**
 * Apple Attributes 对象的原始名称快照。按对象身份弱引用保存：对象被回收即释放，
 * 不再强引用宿主对象，也不会因数量上限整体清空而丢掉仍存活对象的原始值。
 */
internal object AppleMediaApiAttributeSnapshots {
    private val snapshots = WeakIdentityMap<Any, Snapshot>()

    fun remember(
        attributes: Any,
        name: String?,
        artistName: String?,
        albumName: String?,
    ): Snapshot = synchronized(snapshots) {
        snapshots[attributes] ?: Snapshot(
            name = name,
            artistName = artistName,
            albumName = albumName,
        ).also { snapshot -> snapshots[attributes] = snapshot }
    }

    fun get(attributes: Any): Snapshot? = snapshots[attributes]

    data class Snapshot(
        val name: String?,
        val artistName: String?,
        val albumName: String?,
    )
}
