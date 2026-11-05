package dev.infusion.core.sync.store

import dev.infusion.core.domain.watch.WatchStateStore
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.model.watch.WatchState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class InMemoryWatchStateStore(
    initialStates: List<WatchState> = emptyList(),
) : WatchStateStore {

    private val states = MutableStateFlow(initialStates.associateBy { it.watchKey })

    override fun observeAll(): Flow<List<WatchState>> = states.map { it.values.toList() }

    override fun observe(key: WatchKey): Flow<WatchState?> = states.map { it[key] }

    override fun current(key: WatchKey): WatchState? = states.value[key]

    override fun save(state: WatchState) {
        states.update { it + (state.watchKey to state) }
    }
}
