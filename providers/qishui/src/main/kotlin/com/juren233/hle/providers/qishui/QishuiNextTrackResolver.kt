/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qishui

import android.os.Looper
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class QishuiTrackMetadata(
    val mediaId: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
)

internal data class QishuiTrackSnapshot(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

internal data class QishuiQueueSnapshot(
    val current: QishuiTrackSnapshot,
    val next: QishuiTrackSnapshot?,
)

internal object QishuiTrackIdentity {
    private val longNumericId = Regex("""\d{5,}""")

    fun candidates(value: String?): Set<String> {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return emptySet()
        return buildSet {
            add(raw)
            raw.substringAfterLast('/').substringBefore('?').takeIf(String::isNotBlank)?.let(::add)
            longNumericId.findAll(raw).forEach { add(it.value) }
        }
    }

    fun normalize(value: String?): String = value.orEmpty()
        .lowercase()
        .filter(Char::isLetterOrDigit)

    fun sameTrack(first: QishuiTrackMetadata?, second: QishuiTrackMetadata): Boolean {
        first ?: return false
        val firstIds = candidates(first.mediaId)
        val secondIds = candidates(second.mediaId)
        if (firstIds.isNotEmpty() && secondIds.isNotEmpty()) {
            return firstIds.intersect(secondIds).isNotEmpty()
        }

        val firstTitle = normalize(first.title)
        val secondTitle = normalize(second.title)
        if (firstTitle.isEmpty() || firstTitle != secondTitle) return false
        val firstArtist = normalize(first.artist)
        val secondArtist = normalize(second.artist)
        return firstArtist.isEmpty() || secondArtist.isEmpty() || firstArtist == secondArtist
    }
}

internal object QishuiNextTrackBinding {
    fun align(
        metadata: QishuiTrackMetadata?,
        snapshot: QishuiQueueSnapshot?,
    ): QishuiQueueSnapshot? {
        if (metadata == null || snapshot == null) return null
        val ids = QishuiTrackIdentity.candidates(metadata.mediaId)
        if (snapshot.current.id.isNotBlank() && snapshot.current.id in ids) return snapshot

        val metadataTitle = QishuiTrackIdentity.normalize(metadata.title)
        val queueTitle = QishuiTrackIdentity.normalize(snapshot.current.title)
        if (metadataTitle.isEmpty() || metadataTitle != queueTitle) return null
        val metadataArtist = QishuiTrackIdentity.normalize(metadata.artist)
        val queueArtist = QishuiTrackIdentity.normalize(snapshot.current.artist)
        return snapshot.takeIf {
            metadataArtist.isEmpty() || queueArtist.isEmpty() || metadataArtist == queueArtist
        }
    }
}

internal class QishuiNextTrackResolver private constructor(
    private val singletonMethod: Method,
    private val currentMethod: Method,
    private val realNextMethod: Method,
    private val nextMethod: Method,
) {
    @Volatile
    private var accessors: QueueItemAccessors? = null

    fun resolve(): QishuiQueueSnapshot? {
        require(Looper.myLooper() == Looper.getMainLooper()) {
            "汽水下一首解析必须在主线程执行"
        }
        val controller = singletonMethod.invoke(null) ?: return null
        val currentValue = currentMethod.invoke(controller) ?: return null
        val itemAccessors = accessors
            ?.takeIf { it.ownerClass.isInstance(currentValue) }
            ?: QueueItemAccessors.resolve(currentValue.javaClass).also { accessors = it }
        val current = itemAccessors.decode(currentValue) ?: return null
        val nextValue = realNextMethod.invoke(controller) ?: nextMethod.invoke(controller)
        val next = nextValue?.let { value ->
            val nextAccessors = itemAccessors.takeIf { it.ownerClass.isInstance(value) }
                ?: QueueItemAccessors.resolve(value.javaClass)
            nextAccessors.decode(value)
        }?.takeUnless { candidate -> sameTrack(current, candidate) }
        return QishuiQueueSnapshot(current = current, next = next)
    }

    private fun sameTrack(first: QishuiTrackSnapshot, second: QishuiTrackSnapshot): Boolean {
        if (first.id.isNotBlank() && second.id.isNotBlank()) return first.id == second.id
        return QishuiTrackIdentity.normalize(first.title) == QishuiTrackIdentity.normalize(second.title) &&
            QishuiTrackIdentity.normalize(first.artist) == QishuiTrackIdentity.normalize(second.artist)
    }

    private data class QueueItemAccessors(
        val ownerClass: Class<*>,
        val playableId: Method,
        val title: Method,
        val authorNames: Method?,
        val artists: Method?,
        val playDuration: Method?,
        val track: Method?,
    ) {
        fun decode(value: Any): QishuiTrackSnapshot? {
            val id = playableId.invoke(value)?.toString().orEmpty()
            val titleValue = title.invoke(value)?.toString().orEmpty().trim()
            if (id.isBlank() && titleValue.isBlank()) return null
            val authorNameValues = (authorNames?.invoke(value) as? Iterable<*>)
                ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
                .orEmpty()
            val artistValues = (artists?.invoke(value) as? Iterable<*>)
                ?.mapNotNull { artist ->
                    artist ?: return@mapNotNull null
                    runCatching { artist.javaClass.getMethod("getName").invoke(artist)?.toString() }
                        .getOrNull()
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                }
                .orEmpty()
            val artist = (authorNameValues.ifEmpty { artistValues }).joinToString(" / ")
            val trackValue = track?.invoke(value)
            val album = trackValue?.let { trackObject ->
                runCatching {
                    val albumObject = trackObject.javaClass.getMethod("getAlbum").invoke(trackObject)
                        ?: return@runCatching ""
                    albumObject.javaClass.getMethod("getName").invoke(albumObject)?.toString().orEmpty()
                }.getOrDefault("")
            }.orEmpty()
            val duration = (playDuration?.invoke(value) as? Number)?.toLong()
                ?.takeIf { it > 0L } ?: -1L
            return QishuiTrackSnapshot(
                id = id,
                title = titleValue,
                artist = artist,
                album = album,
                durationMs = duration,
            )
        }

        companion object {
            fun resolve(ownerClass: Class<*>): QueueItemAccessors = QueueItemAccessors(
                ownerClass = ownerClass,
                playableId = ownerClass.getMethod("getPlayableId").accessible(),
                title = ownerClass.getMethod("getTitle").accessible(),
                authorNames = ownerClass.findNoArgMethod("getAuthorNames"),
                artists = ownerClass.findNoArgMethod("getArtists"),
                playDuration = ownerClass.findNoArgMethod("getPlayDuration"),
                track = ownerClass.findNoArgMethod("getTrack"),
            )
        }
    }

    companion object {
        fun create(
            classLoader: ClassLoader,
            targets: List<OfficialProviderMethodTarget>,
        ): QishuiNextTrackResolver {
            require(Looper.myLooper() == Looper.getMainLooper()) {
                "汽水下一首解析器必须在主线程创建"
            }
            require(targets.size == 4) { "汽水下一首目标数量错误" }
            val methods = targets.map { it.toMethod(classLoader) }
            val singleton = methods[0]
            require(Modifier.isStatic(singleton.modifiers)) { "汽水播放器入口必须是静态方法" }
            require(methods.drop(1).all { it.parameterCount == 0 })
            require(methods.drop(1).all { it.declaringClass.isAssignableFrom(singleton.returnType) })
            return QishuiNextTrackResolver(
                singletonMethod = singleton,
                currentMethod = methods[1],
                realNextMethod = methods[2],
                nextMethod = methods[3],
            )
        }

        private fun OfficialProviderMethodTarget.toMethod(loader: ClassLoader): Method {
            val owner = loader.loadClass(className)
            val parameters = parameterTypeNames.map(loader::loadClass).toTypedArray()
            return owner.getDeclaredMethod(methodName, *parameters).accessible()
        }

        private fun Class<*>.findNoArgMethod(name: String): Method? = runCatching {
            getMethod(name).accessible()
        }.getOrNull()

        private fun Method.accessible(): Method = apply { isAccessible = true }
    }
}
