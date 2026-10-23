/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import com.juren233.hle.providers.applemusic.xposed.diagnostics.DiagnosticDecisionGate

internal fun createListenNowDiagnosticGate(): DiagnosticDecisionGate = DiagnosticDecisionGate(
    minIntervalMs = 5_000L,
    // Each card contributes several event keys. A small shared budget churns on a
    // normal multi-section feed and turns repeated builder callbacks back into a flood.
    maxKeys = 1_024,
)
