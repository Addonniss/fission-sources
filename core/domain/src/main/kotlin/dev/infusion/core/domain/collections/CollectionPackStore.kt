package dev.infusion.core.domain.collections

import kotlinx.coroutines.flow.Flow

interface CollectionPackStore {

    fun read(profileId: String): CollectionPackConfiguration

    fun readPack(profileId: String, packId: String): CollectionPack? =
        read(profileId).packs.firstOrNull { it.packId == packId }

    fun write(profileId: String, packs: List<CollectionPack>)

    fun clearProfile(profileId: String)

    fun clearAllProfiles()

    fun changes(): Flow<Unit>
}
