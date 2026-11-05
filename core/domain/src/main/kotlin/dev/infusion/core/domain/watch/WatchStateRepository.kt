package dev.infusion.core.domain.watch

import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.model.watch.WatchState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

interface WatchStateRepository {

    fun observeAll(): Flow<List<WatchState>>

    fun observe(watchKey: WatchKey): Flow<WatchState?> = observeAll()
        .map { states -> states.firstOrNull { it.watchKey == watchKey } }
        .distinctUntilChanged()
}
