/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.parser

import com.juren233.hle.providers.applemusic.xposed.AppleReflection
import com.juren233.hle.providers.applemusic.xposed.AppleMusicHookPoint
import com.juren233.hle.providers.applemusic.xposed.AppleMusicHookResolver
import com.juren233.hle.providers.applemusic.xposed.AppleMusicHookTarget
import com.juren233.hle.providers.applemusic.xposed.AppleMusicRuntimeMember

/** Runtime-member access for the native lyric model parser. */
internal class AppleLyricsParserAccess private constructor(
    private val target: AppleMusicHookTarget,
) {
    fun call(any: Any, member: AppleMusicRuntimeMember, vararg args: Any?): Any? =
        runCatching {
            AppleReflection.call(any, target.runtimeMemberName(member), *args)
        }.getOrNull()

    companion object {
        fun from(resolver: AppleMusicHookResolver): AppleLyricsParserAccess =
            AppleLyricsParserAccess(
                resolver.resolveMethod(AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD).target,
            )
    }
}
