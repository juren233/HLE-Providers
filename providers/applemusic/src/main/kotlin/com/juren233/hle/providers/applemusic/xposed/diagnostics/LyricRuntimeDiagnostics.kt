/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.diagnostics

import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.juren233.hle.providers.applemusic.xposed.AppleProviderEnvironment

/** Low-frequency lifecycle markers; inline gating removes detail evaluation from Release. */
internal object LyricRuntimeDiagnostics {
    private const val TAG = "AppleMusicProvider"

    @PublishedApi
    internal val generationId = java.lang.Long.toHexString(System.nanoTime()) + "-" +
        Integer.toHexString(System.identityHashCode(this))

    inline fun record(stage: String, details: () -> String = { "" }) {
        if (AppleProviderEnvironment.diagnosticsEnabled) {
            runCatching {
                Log.i(
                    TAG,
                    LyricRuntimeDiagnosticFormat.prefix(
                        stage, Process.myPid(), Process.myUid(), generationId, SystemClock.elapsedRealtime(),
                    ) + " " + details(),
                )
            }
        }
    }
}

/** Explicit repair revision distinguishes this patch from earlier APKs sharing a versionCode. */
@PublishedApi
internal object LyricRuntimeDiagnosticFormat {
    const val REPAIR_REVISION = "issue44-recovery-20261004-r2"

    fun prefix(stage: String, pid: Int, uid: Int, generation: String, elapsedMs: Long): String =
        "stage=$stage repair=$REPAIR_REVISION generation=$generation elapsedMs=$elapsedMs pid=$pid uid=$uid"
}
