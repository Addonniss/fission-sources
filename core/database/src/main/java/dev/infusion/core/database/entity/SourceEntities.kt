package dev.infusion.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.infusion.core.database.model.HealthState
import dev.infusion.core.database.model.SecretKind
import dev.infusion.core.database.model.SourceType
import dev.infusion.core.database.model.TrustClass

@Entity(
    tableName = "sources",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["profile_id"],
            childColumns = ["owner_profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["owner_profile_id"]),
        Index(value = ["type"]),
        Index(value = ["removed_at_ms"]),
    ],
)
data class SourceEntity(
    @PrimaryKey @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "type") val type: SourceType,
    @ColumnInfo(name = "owner_profile_id") val ownerProfileId: String,
    @ColumnInfo(name = "display_name") val displayName: String,

    @ColumnInfo(name = "origin") val origin: String?,
    @ColumnInfo(name = "trust_class") val trustClass: TrustClass,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "health_state") val healthState: HealthState,
    @ColumnInfo(name = "last_health_check_ms") val lastHealthCheckMs: Long?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,

    @ColumnInfo(name = "removed_at_ms") val removedAtMs: Long?,
)

@Entity(
    tableName = "secret_references",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["source_id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["source_id"]),
        Index(value = ["source_id", "kind", "scope"], unique = true),
    ],
)
data class SecretReferenceEntity(
    @PrimaryKey @ColumnInfo(name = "secret_ref_id") val secretRefId: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "kind") val kind: SecretKind,
    @ColumnInfo(name = "key_alias") val keyAlias: String,
    @ColumnInfo(name = "scope") val scope: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long?,
    @ColumnInfo(name = "revoked_at_ms") val revokedAtMs: Long?,
)
