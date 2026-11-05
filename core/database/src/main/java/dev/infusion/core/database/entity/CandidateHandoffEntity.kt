package dev.infusion.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "candidate_handoff",
    indices = [Index(value = ["candidate_id"], unique = true)],
)
data class CandidateHandoffEntity(
    @PrimaryKey @ColumnInfo(name = "candidate_id") val candidateId: String,

    @ColumnInfo(name = "snapshot_json") val snapshotJson: String,

    @ColumnInfo(name = "transport_secret_ref") val transportSecretRef: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)
