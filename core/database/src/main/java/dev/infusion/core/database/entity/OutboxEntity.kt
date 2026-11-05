package dev.infusion.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.infusion.core.model.sync.OutboxState
import dev.infusion.core.model.watch.WatchEvent

@Entity(
    tableName = "outbox",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["profile_id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["profile_id"]),
        Index(value = ["connector_id", "idempotency_key"], unique = true),
        Index(value = ["state", "next_attempt_at_ms"]),
    ],
)
data class OutboxEntity(
    @PrimaryKey @ColumnInfo(name = "outbox_id") val outboxId: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "connector_id") val connectorId: String,

    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,

    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "watch_key") val watchKey: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "occurred_at_ms") val occurredAtMs: Long,
    @ColumnInfo(name = "state") val state: OutboxState,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,

    @ColumnInfo(name = "next_attempt_at_ms") val nextAttemptAtMs: Long?,

    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "acked_at_ms") val ackedAtMs: Long?,

    @ColumnInfo(name = "payload_blob") val payloadBlob: String? = null,
)
