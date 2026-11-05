package dev.infusion.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "checkpoints",
    primaryKeys = ["profile_id", "connector_id"],
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
        Index(value = ["updated_at_ms"]),
    ],
)
data class CheckpointEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "connector_id") val connectorId: String,
    @ColumnInfo(name = "last_import_cursor") val lastImportCursor: String?,
    @ColumnInfo(name = "last_imported_at_ms") val lastImportedAtMs: Long?,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)
