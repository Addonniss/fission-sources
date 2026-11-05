package dev.infusion.core.database.store

import dev.infusion.core.database.Clock
import dev.infusion.core.database.dao.OutboxDao
import dev.infusion.core.database.entity.OutboxEntity
import dev.infusion.core.database.mapper.OutboxMapper
import dev.infusion.core.database.mapper.OutboxMapper.toEntity
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.model.OutboxEntryId
import dev.infusion.core.model.sync.NOT_SENT_BY_USER_CHOICE
import dev.infusion.core.model.sync.OutboxEntry
import dev.infusion.core.model.sync.OutboxState
import dev.infusion.core.sync.RetryBackoff
import dev.infusion.core.domain.sync.IssuedRequestOutcome
import dev.infusion.core.domain.sync.WatchOutbox
import java.time.Instant
import java.util.UUID

class RoomWatchOutbox(
    private val outboxDao: OutboxDao,
    private val scope: ProfileScope,
    private val clock: Clock = Clock.SYSTEM,
) : WatchOutbox {

    override fun enqueue(entry: OutboxEntry): OutboxEntry {
        val existing = outboxDao.getByKey(entry.connectorId, entry.idempotencyKey)
        if (existing != null) return OutboxMapper.toCanonical(existing) ?: entry
        val row = toEntity(scope.activeProfileId, entry)
        val inserted = outboxDao.enqueue(row)
        return if (inserted == -1L) {

            outboxDao.getByKey(entry.connectorId, entry.idempotencyKey)
                ?.let { OutboxMapper.toCanonical(it) } ?: entry
        } else {
            entry
        }
    }

    override fun pending(connectorId: String, now: Instant): List<OutboxEntry> =
        outboxDao.pending(scope.activeProfileId, connectorId, now.toEpochMilli())
            .mapNotNull(OutboxMapper::toCanonical)

    override fun acknowledge(entryId: OutboxEntryId): Boolean =
        outboxDao.acknowledge(scope.activeProfileId, entryId.value.toString(), clock.nowMs()) > 0

    override fun recordIssuedOutcome(entryId: OutboxEntryId, outcome: IssuedRequestOutcome): Boolean {
        val answer = when (outcome) {
            IssuedRequestOutcome.Delivered -> IssuedAnswer(OutboxState.ACKNOWLEDGED, null, clock.nowMs())
            is IssuedRequestOutcome.Rejected -> IssuedAnswer(OutboxState.CANCELLED, outcome.reason, null)
        }
        return outboxDao.recordIssuedOutcome(
            profileId = scope.activeProfileId,
            outboxId = entryId.value.toString(),
            state = answer.state.name,
            lastError = answer.lastError,
            ackedAtMs = answer.ackedAtMs,
            dispositionReason = NOT_SENT_BY_USER_CHOICE,
        ) > 0
    }

    override fun fail(entryId: OutboxEntryId, error: String, retryable: Boolean, now: Instant) {
        val profileId = scope.activeProfileId
        val row = outboxDao.getByIdForProfile(profileId, entryId.value.toString()) ?: return
        if (row.state == OutboxState.ACKNOWLEDGED || row.state == OutboxState.CANCELLED) return
        if (retryable) {
            val attempts = row.attemptCount + 1
            outboxDao.fail(
                profileId = profileId,
                outboxId = row.outboxId,
                state = OutboxState.FAILED.name,
                attemptCount = attempts,
                lastError = RETRYABLE_FAILURE_CODE,
                nextAttemptAtMs = now.toEpochMilli() + RetryBackoff.delayMs(attempts),
            )
        } else {
            outboxDao.fail(
                profileId = profileId,
                outboxId = row.outboxId,
                state = OutboxState.CANCELLED.name,
                attemptCount = row.attemptCount,
                lastError = NON_RETRYABLE_FAILURE_CODE,
                nextAttemptAtMs = null,
            )
        }
    }

    override fun entry(entryId: OutboxEntryId): OutboxEntry? =
        outboxDao.getById(entryId.value.toString())?.let(OutboxMapper::toCanonical)

    override fun allEntries(): List<OutboxEntry> =
        outboxDao.allForProfile(scope.activeProfileId).mapNotNull(OutboxMapper::toCanonical)

    override fun deleteByConnector(connectorId: String): Int =
        outboxDao.deleteByConnector(connectorId)

    override fun disposeByUserChoice(connectorId: String): Int =
        outboxDao.disposeByUserChoice(scope.activeProfileId, connectorId, NOT_SENT_BY_USER_CHOICE)

    override fun retryableCount(connectorId: String): Int =
        outboxDao.countRetryable(scope.activeProfileId, connectorId)

    override fun notSentByUserChoiceCount(connectorId: String): Int =
        outboxDao.countByReason(scope.activeProfileId, connectorId, NOT_SENT_BY_USER_CHOICE)
}

fun OutboxDao.purgeAckedEntries(clock: Clock = Clock.SYSTEM, retentionMs: Long = ACKED_RETENTION_MS): Int {
    val before = clock.nowMs() - retentionMs
    return purgeAcked(before)
}

private const val ACKED_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
private const val RETRYABLE_FAILURE_CODE = "RETRYABLE_FAILURE"
private const val NON_RETRYABLE_FAILURE_CODE = "NON_RETRYABLE_FAILURE"

private data class IssuedAnswer(
    val state: OutboxState,
    val lastError: String?,
    val ackedAtMs: Long?,
)
