package dev.infusion.tv.ui.screens

internal enum class SeriesBackStep {

    PARENT_CHIP,

    STAGE_ONE,

    LEAVE,
}

internal fun seriesBackStep(
    focusedInRegion: Boolean,
    parentChipKey: String?,
    stageOneControlKey: String?,
): SeriesBackStep = when {
    !focusedInRegion -> SeriesBackStep.LEAVE
    parentChipKey != null -> SeriesBackStep.PARENT_CHIP
    stageOneControlKey != null -> SeriesBackStep.STAGE_ONE
    else -> SeriesBackStep.LEAVE
}
