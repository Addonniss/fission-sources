package dev.infusion.feature.vertical.details

import dev.infusion.core.playback.api.ServerResumePosition
import dev.infusion.feature.vertical.media.ResumeAuthority
import dev.infusion.feature.vertical.model.WatchedDisplay
import java.time.Instant

internal fun effectiveWatched(
    serverResume: ServerResumePosition?,
    local: WatchedDisplay,
    localStatePresent: Boolean,
    hintResumeMs: Long?,

    localObservedAt: Instant? = null,
): WatchedDisplay {

    val serverPosition = serverResume?.takeIf {
        ResumeAuthority.serverDecides(
            serverObservedAt = it.observedAt,
            localObservedAt = localObservedAt,
            localObservationPresent = localStatePresent,
        )
    }
    return when {

        serverPosition != null && serverPosition.positionMs > 0L ->
            WatchedDisplay.InProgress(serverPosition.positionMs)

        local is WatchedDisplay.Completed -> local

        serverPosition != null -> WatchedDisplay.Unseen

        localStatePresent -> local

        hintResumeMs != null -> WatchedDisplay.InProgress(hintResumeMs)

        else -> local
    }
}
