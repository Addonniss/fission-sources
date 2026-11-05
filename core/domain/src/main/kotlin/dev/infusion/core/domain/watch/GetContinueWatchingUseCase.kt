package dev.infusion.core.domain.watch

import dev.infusion.core.model.watch.WatchState
import dev.infusion.core.model.watch.WatchStatus
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GetContinueWatchingUseCase(
    private val repository: WatchStateRepository,
) {
    fun observe(): Flow<List<WatchState>> = repository.observeAll().map { states ->
        states
            .filter { it.status == WatchStatus.IN_PROGRESS }
            .sortedByDescending { it.lastMeaningfulAt ?: Instant.EPOCH }
    }
}
