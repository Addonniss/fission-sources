package dev.infusion.core.sync.store

import dev.infusion.core.domain.sync.IssuedRequestOutcome
import dev.infusion.core.domain.sync.WatchOutbox
import dev.infusion.core.model.OutboxEntryId
import dev.infusion.core.model.sync.NOT_SENT_BY_USER_CHOICE
import dev.infusion.core.model.sync.OutboxEntry
import dev.infusion.core.model.sync.OutboxState
import dev.infusion.core.model.sync.isNotSentByUserChoice
import dev.infusion.core.model.sync.isSuspendedOnDeparture
import dev.infusion.core.sync.RetryBackoff
import java.time.Instant
import java.util.UUID

class InMemoryWatchOutbox : WatchOutbox {

    private val entries = linkedMapOf<UUID, OutboxEntry>()
    private val entryByKey = mutableMapOf<String, UUID>()

    private val durableWriteListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    override fun enqueue(entry: OutboxEntry): OutboxEntry {
        val (result, isNew) = synchronized(this) {
            val existingId = entryByKey[entry.idempotencyKey]
            if (existingId != null) {
                entries.getValue(existingId) to false
            } else {
                entries[entry.entryId.value] = entry
                entryByKey[entry.idempotencyKey] = entry.entryId.value
                entry to true
            }
        }

        if (isNew) notifyDurableWrite(result.connectorId)
        return result
    }

    override fun onDurableWrite(listener: (connectorId: String) -> Unit): AutoCloseable {
        durableWriteListeners += listener
        return AutoCloseable { durableWriteListeners -= listener }
    }

    @Synchronized
    override fun nextEligibleAt(connectorId: String, now: Instant): Instant? =
        entries.values
            .filter { it.connectorId == connectorId && it.state == OutboxState.FAILED }
            .mapNotNull { entry -> entry.nextAttemptAt?.takeIf { it.isAfter(now) } }
            .minOrNull()

    private fun notifyDurableWrite(connectorId: String) {
        for (listener in durableWriteListeners) listener(connectorId)
    }

    @Synchronized
    override fun pending(connectorId: String, now: Instant): List<OutboxEntry> =
        entries.values
            .filter { it.connectorId == connectorId && isDue(it, now) }
            .sortedBy { it.createdAt }

    @Synchronized
    override fun acknowledge(entryId: OutboxEntryId): Boolean {
        val entry = entries[entryId.value] ?: return false
        if (entry.state != OutboxState.PENDING && entry.state != OutboxState.FAILED) return false
        entries[entryId.value] = entry.copy(state = OutboxState.ACKNOWLEDGED)
        return true
    }

    @Synchronized
    override fun recordIssuedOutcome(entryId: OutboxEntryId, outcome: IssuedRequestOutcome): Boolean {
        val entry = entries[entryId.value] ?: return false
        val inFlightOrDisposed = entry.state == OutboxState.PENDING ||
            entry.state == OutboxState.FAILED ||
            entry.isNotSentByUserChoice()
        if (!inFlightOrDisposed) return false
        entries[entryId.value] = when (outcome) {
            IssuedRequestOutcome.Delivered ->
                entry.copy(state = OutboxState.ACKNOWLEDGED, nextAttemptAt = null, lastError = null)
            is IssuedRequestOutcome.Rejected ->
                entry.copy(state = OutboxState.CANCELLED, nextAttemptAt = null, lastError = outcome.reason)
        }
        return true
    }

    @Synchronized
    override fun fail(
        entryId: OutboxEntryId,
        error: String,
        retryable: Boolean,
        now: Instant,
        retryAfterMs: Long?,
    ) {
        val entry = entries[entryId.value] ?: return
        if (entry.state == OutboxState.ACKNOWLEDGED || entry.state == OutboxState.CANCELLED) return
        if (retryable) {
            val attempts = entry.attempts + 1
            entries[entryId.value] = entry.copy(
                state = OutboxState.FAILED,
                attempts = attempts,
                nextAttemptAt = now.plusMillis(retryAfterMs ?: RetryBackoff.delayMs(attempts)),
                lastError = error,
            )
        } else {
            entries[entryId.value] = entry.copy(state = OutboxState.CANCELLED, lastError = error)
        }
    }

    override fun entry(entryId: OutboxEntryId): OutboxEntry? = synchronized(entries) { entries[entryId.value] }

    override fun allEntries(): List<OutboxEntry> = synchronized(entries) { entries.values.toList() }

    @Synchronized
    override fun deleteByConnector(connectorId: String): Int {
        val removed = entries.values.filter { it.connectorId == connectorId }
        removed.forEach { entry ->
            entries.remove(entry.entryId.value)
            entryByKey.remove(entry.idempotencyKey)
        }
        return removed.size
    }

    @Synchronized
    override fun disposeByUserChoice(connectorId: String): Int {
        val targets = entries.values.filter {
            it.connectorId == connectorId &&
                (it.state == OutboxState.PENDING || it.state == OutboxState.FAILED) &&
                !it.payload.isSuspendedOnDeparture()
        }
        targets.forEach { entry ->
            entries[entry.entryId.value] = entry.copy(
                state = OutboxState.CANCELLED,
                nextAttemptAt = null,
                lastError = NOT_SENT_BY_USER_CHOICE,
            )
        }
        return targets.size
    }

    @Synchronized
    override fun retryableCount(connectorId: String): Int =
        entries.values.count {
            it.connectorId == connectorId &&
                (it.state == OutboxState.PENDING || it.state == OutboxState.FAILED)
        }

    @Synchronized
    override fun notSentByUserChoiceCount(connectorId: String): Int =
        entries.values.count {
            it.connectorId == connectorId &&
                it.state == OutboxState.CANCELLED &&
                it.lastError == NOT_SENT_BY_USER_CHOICE
        }

    private fun isDue(entry: OutboxEntry, now: Instant): Boolean = when (entry.state) {
        OutboxState.PENDING -> true
        OutboxState.FAILED -> {
            val nextAttempt = entry.nextAttemptAt
            nextAttempt != null && !nextAttempt.isAfter(now)
        }
        else -> false
    }
}
