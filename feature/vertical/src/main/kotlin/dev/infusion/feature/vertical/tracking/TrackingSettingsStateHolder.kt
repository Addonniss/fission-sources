package dev.infusion.feature.vertical.tracking

import dev.infusion.core.domain.tracking.TrackingAuthorizationPoll
import dev.infusion.core.domain.tracking.TrackingConnectionState
import dev.infusion.core.domain.tracking.TrackingDeviceAuthorization
import dev.infusion.core.domain.tracking.TrackingSelection
import dev.infusion.core.domain.tracking.TrackingTrackerOption
import dev.infusion.core.domain.tracking.WatchTrackingSelectionControl
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrackingSettingsStateHolder(

    val providerId: String,
    private val providerName: String,
    private val scope: CoroutineScope,

    private val clientRegistered: () -> Boolean,
    private val connectionStateAction: suspend () -> TrackingConnectionState,
    private val beginAction: suspend () -> TrackingDeviceAuthorization,
    private val pollAction: suspend () -> TrackingAuthorizationPoll,
    private val cancelAction: suspend () -> Unit,
    private val disconnectAction: suspend () -> Unit,

    private val selectionControl: WatchTrackingSelectionControl,

    private val connectionChanges: Flow<Unit> = emptyFlow(),

    private val jellyfinConnected: () -> Boolean = { false },
    private val actionDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Instant = Instant::now,
) {

    private val _state = MutableStateFlow(
        TrackingSettingsUiState(
            providerId = providerId,
            providerName = providerName,
            clientRegistered = clientRegistered(),
        ),
    )
    val state: StateFlow<TrackingSettingsUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    private var connectionJob: Job? = null

    init {
        connectionJob = connectionChanges
            .onEach { refresh() }
            .launchIn(scope)
    }

    private val busy: Boolean get() = _state.value.busy

    fun refresh() {
        scope.launch {
            val connection = withContext(actionDispatcher) { runCatching { connectionStateAction() }.getOrNull() }
            if (connection == null) return@launch
            val selection = withContext(actionDispatcher) { readSelection() }
            _state.value = _state.value.copy(
                connection = connection,
                selection = selection.selection,
                trackers = selection.trackers,
                retryableSends = selection.retryableSends,
                userChoiceStoppedSends = selection.userChoiceStoppedSends,
                jellyfinConnected = runCatching { jellyfinConnected() }.getOrDefault(false),
            )
            when (connection) {
                is TrackingConnectionState.AwaitingApproval -> startPolling(connection.authorization)
                else -> stopPolling()
            }
        }
    }

    fun select(selection: TrackingSelection) {
        if (busy) return
        val previous = _state.value.selection
        if (previous == selection) return
        val previousTrackerId = (previous as? TrackingSelection.Tracker)?.trackerId
        val nextTrackerId = (selection as? TrackingSelection.Tracker)?.trackerId

        val departed = previousTrackerId?.takeIf { it != nextTrackerId }
        _state.value = _state.value.copy(busy = true, error = null)
        scope.launch {
            withContext(actionDispatcher) { runCatching { selectionControl.select(selection) } }

            val departedTrackerId = departed ?: _state.value.departedTrackerId
            val snapshot = withContext(actionDispatcher) { readSelection(departedTrackerId) }
            _state.value = _state.value.copy(
                busy = false,
                selection = snapshot.selection,
                trackers = snapshot.trackers,
                retryableSends = snapshot.retryableSends,
                userChoiceStoppedSends = snapshot.userChoiceStoppedSends,
                departedTrackerId = departedTrackerId,
                confirmation = trackingChangeConfirmation(selection, snapshot.trackers, providerName),
            )
            refresh()
        }
    }

    private fun readSelection(
        departedTrackerId: String? = _state.value.departedTrackerId,
    ): SelectionSnapshot {
        val selection = runCatching { selectionControl.current() }
            .getOrDefault(TrackingSelection.OnDeviceOnly)
        val trackers = runCatching { selectionControl.trackers() }.getOrDefault(emptyList())
        val selectedTrackerId = (selection as? TrackingSelection.Tracker)?.trackerId
        val retryable = selectedTrackerId?.let {
            runCatching { selectionControl.retryableCount(it) }.getOrDefault(0)
        } ?: 0

        val stopped = departedTrackerId?.let {
            runCatching { selectionControl.notSentByUserChoiceCount(it) }.getOrDefault(0)
        } ?: 0
        return SelectionSnapshot(
            selection = selection,
            trackers = trackers,
            retryableSends = retryable,
            userChoiceStoppedSends = stopped,
        )
    }

    private data class SelectionSnapshot(
        val selection: TrackingSelection,
        val trackers: List<TrackingTrackerOption>,
        val retryableSends: Int,
        val userChoiceStoppedSends: Int,
    )

    fun connect() {
        if (busy) return
        val current = _state.value
        if (current.connection is TrackingConnectionState.AwaitingApproval) {

            if (pollJob == null) startPolling(current.connection.authorization)
            return
        }
        if (current.connection is TrackingConnectionState.Connected && current.error == null) return
        if (!current.clientRegistered) {
            _state.value = current.copy(error = missingRegistrationMessage(providerName))
            return
        }
        _state.value = current.copy(busy = true, error = null)
        scope.launch {
            val outcome = withContext(actionDispatcher) {
                runCatching { beginAction() }.fold(
                    onSuccess = { Result.success(it) },
                    onFailure = { Result.failure(it) },
                )
            }
            val authorization = outcome.getOrNull()
            _state.value = if (authorization == null) {
                _state.value.copy(busy = false, error = connectFailureMessage(outcome))
            } else {
                _state.value.copy(
                    busy = false,
                    error = null,
                    connection = TrackingConnectionState.AwaitingApproval(authorization),
                )
            }
            if (authorization != null) startPolling(authorization)
        }
    }

    fun cancel() {
        if (busy) return
        stopPolling()
        scope.launch {
            withContext(actionDispatcher) { runCatching { cancelAction() } }
            val connection = withContext(actionDispatcher) { runCatching { connectionStateAction() }.getOrNull() }
            _state.value = _state.value.copy(
                connection = connection ?: TrackingConnectionState.Disconnected,
                error = null,
            )
        }
    }

    fun disconnect() {
        if (busy) return
        stopPolling()
        _state.value = _state.value.copy(busy = true, error = null)
        scope.launch {
            withContext(actionDispatcher) { runCatching { disconnectAction() } }
            val connection = withContext(actionDispatcher) { runCatching { connectionStateAction() }.getOrNull() }
            _state.value = _state.value.copy(
                busy = false,
                connection = connection ?: TrackingConnectionState.Disconnected,
                error = null,
            )
        }
    }

    fun stop() {
        stopPolling()
        connectionJob?.cancel()
        connectionJob = null
    }

    private fun startPolling(authorization: TrackingDeviceAuthorization) {
        stopPolling()
        pollJob = scope.launch {
            var intervalSeconds = authorization.intervalSeconds.coerceAtLeast(1)
            while (isActive) {
                delay(intervalSeconds * 1_000L)

                if (!now().isBefore(authorization.expiresAt)) {
                    _state.value = _state.value.copy(error = expiredMessage(providerName))
                    refresh()
                    return@launch
                }
                when (val result = withContext(actionDispatcher) { runCatching { pollAction() }.getOrNull() }) {
                    is TrackingAuthorizationPoll.Pending -> Unit
                    is TrackingAuthorizationPoll.SlowDown ->
                        intervalSeconds = result.retryAfterSeconds.coerceAtLeast(1)
                    is TrackingAuthorizationPoll.Authorized -> {
                        refresh()
                        return@launch
                    }
                    is TrackingAuthorizationPoll.Expired -> {
                        _state.value = _state.value.copy(error = expiredMessage(providerName))
                        refresh()
                        return@launch
                    }
                    is TrackingAuthorizationPoll.Failed -> {
                        _state.value = _state.value.copy(error = result.reason)
                        refresh()
                        return@launch
                    }
                    null -> Unit
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun connectFailureMessage(outcome: Result<TrackingDeviceAuthorization>): String =
        outcome.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: connectFailedMessage(providerName)

    companion object {

        fun missingRegistrationMessage(providerName: String): String =
            "$providerName is not registered in this build. Connect is unavailable."

        fun expiredMessage(providerName: String): String =
            "The $providerName code expired. Start again to get a new one."

        fun connectFailedMessage(providerName: String): String =
            "Could not start the $providerName authorization."
    }
}

data class TrackingSettingsUiState(
    val providerId: String,
    val providerName: String,
    val clientRegistered: Boolean,
    val connection: TrackingConnectionState = TrackingConnectionState.Disconnected,
    val busy: Boolean = false,
    val error: String? = null,

    val selection: TrackingSelection = TrackingSelection.OnDeviceOnly,

    val trackers: List<TrackingTrackerOption> = emptyList(),

    val retryableSends: Int = 0,

    val userChoiceStoppedSends: Int = 0,

    val departedTrackerId: String? = null,

    val jellyfinConnected: Boolean = false,

    val confirmation: String? = null,
) {

    val awaitingApproval: TrackingDeviceAuthorization?
        get() = (connection as? TrackingConnectionState.AwaitingApproval)?.authorization

    val isConnected: Boolean get() = connection is TrackingConnectionState.Connected

    val selectedTrackerId: String? get() = (selection as? TrackingSelection.Tracker)?.trackerId

    val selectedTracker: TrackingTrackerOption?
        get() = selectedTrackerId?.let { id -> trackers.firstOrNull { it.trackerId == id } }

    val selectableTrackers: List<TrackingTrackerOption> get() = trackers.filter { it.selectable }

    val selectedTrackerUnavailable: Boolean
        get() {
            val id = selectedTrackerId ?: return false
            return trackers.none { it.trackerId == id && it.connected }
        }

    val connectedButUnselected: Boolean get() = isConnected && selectedTrackerId == null
}

fun trackingSelectionRowValue(state: TrackingSettingsUiState): String =
    when (val selection = state.selection) {
        TrackingSelection.OnDeviceOnly -> TRACKING_LOCAL_LABEL
        is TrackingSelection.Tracker -> trackingTrackerLabel(
            selection.trackerId,
            state.trackers.firstOrNull { it.trackerId == selection.trackerId }?.displayName,
        )
    }

fun trackingTrackerLabel(trackerId: String, providerDisplayName: String? = null): String =
    when (trackerId.trim().lowercase()) {
        TRACKING_SIMKL_ID -> "SIMKL"
        TRACKING_TRAKT_ID -> "TRAKT"
        TRACKING_MDBLIST_ID -> "MDBList"
        else -> providerDisplayName?.trim()?.takeIf { it.isNotEmpty() } ?: trackerId
    }

private const val TRACKING_SIMKL_ID: String = "simkl"
private const val TRACKING_TRAKT_ID: String = "trakt"
private const val TRACKING_MDBLIST_ID: String = "mdblist"

const val TRACKING_SELECTION_TITLE: String = "Watch tracking"

const val TRACKING_THIS_DEVICE_SUBTITLE: String =
    "fission always records what you watch here. This cannot be turned off."

const val TRACKING_LOCAL_LABEL: String = "Local"

const val TRACKING_ON_DEVICE_ONLY_SUBTITLE: String =
    "Nothing is sent to a service and nothing is imported. fission still records what " +
        "you watch here, and your Jellyfin server is unaffected."

fun trackingSelectionSubtitle(
    selection: TrackingSelection,
    trackers: List<TrackingTrackerOption>,
    providerName: String? = null,
): String {
    if (selection !is TrackingSelection.Tracker) return TRACKING_ON_DEVICE_ONLY_SUBTITLE
    val tracker = trackers.firstOrNull { it.trackerId == selection.trackerId }
    val name = tracker?.displayName ?: providerName ?: selection.trackerId
    val halves = tracker?.halves
    return when {
        halves == null || (halves.sends && halves.reads) ->
            "$name receives playback progress and watched marks from now on, and supplies " +
                "progress and watched state for fission to read."
        halves.sends ->
            "$name receives playback progress and watched marks from now on. " +
                "$name does not supply progress or watched state."
        else ->
            "$name supplies progress and watched state. " +
                "$name does not receive playback progress or watched marks."
    }
}

fun trackingSelectedSendCountLine(state: TrackingSettingsUiState): String? {
    val tracker = state.selectedTracker ?: return null
    if (!tracker.connected || !tracker.halves.sends) return null
    val name = tracker.displayName
    return when {
        state.retryableSends == 1 -> "1 send is waiting to be delivered to $name."
        state.retryableSends > 1 -> "${state.retryableSends} sends are waiting to be delivered to $name."
        else -> "No sends are waiting for $name."
    }
}

fun trackingUserChoiceCountLine(state: TrackingSettingsUiState): String? {
    if (state.selection != TrackingSelection.OnDeviceOnly) return null
    if (state.departedTrackerId == null) return null
    return when {
        state.userChoiceStoppedSends == 1 ->
            "1 send from before you changed this was not sent, by your choice."
        state.userChoiceStoppedSends > 1 ->
            "${state.userChoiceStoppedSends} sends from before you changed this were not sent, by your choice."
        else -> "No sends were stopped by changing this."
    }
}

fun trackingSelectionNotice(state: TrackingSettingsUiState): String? = when {
    state.selectedTrackerUnavailable -> {
        val name = state.selectedTracker?.displayName ?: state.selectedTrackerId.orEmpty()
        "$name is not connected. It stays your chosen service. fission is using your " +
            "history on this device" + (if (state.jellyfinConnected) " and your Jellyfin server" else "") +
            ". Choosing another service here is the only way it changes."
    }
    state.connectedButUnselected ->
        "Connected. Not used for watch tracking — it receives nothing and supplies nothing. " +
            "Choose it above to use it."
    else -> null
}

fun trackingChangeConfirmation(
    selection: TrackingSelection,
    trackers: List<TrackingTrackerOption>,
    providerName: String? = null,
): String = when (selection) {
    TrackingSelection.OnDeviceOnly -> "Watch tracking is now on this device only. Nothing was deleted."
    is TrackingSelection.Tracker -> {
        val name = trackers.firstOrNull { it.trackerId == selection.trackerId }?.displayName
            ?: providerName
            ?: selection.trackerId
        "Watch tracking is now $name. Nothing was deleted."
    }
}
