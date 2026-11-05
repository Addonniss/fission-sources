package dev.infusion.core.playback.api

data class AudioOutputProgress(

    val routeStarted: Boolean = false,

    val outputAdvanced: Boolean = false,

    val underruns: Int = 0,
) {
    init {
        require(underruns >= 0) { "underruns must not be negative (was $underruns)" }
    }

    companion object {

        val NONE: AudioOutputProgress = AudioOutputProgress()
    }
}
