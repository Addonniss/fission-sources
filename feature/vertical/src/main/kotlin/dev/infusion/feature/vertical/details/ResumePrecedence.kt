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

internal data class DetailsWatchFacts(
    val watched: WatchedDisplay,
    val attemptResumePositionMs: Long?,
) {

    val resumable: Boolean get() = attemptResumePositionMs != null

    companion object {

        fun hint(resumeHintMs: Long?): DetailsWatchFacts = DetailsWatchFacts(
            watched = resumeHintMs?.let { WatchedDisplay.InProgress(it) } ?: WatchedDisplay.Unseen,
            attemptResumePositionMs = resumeHintMs?.takeIf { it > 0L },
        )
    }
}

internal fun detailsWatchFacts(
    localState: dev.infusion.core.model.watch.WatchState?,
    serverResume: ServerResumePosition?,
    hintResumeMs: Long?,
): DetailsWatchFacts {

    val attempt: WatchedDisplay = when (localState?.status) {
        dev.infusion.core.model.watch.WatchStatus.IN_PROGRESS ->
            WatchedDisplay.InProgress(localState.positionMs, localState.completionFraction)

        dev.infusion.core.model.watch.WatchStatus.COMPLETED -> WatchedDisplay.Completed
        else -> WatchedDisplay.Unseen
    }
    val resolved = effectiveWatched(
        serverResume = serverResume,
        local = attempt,
        localStatePresent = localState != null,
        hintResumeMs = hintResumeMs,
        localObservedAt = localState?.lastMeaningfulAt,
    )

    val watched = if (localState?.isWatched == true) WatchedDisplay.Completed else resolved
    return DetailsWatchFacts(
        watched = watched,
        attemptResumePositionMs = (resolved as? WatchedDisplay.InProgress)
            ?.positionMs
            ?.takeIf { it > 0L },
    )
}
