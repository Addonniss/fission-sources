package dev.infusion.core.playback.api

enum class SubtitleMatchReason {

    PRIMARY_EXACT,

    PRIMARY_BASE,

    SECONDARY_EXACT,

    SECONDARY_BASE,
}

sealed interface SubtitleSelectionDecision {

    data class Select(val trackId: TrackId, val reason: SubtitleMatchReason) : SubtitleSelectionDecision

    data object Off : SubtitleSelectionDecision

    data object NoEligible : SubtitleSelectionDecision

    data object LeaveToEngine : SubtitleSelectionDecision
}
