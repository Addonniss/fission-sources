package dev.infusion.core.domain.candidate

import dev.infusion.core.model.EditionId
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.ProviderNotice
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.playback.PlaybackFailure
import kotlinx.coroutines.flow.Flow

sealed interface ResolutionPhase {

    data object Loading : ResolutionPhase

    data class Candidates(
        val candidates: List<PlayableCandidate>,
        val complete: Boolean,
        val notices: List<ProviderNotice> = emptyList(),
        val existingTerminal: Boolean = false,
    ) : ResolutionPhase

    data class Failed(val failure: PlaybackFailure) : ResolutionPhase
}

interface CandidateResolverPort {
    fun resolve(titleId: TitleId, editionId: EditionId?): Flow<ResolutionPhase>
}
