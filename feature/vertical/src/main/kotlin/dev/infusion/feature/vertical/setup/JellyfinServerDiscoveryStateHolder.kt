package dev.infusion.feature.vertical.setup

import dev.infusion.core.domain.jellyfin.JellyfinDiscoveredServer
import dev.infusion.core.domain.jellyfin.JellyfinDiscoveryPort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface JellyfinDiscoveryState {

    data object Idle : JellyfinDiscoveryState

    data object Searching : JellyfinDiscoveryState

    data class Ready(val servers: List<JellyfinDiscoveredServer>) : JellyfinDiscoveryState
}

class JellyfinServerDiscoveryStateHolder(
    private val scope: CoroutineScope,
    private val discovery: JellyfinDiscoveryPort,
    private val lanDiscoveryAllowed: () -> Boolean = { true },
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val _state = MutableStateFlow<JellyfinDiscoveryState>(JellyfinDiscoveryState.Idle)
    val state: StateFlow<JellyfinDiscoveryState> = _state.asStateFlow()

    private var generation: Int = 0
    private var job: Job? = null

    fun start() {
        job?.cancel()
        val gen = ++generation
        if (!lanDiscoveryAllowed()) {
            _state.value = JellyfinDiscoveryState.Ready(emptyList())
            return
        }
        _state.value = JellyfinDiscoveryState.Searching
        job = scope.launch {
            val servers = withContext(dispatcher) {
                try {
                    discovery.discover(timeoutMs)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {

                    emptyList()
                }
            }
            if (gen != generation) return@launch
            _state.value = JellyfinDiscoveryState.Ready(servers)
        }
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
        _state.value = JellyfinDiscoveryState.Idle
    }

    companion object {

        const val DEFAULT_TIMEOUT_MS: Long = 1_200L
    }
}
