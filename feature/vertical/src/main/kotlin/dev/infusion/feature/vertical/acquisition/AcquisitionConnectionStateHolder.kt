package dev.infusion.feature.vertical.acquisition

import dev.infusion.core.domain.acquisition.AcquisitionConnection
import dev.infusion.core.domain.acquisition.AcquisitionConnectionStore
import dev.infusion.core.domain.acquisition.AcquisitionQualityProfile
import dev.infusion.core.domain.acquisition.AcquisitionRootFolder
import dev.infusion.core.domain.acquisition.AcquisitionTestOutcome
import dev.infusion.core.domain.acquisition.StoredAcquisitionConnection
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Phase { IDLE, LOADING, LOADED, SAVING, SAVED, FAILED }

data class AcquisitionConnectionUiState(
    val serviceId: String,
    val serviceLabel: String,
    val phase: Phase,
    val url: String,
    val apiKey: String,
    val roots: List<AcquisitionRootFolder>,
    val profiles: List<AcquisitionQualityProfile>,
    val selectedRoot: String?,
    val selectedProfileId: Int?,
    val selectedProfileLabel: String?,
    val serverVersion: String?,
    val connectedSummary: String?,
    val error: String?,
    val loadedSignature: String?,

    val savedServerVersion: String? = null,
)

class AcquisitionConnectionStateHolder(
    private val scope: CoroutineScope,
    val serviceId: String,
    val serviceLabel: String,
    private val loadAction: suspend (url: String, apiKey: String) -> AcquisitionTestOutcome,
    private val store: AcquisitionConnectionStore,
    initial: StoredAcquisitionConnection?,
    private val actionDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    init {
        require(serviceId.isNotBlank()) { "serviceId must not be blank" }
    }

    private val _state = MutableStateFlow(initialState(initial))
    val state: StateFlow<AcquisitionConnectionUiState> = _state.asStateFlow()

    private var loadGeneration: Int = 0

    val busy: Boolean get() = _state.value.phase == Phase.LOADING || _state.value.phase == Phase.SAVING

    fun onUrlChange(value: String) {
        val current = _state.value

        if (value == current.url) return
        val next = current.copy(url = value)
        _state.value = if (draftSignature(next) != current.loadedSignature) next.invalidateLoaded() else next
    }

    fun onApiKeyChange(value: String) {
        val current = _state.value
        if (value == current.apiKey) return
        val next = current.copy(apiKey = value)
        _state.value = if (draftSignature(next) != current.loadedSignature) next.invalidateLoaded() else next
    }

    fun testAndLoad() {
        val current = _state.value
        if (current.phase == Phase.LOADING || current.phase == Phase.SAVING) return
        val url = current.url.trim()
        val apiKey = current.apiKey.trim()
        val requestSignature = signature(url, apiKey)
        val gen = ++loadGeneration
        _state.value = current.invalidateLoaded().copy(phase = Phase.LOADING, error = null)
        scope.launch {
            val outcome = withContext(actionDispatcher) { loadAction(url, apiKey) }
            val after = _state.value

            if (gen != loadGeneration) return@launch
            if (after.phase != Phase.LOADING || draftSignature(after) != requestSignature) return@launch
            _state.value = when (outcome) {
                is AcquisitionTestOutcome.Failed -> applyFailed(outcome)
                is AcquisitionTestOutcome.Ok -> applyOk(requestSignature, outcome)
            }
        }
    }

    private fun applyFailed(outcome: AcquisitionTestOutcome.Failed): AcquisitionConnectionUiState =
        _state.value.copy(
            phase = Phase.FAILED,
            roots = emptyList(),
            profiles = emptyList(),
            selectedRoot = null,
            selectedProfileId = null,
            selectedProfileLabel = null,
            serverVersion = null,
            loadedSignature = null,
            error = outcome.message,

        )

    private fun applyOk(
        requestSignature: String,
        outcome: AcquisitionTestOutcome.Ok,
    ): AcquisitionConnectionUiState {
        if (outcome.roots.isEmpty()) {
            return _state.value.copy(
                phase = Phase.FAILED,
                roots = emptyList(),
                profiles = outcome.profiles,
                selectedRoot = null,
                selectedProfileId = null,
                selectedProfileLabel = null,
                serverVersion = outcome.serverVersion,
                loadedSignature = null,
                error = "No root folders are configured on $serviceLabel; add one in the server's settings.",
            )
        }
        if (outcome.profiles.isEmpty()) {
            return _state.value.copy(
                phase = Phase.FAILED,
                roots = outcome.roots,
                profiles = emptyList(),
                selectedRoot = null,
                selectedProfileId = null,
                selectedProfileLabel = null,
                serverVersion = outcome.serverVersion,
                loadedSignature = null,
                error = "No quality profiles are available on $serviceLabel; create one in the server's settings.",
            )
        }

        val singleRoot = outcome.roots.singleOrNull()?.path
        val singleProfile = outcome.profiles.singleOrNull()
        return _state.value.copy(
            phase = Phase.LOADED,
            roots = outcome.roots,
            profiles = outcome.profiles,
            selectedRoot = singleRoot,
            selectedProfileId = singleProfile?.id,
            selectedProfileLabel = singleProfile?.name,
            serverVersion = outcome.serverVersion,
            loadedSignature = requestSignature,
            error = null,
        )
    }

    fun loadExisting() {
        val current = _state.value
        if (current.phase == Phase.LOADING || current.phase == Phase.SAVING) return
        val committed = store.connection(serviceId) ?: return
        val url = committed.connection.url.trim()
        if (url.isEmpty()) return

        val apiKey = store.apiKey(serviceId)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (current.loadedSignature == signature(url, apiKey)) return
        val gen = ++loadGeneration
        _state.value = current.copy(phase = Phase.LOADING, error = null)
        scope.launch {
            val outcome = withContext(actionDispatcher) { loadAction(url, apiKey) }
            if (gen != loadGeneration) return@launch
            val after = _state.value
            if (after.phase != Phase.LOADING) return@launch
            _state.value = when (outcome) {
                is AcquisitionTestOutcome.Failed -> after.copy(

                    phase = Phase.FAILED,
                    error = outcome.message,
                )
                is AcquisitionTestOutcome.Ok -> applyExistingOk(url, apiKey, outcome)
            }
        }
    }

    private fun applyExistingOk(
        url: String,
        apiKey: String,
        outcome: AcquisitionTestOutcome.Ok,
    ): AcquisitionConnectionUiState {
        val current = _state.value
        if (outcome.roots.isEmpty()) {
            return current.copy(
                phase = Phase.FAILED,
                roots = emptyList(),
                profiles = outcome.profiles,
                serverVersion = outcome.serverVersion,
                error = "No root folders are configured on $serviceLabel; add one in the server's settings.",
            )
        }
        if (outcome.profiles.isEmpty()) {
            return current.copy(
                phase = Phase.FAILED,
                roots = outcome.roots,
                profiles = emptyList(),
                serverVersion = outcome.serverVersion,
                error = "No quality profiles are available on $serviceLabel; create one in the server's settings.",
            )
        }
        val keptRoot = current.selectedRoot?.takeIf { path -> outcome.roots.any { it.path == path } }
            ?: outcome.roots.singleOrNull()?.path
        val keptProfile = current.selectedProfileId
            ?.let { id -> outcome.profiles.firstOrNull { it.id == id } }
            ?: outcome.profiles.singleOrNull()
        return current.copy(
            phase = Phase.LOADED,
            roots = outcome.roots,
            profiles = outcome.profiles,
            selectedRoot = keptRoot,
            selectedProfileId = keptProfile?.id,
            selectedProfileLabel = keptProfile?.name,
            serverVersion = outcome.serverVersion,
            loadedSignature = signature(url, apiKey),
            error = null,
        )
    }

    fun selectRoot(path: String) {
        val current = _state.value
        if (current.phase != Phase.LOADED) return
        if (current.roots.none { it.path == path }) return
        _state.value = current.copy(selectedRoot = path)
    }

    fun selectProfile(id: Int) {
        val current = _state.value
        if (current.phase != Phase.LOADED) return
        val profile = current.profiles.firstOrNull { it.id == id } ?: return
        _state.value = current.copy(selectedProfileId = profile.id, selectedProfileLabel = profile.name)
    }

    fun save() {
        val current = _state.value
        if (current.phase != Phase.LOADED) return
        val root = current.selectedRoot ?: return
        val profileId = current.selectedProfileId ?: return
        val profileLabel = current.selectedProfileLabel ?: return
        val url = current.url.trim()

        val apiKey = current.apiKey.trim().ifEmpty { store.apiKey(serviceId)?.trim().orEmpty() }
        if (url.isEmpty() || apiKey.isEmpty()) return
        val connection = AcquisitionConnection(
            serviceId = serviceId,
            url = url,
            rootFolderPath = root,
            qualityProfileId = profileId,
            qualityProfileLabel = profileLabel,
            serverVersion = current.serverVersion,
        )
        _state.value = current.copy(phase = Phase.SAVING)
        scope.launch {
            withContext(actionDispatcher) { store.save(serviceId, connection, apiKey) }
            val after = _state.value
            if (after.phase != Phase.SAVING) return@launch

            _state.value = after.copy(
                phase = Phase.LOADED,
                connectedSummary = summaryOf(connection),
                savedServerVersion = connection.serverVersion ?: after.savedServerVersion,
                serverVersion = connection.serverVersion ?: after.serverVersion,
                loadedSignature = signature(url, apiKey),
                error = null,
            )
        }
    }

    fun clear() {
        loadGeneration++
        scope.launch {
            withContext(actionDispatcher) { store.clear(serviceId) }
            _state.value = initialState(initial = null)
        }
    }

    fun reload() {

        loadGeneration++
        _state.value = initialState(initial = store.connection(serviceId))
    }

    private fun initialState(initial: StoredAcquisitionConnection?): AcquisitionConnectionUiState =
        AcquisitionConnectionUiState(
            serviceId = serviceId,
            serviceLabel = serviceLabel,
            phase = Phase.IDLE,
            url = initial?.connection?.url ?: "",

            apiKey = "",
            roots = emptyList(),
            profiles = emptyList(),

            selectedRoot = initial?.connection?.rootFolderPath,
            selectedProfileId = initial?.connection?.qualityProfileId,
            selectedProfileLabel = initial?.connection?.qualityProfileLabel,
            serverVersion = null,
            connectedSummary = initial?.let { summaryOf(it.connection) },
            error = null,
            loadedSignature = null,
            savedServerVersion = initial?.connection?.serverVersion,
        )

    private fun summaryOf(connection: AcquisitionConnection): String {
        val version = connection.serverVersion?.let { " · v$it" }.orEmpty()
        return "${connection.url}$version · ${connection.rootFolderPath} · ${connection.qualityProfileLabel}"
    }

    private fun AcquisitionConnectionUiState.invalidateLoaded(): AcquisitionConnectionUiState =
        if (loadedSignature == null) this else copy(
            phase = Phase.IDLE,
            roots = emptyList(),
            profiles = emptyList(),
            selectedRoot = null,
            selectedProfileId = null,
            selectedProfileLabel = null,
            serverVersion = null,
            loadedSignature = null,
            error = null,
        )

    private fun draftSignature(state: AcquisitionConnectionUiState): String =
        signature(state.url.trim(), state.apiKey.trim())

    private fun signature(url: String, apiKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$url\u0000$apiKey".toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}
