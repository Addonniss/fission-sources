package dev.infusion.feature.vertical.sourcepicker

import dev.infusion.core.domain.candidate.CandidateResolverPort
import dev.infusion.core.domain.candidate.ResolutionPhase
import dev.infusion.core.model.EditionId
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.ProviderNotice
import dev.infusion.core.model.TitleId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CompositeCandidateResolver(
    private val existing: CandidateResolverPort,
    private val addons: CandidateResolverPort,
) : CandidateResolverPort {

    override fun resolve(titleId: TitleId, editionId: EditionId?): Flow<ResolutionPhase> = channelFlow {
        send(ResolutionPhase.Loading)
        val seen = LinkedHashMap<String, PlayableCandidate>()

        val seenNotices = LinkedHashSet<ProviderNotice>()
        val mutex = Mutex()
        var sawCandidates = false
        var failed: ResolutionPhase.Failed? = null
        var lastEmittedKeys: List<String>? = null
        var existingTerminal = false

        suspend fun emitSnapshot() {
            val keys = seen.keys.toList()
            if (keys.isEmpty() || keys == lastEmittedKeys) return
            lastEmittedKeys = keys
            send(
                ResolutionPhase.Candidates(
                    candidates = seen.values.toList(),
                    complete = false,
                    notices = seenNotices.toList(),
                    existingTerminal = existingTerminal,
                ),
            )
        }

        suspend fun consume(phase: ResolutionPhase) {
            when (phase) {
                is ResolutionPhase.Loading -> Unit
                is ResolutionPhase.Candidates -> mutex.withLock {
                    if (phase.candidates.isNotEmpty()) sawCandidates = true
                    for (candidate in phase.candidates) {
                        seen[candidate.candidateId.value.toString()] = candidate
                    }
                    seenNotices.addAll(phase.notices)
                    emitSnapshot()
                }
                is ResolutionPhase.Failed -> mutex.withLock { if (!sawCandidates) failed = phase }
            }
        }

        val existingJob = launch {
            existing.resolve(titleId, editionId).collect { consume(it) }

            mutex.withLock {
                existingTerminal = true
                emitSnapshot()
            }
        }
        val addonsJob = launch {
            addons.resolve(titleId, editionId).collect { consume(it) }
        }
        existingJob.join()
        addonsJob.join()

        mutex.withLock {
            val failure = failed
            if (!sawCandidates && failure != null) {
                send(failure)
            } else {
                send(
                    ResolutionPhase.Candidates(
                        candidates = seen.values.toList(),
                        complete = true,
                        notices = seenNotices.toList(),
                        existingTerminal = existingTerminal,
                    ),
                )
            }
        }
    }
}
