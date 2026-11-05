package dev.infusion.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.infusion.core.model.identity.MappingOrigin
import dev.infusion.core.model.identity.MappingStatus

@Entity(
    tableName = "identity_mappings",
    indices = [
        Index(value = ["authority", "external_value"]),
        Index(value = ["canonical_title_id"]),
        Index(value = ["supersedes_mapping_id"]),
    ],
)
data class IdentityMappingEntity(
    @PrimaryKey @ColumnInfo(name = "mapping_id") val mappingId: String,
    @ColumnInfo(name = "authority") val authority: String,
    @ColumnInfo(name = "external_value") val externalValue: String,
    @ColumnInfo(name = "canonical_title_id") val canonicalTitleId: String,
    @ColumnInfo(name = "canonical_edition_id") val canonicalEditionId: String?,
    @ColumnInfo(name = "status") val status: MappingStatus,
    @ColumnInfo(name = "confidence") val confidence: Double,
    @ColumnInfo(name = "origin") val origin: MappingOrigin,
    @ColumnInfo(name = "supersedes_mapping_id") val supersedesMappingId: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)
