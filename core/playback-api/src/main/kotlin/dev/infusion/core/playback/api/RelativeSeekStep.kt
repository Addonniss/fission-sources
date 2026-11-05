package dev.infusion.core.playback.api

enum class RelativeSeekStep(

    val seconds: Int,
) {
    TEN_SECONDS(10),
    FIFTEEN_SECONDS(15),
    THIRTY_SECONDS(30),
    SIXTY_SECONDS(60),
    ;

    val stepMs: Long get() = seconds * 1_000L

    val storageKey: String get() = seconds.toString()

    companion object {

        val DEFAULT: RelativeSeekStep = TEN_SECONDS

        fun orDefault(storageKey: String?): RelativeSeekStep {
            val value = storageKey?.trim()?.toIntOrNull() ?: return DEFAULT
            return entries.firstOrNull { it.seconds == value } ?: DEFAULT
        }
    }
}

data class RelativeSeekOutcome(

    val requestedStepMs: Long,

    val commandedMs: Long,
) {

    val performed: Boolean get() = commandedMs != 0L

    val direction: Int get() = commandedMs.compareTo(0L)
}
