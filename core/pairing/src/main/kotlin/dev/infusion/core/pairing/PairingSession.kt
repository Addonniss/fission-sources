package dev.infusion.core.pairing

import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class PairingSessionState {
    PENDING,
    APPROVED,
    EXCHANGED,
    EXPIRED,
    CANCELLED,
}

class PairingSession internal constructor(
    val id: String,
    internal val codeDigest: ByteArray,
    internal val nonceDigest: ByteArray,
    val createdAtEpochMs: Long,
    val ttlMs: Long,
    private val maxPolls: Int,
) {
    private val lock = ReentrantLock()

    @Volatile
    private var state: PairingSessionState = PairingSessionState.PENDING

    @Volatile
    private var approvalDeviceName: String? = null

    private var pollsRemaining: Int = maxPolls

    sealed interface Transition {

        data object Applied : Transition

        data class Refused(val reason: PairingSessionState) : Transition

        data object PollBudgetExhausted : Transition
    }

    sealed interface Consume {
        data object Exchanged : Consume
        data class Refused(val reason: PairingSessionState) : Consume
    }

    fun verifiesCode(presentedCode: String): Boolean =
        ConstantTime.matches(sha256(PairingHex.decode(presentedCode)), codeDigest)

    fun verifiesNonce(presentedNonce: String): Boolean =
        ConstantTime.matches(sha256(PairingHex.decode(presentedNonce)), nonceDigest)

    fun expireIfPastTtl(nowMs: Long): Boolean = lock.withLock {
        if (state == PairingSessionState.PENDING || state == PairingSessionState.APPROVED) {
            if (nowMs - createdAtEpochMs >= ttlMs) {
                state = PairingSessionState.EXPIRED
                return true
            }
        }
        false
    }

    fun currentState(nowMs: Long): PairingSessionState {
        expireIfPastTtl(nowMs)
        return state
    }

    fun requestedDeviceName(): String? = approvalDeviceName

    fun requestApproval(nowMs: Long, deviceName: String?): Transition = lock.withLock {
        expireIfPastTtl(nowMs)
        when (state) {
            PairingSessionState.PENDING -> {
                approvalDeviceName = deviceName
                Transition.Applied
            }
            else -> Transition.Refused(state)
        }
    }

    fun confirm(nowMs: Long): Transition = lock.withLock {
        expireIfPastTtl(nowMs)
        when (state) {
            PairingSessionState.PENDING -> {
                state = PairingSessionState.APPROVED
                Transition.Applied
            }
            else -> Transition.Refused(state)
        }
    }

    fun cancel(nowMs: Long): Transition = lock.withLock {
        expireIfPastTtl(nowMs)
        when (state) {
            PairingSessionState.PENDING, PairingSessionState.APPROVED -> {
                state = PairingSessionState.CANCELLED
                Transition.Applied
            }
            else -> Transition.Refused(state)
        }
    }

    fun tryConsume(nowMs: Long): Consume = lock.withLock {
        expireIfPastTtl(nowMs)
        when (state) {
            PairingSessionState.APPROVED -> {
                state = PairingSessionState.EXCHANGED
                Consume.Exchanged
            }
            else -> Consume.Refused(state)
        }
    }

    fun spendPoll(nowMs: Long): Transition = lock.withLock {
        expireIfPastTtl(nowMs)
        when (state) {
            PairingSessionState.PENDING, PairingSessionState.APPROVED -> {
                if (pollsRemaining <= 0) {
                    Transition.PollBudgetExhausted
                } else {
                    pollsRemaining--
                    Transition.Applied
                }
            }
            else -> Transition.Refused(state)
        }
    }

    fun ttlRemainingMs(nowMs: Long): Long? {
        if (currentState(nowMs) == PairingSessionState.PENDING ||
            currentState(nowMs) == PairingSessionState.APPROVED
        ) {
            return (createdAtEpochMs + ttlMs - nowMs).coerceAtLeast(0)
        }
        return null
    }

    override fun toString(): String =
        "PairingSession(id=${id.take(4)}…, state=$state, ttlMs=$ttlMs)"

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
