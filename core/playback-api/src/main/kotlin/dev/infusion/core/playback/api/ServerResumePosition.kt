package dev.infusion.core.playback.api

import java.time.Instant

data class ServerResumePosition(
    val positionMs: Long,

    val observedAt: Instant? = null,
) {
    init {
        require(positionMs >= 0L) { "a server resume position must be >= 0" }
    }
}
