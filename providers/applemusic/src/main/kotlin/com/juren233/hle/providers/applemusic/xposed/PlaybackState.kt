package com.juren233.hle.providers.applemusic.xposed

enum class PlaybackState(private val value: Int) {
    UNKNOWN(-1),
    STOPPED(0),
    PLAYING(1),
    PAUSED(2);

    companion object {
        private val valueMap = entries.associateBy { it.value }

        fun of(n: Int): PlaybackState {
            return valueMap[n] ?: UNKNOWN
        }
    }
}