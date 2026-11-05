package dev.infusion.core.domain.collections

import kotlinx.coroutines.flow.Flow

interface CollectionRowPreferenceStore {

    fun read(profileId: String): CollectionRowPreferenceConfiguration

    fun readPage(
        profileId: String,
        packId: String,
        groupKey: String,
        tileKey: String,
    ): CollectionPageRowPreferences {
        val page = read(profileId).pages.firstOrNull { record ->
            record.packId == packId && record.groupKey == groupKey && record.tileKey == tileKey
        }
        return page?.let { CollectionPageRowPreferences(it.entries, it.unreadableEntries) }
            ?: CollectionPageRowPreferences.None
    }

    fun writePage(
        profileId: String,
        packId: String,
        groupKey: String,
        tileKey: String,
        entries: List<CollectionRowPreferenceEntry>,
    ): Boolean

    fun clearProfile(profileId: String)

    fun clearAllProfiles()

    fun changes(): Flow<Unit>
}
