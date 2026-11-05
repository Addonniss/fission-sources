package dev.infusion.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.infusion.core.database.model.EventOrigin
import dev.infusion.core.model.watch.WatchEvent
import dev.infusion.core.model.watch.WatchStatus

@Entity(
    tableName = "watch_events",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["profile_id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [

        Index(value = ["profile_id", "watch_key", "occurred_at_ms"]),

        Index(value = ["profile_id", "connector_event_id"], unique = true),
        Index(value = ["session_id"]),
    ],
)
data class WatchEventEntity(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "profile_id") val profileId: String,

    @ColumnInfo(name = "watch_key") val watchKey: String,

    @ColumnInfo(name = "kind") val kind: WatchEvent.Kind,
    @ColumnInfo(name = "origin") val origin: EventOrigin,
    @ColumnInfo(name = "position_ms") val positionMs: Long?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,

    @ColumnInfo(name = "occurred_at_ms") val occurredAtMs: Long,

    @ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,

    @ColumnInfo(name = "observed_at_ms") val observedAtMs: Long?,

    @ColumnInfo(name = "connector_id") val connectorId: String?,

    @ColumnInfo(name = "connector_event_id") val connectorEventId: String?,

    @ColumnInfo(name = "session_id") val sessionId: String?,

    @ColumnInfo(name = "anchor_source_id") val anchorSourceId: String?,
    @ColumnInfo(name = "anchor_native_id") val anchorNativeId: String?,
    @ColumnInfo(name = "anchor_family_id") val anchorFamilyId: String?,
    @ColumnInfo(name = "anchor_version_id") val anchorVersionId: String?,
    @ColumnInfo(name = "anchor_position_ms") val anchorPositionMs: Long?,
    @ColumnInfo(name = "anchor_anchored_at_ms") val anchorAnchoredAtMs: Long?,
)

@Entity(
    tableName = "watch_states",
    primaryKeys = ["profile_id", "watch_key"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["profile_id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["status"]),
    ],
)
data class WatchStateEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "watch_key") val watchKey: String,

    @ColumnInfo(name = "status") val status: WatchStatus,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "completion_fraction") val completionFraction: Double,
    @ColumnInfo(name = "last_event_id") val lastEventId: String?,
    @ColumnInfo(name = "last_meaningful_at_ms") val lastMeaningfulAtMs: Long?,
    @ColumnInfo(name = "active_session_id") val activeSessionId: String?,

    @ColumnInfo(name = "anchor_source_id") val anchorSourceId: String?,
    @ColumnInfo(name = "anchor_native_id") val anchorNativeId: String?,
    @ColumnInfo(name = "anchor_family_id") val anchorFamilyId: String?,
    @ColumnInfo(name = "anchor_version_id") val anchorVersionId: String?,
    @ColumnInfo(name = "anchor_position_ms") val anchorPositionMs: Long?,
    @ColumnInfo(name = "anchor_anchored_at_ms") val anchorAnchoredAtMs: Long?,

    @ColumnInfo(name = "conflicts_json") val conflictsJson: String,

    @ColumnInfo(name = "connector_states_json") val connectorStatesJson: String,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)
