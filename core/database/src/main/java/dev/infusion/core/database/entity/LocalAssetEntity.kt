package dev.infusion.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.infusion.core.database.model.AccessState

@Entity(
    tableName = "local_assets",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["source_id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TitleEntity::class,
            parentColumns = ["title_id"],
            childColumns = ["title_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = EditionEntity::class,
            parentColumns = ["edition_id"],
            childColumns = ["edition_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["source_id", "uri_identity"], unique = true),
        Index(value = ["title_id"]),
        Index(value = ["edition_id"]),
    ],
)
data class LocalAssetEntity(
    @PrimaryKey @ColumnInfo(name = "local_asset_id") val localAssetId: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "uri_identity") val uriIdentity: String,

    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long?,
    @ColumnInfo(name = "modified_at_ms") val modifiedAtMs: Long?,
    @ColumnInfo(name = "seekable") val seekable: Boolean?,
    @ColumnInfo(name = "fingerprint") val fingerprint: String?,
    @ColumnInfo(name = "title_id") val titleId: String?,
    @ColumnInfo(name = "edition_id") val editionId: String?,
    @ColumnInfo(name = "media_facts_json") val mediaFactsJson: String?,
    @ColumnInfo(name = "access_state") val accessState: AccessState,
    @ColumnInfo(name = "last_checked_ms") val lastCheckedMs: Long?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)
