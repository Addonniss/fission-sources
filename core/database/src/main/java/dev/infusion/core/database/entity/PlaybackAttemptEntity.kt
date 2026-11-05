package dev.infusion.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playback_attempts",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["profile_id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["source_id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["profile_id"]),
        Index(value = ["candidate_id"]),
        Index(value = ["source_id"]),
    ],
)
data class PlaybackAttemptEntity(
    @PrimaryKey @ColumnInfo(name = "attempt_id") val attemptId: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "candidate_id") val candidateId: String?,
    @ColumnInfo(name = "source_id") val sourceId: String?,
    @ColumnInfo(name = "engine") val engine: String,

    @ColumnInfo(name = "route_snapshot_json") val routeSnapshotJson: String?,

    @ColumnInfo(name = "stages_json") val stagesJson: String?,

    @ColumnInfo(name = "classified_failure") val classifiedFailure: String?,
    @ColumnInfo(name = "redacted_diagnostics_json") val redactedDiagnosticsJson: String?,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
    @ColumnInfo(name = "ended_at_ms") val endedAtMs: Long?,
)
