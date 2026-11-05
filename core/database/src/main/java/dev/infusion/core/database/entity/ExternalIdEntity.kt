package dev.infusion.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "external_ids",
    primaryKeys = ["authority", "native_id"],
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
    ],
    indices = [Index(value = ["title_id"]), Index(value = ["edition_id"])],
)
data class ExternalIdEntity(
    @ColumnInfo(name = "authority") val authority: String,
    @ColumnInfo(name = "native_id") val nativeId: String,
    @ColumnInfo(name = "title_id") val titleId: String?,
    @ColumnInfo(name = "edition_id") val editionId: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)
