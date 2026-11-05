package dev.infusion.tv.configweb

import dev.infusion.core.lanweb.BoundLanwebServer
import dev.infusion.core.lanweb.LanwebConfig
import dev.infusion.core.lanweb.LanwebRouter
import dev.infusion.core.lanweb.LanwebServer
import dev.infusion.feature.configweb.ConfigDraftStatus
import dev.infusion.feature.configweb.ConfigWebCoordinator
import dev.infusion.feature.configweb.ConfigWebEndpoints
import dev.infusion.feature.configweb.ConfigWebStart
import dev.infusion.feature.configweb.ConfigWebTvState
import dev.infusion.feature.pairing.PairingQrEncoder
import dev.infusion.feature.pairing.QrMatrix
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface PhoneSetupUiState {

    data object Idle : PhoneSetupUiState

    data object Starting : PhoneSetupUiState

    data class Active(
        val qr: QrMatrix,
        val addressLabel: String,
        val manualCode: String,
        val sessionId: String,
    ) : PhoneSetupUiState

    data class NoNetwork(val message: String) : PhoneSetupUiState

    data class Unavailable(val message: String) : PhoneSetupUiState

    data object Ended : PhoneSetupUiState
}

class ConfigWebSessionStateHolder(
    private val coordinator: ConfigWebCoordinator,
    private val assets: ConfigWebAssets,

    private val lanAddress: () -> String?,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,

    private val playbackActive: StateFlow<Boolean>? = null,

    private val activeProfileFlow: Flow<String>? = null,
    private val serverFactory: () -> LanwebServer = { LanwebServer() },
    private val qrEncoder: (String) -> QrMatrix = { PairingQrEncoder().encode(it) },
    private val logger: (String) -> Unit = {},
) {

    private val lock = Any()

    private val _state = MutableStateFlow<PhoneSetupUiState>(PhoneSetupUiState.Idle)

    val state: StateFlow<PhoneSetupUiState> = _state

    val tvState: StateFlow<ConfigWebTvState> get() = coordinator.tvState

    @Volatile
    var mintCount: Int = 0
        private set

    private var bound: BoundLanwebServer? = null
    private var lifecycleJobs: MutableList<Job> = mutableListOf()
    private var countdownJob: Job? = null
    private var pendingApplyJob: Job? = null

    fun start(): PhoneSetupUiState {
        synchronized(lock) {
            val current = _state.value
            if (current is PhoneSetupUiState.Active && bound != null) {

                val live = coordinator.startSession()
                if (live != null) return current

            }
            teardown()

            val address = lanAddress()?.trim()?.takeIf { it.isNotEmpty() }
            if (address == null) {

                val failed = PhoneSetupUiState.NoNetwork(
                    "No network connection. Connect this TV to your home network, then try again.",
                )
                _state.value = failed
                logger("phonesetup start refused: no LAN address")
                return failed
            }

            _state.value = PhoneSetupUiState.Starting
            val listener = try {
                serverFactory().start(
                    router = router(),
                    config = LanwebConfig(
                        bindAddress = address,
                        port = 0,
                        maxBodyBytes = ConfigWebEndpoints.BODY_CAP_BYTES,
                    ),
                    validation = ConfigWebEndpoints.VALIDATION,
                )
            } catch (error: Throwable) {
                _state.value = PhoneSetupUiState.NoNetwork(
                    "Could not open the phone-setup listener on this network. Try again.",
                )
                logger("phonesetup start failed: listener not bound")
                return _state.value
            }

            val start: ConfigWebStart? = try {
                coordinator.startSession()
            } catch (error: Throwable) {
                null
            }
            if (start == null) {

                runCatching { listener.stop() }
                _state.value = PhoneSetupUiState.Unavailable(
                    "No profile is active. Choose a profile, then try again.",
                )
                logger("phonesetup start refused: no active profile")
                return _state.value
            }

            bound = listener
            if (!start.alreadyRunning) mintCount++

            val url = "${listener.baseUrl}${ConfigWebEndpoints.PAGE}"
            val activation = try {
                PhoneSetupUiState.Active(
                    qr = qrEncoder("$url#${start.wireToken}"),
                    addressLabel = url,
                    manualCode = start.manualCode,
                    sessionId = start.session.id,
                )
            } catch (error: Throwable) {

                runCatching { listener.stop() }
                coordinator.endSession()
                bound = null
                _state.value = PhoneSetupUiState.Unavailable("Could not prepare the session code.")
                logger("phonesetup start failed: QR not encoded")
                return _state.value
            }

            _state.value = activation
            startLifecycle(start.session.profileId)
            logger("phonesetup session started: session=${start.session.id}")
            return activation
        }
    }

    fun stop(reason: String = "stopped") {
        synchronized(lock) {
            val hadSession = bound != null || _state.value is PhoneSetupUiState.Active
            teardown()
            if (hadSession) {
                _state.value = PhoneSetupUiState.Ended
                logger("phonesetup session ended: $reason")
            }
        }
    }

    fun confirm(draftId: String) {
        runApply { coordinator.confirm(draftId) }
    }

    fun reject(draftId: String) {
        runApply { coordinator.reject(draftId) }
    }

    fun retry(draftId: String) {
        runApply { coordinator.retry(draftId) }
    }

    fun tick() {
        if (_state.value !is PhoneSetupUiState.Active) return
        coordinator.tick()
        val session = coordinator.tvState.value.session
        if (session == null) {

            stop(reason = "expired")
        }
    }

    private fun router(): LanwebRouter {
        val router = coordinator.declareRoutes(LanwebRouter())
        return ConfigWebPageRoutes.declare(router, assets)
    }

    private fun startLifecycle(profileId: String) {
        playbackActive?.let { active ->
            lifecycleJobs += scope.launch {
                active.collect { playing ->

                    if (playing) stop(reason = "playback")
                }
            }
        }
        activeProfileFlow?.let { profiles ->
            lifecycleJobs += scope.launch {
                profiles.collect { current ->
                    if (current != profileId) stop(reason = "profile_changed")
                }
            }
        }
        countdownJob = scope.launch {
            while (isActive) {
                delay(COUNTDOWN_INTERVAL_MS)
                if (_state.value !is PhoneSetupUiState.Active) break
                tick()
            }
        }
    }

    private fun runApply(block: () -> ConfigDraftStatus?) {
        pendingApplyJob?.cancel()
        pendingApplyJob = scope.launch(ioDispatcher) {
            runCatching { block() }
        }
    }

    private fun teardown() {
        lifecycleJobs.forEach { it.cancel() }
        lifecycleJobs = mutableListOf()
        countdownJob?.cancel()
        countdownJob = null
        pendingApplyJob?.cancel()
        pendingApplyJob = null
        val listener = bound
        bound = null
        runCatching { listener?.stop() }
        runCatching { coordinator.endSession() }
    }

    private companion object {
        const val COUNTDOWN_INTERVAL_MS: Long = 1_000L
    }
}
