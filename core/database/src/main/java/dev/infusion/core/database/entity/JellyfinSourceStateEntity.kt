package dev.infusion.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "jellyfin_source_state",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["source_id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["source_id"], unique = true)],
)
data class JellyfinSourceStateEntity(
    @PrimaryKey @ColumnInfo(name = "source_id") val sourceId: String,

    @ColumnInfo(name = "origin") val origin: String,
    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "server_id") val serverId: String,

    @ColumnInfo(name = "device_id") val deviceId: String,

    @ColumnInfo(name = "token_ref_id") val tokenRefId: String?,
    @ColumnInfo(name = "server_version") val serverVersion: String?,
    @ColumnInfo(name = "version_qualification") val versionQualification: String?,

    @ColumnInfo(name = "user_avatar_tag") val userAvatarTag: String? = null,

    @ColumnInfo(name = "lan_consented") val lanConsented: Boolean,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)
