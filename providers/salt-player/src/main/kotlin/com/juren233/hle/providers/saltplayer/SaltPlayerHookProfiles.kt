/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.saltplayer

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget

internal data class SaltPlayerLyricsHookProfile(
    val documentClassName: String,
    val lineClassName: String,
    val cellClassName: String,
    val publishMethodName: String,
    val documentLinesFieldName: String,
    val lineBeginFieldName: String,
    val lineEndFieldName: String,
    val lineCellsFieldName: String,
    val lineTranslationFieldName: String,
    val lineMainTextFieldName: String,
    val cellBeginFieldName: String,
    val cellEndFieldName: String,
    val cellTextFieldName: String,
)

internal data class SaltPlayerQueueHookProfile(
    val stateFlowClassName: String,
    val stateFlowValueGetterName: String,
    val stateFlowFieldName: String,
    val stateClassName: String,
    val itemClassName: String,
    val modeClassName: String,
    val circleModeName: String,
    val circleEndModeName: String,
    val repeatOneModeName: String,
    val randomModeName: String,
    val stateModeFieldName: String,
    val stateNormalQueueFieldName: String,
    val stateNormalIndexFieldName: String,
    val stateRandomQueueFieldName: String,
    val stateRandomIndexFieldName: String,
    val stateReadyToSaveFieldName: String,
    val itemDataFieldName: String,
)

internal data class SaltPlayerSongHookProfile(
    val className: String,
    val idGetterName: String,
    val titleGetterName: String,
    val artistGetterName: String,
    val albumGetterName: String,
    val durationGetterName: String,
)

internal data class SaltPlayerHookProfile(
    val versionName: String,
    val versionCode: Long,
    val musicControllerClassName: String,
    val lyrics: SaltPlayerLyricsHookProfile,
    val queue: SaltPlayerQueueHookProfile,
    val song: SaltPlayerSongHookProfile,
) {
    val publishLyricsDocument: OfficialProviderMethodTarget
        get() = OfficialProviderMethodTarget(
            className = musicControllerClassName,
            methodName = lyrics.publishMethodName,
            parameterTypeNames = listOf(lyrics.documentClassName),
            returnTypeName = "void",
            isStatic = true,
        )
}

/**
 * Exact-version registry for every Salt Player runtime identifier used by the Provider.
 *
 * All identifiers below were verified against the original APK DEX on 2026-08-06.
 * They intentionally use raw binary names instead of JADX-generated aliases.
 */
internal object SaltPlayerHookProfiles {
    private const val MUSIC_CONTROLLER_CLASS = "com.salt.music.service.MusicController"
    private const val STATE_FLOW_CLASS = "kotlinx.coroutines.flow.StateFlow"
    private const val SONG_CLASS = "com.salt.music.data.entry.Song"

    private val SONG = SaltPlayerSongHookProfile(
        className = SONG_CLASS,
        idGetterName = "getId",
        titleGetterName = "getTitle",
        artistGetterName = "getArtist",
        albumGetterName = "getAlbum",
        durationGetterName = "getDuration",
    )

    // Original DEX descriptors shared by 12.1.0 (2026070208) and 12.1.1 (2026070502):
    // MusicController.ޣ(xv0):void, MusicController.ތ:StateFlow<r42>,
    // r42(a42, List<o42>, int, List<o42>, int, boolean), o42.ׯ:Object.
    private fun version12_1(versionName: String, versionCode: Long) = SaltPlayerHookProfile(
        versionName = versionName,
        versionCode = versionCode,
        musicControllerClassName = MUSIC_CONTROLLER_CLASS,
        lyrics = SaltPlayerLyricsHookProfile(
            documentClassName = "androidx.obf.xv0",
            lineClassName = "androidx.obf.hw0",
            cellClassName = "androidx.obf.tv0",
            publishMethodName = "\u07a3",
            documentLinesFieldName = "\u0528",
            lineBeginFieldName = "\u037f",
            lineEndFieldName = "\u0528",
            lineCellsFieldName = "\u0529",
            lineTranslationFieldName = "\u052a",
            lineMainTextFieldName = "\u052b",
            cellBeginFieldName = "\u037f",
            cellEndFieldName = "\u0528",
            cellTextFieldName = "\u0529",
        ),
        queue = SaltPlayerQueueHookProfile(
            stateFlowClassName = STATE_FLOW_CLASS,
            stateFlowValueGetterName = "getValue",
            stateFlowFieldName = "\u078c",
            stateClassName = "androidx.obf.r42",
            itemClassName = "androidx.obf.o42",
            modeClassName = "androidx.obf.a42",
            circleModeName = "Circle",
            circleEndModeName = "CircleEnd",
            repeatOneModeName = "RepeatOne",
            randomModeName = "Random",
            stateModeFieldName = "\u037f",
            stateNormalQueueFieldName = "\u0528",
            stateNormalIndexFieldName = "\u0529",
            stateRandomQueueFieldName = "\u052a",
            stateRandomIndexFieldName = "\u052b",
            stateReadyToSaveFieldName = "\u052c",
            itemDataFieldName = "\u05ef",
        ),
        song = SONG,
    )

    val V12_1_1 = version12_1("12.1.1", 2_026_070_502L)
    val V12_1_0 = version12_1("12.1.0", 2_026_070_208L)

    // Original DEX descriptors:
    // MusicController.ޕ(jv0):void, MusicController.އ:StateFlow<h32>,
    // h32(q22, List<e32>, int, List<e32>, int, boolean), e32.Ԯ:Object.
    val V12_0_0 = SaltPlayerHookProfile(
        versionName = "12.0.0",
        versionCode = 2_026_061_801L,
        musicControllerClassName = MUSIC_CONTROLLER_CLASS,
        lyrics = SaltPlayerLyricsHookProfile(
            documentClassName = "androidx.obf.jv0",
            lineClassName = "androidx.obf.tv0",
            cellClassName = "androidx.obf.fv0",
            publishMethodName = "\u0795",
            documentLinesFieldName = "\u0528",
            lineBeginFieldName = "\u037f",
            lineEndFieldName = "\u0528",
            lineCellsFieldName = "\u0529",
            lineTranslationFieldName = "\u052a",
            lineMainTextFieldName = "\u052b",
            cellBeginFieldName = "\u037f",
            cellEndFieldName = "\u0528",
            cellTextFieldName = "\u0529",
        ),
        queue = SaltPlayerQueueHookProfile(
            stateFlowClassName = STATE_FLOW_CLASS,
            stateFlowValueGetterName = "getValue",
            stateFlowFieldName = "\u0787",
            stateClassName = "androidx.obf.h32",
            itemClassName = "androidx.obf.e32",
            modeClassName = "androidx.obf.q22",
            circleModeName = "Circle",
            circleEndModeName = "CircleEnd",
            repeatOneModeName = "RepeatOne",
            randomModeName = "Random",
            stateModeFieldName = "\u037f",
            stateNormalQueueFieldName = "\u0528",
            stateNormalIndexFieldName = "\u0529",
            stateRandomQueueFieldName = "\u052a",
            stateRandomIndexFieldName = "\u052b",
            stateReadyToSaveFieldName = "\u052c",
            itemDataFieldName = "\u052e",
        ),
        song = SONG,
    )

    // Original DEX descriptors:
    // MusicController.ޔ(cs0):void, MusicController.އ:StateFlow<ez1>,
    // ez1(oy1, List<bz1>, int, List<bz1>, int, boolean), bz1.֏:Object.
    val V11_1_0 = SaltPlayerHookProfile(
        versionName = "11.1.0",
        versionCode = 2_026_031_101L,
        musicControllerClassName = MUSIC_CONTROLLER_CLASS,
        lyrics = SaltPlayerLyricsHookProfile(
            documentClassName = "androidx.core.cs0",
            lineClassName = "androidx.core.ks0",
            cellClassName = "androidx.core.ur0",
            publishMethodName = "\u0794",
            documentLinesFieldName = "\u0528",
            lineBeginFieldName = "\u037f",
            lineEndFieldName = "\u0528",
            lineCellsFieldName = "\u0529",
            lineTranslationFieldName = "\u052a",
            lineMainTextFieldName = "\u052b",
            cellBeginFieldName = "\u037f",
            cellEndFieldName = "\u0528",
            cellTextFieldName = "\u0529",
        ),
        queue = SaltPlayerQueueHookProfile(
            stateFlowClassName = STATE_FLOW_CLASS,
            stateFlowValueGetterName = "getValue",
            stateFlowFieldName = "\u0787",
            stateClassName = "androidx.core.ez1",
            itemClassName = "androidx.core.bz1",
            modeClassName = "androidx.core.oy1",
            circleModeName = "Circle",
            circleEndModeName = "CircleEnd",
            repeatOneModeName = "RepeatOne",
            randomModeName = "Random",
            stateModeFieldName = "\u037f",
            stateNormalQueueFieldName = "\u0528",
            stateNormalIndexFieldName = "\u0529",
            stateRandomQueueFieldName = "\u052a",
            stateRandomIndexFieldName = "\u052b",
            stateReadyToSaveFieldName = "\u052c",
            itemDataFieldName = "\u058f",
        ),
        song = SONG,
    )

    private val exactProfiles = listOf(V12_1_1, V12_1_0, V12_0_0, V11_1_0)

    fun resolve(versionName: String, versionCode: Long): SaltPlayerHookProfile? =
        exactProfiles.firstOrNull {
            it.versionName == versionName && it.versionCode == versionCode
        }
}
