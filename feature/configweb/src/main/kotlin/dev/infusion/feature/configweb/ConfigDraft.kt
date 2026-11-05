package dev.infusion.feature.configweb

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class ConfigDraftState(val wireName: String) {

    EDITING("editing"),

    SUBMITTED("submitted"),

    APPLYING("applying"),

    APPLIED("applied"),

    PARTIALLY_APPLIED("partially_applied"),

    FAILED("failed"),

    STALE("stale"),

    REJECTED("rejected"),

    EXPIRED("expired"),
    ;

    val isTerminal: Boolean
        get() = this == APPLIED || this == STALE || this == REJECTED || this == EXPIRED

    val isRetryable: Boolean
        get() = this == PARTIALLY_APPLIED || this == FAILED

    val isLive: Boolean
        get() = this == EDITING || this == SUBMITTED || this == APPLYING
}

class ConfigDraft internal constructor(
    val id: String,
    val sessionId: String,
    val profileId: String,
    operations: List<ConfigOperation>,
    revision: String,
    val createdAtMs: Long,
) {

    private val lock = ReentrantLock()

    private var operationList: List<ConfigOperation> = operations.toList()
    private var outcomeList: MutableList<OperationOutcome> =
        MutableList(operations.size) { OperationOutcome.Pending }

    var revision: String = revision
        private set

    @Volatile
    private var applyBaselineFingerprint: String = revision

    fun applyBaseline(): String = applyBaselineFingerprint

    internal fun adoptApplyBaseline(fingerprint: String) {
        applyBaselineFingerprint = fingerprint
    }

    @Volatile
    private var state: ConfigDraftState = ConfigDraftState.EDITING

    @Volatile
    var submittedAtMs: Long? = null
        private set

    @Volatile
    var concludedAtMs: Long? = null
        private set

    @Volatile
    var applyAttempts: Int = 0
        private set

    fun state(): ConfigDraftState = state

    fun operations(): List<ConfigOperation> = lock.withLock { operationList }

    fun outcomes(): List<OperationOutcome> = lock.withLock { outcomeList.toList() }

    fun operationCount(): Int = lock.withLock { operationList.size }

    internal fun replaceContent(operations: List<ConfigOperation>, revision: String): Boolean =
        lock.withLock {
            if (state != ConfigDraftState.EDITING) return false
            operationList = operations.toList()
            outcomeList = MutableList(operations.size) { OperationOutcome.Pending }
            this.revision = revision
            true
        }

    internal fun tryBeginSubmit(nowMs: Long): Boolean = lock.withLock {
        if (state != ConfigDraftState.EDITING) return false
        state = ConfigDraftState.SUBMITTED
        submittedAtMs = nowMs
        true
    }

    internal fun tryBeginApply(nowMs: Long): Boolean = lock.withLock {
        if (state != ConfigDraftState.SUBMITTED && !state.isRetryable) return false
        if (state.isRetryable) {

            for (index in outcomeList.indices) {
                if (outcomeList[index] != OperationOutcome.Applied) {
                    outcomeList[index] = OperationOutcome.Pending
                }
            }
        }
        state = ConfigDraftState.APPLYING
        applyAttempts += 1
        if (submittedAtMs == null) submittedAtMs = nowMs
        concludedAtMs = null
        true
    }

    internal fun recordOutcome(index: Int, outcome: OperationOutcome) = lock.withLock {
        require(index in outcomeList.indices) { "operation index out of range" }

        if (outcomeList[index] == OperationOutcome.Applied) return@withLock
        outcomeList[index] = outcome
    }

    internal fun notAttemptRemaining(reason: NotAttemptedReason) = lock.withLock {
        for (index in outcomeList.indices) {
            if (outcomeList[index] == OperationOutcome.Pending) {
                outcomeList[index] = OperationOutcome.NotAttempted(reason)
            }
        }
    }

    internal fun notAttemptFrom(fromIndex: Int, reason: NotAttemptedReason) = lock.withLock {
        for (index in fromIndex until outcomeList.size) {
            if (outcomeList[index] != OperationOutcome.Applied) {
                outcomeList[index] = OperationOutcome.NotAttempted(reason)
            }
        }
    }

    internal fun conclude(outcome: ConfigDraftState, nowMs: Long) = lock.withLock {
        require(outcome != ConfigDraftState.EDITING && outcome != ConfigDraftState.SUBMITTED) {
            "not a conclusive state"
        }
        state = outcome
        concludedAtMs = nowMs
    }

    internal fun reject(nowMs: Long): ConfigDraftState? = lock.withLock {
        if (state != ConfigDraftState.EDITING && state != ConfigDraftState.SUBMITTED) return null
        notAttemptRemainingLocked(NotAttemptedReason.CANCELLED)
        state = ConfigDraftState.REJECTED
        concludedAtMs = nowMs
        state
    }

    internal fun expire(nowMs: Long): ConfigDraftState? = lock.withLock {
        if (state != ConfigDraftState.EDITING && state != ConfigDraftState.SUBMITTED) return null
        notAttemptRemainingLocked(NotAttemptedReason.SESSION_ENDED)
        state = ConfigDraftState.EXPIRED
        concludedAtMs = nowMs
        state
    }

    private fun notAttemptRemainingLocked(reason: NotAttemptedReason) {
        for (index in outcomeList.indices) {
            if (outcomeList[index] == OperationOutcome.Pending) {
                outcomeList[index] = OperationOutcome.NotAttempted(reason)
            }
        }
    }

    internal fun pendingIndexes(): List<Int> = lock.withLock {
        outcomeList.indices.filter { index -> outcomeList[index] != OperationOutcome.Applied }
    }

    override fun toString(): String =
        "ConfigDraft(id=$id, state=$state, operations=${operationList.size}, revision=${revision.take(8)}…)"
}

data class ConfigOutcomeCounts(
    val total: Int,
    val applied: Int,
    val failed: Int,
    val notAttempted: Int,
    val pending: Int,
)

data class ConfigOperationStatus(
    val index: Int,
    val family: ConfigFamily,
    val kind: ConfigOperationKind,
    val rowKey: String,
    val outcome: String,
    val reason: String?,
)

data class ConfigDraftStatus(
    val draftId: String,
    val state: ConfigDraftState,
    val revision: String,
    val operations: List<ConfigOperationStatus>,
    val counts: ConfigOutcomeCounts,
)

data class ConfigFamilySummary(
    val family: ConfigFamily,
    val count: Int,
    val labels: List<String>,
)

data class ConfigDraftSummary(
    val draftId: String,
    val operationCount: Int,
    val families: List<ConfigFamilySummary>,
)
