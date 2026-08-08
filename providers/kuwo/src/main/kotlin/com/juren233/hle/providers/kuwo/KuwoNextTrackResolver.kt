/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

import android.app.Application
import android.os.Looper
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderDexMethodQuery
import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class KuwoTrackSnapshot(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

internal data class KuwoQueueSnapshot(
    val current: KuwoTrackSnapshot,
    val next: KuwoTrackSnapshot?,
)

internal object KuwoNextTrackBinding {
    fun align(
        metadata: KuwoTrackMetadata?,
        snapshot: KuwoQueueSnapshot?,
    ): KuwoQueueSnapshot? {
        if (metadata == null || snapshot == null) return null
        val mediaRid = KuwoTrackIdResolver.directRid(metadata.mediaId)
        if (mediaRid != null) return snapshot.takeIf { it.current.id == mediaRid.toString() }

        val mediaTitle = KuwoTrackIdResolver.normalize(metadata.title)
        val queueTitle = KuwoTrackIdResolver.normalize(snapshot.current.title)
        return snapshot.takeIf {
            mediaTitle.isNotEmpty() && mediaTitle == queueTitle
        }
    }
}

/**
 * Main-thread read of Kuwo's own current/next-content API.
 * Every reflected identifier is supplied by [KuwoHookProfile].
 */
internal class KuwoNextTrackResolver private constructor(
    private val singletonMethod: Method,
    private val currentMusicMethod: Method,
    private val nextContentMethod: Method,
    private val musicClass: Class<*>,
    private val ridField: Field,
    private val titleField: Field,
    private val artistField: Field,
    private val albumField: Field,
    private val durationSecondsField: Field,
) {
    fun resolve(): KuwoQueueSnapshot? {
        require(Looper.myLooper() == Looper.getMainLooper()) {
            "酷我下一首解析必须在主线程执行"
        }
        val manager = singletonMethod.invoke(null) ?: return null
        val currentValue = currentMusicMethod.invoke(manager) ?: return null
        val current = decodeMusic(currentValue) ?: return null
        val next = nextContentMethod.invoke(manager)?.let(::decodeMusic)
        return KuwoQueueSnapshot(current = current, next = next)
    }

    private fun decodeMusic(value: Any): KuwoTrackSnapshot? {
        if (!musicClass.isInstance(value)) return null
        val durationSeconds = (durationSecondsField.get(value) as Number).toLong()
        return KuwoTrackSnapshot(
            id = (ridField.get(value) as Number).toLong().takeIf { it > 0L }?.toString().orEmpty(),
            title = titleField.get(value) as? String ?: "",
            artist = artistField.get(value) as? String ?: "",
            album = albumField.get(value) as? String ?: "",
            durationMs = durationSeconds.takeIf { it > 0L }?.times(1_000L) ?: -1L,
        )
    }

    companion object {
        fun queries(application: Application): List<OfficialProviderDexMethodQuery>? {
            val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            val profile = KuwoHookProfiles.resolve(
                packageInfo.versionName.orEmpty(),
                packageInfo.longVersionCode,
            ) ?: KuwoHookProfiles.V12_1_8_2
            fun target(methodName: String, returnTypeName: String, isStatic: Boolean = false) =
                OfficialProviderMethodTarget(
                    className = profile.playback.managerClassName,
                    methodName = methodName,
                    returnTypeName = returnTypeName,
                    isStatic = isStatic,
                )
            val prefix = profile.playback.managerClassName.substringBeforeLast('.') + "."
            return listOf(
                OfficialProviderDexMethodQuery(
                    cacheKey = "kuwo-player-singleton-v1",
                    preferredTarget = target(
                        profile.playback.singletonMethodName,
                        profile.playback.managerClassName,
                        isStatic = true,
                    ),
                    declaringClassNamePrefix = prefix,
                    parameterTypeNames = emptyList(),
                    returnTypeMatchesDeclaringClass = true,
                    isStatic = true,
                ),
                OfficialProviderDexMethodQuery(
                    cacheKey = "kuwo-current-music-v1",
                    preferredTarget = target(
                        profile.playback.currentMusicMethodName,
                        profile.playback.musicClassName,
                    ),
                    declaringClassNamePrefix = prefix,
                    parameterTypeNames = emptyList(),
                    returnTypeName = profile.playback.musicClassName,
                    isStatic = false,
                ),
                OfficialProviderDexMethodQuery(
                    cacheKey = "kuwo-next-content-v1",
                    preferredTarget = target(
                        profile.playback.nextContentMethodName,
                        profile.playback.contentClassName,
                    ),
                    declaringClassNamePrefix = prefix,
                    parameterTypeNames = emptyList(),
                    returnTypeName = profile.playback.contentClassName,
                    isStatic = false,
                ),
            )
        }

        fun create(
            application: Application,
            targets: List<OfficialProviderMethodTarget>,
        ): KuwoNextTrackResolver {
            require(Looper.myLooper() == Looper.getMainLooper()) {
                "酷我下一首解析器必须在主线程创建"
            }
            val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            val profile = KuwoHookProfiles.resolve(
                packageInfo.versionName.orEmpty(),
                packageInfo.longVersionCode,
            ) ?: KuwoHookProfiles.V12_1_8_2
            require(targets.size == 3) { "酷我下一首目标数量错误" }
            val loader = application.classLoader
            val contentClass = loader.loadClass(profile.playback.contentClassName)
            val musicClass = loader.loadClass(profile.playback.musicClassName)
            val methods = targets.map { it.toMethod(loader) }
            val singletonMethod = methods[0]
            val currentMusicMethod = methods[1]
            val nextContentMethod = methods[2]
            val managerClass = singletonMethod.returnType

            require(Modifier.isStatic(singletonMethod.modifiers))
            require(singletonMethod.returnType == managerClass)
            require(currentMusicMethod.returnType == musicClass)
            require(nextContentMethod.returnType == contentClass)
            require(contentClass.isAssignableFrom(musicClass))

            val ridField = musicClass.getDeclaredField(profile.music.ridFieldName).accessible()
            val titleField = musicClass.getDeclaredField(profile.music.titleFieldName).accessible()
            val artistField = musicClass.getDeclaredField(profile.music.artistFieldName).accessible()
            val albumField = musicClass.getDeclaredField(profile.music.albumFieldName).accessible()
            val durationSecondsField = musicClass
                .getDeclaredField(profile.music.durationSecondsFieldName)
                .accessible()
            require(ridField.type == Long::class.javaPrimitiveType)
            require(titleField.type == String::class.java)
            require(artistField.type == String::class.java)
            require(albumField.type == String::class.java)
            require(durationSecondsField.type == Int::class.javaPrimitiveType)

            return KuwoNextTrackResolver(
                singletonMethod = singletonMethod,
                currentMusicMethod = currentMusicMethod,
                nextContentMethod = nextContentMethod,
                musicClass = musicClass,
                ridField = ridField,
                titleField = titleField,
                artistField = artistField,
                albumField = albumField,
                durationSecondsField = durationSecondsField,
            )
        }

        private fun Field.accessible(): Field = apply { isAccessible = true }

        private fun Method.accessible(): Method = apply { isAccessible = true }

        private fun OfficialProviderMethodTarget.toMethod(loader: ClassLoader): Method {
            val clazz = loader.loadClass(className)
            val parameters = parameterTypeNames.map { name ->
                when (name) {
                    "boolean" -> Boolean::class.javaPrimitiveType!!
                    "int" -> Int::class.javaPrimitiveType!!
                    "long" -> Long::class.javaPrimitiveType!!
                    else -> loader.loadClass(name)
                }
            }.toTypedArray()
            return clazz.getDeclaredMethod(methodName, *parameters).accessible()
        }
    }
}
