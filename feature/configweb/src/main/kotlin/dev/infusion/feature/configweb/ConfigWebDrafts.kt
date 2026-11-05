package dev.infusion.feature.configweb

import dev.infusion.core.lanweb.Clock
import dev.infusion.core.lanweb.SystemClock
import dev.infusion.core.pairing.PairingRandom
import dev.infusion.core.pairing.PairingRandomness
import java.util.concurrent.ConcurrentHashMap

sealed interface DraftCreate {

    data class Ok(val draft: ConfigDraft, val replaced: Boolean) : DraftCreate

    data class Invalid(val field: String, val reason: String) : DraftCreate

    data class SubmittedPending(val draft: ConfigDraft) : DraftCreate

    data class Unavailable(val reason: String) : DraftCreate
}

sealed interface DraftSubmit {

    data class Pending(val draft: ConfigDraft) : DraftSubmit

    data class Recorded(val draft: ConfigDraft) : DraftSubmit

    data class Stale(val draft: ConfigDraft, val currentRevision: String?) : DraftSubmit

    data class SessionEnded(val draft: ConfigDraft, val reason: ConfigWebSessionState) : DraftSubmit

    data object NotFound : DraftSubmit
}

sealed interface DraftCancel {

    data class Rejected(val draft: ConfigDraft) : DraftCancel

    data class Already(val draft: ConfigDraft) : DraftCancel

    data class TooLate(val draft: ConfigDraft) : DraftCancel

    data object NotFound : DraftCancel
}

sealed interface DraftApply {

    data class Attempted(val draft: ConfigDraft) : DraftApply

    data class Refused(val draft: ConfigDraft) : DraftApply

    data object NotFound : DraftApply
}

class ConfigWebDrafts(
    private val config: ConfigWebConfig = ConfigWebConfig.DEFAULT,
    private val clock: Clock = SystemClock,
    private val port: ConfigOwnerPort,
    private val random: PairingRandom = PairingRandomness.DEFAULT,
) {

    private val drafts = ConcurrentHashMap<String, ConfigDraft>()

    private val bySession = ConcurrentHashMap<String, MutableList<String>>()

    fun createOrReplace(session: ConfigWebSession, operations: List<ConfigOperation>): DraftCreate {
        if (operations.isEmpty()) {
            return DraftCreate.Invalid("operations", "a draft must include at least one changed row or operation")
        }
        if (operations.size > config.maxOperationsPerDraft) {
            return DraftCreate.Invalid(
                "operations",
                "a draft may carry at most ${config.maxOperationsPerDraft} operations",
            )
        }
        operations.forEachIndexed { index, operation ->
            val error = runCatching { port.validate(operation) }.getOrElse {
                ConfigValidationError("", "the owner could not validate this row")
            }
            if (error != null) {
                return DraftCreate.Invalid(
                    field = validationField(error.field, index),
                    reason = ConfigWebText.sanitize(
                        error.reason,
                        operation.redactionSecrets(),
                        ConfigWebLimits.MAX_REASON_CHARS,
                    ).ifEmpty { DEFAULT_FAILURE_REASON },
                )
            }
        }
        val revision = runCatching { port.revisionFingerprint() }.getOrNull()
        if (revision.isNullOrBlank() || revision.length > MAX_REVISION_CHARS) {
            return DraftCreate.Unavailable("the owner bridge did not produce a usable revision fingerprint")
        }

        val live = liveDraft(session.id)
        if (live != null) {
            when (live.state()) {
                ConfigDraftState.EDITING -> {
                    if (!live.replaceContent(operations, revision)) {
                        return DraftCreate.SubmittedPending(live)
                    }
                    return DraftCreate.Ok(live, replaced = true)
                }
                ConfigDraftState.SUBMITTED, ConfigDraftState.APPLYING -> {
                    return DraftCreate.SubmittedPending(live)
                }
                else -> Unit
            }
        }

        val draft = ConfigDraft(
            id = newDraftId(),
            sessionId = session.id,
            profileId = session.profileId,
            operations = operations,
            revision = revision,
            createdAtMs = clock.nowEpochMs(),
        )
        drafts[draft.id] = draft
        val ids = bySession.computeIfAbsent(session.id) { mutableListOf() }
        synchronized(ids) {
            ids += draft.id
            while (ids.size > config.maxRetainedDraftsPerSession + 1) {
                drafts.remove(ids.removeAt(0))
            }
        }
        return DraftCreate.Ok(draft, replaced = false)
    }

    fun get(session: ConfigWebSession, draftId: String): ConfigDraft? =
        drafts[draftId]?.takeIf { it.sessionId == session.id }

    fun liveDraft(sessionId: String): ConfigDraft? = lastDraft(sessionId)?.takeIf { it.state().isLive }

    fun lastDraft(sessionId: String): ConfigDraft? {
        val ids = bySession[sessionId] ?: return null
        val snapshot = synchronized(ids) { ids.toList() }
        return snapshot.asReversed().firstNotNullOfOrNull { drafts[it] }
    }

    fun submit(session: ConfigWebSession, draftId: String): DraftSubmit {
        val draft = get(session, draftId) ?: return DraftSubmit.NotFound
        if (draft.state() != ConfigDraftState.EDITING) return DraftSubmit.Recorded(draft)

        val now = clock.nowEpochMs()
        if (!session.isActive(now)) {
            draft.expire(now)
            return DraftSubmit.SessionEnded(draft, session.stateAt(now))
        }
        val activeProfile = runCatching { port.activeProfileId() }.getOrNull()
        if (activeProfile != session.profileId) {
            session.end(ConfigWebSessionState.PROFILE_CHANGED)
            draft.expire(now)
            return DraftSubmit.SessionEnded(draft, ConfigWebSessionState.PROFILE_CHANGED)
        }
        val current = runCatching { port.revisionFingerprint() }.getOrNull()
        if (current == null || current != draft.revision) {
            return DraftSubmit.Stale(draft, current)
        }
        if (!draft.tryBeginSubmit(now)) return DraftSubmit.Recorded(draft)
        return DraftSubmit.Pending(draft)
    }

    fun cancel(session: ConfigWebSession, draftId: String): DraftCancel {
        val draft = get(session, draftId) ?: return DraftCancel.NotFound
        val now = clock.nowEpochMs()
        draft.reject(now)?.let { return DraftCancel.Rejected(draft) }
        return when {
            draft.state() == ConfigDraftState.APPLYING -> DraftCancel.TooLate(draft)
            draft.state().isTerminal || draft.state().isRetryable -> DraftCancel.Already(draft)
            else -> DraftCancel.TooLate(draft)
        }
    }

    fun confirm(session: ConfigWebSession, draftId: String): DraftApply {
        val draft = get(session, draftId) ?: return DraftApply.NotFound
        val now = clock.nowEpochMs()
        if (!session.isActive(now)) {
            draft.expire(now)
            return DraftApply.Refused(draft)
        }
        if (!draft.tryBeginApply(now)) return DraftApply.Refused(draft)
        runApplyAttempt(draft, session)
        return DraftApply.Attempted(draft)
    }

    fun retry(session: ConfigWebSession, draftId: String): DraftApply {
        val draft = get(session, draftId) ?: return DraftApply.NotFound
        if (!draft.state().isRetryable) return DraftApply.Refused(draft)
        val now = clock.nowEpochMs()
        if (!session.isActive(now)) return DraftApply.Refused(draft)
        if (!draft.tryBeginApply(now)) return DraftApply.Refused(draft)
        runApplyAttempt(draft, session)
        return DraftApply.Attempted(draft)
    }

    fun expireSessionDrafts(sessionId: String, nowMs: Long = clock.nowEpochMs()): Int {
        val ids = bySession[sessionId] ?: return 0
        val snapshot = synchronized(ids) { ids.toList() }
        var expired = 0
        for (id in snapshot) {
            val draft = drafts[id] ?: continue
            if (draft.expire(nowMs) != null) expired++
        }
        return expired
    }

    fun dropSession(sessionId: String) {
        val ids = bySession.remove(sessionId) ?: return
        synchronized(ids) { ids.forEach { drafts.remove(it) } }
    }

    private enum class Abort { STALE, SESSION_ENDED }

    private fun runApplyAttempt(draft: ConfigDraft, session: ConfigWebSession) {
        val operations = draft.operations()

        var baseline = draft.applyBaseline()

        revalidateBeforeWrite(draft, session, fromIndex = 0, expected = baseline)?.let { abort ->
            draft.conclude(conclusionOf(draft, abort), clock.nowEpochMs())
            return
        }

        val remaining = draft.pendingIndexes().toSet()
        for (family in ConfigFamily.entries) {
            val indexes = operations.indices.filter { it in remaining && operations[it].family == family }
            if (indexes.isEmpty()) continue
            val abort = revalidateBeforeWrite(
                draft,
                session,
                fromIndex = indexes.first(),
                expected = baseline,
            )
            if (abort != null) {
                draft.conclude(conclusionOf(draft, abort), clock.nowEpochMs())
                return
            }
            for (index in indexes) {
                val operation = operations[index]
                val result = runCatching { port.apply(operation) }.getOrElse { failure ->
                    ConfigApplyResult.Failed(failure.message ?: "the owner could not apply this operation")
                }
                when (result) {
                    ConfigApplyResult.Applied -> draft.recordOutcome(index, OperationOutcome.Applied)
                    is ConfigApplyResult.Failed -> draft.recordOutcome(
                        index,
                        OperationOutcome.Failed(
                            ConfigWebText.sanitize(
                                result.reason,
                                operation.redactionSecrets(),
                                ConfigWebLimits.MAX_REASON_CHARS,
                            ).ifEmpty { DEFAULT_FAILURE_REASON },
                        ),
                    )
                }
            }

            baseline = runCatching { port.revisionFingerprint() }.getOrNull() ?: baseline
            draft.adoptApplyBaseline(baseline)
        }
        draft.conclude(conclusionOf(draft, abortedBy = null), clock.nowEpochMs())
    }

    private fun revalidateBeforeWrite(
        draft: ConfigDraft,
        session: ConfigWebSession,
        fromIndex: Int,
        expected: String,
    ): Abort? {
        val now = clock.nowEpochMs()
        if (!session.isActive(now)) {
            draft.notAttemptFrom(fromIndex, NotAttemptedReason.SESSION_ENDED)
            return Abort.SESSION_ENDED
        }
        val activeProfile = runCatching { port.activeProfileId() }.getOrNull()
        if (activeProfile != session.profileId) {
            session.end(ConfigWebSessionState.PROFILE_CHANGED)
            draft.notAttemptFrom(fromIndex, NotAttemptedReason.SESSION_ENDED)
            return Abort.SESSION_ENDED
        }

        val current = runCatching { port.revisionFingerprint() }.getOrNull()
        if (current == null || current != expected) {
            draft.notAttemptFrom(fromIndex, NotAttemptedReason.STALE)
            return Abort.STALE
        }
        return null
    }

    private fun conclusionOf(draft: ConfigDraft, abortedBy: Abort?): ConfigDraftState {
        val outcomes = draft.outcomes()
        val total = outcomes.size
        val applied = outcomes.count { it == OperationOutcome.Applied }
        val stale = outcomes.any {
            it is OperationOutcome.NotAttempted && it.reason == NotAttemptedReason.STALE
        }
        val sessionEnded = outcomes.any {
            it is OperationOutcome.NotAttempted && it.reason == NotAttemptedReason.SESSION_ENDED
        }
        return when {
            total > 0 && applied == total -> ConfigDraftState.APPLIED
            sessionEnded || abortedBy == Abort.SESSION_ENDED -> ConfigDraftState.EXPIRED
            applied > 0 -> ConfigDraftState.PARTIALLY_APPLIED
            stale || abortedBy == Abort.STALE -> ConfigDraftState.STALE
            else -> ConfigDraftState.FAILED
        }
    }

    fun status(draft: ConfigDraft): ConfigDraftStatus {
        val operations = draft.operations()
        val outcomes = draft.outcomes()
        val statuses = operations.indices.map { index ->
            val operation = operations[index]
            ConfigOperationStatus(
                index = index,
                family = operation.family,
                kind = operation.kind,
                rowKey = operation.rowKey,
                outcome = OperationOutcome.wireName(outcomes[index]),
                reason = reasonOf(outcomes[index]),
            )
        }
        return ConfigDraftStatus(
            draftId = draft.id,
            state = draft.state(),
            revision = draft.revision,
            operations = statuses,
            counts = ConfigOutcomeCounts(
                total = statuses.size,
                applied = outcomes.count { it == OperationOutcome.Applied },
                failed = outcomes.count { it is OperationOutcome.Failed },
                notAttempted = outcomes.count { it is OperationOutcome.NotAttempted },
                pending = outcomes.count { it == OperationOutcome.Pending },
            ),
        )
    }

    fun summary(draft: ConfigDraft): ConfigDraftSummary {
        val operations = draft.operations()
        val families = ConfigFamily.entries.mapNotNull { family ->
            val matching = operations.filter { it.family == family }
            if (matching.isEmpty()) return@mapNotNull null
            val labels = matching.take(MAX_SUMMARY_LABELS).map { labelOf(it) }
            ConfigFamilySummary(family = family, count = matching.size, labels = labels)
        }
        return ConfigDraftSummary(
            draftId = draft.id,
            operationCount = operations.size,
            families = families,
        )
    }

    private fun labelOf(operation: ConfigOperation): String {
        val raw = runCatching { port.labelOf(operation) }.getOrNull().orEmpty()
        if (raw.isBlank()) return "${operation.kind.wireName.replace('_', ' ')} — ${operation.family.label}"
        return ConfigWebText.sanitize(
            raw,
            operation.redactionSecrets(),
            ConfigWebLimits.MAX_LABEL_CHARS,
        )
    }

    private fun reasonOf(outcome: OperationOutcome): String? = when (outcome) {
        is OperationOutcome.Failed -> outcome.reason
        is OperationOutcome.NotAttempted -> outcome.reason.wireName
        else -> null
    }

    private fun validationField(field: String, index: Int): String {
        val safe = field.takeIf { SAFE_FIELD.matches(it) } ?: ""
        return if (safe.isEmpty()) "operations[$index]" else "operations[$index].$safe"
    }

    private fun newDraftId(): String = "d_" + random.nextBytes(DRAFT_ID_BYTES)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val DRAFT_ID_BYTES: Int = 8
        const val MAX_REVISION_CHARS: Int = 128
        const val MAX_SUMMARY_LABELS: Int = 10
        const val DEFAULT_FAILURE_REASON: String = "the owner refused this operation"
        val SAFE_FIELD = Regex("[A-Za-z0-9_.\\[\\]]{1,64}")
    }
}
