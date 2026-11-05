package dev.infusion.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.infusion.core.database.model.EditionKind
import dev.infusion.core.database.model.TitleKind
import dev.infusion.core.database.model.TitleRelation

@Entity(
    tableName = "titles",
    indices = [Index(value = ["kind"])],
)
data class TitleEntity(
    @PrimaryKey @ColumnInfo(name = "title_id") val titleId: String,
    @ColumnInfo(name = "kind") val kind: TitleKind,
    @ColumnInfo(name = "primary_title") val primaryTitle: String,
    @ColumnInfo(name = "original_title") val originalTitle: String?,
    @ColumnInfo(name = "year") val year: Int?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,

    @ColumnInfo(name = "synopsis") val synopsis: String? = null,

    @ColumnInfo(name = "genres") val genres: String? = null,

    @ColumnInfo(name = "rating") val rating: String? = null,

    @ColumnInfo(name = "synopsis_provenance") val synopsisProvenanceJson: String? = null,

    @ColumnInfo(name = "genres_provenance") val genresProvenanceJson: String? = null,

    @ColumnInfo(name = "rating_provenance") val ratingProvenanceJson: String? = null,

    @ColumnInfo(name = "season_number") val seasonNumber: Int? = null,

    @ColumnInfo(name = "episode_number") val episodeNumber: Int? = null,

    @ColumnInfo(name = "superseded_by_title_id") val supersededByTitleId: String? = null,
)

@Entity(
    tableName = "title_links",
    primaryKeys = ["parent_title_id", "child_title_id"],
    foreignKeys = [
        ForeignKey(
            entity = TitleEntity::class,
            parentColumns = ["title_id"],
            childColumns = ["parent_title_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TitleEntity::class,
            parentColumns = ["title_id"],
            childColumns = ["child_title_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["child_title_id"])],
)
data class TitleLinkEntity(
    @ColumnInfo(name = "parent_title_id") val parentTitleId: String,
    @ColumnInfo(name = "child_title_id") val childTitleId: String,
    @ColumnInfo(name = "relation") val relation: TitleRelation,
)

@Entity(
    tableName = "editions",
    foreignKeys = [
        ForeignKey(
            entity = TitleEntity::class,
            parentColumns = ["title_id"],
            childColumns = ["title_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["title_id"])],
)
data class EditionEntity(
    @PrimaryKey @ColumnInfo(name = "edition_id") val editionId: String,
    @ColumnInfo(name = "title_id") val titleId: String,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "kind") val kind: EditionKind,
    @ColumnInfo(name = "runtime_minutes") val runtimeMinutes: Int?,
    @ColumnInfo(name = "confidence") val confidence: Double,
    @ColumnInfo(name = "provenance") val provenance: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)
