package dev.infusion.core.domain.sync

import dev.infusion.core.model.OutboxEntryId
import dev.infusion.core.model.sync.OutboxEntry
import dev.infusion.core.model.sync.OutboxState
import java.time.Instant

interface WatchOutbox {

    fun enqueue(entry: OutboxEntry): OutboxEntry

    fun pending(connectorId: String, now: Instant = Instant.now()): List<OutboxEntry>

    fun acknowledge(entryId: OutboxEntryId): Boolean

    fun recordIssuedOutcome(entryId: OutboxEntryId, outcome: IssuedRequestOutcome): Boolean

    fun fail(
        entryId: OutboxEntryId,
        error: String,
        retryable: Boolean,
        now: Instant = Instant.now(),
        retryAfterMs: Long? = null,
    )

    fun entry(entryId: OutboxEntryId): OutboxEntry?

    fun allEntries(): List<OutboxEntry>

    fun deleteByConnector(connectorId: String): Int

    fun disposeByUserChoice(connectorId: String): Int

    fun retryableCount(connectorId: String): Int

    fun notSentByUserChoiceCount(connectorId: String): Int

    fun onDurableWrite(listener: (connectorId: String) -> Unit): AutoCloseable = AutoCloseable {}

    fun nextEligibleAt(connectorId: String, now: Instant = Instant.now()): Instant? =
        allEntries().asSequence()
            .filter { it.connectorId == connectorId && it.state == OutboxState.FAILED }
            .mapNotNull { entry -> entry.nextAttemptAt?.takeIf { it.isAfter(now) } }
            .minOrNull()
}

sealed interface IssuedRequestOutcome {

    data object Delivered : IssuedRequestOutcome

    data class Rejected(val reason: String) : IssuedRequestOutcome
}
