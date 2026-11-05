package dev.infusion.tv.playback

import dev.infusion.core.playback.api.DisplayModeOption

interface DisplayModeHost {

    fun advertisedModes(): List<DisplayModeOption>

    fun currentMode(): DisplayModeOption?

    fun requestMode(modeId: Int)
}

const val NoPreferredDisplayMode: Int = 0
