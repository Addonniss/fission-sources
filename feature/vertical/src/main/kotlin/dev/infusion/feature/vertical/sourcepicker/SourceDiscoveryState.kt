package dev.infusion.feature.vertical.sourcepicker

import dev.infusion.feature.vertical.model.PickerResolution

enum class SourceDiscoveryState {

    UNSEARCHED,

    SEARCHING,

    AVAILABLE,

    EMPTY,

    ERROR,
}

fun deriveSourceDiscoveryState(
    started: Boolean,
    resolution: PickerResolution,
): SourceDiscoveryState {
    if (!started) return SourceDiscoveryState.UNSEARCHED
    return when (resolution) {
        is PickerResolution.Failed -> SourceDiscoveryState.ERROR
        is PickerResolution.Loading -> SourceDiscoveryState.SEARCHING
        is PickerResolution.Resolved -> when {
            resolution.rows.isNotEmpty() -> SourceDiscoveryState.AVAILABLE
            resolution.complete -> SourceDiscoveryState.EMPTY
            else -> SourceDiscoveryState.SEARCHING
        }
    }
}
