package dev.infusion.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.infusion.core.model.SourceHealth

@Entity(
    tableName = "installed_addons",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["source_id"],
            childColumns = ["addon_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["profile_id"],
            childColumns = ["owner_profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["owner_profile_id"]),
        Index(value = ["public_base_url"]),
    ],
)
data class AddonEntity(
    @PrimaryKey @ColumnInfo(name = "addon_id") val addonId: String,

    @ColumnInfo(name = "public_base_url") val publicBaseUrl: String,

    @ColumnInfo(name = "query_secret_ref_id") val querySecretRefId: String?,
    @ColumnInfo(name = "manifest_id") val manifestId: String,
    @ColumnInfo(name = "manifest_name") val manifestName: String,
    @ColumnInfo(name = "manifest_version") val manifestVersion: String,
    @ColumnInfo(name = "manifest_description") val manifestDescription: String,

    @ColumnInfo(name = "manifest_snapshot_json") val manifestSnapshotJson: String,

    @ColumnInfo(name = "manifest_fetched_at_ms") val manifestFetchedAtMs: Long,

    @ColumnInfo(name = "display_name_override") val displayNameOverride: String? = null,

    @ColumnInfo(name = "disclosure_json") val disclosureJson: String,

    @ColumnInfo(name = "lan_consented") val lanConsented: Boolean,
    @ColumnInfo(name = "lan_host") val lanHost: String?,
    @ColumnInfo(name = "enabled") val enabled: Boolean,

    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "owner_profile_id") val ownerProfileId: String,
    @ColumnInfo(name = "installed_at_ms") val installedAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,

    @ColumnInfo(name = "removed_at_ms") val removedAtMs: Long?,
)

@Entity(
    tableName = "addon_catalog_preferences",
    primaryKeys = ["profile_id", "addon_id", "catalog_type", "catalog_id"],
    foreignKeys = [
        ForeignKey(
            entity = AddonEntity::class,
            parentColumns = ["addon_id"],
            childColumns = ["addon_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["profile_id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["addon_id"]),
        Index(value = ["profile_id", "sort_order"]),
    ],
)
data class AddonCatalogPreferenceEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "addon_id") val addonId: String,
    @ColumnInfo(name = "catalog_type") val catalogType: String,
    @ColumnInfo(name = "catalog_id") val catalogId: String,

    @ColumnInfo(name = "sort_order") val sortOrder: Int,

    @ColumnInfo(name = "hidden") val hidden: Boolean,

    @ColumnInfo(name = "discover_visible", defaultValue = "1") val discoverVisible: Boolean = true,
)

@Entity(
    tableName = "addon_health_observations",
    foreignKeys = [
        ForeignKey(
            entity = AddonEntity::class,
            parentColumns = ["addon_id"],
            childColumns = ["addon_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["addon_id", "observed_at_ms"]),
    ],
)
data class AddonHealthObservationEntity(
    @PrimaryKey @ColumnInfo(name = "observation_id") val observationId: String,
    @ColumnInfo(name = "addon_id") val addonId: String,
    @ColumnInfo(name = "observed_at_ms") val observedAtMs: Long,
    @ColumnInfo(name = "healthy") val healthy: Boolean,
    @ColumnInfo(name = "reason") val reason: String?,
    @ColumnInfo(name = "status") val status: Int?,
    @ColumnInfo(name = "source_health") val sourceHealth: SourceHealth,
) {
    companion object {

        const val MAX_HISTORY = 20
    }
}
