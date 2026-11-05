package dev.infusion.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.infusion.core.database.entity.SourceEntity

@Entity(
    tableName = "play_session_contexts",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["source_id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["source_id", "watch_session_id"], unique = true),
        Index(value = ["source_id"]),
    ],
)
data class PlaySessionContextEntity(
    @PrimaryKey @ColumnInfo(name = "context_id") val contextId: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "watch_session_id") val watchSessionId: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "media_source_id") val mediaSourceId: String,
    @ColumnInfo(name = "play_session_id") val playSessionId: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)
