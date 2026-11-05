package dev.infusion.core.domain.candidate

import dev.infusion.core.model.CandidateId
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.SourceId

interface CandidateHandoffStore {

    suspend fun put(candidate: PlayableCandidate)

    suspend fun byId(candidateId: CandidateId): PlayableCandidate?

    suspend fun clear()

    fun clearForSource(sourceId: SourceId)
}
