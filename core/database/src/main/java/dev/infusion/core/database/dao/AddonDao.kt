package dev.infusion.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.infusion.core.database.entity.AddonCatalogPreferenceEntity
import dev.infusion.core.database.entity.AddonEntity
import dev.infusion.core.database.entity.AddonHealthObservationEntity

@Dao
interface AddonDao {

    @Upsert
    fun upsert(addon: AddonEntity)

    @Query("SELECT * FROM installed_addons WHERE addon_id = :addonId")
    fun getById(addonId: String): AddonEntity?

    @Query("SELECT * FROM installed_addons WHERE removed_at_ms IS NULL ORDER BY sort_order ASC, installed_at_ms ASC")
    fun listActive(): List<AddonEntity>

    @Query("UPDATE installed_addons SET enabled = :enabled, updated_at_ms = :updatedAtMs WHERE addon_id = :addonId")
    fun setEnabled(addonId: String, enabled: Boolean, updatedAtMs: Long): Int

    @Query(
        "UPDATE installed_addons SET display_name_override = :displayName, updated_at_ms = :updatedAtMs " +
            "WHERE addon_id = :addonId",
    )
    fun setDisplayName(addonId: String, displayName: String?, updatedAtMs: Long): Int

    @Query("DELETE FROM installed_addons WHERE addon_id = :addonId")
    fun deleteAddon(addonId: String): Int

    @Upsert
    fun upsertPreference(preference: AddonCatalogPreferenceEntity)

    @Query(
        "SELECT * FROM addon_catalog_preferences WHERE profile_id = :profileId ORDER BY sort_order ASC",
    )
    fun listPreferences(profileId: String): List<AddonCatalogPreferenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertHealthObservation(observation: AddonHealthObservationEntity)

    @Query("SELECT * FROM addon_health_observations WHERE addon_id = :addonId ORDER BY observed_at_ms DESC")
    fun listHealthObservations(addonId: String): List<AddonHealthObservationEntity>

    @Query("SELECT * FROM addon_health_observations WHERE addon_id = :addonId ORDER BY observed_at_ms DESC LIMIT :limit")
    fun listRecentHealthObservations(addonId: String, limit: Int): List<AddonHealthObservationEntity>

    @Query(
        "DELETE FROM addon_health_observations WHERE addon_id = :addonId AND observation_id NOT IN (" +
            "SELECT observation_id FROM addon_health_observations WHERE addon_id = :addonId " +
            "ORDER BY observed_at_ms DESC LIMIT :keep)",
    )
    fun pruneHealthObservations(addonId: String, keep: Int): Int

    @Query("DELETE FROM addon_health_observations WHERE addon_id = :addonId")
    fun deleteHealthObservations(addonId: String): Int
}
