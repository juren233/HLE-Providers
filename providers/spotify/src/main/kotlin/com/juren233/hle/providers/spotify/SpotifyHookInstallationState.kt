/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

import com.juren233.hyperlyricsenhanced.provider.OfficialProviderMethodTarget

/** Tracks installed descriptors across the early fallback and later selected profile. */
internal class SpotifyHookInstallationState {
    private val requestTargets = linkedSetOf<OfficialProviderMethodTarget>()
    private val clientConstructors = linkedSetOf<SpotifyLyricsClientConstructorProfile>()
    private val endpointSelectors = linkedSetOf<OfficialProviderMethodTarget>()
    private var missingEndpointSelectorLogged = false

    fun needsRequestTarget(target: OfficialProviderMethodTarget): Boolean = target !in requestTargets

    fun recordRequestTarget(target: OfficialProviderMethodTarget) {
        requestTargets += target
    }

    fun needsClientConstructor(profile: SpotifyLyricsClientConstructorProfile): Boolean =
        profile !in clientConstructors

    fun recordClientConstructor(profile: SpotifyLyricsClientConstructorProfile) {
        clientConstructors += profile
    }

    fun needsEndpointSelector(target: OfficialProviderMethodTarget): Boolean =
        target !in endpointSelectors

    fun recordEndpointSelector(target: OfficialProviderMethodTarget) {
        endpointSelectors += target
    }

    fun shouldLogMissingEndpointSelector(): Boolean {
        if (missingEndpointSelectorLogged) return false
        missingEndpointSelectorLogged = true
        return true
    }
}
