package dev.infusion.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "profiles",
    indices = [
        Index(value = ["profile_index"], unique = true),
    ],
)
data class ProfileEntity(
    @PrimaryKey @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "is_default") val isDefault: Boolean,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "profile_index") val profileIndex: Int = 1,
    @ColumnInfo(name = "pin_hash") val pinHash: String? = null,
    @ColumnInfo(name = "failed_pin_attempts") val failedPinAttempts: Int = 0,
    @ColumnInfo(name = "pin_locked_until_ms") val pinLockedUntilMs: Long? = null,
    @ColumnInfo(name = "uses_primary_sources") val usesPrimarySources: Boolean = true,
)
