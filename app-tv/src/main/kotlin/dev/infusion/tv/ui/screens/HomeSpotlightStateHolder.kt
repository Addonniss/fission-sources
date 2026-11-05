package dev.infusion.tv.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.feature.vertical.home.HomeSpotlightSource
import kotlin.random.Random

class HomeSpotlightStateHolder {

    internal var drawn: List<HomeHeroCandidate>? by mutableStateOf(null)
        private set

    var index: Int by mutableIntStateOf(0)

    var appliedSource: HomeSpotlightSource? by mutableStateOf(null)
        private set

    var lastShownArtwork: MediaArtworkRef? = null

    internal fun present(source: HomeSpotlightSource, seed: List<HomeHeroCandidate>) {
        if (drawn != null && appliedSource == source) return
        drawn = HomeHeroSpotlight.sample(seed, Random.Default)
        index = 0
        appliedSource = source
    }

    internal fun awaitSource(source: HomeSpotlightSource) {
        if (drawn == null && appliedSource == source) return
        drawn = null
        index = 0
        lastShownArtwork = null
        appliedSource = source
    }

    fun reshuffle() {
        drawn = null
        index = 0
        lastShownArtwork = null
        appliedSource = null
    }
}
