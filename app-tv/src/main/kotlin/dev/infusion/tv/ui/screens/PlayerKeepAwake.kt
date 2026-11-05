package dev.infusion.tv.ui.screens

import dev.infusion.core.playback.api.PlaybackState
import dev.infusion.feature.vertical.model.PlayerUiState

internal object PlayerKeepAwake {

    private val HELD_PHASES = setOf(
        PlaybackState.IDLE,
        PlaybackState.PREPARING,
        PlaybackState.BUFFERING,
        PlaybackState.READY,
        PlaybackState.PLAYING,
    )

    fun shouldHoldDisplayOn(state: PlayerUiState?, startupPresentationActive: Boolean): Boolean {
        val phase = state?.phase ?: return startupPresentationActive
        return phase in HELD_PHASES
    }
}
