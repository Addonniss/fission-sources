package dev.infusion.core.domain.sync

import dev.infusion.core.model.OutboxEntryId
import dev.infusion.core.model.sync.OutboxEntry
import java.time.Instant

interface WatchOutbox {

    fun enqueue(entry: OutboxEntry): OutboxEntry

    fun pending(connectorId: String, now: Instant = Instant.now()): List<OutboxEntry>

    fun acknowledge(entryId: OutboxEntryId): Boolean

    fun recordIssuedOutcome(entryId: OutboxEntryId, outcome: IssuedRequestOutcome): Boolean

    fun fail(entryId: OutboxEntryId, error: String, retryable: Boolean, now: Instant = Instant.now())

    fun entry(entryId: OutboxEntryId): OutboxEntry?

    fun allEntries(): List<OutboxEntry>

    fun deleteByConnector(connectorId: String): Int

    fun disposeByUserChoice(connectorId: String): Int

    fun retryableCount(connectorId: String): Int

    fun notSentByUserChoiceCount(connectorId: String): Int
}

sealed interface IssuedRequestOutcome {

    data object Delivered : IssuedRequestOutcome

    data class Rejected(val reason: String) : IssuedRequestOutcome
}
