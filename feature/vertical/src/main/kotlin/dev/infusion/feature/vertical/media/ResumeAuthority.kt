package dev.infusion.feature.vertical.media

import java.time.Instant

object ResumeAuthority {

    fun serverOutranksLocal(serverObservedAt: Instant?, localObservedAt: Instant?): Boolean {
        if (serverObservedAt == null) return false
        if (localObservedAt == null) return true
        return serverObservedAt.isAfter(localObservedAt)
    }

    fun localOutranksServer(serverObservedAt: Instant?, localObservedAt: Instant?): Boolean =
        !serverOutranksLocal(serverObservedAt, localObservedAt)

    fun serverDecides(
        serverObservedAt: Instant?,
        localObservedAt: Instant?,
        localObservationPresent: Boolean,
    ): Boolean {
        if (!localObservationPresent) return true
        return serverOutranksLocal(serverObservedAt, localObservedAt)
    }
}

data class LocalResumeObservation(
    val positionMs: Long,

    val observedAt: Instant?,
)
