package dev.infusion.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.infusion.core.database.model.AvailabilityKind

@Entity(
    tableName = "availability",
    foreignKeys = [
        ForeignKey(
            entity = TitleEntity::class,
            parentColumns = ["title_id"],
            childColumns = ["title_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = EditionEntity::class,
            parentColumns = ["edition_id"],
            childColumns = ["edition_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["source_id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["title_id"]),
        Index(value = ["edition_id"]),
        Index(value = ["source_id"]),
        Index(value = ["source_id", "native_resource_id"], unique = true),
    ],
)
data class AvailabilityEntity(
    @PrimaryKey @ColumnInfo(name = "availability_id") val availabilityId: String,
    @ColumnInfo(name = "title_id") val titleId: String,
    @ColumnInfo(name = "edition_id") val editionId: String?,
    @ColumnInfo(name = "source_id") val sourceId: String,

    @ColumnInfo(name = "native_resource_id") val nativeResourceId: String?,
    @ColumnInfo(name = "kind") val kind: AvailabilityKind,
    @ColumnInfo(name = "region") val region: String?,
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long?,
    @ColumnInfo(name = "entitlement_hints_json") val entitlementHintsJson: String?,
    @ColumnInfo(name = "provenance") val provenance: String?,
    @ColumnInfo(name = "refreshed_at_ms") val refreshedAtMs: Long?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)
