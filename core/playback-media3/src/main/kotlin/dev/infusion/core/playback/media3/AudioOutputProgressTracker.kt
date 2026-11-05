package dev.infusion.core.playback.media3

import dev.infusion.core.playback.api.AudioOutputProgress

internal class AudioOutputProgressTracker {

    private var routeStarted: Boolean = false
    private var outputAdvanced: Boolean = false
    private var underruns: Int = 0

    fun onRouteStarted() {
        routeStarted = true
    }

    fun onOutputAdvanced() {
        outputAdvanced = true
    }

    fun onUnderrun() {
        underruns++
    }

    fun snapshot(): AudioOutputProgress = AudioOutputProgress(
        routeStarted = routeStarted,
        outputAdvanced = outputAdvanced,
        underruns = underruns,
    )
}
