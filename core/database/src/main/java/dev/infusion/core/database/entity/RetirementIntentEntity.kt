package dev.infusion.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "retirement_intents",
)
data class RetirementIntentEntity(
    @PrimaryKey @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "payload") val payload: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)
