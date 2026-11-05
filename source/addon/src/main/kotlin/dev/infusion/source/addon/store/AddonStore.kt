package dev.infusion.source.addon.store

import dev.infusion.core.database.dao.AddonDao
import dev.infusion.core.database.entity.AddonCatalogPreferenceEntity
import dev.infusion.core.database.entity.AddonEntity
import dev.infusion.source.addon.AddonManifestSnapshot

interface AddonStore {

    data class StoredAddon(
        val entity: AddonEntity,

        val snapshot: AddonManifestSnapshot,
    )

    data class RestoreIssue(
        val addonId: String,
        val reason: String,
    )

    fun put(addon: AddonEntity, snapshot: AddonManifestSnapshot)

    fun getEntity(addonId: String): AddonEntity?

    fun get(addonId: String): StoredAddon?

    fun listActive(): List<StoredAddon>

    fun restoreIssues(): List<RestoreIssue>

    fun reorder(addonIds: List<String>)

    fun setEnabled(addonId: String, enabled: Boolean, updatedAtMs: Long): Boolean

    fun setDisplayName(addonId: String, displayName: String?, updatedAtMs: Long): Boolean

    fun delete(addonId: String): Boolean

    fun recordHealthObservation(observation: dev.infusion.core.database.entity.AddonHealthObservationEntity)

    fun recentHealthObservations(addonId: String, limit: Int): List<dev.infusion.core.database.entity.AddonHealthObservationEntity>

    fun pruneHealthObservations(addonId: String, keep: Int)

    fun setCatalogPreference(preference: AddonCatalogPreferenceEntity)

    fun catalogPreferences(profileId: String): List<AddonCatalogPreferenceEntity>
}
