/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed.model

interface LyricTiming {
    var agent: String?
    var begin: Int
    var duration: Int
    var end: Int
}
