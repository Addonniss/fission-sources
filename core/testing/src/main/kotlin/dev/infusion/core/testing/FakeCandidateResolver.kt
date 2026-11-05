package dev.infusion.core.testing

import dev.infusion.core.domain.candidate.CandidateResolverPort
import dev.infusion.core.domain.candidate.ResolutionPhase
import dev.infusion.core.model.EditionId
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.playback.FailureRecoverability
import dev.infusion.core.model.playback.PlaybackFailure
import dev.infusion.core.model.playback.PlaybackFailureKind
import dev.infusion.core.model.playback.PlaybackStage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FakeCandidateResolver(
    private val fixtures: FakeFixtures = FakeFixtures,
) : CandidateResolverPort {

    var incrementalDelayMs: Long = 400

    var failAll: Boolean = false

    override fun resolve(titleId: TitleId, editionId: EditionId?): Flow<ResolutionPhase> = flow {
        if (failAll) {
            emit(
                ResolutionPhase.Failed(
                    PlaybackFailure(
                        kind = PlaybackFailureKind.SOURCE_UNAVAILABLE,
                        recoverability = FailureRecoverability.TRANSIENT,
                        stage = PlaybackStage.RESOLVE,
                        message = "Demo source timed out while resolving candidates.",
                        remedyHint = "Retry or choose another source.",
                    ),
                ),
            )
            return@flow
        }
        val candidates = fixtures.candidatesFor(titleId, editionId)
        if (candidates.isEmpty()) {
            emit(ResolutionPhase.Candidates(candidates = emptyList(), complete = true))
            return@flow
        }

        emit(ResolutionPhase.Candidates(candidates = listOf(candidates.first()), complete = false))
        if (candidates.size > 1) {
            delay(incrementalDelayMs)
            emit(ResolutionPhase.Candidates(candidates = candidates.drop(1), complete = false))
        }
        emit(ResolutionPhase.Candidates(candidates = emptyList(), complete = true))
    }
}
