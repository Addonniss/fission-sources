package dev.infusion.feature.vertical.profile

import dev.infusion.core.domain.profile.PinVerificationResult
import dev.infusion.core.domain.profile.ProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PinEntryStatus {

    data object Entering : PinEntryStatus

    data object Verifying : PinEntryStatus

    data class WrongPin(val remainingAttempts: Int) : PinEntryStatus

    data class Locked(val lockedUntilMs: Long, val remainingMs: Long) : PinEntryStatus

    data object Unlocked : PinEntryStatus

    data object Verified : PinEntryStatus
}

data class PinEntryUiState(
    val profileId: String,
    val digits: String = "",
    val status: PinEntryStatus = PinEntryStatus.Entering,
)

class PinEntryStateHolder(
    private val profileId: String,
    private val repository: ProfileRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long,
) {

    private val _state = MutableStateFlow(PinEntryUiState(profileId = profileId))
    val state: StateFlow<PinEntryUiState> = _state.asStateFlow()

    private var countdownJob: Job? = null

    fun onDigit(digit: Char) {
        val s = _state.value
        if (s.status != PinEntryStatus.Entering && s.status !is PinEntryStatus.WrongPin) return
        if (s.digits.length >= MAX_PIN_DIGITS) return
        _state.update { it.copy(digits = it.digits + digit, status = PinEntryStatus.Entering) }
    }

    fun onBackspace() {
        val s = _state.value
        if (s.status != PinEntryStatus.Entering) return
        if (s.digits.isNotEmpty()) _state.update { it.copy(digits = it.digits.dropLast(1)) }
    }

    fun onClear() {
        val s = _state.value
        if (s.status != PinEntryStatus.Entering) return
        _state.update { it.copy(digits = "") }
    }

    fun submit() {
        val s = _state.value
        if (s.status != PinEntryStatus.Entering && s.status !is PinEntryStatus.WrongPin) return
        if (s.digits.length < MIN_PIN_DIGITS) return
        _state.update { it.copy(status = PinEntryStatus.Verifying) }
        scope.launch {
            val result = withContext(ioDispatcher) { repository.verifyPin(profileId, s.digits) }
            when (result) {
                PinVerificationResult.Verified -> _state.update {
                    it.copy(status = PinEntryStatus.Verified, digits = "")
                }
                is PinVerificationResult.WrongPin -> _state.update {
                    it.copy(status = PinEntryStatus.WrongPin(result.remainingAttempts), digits = "")
                }
                is PinVerificationResult.Locked -> startLockoutCountdown(result.lockedUntilMs)
            }
        }
    }

    fun reset() {
        countdownJob?.cancel()
        countdownJob = null
        _state.update { PinEntryUiState(profileId = profileId) }
    }

    private fun startLockoutCountdown(lockedUntilMs: Long) {
        countdownJob?.cancel()
        _state.update { it.copy(digits = "", status = PinEntryStatus.Locked(lockedUntilMs, remainingMs(lockedUntilMs))) }
        countdownJob = scope.launch {
            while (true) {
                val remaining = remainingMs(lockedUntilMs)
                if (remaining <= 0L) {
                    _state.update { it.copy(status = PinEntryStatus.Unlocked) }
                    break
                }
                _state.update { it.copy(status = PinEntryStatus.Locked(lockedUntilMs, remaining)) }
                delay(COUNTDOWN_TICK_MS)
            }
        }
    }

    private fun remainingMs(lockedUntilMs: Long): Long =
        (lockedUntilMs - nowMs()).coerceAtLeast(0L)

    private companion object {
        const val MAX_PIN_DIGITS = 64
        const val MIN_PIN_DIGITS = 4
        const val COUNTDOWN_TICK_MS = 250L
    }
}
