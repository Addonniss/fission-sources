package dev.infusion.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "server_items",
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
        Index(value = ["source_id", "native_id"], unique = true),
        Index(value = ["title_id"]),
        Index(value = ["edition_id"]),
    ],
)
data class ServerItemEntity(
    @PrimaryKey @ColumnInfo(name = "server_item_id") val serverItemId: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "native_id") val nativeId: String,
    @ColumnInfo(name = "native_parent_id") val nativeParentId: String?,
    @ColumnInfo(name = "native_type") val nativeType: String,
    @ColumnInfo(name = "library_id") val libraryId: String?,

    @ColumnInfo(name = "revision") val revision: String?,
    @ColumnInfo(name = "title_id") val titleId: String?,
    @ColumnInfo(name = "edition_id") val editionId: String?,

    @ColumnInfo(name = "display_title") val displayTitle: String?,
    @ColumnInfo(name = "production_year") val productionYear: Int?,
    @ColumnInfo(name = "provider_payload_json") val providerPayloadJson: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

@Entity(
    tableName = "server_media_versions",
    foreignKeys = [
        ForeignKey(
            entity = ServerItemEntity::class,
            parentColumns = ["server_item_id"],
            childColumns = ["server_item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["server_item_id"])],
)
data class ServerMediaVersionEntity(
    @PrimaryKey @ColumnInfo(name = "version_id") val versionId: String,
    @ColumnInfo(name = "server_item_id") val serverItemId: String,
    @ColumnInfo(name = "native_version_id") val nativeVersionId: String?,
    @ColumnInfo(name = "container") val container: String?,

    @ColumnInfo(name = "stream_facts_json") val streamFactsJson: String?,

    @ColumnInfo(name = "path_class") val pathClass: String?,
    @ColumnInfo(name = "decision_inputs_json") val decisionInputsJson: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)
