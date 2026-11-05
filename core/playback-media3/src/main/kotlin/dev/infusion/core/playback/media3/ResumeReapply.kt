package dev.infusion.core.playback.media3

internal object ResumeReapply {

    const val ATTAINED_TOLERANCE_MS: Long = 2_000L

    const val ATTAINED_SAMPLES: Int = 2

    enum class Decision {

        NONE,

        ATTAINED,

        REAPPLY,
    }

    data class Input(
        val requestedMs: Long?,
        val attainedMs: Long,
        val playbackReady: Boolean,
        val playing: Boolean,
        val attainedSamples: Int,
        val userCommanded: Boolean,
        val alreadyFired: Boolean,
    )

    fun decide(input: Input): Decision = when {

        input.requestedMs == null || input.requestedMs <= 0L -> Decision.NONE

        !input.playbackReady || !input.playing -> Decision.NONE

        input.attainedMs >= input.requestedMs - ATTAINED_TOLERANCE_MS &&
            input.attainedSamples >= ATTAINED_SAMPLES -> Decision.ATTAINED

        input.attainedMs >= input.requestedMs - ATTAINED_TOLERANCE_MS -> Decision.NONE

        input.userCommanded || input.alreadyFired -> Decision.NONE

        else -> Decision.REAPPLY
    }
}
