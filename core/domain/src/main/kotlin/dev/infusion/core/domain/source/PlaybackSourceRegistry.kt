package dev.infusion.core.domain.source

import dev.infusion.core.model.PlayableCandidate

fun interface PlaybackSourceRegistry {

    fun ensureSourceRow(candidate: PlayableCandidate): Boolean
}
