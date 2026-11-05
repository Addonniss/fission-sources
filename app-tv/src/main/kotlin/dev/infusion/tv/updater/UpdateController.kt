package dev.infusion.tv.updater

import dev.infusion.core.update.UpdateCandidate
import dev.infusion.core.update.UpdateCheckResult
import dev.infusion.core.update.UpdateCheckSource
import dev.infusion.core.update.UpdateFailureKind
import dev.infusion.core.update.UpdateState
import dev.infusion.core.update.UpdateStorage
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UpdateController(
    private val scope: CoroutineScope,
    private val checkSource: UpdateCheckSource,
    private val installer: UpdateInstaller,
    private val storage: UpdateStorage,
    private val installedVersionCode: () -> Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val cleanedAtStart: Boolean = run {
        storage.cleanup()
        true
    }

    private val checked = AtomicBoolean(false)

    private var checkJob: Job? = null
    private var installJob: Job? = null

    private var candidate: UpdateCandidate? = null

    init {

        installer.observeResults(::onInstallOutcome)
    }

    fun startupCheckOnce() {
        if (!checked.compareAndSet(false, true)) return
        checkJob = scope.launch {
            _state.value = UpdateState.Checking
            val result = checkSource.check(installedVersionCode())
            _state.value = when (result) {
                is UpdateCheckResult.Found -> {
                    candidate = result.candidate
                    UpdateState.Available(result.candidate)
                }
                is UpdateCheckResult.NoUpdate -> UpdateState.SilentSkip(result.reason)
            }
        }
    }

    fun onUpdateConfirmed() {
        val current = candidate ?: return
        if (installJob?.isActive == true) return
        _state.value = UpdateState.Downloading(bytesRead = 0L, totalBytes = current.sizeBytes)
        installJob = scope.launch(dispatcher) {
            val outcome = installer.install(current) { event ->
                _state.value = when (event) {
                    is UpdateInstallEvent.Downloading ->
                        UpdateState.Downloading(event.bytesRead, event.totalBytes)
                    UpdateInstallEvent.Verifying -> UpdateState.Verifying
                }
            }
            _state.value = when (outcome) {
                InstallOutcome.AwaitingUserConfirmation -> UpdateState.ReadyToInstall
                InstallOutcome.PermissionMissing -> {

                    finish()
                    UpdateState.NeedsPermission
                }
                InstallOutcome.UserCancelled -> {
                    finish()
                    UpdateState.UserDismissed
                }
                InstallOutcome.Installed -> {

                    finish()
                    UpdateState.UserDismissed
                }
                is InstallOutcome.Failed -> {
                    finish()
                    UpdateState.Failed(outcome.kind)
                }
            }
        }
    }

    fun onRetry() {
        val current = candidate ?: return
        if (_state.value !is UpdateState.Failed) return
        _state.value = UpdateState.Available(current)
        onUpdateConfirmed()
    }

    fun onDismiss() {
        installJob?.cancel()
        installJob = null
        finish()
        _state.value = UpdateState.UserDismissed
    }

    fun onPermissionGranted() {
        if (_state.value !is UpdateState.NeedsPermission) return
        onUpdateConfirmed()
    }

    private fun onInstallOutcome(outcome: InstallOutcome) {
        _state.value = when (outcome) {
            InstallOutcome.Installed -> {
                finish()
                UpdateState.UserDismissed
            }
            InstallOutcome.UserCancelled -> {
                finish()
                UpdateState.UserDismissed
            }
            InstallOutcome.PermissionMissing -> {

                finish()
                UpdateState.NeedsPermission
            }
            InstallOutcome.AwaitingUserConfirmation -> UpdateState.ReadyToInstall
            is InstallOutcome.Failed -> {
                finish()
                UpdateState.Failed(outcome.kind)
            }
        }
    }

    private fun finish() {
        storage.cleanup()
    }

    fun close() {
        checkJob?.cancel()
        installJob?.cancel()
        finish()
    }

    internal fun hasChecked(): Boolean = checked.get()

    internal fun cleanedOnStart(): Boolean = cleanedAtStart
}
