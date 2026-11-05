package dev.infusion.core.domain.watch

import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.model.watch.WatchState
import kotlinx.coroutines.flow.Flow

interface WatchStateStore : WatchStateRepository {

    fun save(state: WatchState)

    override fun observe(key: WatchKey): Flow<WatchState?>

    fun current(key: WatchKey): WatchState?
}
