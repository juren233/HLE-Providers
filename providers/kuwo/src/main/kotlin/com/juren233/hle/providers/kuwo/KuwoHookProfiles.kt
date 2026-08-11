/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.kuwo

internal data class KuwoPlaybackHookProfile(
    val managerClassName: String,
    val contentClassName: String,
    val musicClassName: String,
    val singletonMethodName: String,
    val currentMusicMethodName: String,
    val nextContentMethodName: String,
)

internal data class KuwoMusicHookProfile(
    val ridFieldName: String,
    val titleFieldName: String,
    val artistFieldName: String,
    val albumFieldName: String,
    val durationSecondsFieldName: String,
)

internal data class KuwoHookProfile(
    val versionName: String,
    val versionCode: Long,
    val playback: KuwoPlaybackHookProfile,
    val music: KuwoMusicHookProfile,
)

/** Verified templates for every Kuwo runtime identifier used by the Provider. */
internal object KuwoHookProfiles {
    // Verified from the original Kuwo Music 12.1.8.2 (12182) APK DEX on 2026-08-07.
    // Exact descriptors:
    // Lcn/kuwo/mod/playcontrol/n;->L()Lcn/kuwo/mod/playcontrol/n;
    // Lcn/kuwo/mod/playcontrol/n;->S()Lcn/kuwo/base/bean/Music;
    // Lcn/kuwo/mod/playcontrol/n;->g0()Lcn/kuwo/base/bean/IContent;.
    // g0() is Kuwo's own read path for the next item and covers sequential, list-end,
    // single-pass and randomized queues. Music fields below are public DEX fields.
    val V12_1_8_2 = KuwoHookProfile(
        versionName = "12.1.8.2",
        versionCode = 12_182L,
        playback = KuwoPlaybackHookProfile(
            managerClassName = "cn.kuwo.mod.playcontrol.n",
            contentClassName = "cn.kuwo.base.bean.IContent",
            musicClassName = "cn.kuwo.base.bean.Music",
            singletonMethodName = "L",
            currentMusicMethodName = "S",
            nextContentMethodName = "g0",
        ),
        music = KuwoMusicHookProfile(
            ridFieldName = "rid",
            titleFieldName = "name",
            artistFieldName = "artist",
            albumFieldName = "album",
            durationSecondsFieldName = "duration",
        ),
    )

    private val exactProfiles = listOf(V12_1_8_2)

    fun resolve(versionName: String, versionCode: Long): KuwoHookProfile =
        exactProfiles.firstOrNull {
            it.versionName == versionName && it.versionCode == versionCode
        } ?: exactProfiles.maxBy(KuwoHookProfile::versionCode)
}
