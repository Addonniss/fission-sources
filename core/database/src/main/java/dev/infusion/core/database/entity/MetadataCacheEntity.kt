package dev.infusion.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "metadata_cache",
    primaryKeys = ["namespace", "cache_key"],
    indices = [androidx.room.Index(value = ["stored_at_ms"])],
)
data class MetadataCacheEntity(
    @ColumnInfo(name = "namespace") val namespace: String,
    @ColumnInfo(name = "cache_key") val cacheKey: String,
    @ColumnInfo(name = "payload") val payload: String,
    @ColumnInfo(name = "stored_at_ms") val storedAtMs: Long,
)
