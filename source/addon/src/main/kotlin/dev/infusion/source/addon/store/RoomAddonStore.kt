package dev.infusion.source.addon.store

import dev.infusion.core.database.dao.AddonDao
import dev.infusion.core.database.entity.AddonCatalogPreferenceEntity
import dev.infusion.core.database.entity.AddonEntity
import dev.infusion.source.addon.AddonManifestSnapshot
import dev.infusion.source.addon.AddonManifestSnapshotCodec

class RoomAddonStore(
    private val dao: AddonDao,
    private val transaction: (block: () -> Unit) -> Unit,
) : AddonStore {

    override fun put(addon: AddonEntity, snapshot: AddonManifestSnapshot) {
        transaction {

            val encoded = AddonManifestSnapshotCodec.encode(snapshot)
            dao.upsert(addon.copy(manifestSnapshotJson = encoded))
        }
    }

    override fun getEntity(addonId: String): AddonEntity? = dao.getById(addonId)

    override fun get(addonId: String): AddonStore.StoredAddon? {
        val entity = dao.getById(addonId) ?: return null
        return decode(entity)
    }

    override fun listActive(): List<AddonStore.StoredAddon> =
        dao.listActive().mapNotNull { decode(it) }

    override fun restoreIssues(): List<AddonStore.RestoreIssue> =
        dao.listActive().mapNotNull { entity ->
            runCatching { AddonManifestSnapshotCodec.decode(entity.manifestSnapshotJson) }
                .fold(
                    onSuccess = { null },
                    onFailure = { e ->
                        AddonStore.RestoreIssue(
                            addonId = entity.addonId,
                            reason = "corrupt manifest snapshot: ${e.message?.take(120)}",
                        )
                    },
                )
        }

    override fun reorder(addonIds: List<String>) {
        transaction {
            addonIds.forEachIndexed { index, addonId ->
                dao.upsert(
                    checkNotNull(dao.getById(addonId)) { "reorder target $addonId does not exist" }
                        .copy(sortOrder = index),
                )
            }
        }
    }

    override fun setEnabled(addonId: String, enabled: Boolean, updatedAtMs: Long): Boolean =
        dao.setEnabled(addonId, enabled, updatedAtMs) > 0

    override fun setDisplayName(addonId: String, displayName: String?, updatedAtMs: Long): Boolean =
        dao.setDisplayName(addonId, displayName, updatedAtMs) > 0

    override fun delete(addonId: String): Boolean = dao.deleteAddon(addonId) > 0

    override fun recordHealthObservation(observation: dev.infusion.core.database.entity.AddonHealthObservationEntity) {
        dao.insertHealthObservation(observation)
    }

    override fun recentHealthObservations(
        addonId: String,
        limit: Int,
    ): List<dev.infusion.core.database.entity.AddonHealthObservationEntity> =
        dao.listRecentHealthObservations(addonId, limit)

    override fun pruneHealthObservations(addonId: String, keep: Int) {
        dao.pruneHealthObservations(addonId, keep)
    }

    override fun setCatalogPreference(preference: AddonCatalogPreferenceEntity) {
        dao.upsertPreference(preference)
    }

    override fun catalogPreferences(profileId: String): List<AddonCatalogPreferenceEntity> =
        dao.listPreferences(profileId)

    private fun decode(entity: AddonEntity): AddonStore.StoredAddon? {
        val snapshot = runCatching { AddonManifestSnapshotCodec.decode(entity.manifestSnapshotJson) }
            .getOrNull() ?: return null
        return AddonStore.StoredAddon(entity = entity, snapshot = snapshot)
    }
}
