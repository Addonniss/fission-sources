package dev.infusion.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.infusion.core.database.entity.CheckpointEntity
import dev.infusion.core.database.entity.OutboxEntity

@Dao
interface OutboxDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun enqueue(row: OutboxEntity): Long

    @Query("SELECT * FROM outbox WHERE outbox_id = :outboxId")
    fun getById(outboxId: String): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE connector_id = :connectorId AND idempotency_key = :idempotencyKey")
    fun getByKey(connectorId: String, idempotencyKey: String): OutboxEntity?

    @Query(
        "SELECT * FROM outbox WHERE profile_id = :profileId AND connector_id = :connectorId AND " +
            "(state = 'PENDING' OR (state = 'FAILED' AND next_attempt_at_ms IS NOT NULL AND next_attempt_at_ms <= :nowMs)) " +
            "ORDER BY created_at_ms ASC",
    )
    fun pending(profileId: String, connectorId: String, nowMs: Long): List<OutboxEntity>

    @Query(
        "UPDATE outbox SET state = 'ACKNOWLEDGED', acked_at_ms = :ackedAtMs, last_error = NULL " +
            "WHERE outbox_id = :outboxId AND profile_id = :profileId AND state IN ('PENDING', 'FAILED')",
    )
    fun acknowledge(profileId: String, outboxId: String, ackedAtMs: Long): Int

    @Query(
        "UPDATE outbox SET state = :state, last_error = :lastError, next_attempt_at_ms = NULL, acked_at_ms = :ackedAtMs " +
            "WHERE outbox_id = :outboxId AND profile_id = :profileId AND " +
            "(state IN ('PENDING', 'FAILED') OR (state = 'CANCELLED' AND last_error = :dispositionReason))",
    )
    fun recordIssuedOutcome(
        profileId: String,
        outboxId: String,
        state: String,
        lastError: String?,
        ackedAtMs: Long?,
        dispositionReason: String,
    ): Int

    @Query(
        "UPDATE outbox SET state = :state, attempt_count = :attemptCount, last_error = :lastError, " +
            "next_attempt_at_ms = :nextAttemptAtMs WHERE outbox_id = :outboxId AND profile_id = :profileId AND state IN ('PENDING', 'FAILED')",
    )
    fun fail(
        profileId: String,
        outboxId: String,
        state: String,
        attemptCount: Int,
        lastError: String?,
        nextAttemptAtMs: Long?,
    ): Int

    @Query("SELECT COUNT(*) FROM outbox WHERE connector_id = :connectorId AND state = :state")
    fun countByState(connectorId: String, state: String): Int

    @Query(
        "UPDATE outbox SET state = 'CANCELLED', last_error = :reason, next_attempt_at_ms = NULL " +
            "WHERE profile_id = :profileId AND connector_id = :connectorId AND state IN ('PENDING', 'FAILED')",
    )
    fun disposeByUserChoice(profileId: String, connectorId: String, reason: String): Int

    @Query(
        "SELECT COUNT(*) FROM outbox WHERE profile_id = :profileId AND connector_id = :connectorId " +
            "AND state IN ('PENDING', 'FAILED')",
    )
    fun countRetryable(profileId: String, connectorId: String): Int

    @Query(
        "SELECT COUNT(*) FROM outbox WHERE profile_id = :profileId AND connector_id = :connectorId " +
            "AND state = 'CANCELLED' AND last_error = :reason",
    )
    fun countByReason(profileId: String, connectorId: String, reason: String): Int

    @Query("SELECT * FROM outbox ORDER BY created_at_ms ASC")
    fun all(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE profile_id = :profileId ORDER BY created_at_ms ASC")
    fun allForProfile(profileId: String): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE outbox_id = :outboxId AND profile_id = :profileId")
    fun getByIdForProfile(profileId: String, outboxId: String): OutboxEntity?

    @Query("DELETE FROM outbox WHERE state = 'ACKNOWLEDGED' AND acked_at_ms IS NOT NULL AND acked_at_ms < :beforeMs")
    fun purgeAcked(beforeMs: Long): Int

    @Query("DELETE FROM outbox WHERE outbox_id = :outboxId")
    fun hardDelete(outboxId: String)

    @Query("DELETE FROM outbox WHERE connector_id = :connectorId")
    fun deleteByConnector(connectorId: String): Int

    @Query("DELETE FROM outbox WHERE profile_id = :profileId")
    fun deleteByProfile(profileId: String): Int
}

@Dao
interface CheckpointDao {
    @Query("SELECT * FROM checkpoints WHERE profile_id = :profileId AND connector_id = :connectorId")
    fun get(profileId: String, connectorId: String): CheckpointEntity?

    @Upsert
    fun put(row: CheckpointEntity)

    @Query("DELETE FROM checkpoints WHERE profile_id = :profileId AND connector_id = :connectorId")
    fun delete(profileId: String, connectorId: String)

    @Query("SELECT connector_id FROM checkpoints WHERE profile_id = :profileId")
    fun listConnectorIds(profileId: String): List<String>

    @Query("DELETE FROM checkpoints WHERE profile_id = :profileId")
    fun deleteByProfile(profileId: String): Int

    @Query("DELETE FROM checkpoints WHERE connector_id LIKE :prefix || '%'")
    fun deleteByConnectorPrefix(prefix: String): Int

    @Query("SELECT DISTINCT connector_id FROM checkpoints WHERE connector_id LIKE :prefix || '%'")
    fun listConnectorIdsWithPrefix(prefix: String): List<String>
}
