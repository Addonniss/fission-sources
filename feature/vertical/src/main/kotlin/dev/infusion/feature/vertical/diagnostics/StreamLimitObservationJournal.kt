package dev.infusion.feature.vertical.diagnostics

import dev.infusion.source.aggregation.resolve.ProviderResolutionObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class StreamLimitObservationJournal {

    private val _observations = MutableStateFlow<List<ProviderResolutionObservation>>(emptyList())

    val observations: StateFlow<List<ProviderResolutionObservation>> = _observations.asStateFlow()

    fun record(observation: ProviderResolutionObservation) {
        _observations.value = (_observations.value + observation).takeLast(MAX_OBSERVATIONS)
    }

    fun toLogLines(): List<String> = _observations.value.flatMap { it.toLogLines() }

    companion object {
        const val MAX_OBSERVATIONS = 4
    }
}
