package dev.infusion.core.domain.watch

import dev.infusion.core.model.watch.WatchState
import dev.infusion.core.model.watch.WatchStatus
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class ContinueWatchingProjection(

    val inProgress: List<WatchState> = emptyList(),

    val finished: List<WatchState> = emptyList(),
)

class GetContinueWatchingUseCase(
    private val repository: WatchStateRepository,
) {
    fun observe(): Flow<List<WatchState>> = observeProjection().map { it.inProgress }

    fun observeProjection(): Flow<ContinueWatchingProjection> = repository.observeAll().map { states ->
        val ordered = states.sortedByDescending { it.lastMeaningfulAt ?: Instant.EPOCH }
        ContinueWatchingProjection(
            inProgress = ordered.filter { it.status == WatchStatus.IN_PROGRESS },
            finished = ordered.filter {
                it.status == WatchStatus.COMPLETED || it.status == WatchStatus.EXPLICITLY_RESET
            },
        )
    }
}
