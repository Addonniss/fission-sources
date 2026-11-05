package dev.infusion.feature.vertical.setup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SourceConnectOutcome {

    data class Connected(
        val versionLabel: String?,
        val qualificationLabel: String?,
    ) : SourceConnectOutcome

    data class Failed(val message: String) : SourceConnectOutcome
    data class Invalid(val reason: String) : SourceConnectOutcome
}

sealed interface JellyfinSetupStatus {
    data object Idle : JellyfinSetupStatus
    data object Connecting : JellyfinSetupStatus
    data class Connected(val versionLabel: String?, val qualificationLabel: String?) : JellyfinSetupStatus
    data class Failed(val message: String) : JellyfinSetupStatus
    data object Removed : JellyfinSetupStatus
}

class JellyfinSetupStateHolder(
    private val scope: CoroutineScope,
    private val connectAction: suspend (serverUrl: String, username: String, password: String, consentLan: Boolean) -> SourceConnectOutcome,
    private val removeAction: suspend () -> Boolean,
    private val initialStatus: JellyfinSetupStatus = JellyfinSetupStatus.Idle,

    private val actionDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
) {

    private val _state = MutableStateFlow(initialStatus)
    val state: StateFlow<JellyfinSetupStatus> = _state.asStateFlow()

    val busy: Boolean get() = _state.value == JellyfinSetupStatus.Connecting

    fun connect(serverUrl: String, username: String, password: String, consentLan: Boolean) {
        if (busy) return
        _state.value = JellyfinSetupStatus.Connecting
        scope.launch {

            _state.value = when (
                val outcome = withContext(actionDispatcher) {
                    connectAction(serverUrl.trim(), username.trim(), password, consentLan)
                }
            ) {

                is SourceConnectOutcome.Connected -> JellyfinSetupStatus.Connected(
                    versionLabel = outcome.versionLabel,
                    qualificationLabel = outcome.qualificationLabel,
                )
                is SourceConnectOutcome.Failed -> JellyfinSetupStatus.Failed(outcome.message)
                is SourceConnectOutcome.Invalid -> JellyfinSetupStatus.Failed(outcome.reason)
            }
        }
    }

    fun remove() {
        if (busy) return
        _state.value = JellyfinSetupStatus.Connecting
        scope.launch {
            _state.value = if (withContext(actionDispatcher) { removeAction() }) {
                JellyfinSetupStatus.Removed
            } else {
                JellyfinSetupStatus.Idle
            }
        }
    }

    fun reset() {
        _state.value = JellyfinSetupStatus.Idle
    }
}
