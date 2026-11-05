package dev.infusion.feature.vertical.sourcepicker

import dev.infusion.core.domain.candidate.CandidateResolverPort
import dev.infusion.core.domain.candidate.ResolutionPhase
import dev.infusion.core.model.EditionId
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.playback.PlaybackFailure
import dev.infusion.feature.vertical.model.PickerRow
import dev.infusion.feature.vertical.model.PickerRowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PlaybackStartPhase {
    data object Resolving : PlaybackStartPhase
    data class Selected(val candidate: PlayableCandidate) : PlaybackStartPhase
    data object ManualFallback : PlaybackStartPhase
    data class Failed(val failure: PlaybackFailure) : PlaybackStartPhase
}

class PlaybackStartStateHolder(
    private val titleId: TitleId,
    private val editionId: EditionId?,
    private val resolver: CandidateResolverPort,
    private val plan: AutoplaySelection,
    private val scope: CoroutineScope,
) {

    data class State(val phase: PlaybackStartPhase = PlaybackStartPhase.Resolving)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val jellyfinSourceId: String? get() = plan.jellyfinSourceId

    private var resolveJob: Job? = null
    private var rows: List<PickerRow> = emptyList()
    private var existingTerminal = false
    private var resolutionComplete = false
    private var committed = false

    init {
        start()
    }

    fun start() {
        resolveJob?.cancel()
        rows = emptyList()
        existingTerminal = false
        resolutionComplete = false
        committed = false
        _state.value = State(PlaybackStartPhase.Resolving)
        resolveJob = scope.launch {
            resolver.resolve(titleId, editionId).collect { phase ->
                if (committed) return@collect
                when (phase) {
                    is ResolutionPhase.Loading -> Unit
                    is ResolutionPhase.Candidates -> onCandidates(phase)
                    is ResolutionPhase.Failed -> commit(PlaybackStartPhase.Failed(phase.failure))
                }
            }
        }
    }

    private fun onCandidates(phase: ResolutionPhase.Candidates) {
        rows = SourceCandidateProjection.mergeRows(rows, phase.candidates, phase.complete)
        resolutionComplete = phase.complete

        existingTerminal = existingTerminal || phase.existingTerminal
        val decision = AutoplaySelector.decide(
            plan = plan,
            selectionOrder = rows,
            allOrdered = SourceCandidateProjection.allOrderedBySizeDescending(rows),
            jellyfinTerminal = existingTerminal || resolutionComplete,
            complete = resolutionComplete,
        )
        when (decision) {
            is AutoplayDecision.Winner -> {
                val candidate = rows.firstOrNull { it.key == decision.key }
                    ?.let { (it.state as? PickerRowState.Ready)?.candidate }

                if (candidate != null) commit(PlaybackStartPhase.Selected(candidate))
            }
            AutoplayDecision.None -> {

                if (resolutionComplete) commit(PlaybackStartPhase.ManualFallback)
            }
            AutoplayDecision.Wait -> Unit
        }
    }

    private fun commit(phase: PlaybackStartPhase) {
        if (committed) return
        committed = true
        _state.value = State(phase)
        resolveJob?.cancel()
    }

    fun cancel() {
        committed = true
        resolveJob?.cancel()
    }
}
