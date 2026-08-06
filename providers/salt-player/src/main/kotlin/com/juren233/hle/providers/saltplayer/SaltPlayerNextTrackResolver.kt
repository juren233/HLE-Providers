/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import android.app.Application
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class SaltPlayerTrackSnapshot(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

internal data class SaltPlayerQueueStateSnapshot(
    val mode: SaltPlayerPlaybackMode,
    val normalQueue: List<SaltPlayerTrackSnapshot>,
    val normalIndex: Int,
    val randomQueue: List<SaltPlayerTrackSnapshot>,
    val randomIndex: Int,
    val readyToSave: Boolean,
)

internal enum class SaltPlayerPlaybackMode {
    CIRCLE,
    CIRCLE_END,
    REPEAT_ONE,
    RANDOM,
}

internal object SaltPlayerNextTrackSelector {
    fun select(
        state: SaltPlayerQueueStateSnapshot,
        currentId: String?,
        currentFallback: SaltPlayerTrackSnapshot? = null,
    ): SaltPlayerTrackSnapshot? {
        if (state.mode == SaltPlayerPlaybackMode.REPEAT_ONE) {
            return currentFallback?.takeIf { it.title.isNotBlank() }
                ?: currentQueueItem(state, currentId)
        }

        val random = state.mode == SaltPlayerPlaybackMode.RANDOM
        val queue = if (random) state.randomQueue else state.normalQueue
        if (queue.isEmpty()) return null
        val configuredIndex = if (random) state.randomIndex else state.normalIndex
        val currentIndex = resolveCurrentIndex(queue, configuredIndex, currentId)
        if (currentIndex !in queue.indices) return null
        if (random && queue.size > 1 && currentIndex == queue.lastIndex) return null
        return queue[(currentIndex + 1) % queue.size]
    }

    private fun currentQueueItem(
        state: SaltPlayerQueueStateSnapshot,
        currentId: String?,
    ): SaltPlayerTrackSnapshot? {
        val random = state.mode == SaltPlayerPlaybackMode.RANDOM
        val queue = if (random) state.randomQueue else state.normalQueue
        val configuredIndex = if (random) state.randomIndex else state.normalIndex
        return queue.getOrNull(resolveCurrentIndex(queue, configuredIndex, currentId))
    }

    private fun resolveCurrentIndex(
        queue: List<SaltPlayerTrackSnapshot>,
        configuredIndex: Int,
        currentId: String?,
    ): Int {
        val normalizedId = currentId?.trim().orEmpty()
        if (normalizedId.isEmpty()) return configuredIndex
        if (queue.getOrNull(configuredIndex)?.id == normalizedId) return configuredIndex
        return queue.indexOfFirst { it.id == normalizedId }
    }
}

/**
 * Read-only projection of Salt Player's PlaybackQueueState StateFlow.
 * Every reflected identifier is supplied by [SaltPlayerHookProfile].
 */
internal class SaltPlayerNextTrackResolver private constructor(
    private val queueStateFlowField: Field,
    private val stateFlowValueMethod: Method,
    private val queueStateClass: Class<*>,
    private val queueItemClass: Class<*>,
    private val songClass: Class<*>,
    private val stateModeField: Field,
    private val stateNormalQueueField: Field,
    private val stateNormalIndexField: Field,
    private val stateRandomQueueField: Field,
    private val stateRandomIndexField: Field,
    private val stateReadyToSaveField: Field,
    private val itemDataField: Field,
    private val songIdMethod: Method,
    private val songTitleMethod: Method,
    private val songArtistMethod: Method,
    private val songAlbumMethod: Method,
    private val songDurationMethod: Method,
    private val queueProfile: SaltPlayerQueueHookProfile,
) {
    fun resolve(current: SaltPlayerTrackMetadata): SaltPlayerTrackSnapshot? {
        val stateFlow = queueStateFlowField.get(null) ?: return null
        val value = stateFlowValueMethod.invoke(stateFlow) ?: return null
        if (value.javaClass != queueStateClass) return null
        val state = decodeState(value) ?: return null
        return SaltPlayerNextTrackSelector.select(
            state = state,
            currentId = current.id,
            currentFallback = current.toSnapshot(),
        )
    }

    private fun decodeState(value: Any): SaltPlayerQueueStateSnapshot? {
        val mode = stateModeField.get(value) as? Enum<*> ?: return null
        return SaltPlayerQueueStateSnapshot(
            mode = decodeMode(mode.name) ?: return null,
            normalQueue = decodeQueue(stateNormalQueueField.get(value)) ?: return null,
            normalIndex = (stateNormalIndexField.get(value) as? Number)?.toInt() ?: return null,
            randomQueue = decodeQueue(stateRandomQueueField.get(value)) ?: return null,
            randomIndex = (stateRandomIndexField.get(value) as? Number)?.toInt() ?: return null,
            readyToSave = stateReadyToSaveField.get(value) as? Boolean ?: return null,
        )
    }

    private fun decodeMode(value: String): SaltPlayerPlaybackMode? = when (value) {
        queueProfile.circleModeName -> SaltPlayerPlaybackMode.CIRCLE
        queueProfile.circleEndModeName -> SaltPlayerPlaybackMode.CIRCLE_END
        queueProfile.repeatOneModeName -> SaltPlayerPlaybackMode.REPEAT_ONE
        queueProfile.randomModeName -> SaltPlayerPlaybackMode.RANDOM
        else -> null
    }

    private fun decodeQueue(value: Any?): List<SaltPlayerTrackSnapshot>? {
        val queue = value as? List<*> ?: return null
        return buildList(queue.size) {
            queue.forEach { item -> add(decodeItem(item) ?: return null) }
        }
    }

    private fun decodeItem(value: Any?): SaltPlayerTrackSnapshot? {
        if (value?.javaClass != queueItemClass) return null
        val song = itemDataField.get(value) ?: return null
        if (song.javaClass != songClass) return null
        return SaltPlayerTrackSnapshot(
            id = songIdMethod.invoke(song) as? String ?: "",
            title = songTitleMethod.invoke(song) as? String ?: "",
            artist = songArtistMethod.invoke(song) as? String ?: "",
            album = songAlbumMethod.invoke(song) as? String ?: "",
            durationMs = (songDurationMethod.invoke(song) as? Number)?.toLong() ?: -1L,
        )
    }

    private fun SaltPlayerTrackMetadata.toSnapshot(): SaltPlayerTrackSnapshot? {
        val resolvedTitle = title?.trim().orEmpty()
        if (resolvedTitle.isEmpty()) return null
        return SaltPlayerTrackSnapshot(
            id = id?.trim().orEmpty(),
            title = resolvedTitle,
            artist = artist?.trim().orEmpty(),
            album = album?.trim().orEmpty(),
            durationMs = durationMs.takeIf { it >= 0L } ?: -1L,
        )
    }

    companion object {
        fun create(
            application: Application,
            profile: SaltPlayerHookProfile,
        ): SaltPlayerNextTrackResolver {
            val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            require(
                SaltPlayerHookProfiles.resolve(
                    packageInfo.versionName.orEmpty(),
                    packageInfo.longVersionCode,
                ) == profile,
            ) { "Salt Player Profile 与已安装版本不匹配" }

            val loader = application.classLoader
            val controllerClass = loader.loadClass(profile.musicControllerClassName)
            val stateFlowClass = loader.loadClass(profile.queue.stateFlowClassName)
            val queueStateClass = loader.loadClass(profile.queue.stateClassName)
            val queueItemClass = loader.loadClass(profile.queue.itemClassName)
            val modeClass = loader.loadClass(profile.queue.modeClassName)
            val songClass = loader.loadClass(profile.song.className)

            val queueStateFlowField = controllerClass
                .getDeclaredField(profile.queue.stateFlowFieldName)
                .accessible()
            require(Modifier.isStatic(queueStateFlowField.modifiers))
            require(queueStateFlowField.type == stateFlowClass)
            val stateFlowValueMethod = stateFlowClass
                .getMethod(profile.queue.stateFlowValueGetterName)
                .accessible()
            require(stateFlowValueMethod.returnType == Any::class.java)

            val stateModeField = queueStateClass
                .getDeclaredField(profile.queue.stateModeFieldName)
                .accessible()
            val stateNormalQueueField = queueStateClass
                .getDeclaredField(profile.queue.stateNormalQueueFieldName)
                .accessible()
            val stateNormalIndexField = queueStateClass
                .getDeclaredField(profile.queue.stateNormalIndexFieldName)
                .accessible()
            val stateRandomQueueField = queueStateClass
                .getDeclaredField(profile.queue.stateRandomQueueFieldName)
                .accessible()
            val stateRandomIndexField = queueStateClass
                .getDeclaredField(profile.queue.stateRandomIndexFieldName)
                .accessible()
            val stateReadyToSaveField = queueStateClass
                .getDeclaredField(profile.queue.stateReadyToSaveFieldName)
                .accessible()
            require(stateModeField.type == modeClass && modeClass.isEnum)
            require(List::class.java.isAssignableFrom(stateNormalQueueField.type))
            require(stateNormalIndexField.type == Int::class.javaPrimitiveType)
            require(List::class.java.isAssignableFrom(stateRandomQueueField.type))
            require(stateRandomIndexField.type == Int::class.javaPrimitiveType)
            require(stateReadyToSaveField.type == Boolean::class.javaPrimitiveType)
            val modeNames = requireNotNull(modeClass.enumConstants)
                .map { (it as Enum<*>).name }
                .toSet()
            require(
                modeNames.containsAll(
                    setOf(
                        profile.queue.circleModeName,
                        profile.queue.circleEndModeName,
                        profile.queue.repeatOneModeName,
                        profile.queue.randomModeName,
                    ),
                ),
            )

            val itemDataField = queueItemClass
                .getDeclaredField(profile.queue.itemDataFieldName)
                .accessible()
            require(itemDataField.type == Any::class.java)

            val songIdMethod = songClass.getDeclaredMethod(profile.song.idGetterName).accessible()
            val songTitleMethod = songClass.getDeclaredMethod(profile.song.titleGetterName).accessible()
            val songArtistMethod = songClass.getDeclaredMethod(profile.song.artistGetterName).accessible()
            val songAlbumMethod = songClass.getDeclaredMethod(profile.song.albumGetterName).accessible()
            val songDurationMethod = songClass
                .getDeclaredMethod(profile.song.durationGetterName)
                .accessible()
            require(songIdMethod.returnType == String::class.java)
            require(songTitleMethod.returnType == String::class.java)
            require(songArtistMethod.returnType == String::class.java)
            require(songAlbumMethod.returnType == String::class.java)
            require(songDurationMethod.returnType == Long::class.javaPrimitiveType)

            return SaltPlayerNextTrackResolver(
                queueStateFlowField = queueStateFlowField,
                stateFlowValueMethod = stateFlowValueMethod,
                queueStateClass = queueStateClass,
                queueItemClass = queueItemClass,
                songClass = songClass,
                stateModeField = stateModeField,
                stateNormalQueueField = stateNormalQueueField,
                stateNormalIndexField = stateNormalIndexField,
                stateRandomQueueField = stateRandomQueueField,
                stateRandomIndexField = stateRandomIndexField,
                stateReadyToSaveField = stateReadyToSaveField,
                itemDataField = itemDataField,
                songIdMethod = songIdMethod,
                songTitleMethod = songTitleMethod,
                songArtistMethod = songArtistMethod,
                songAlbumMethod = songAlbumMethod,
                songDurationMethod = songDurationMethod,
                queueProfile = profile.queue,
            )
        }

        private fun Field.accessible(): Field = apply { isAccessible = true }

        private fun Method.accessible(): Method = apply { isAccessible = true }
    }
}
