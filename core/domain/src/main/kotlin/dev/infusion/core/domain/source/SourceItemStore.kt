package dev.infusion.core.domain.source

import dev.infusion.core.model.Availability
import dev.infusion.core.model.EditionId
import dev.infusion.core.model.ServerItem
import dev.infusion.core.model.ServerMediaVersion
import dev.infusion.core.model.SourceId
import dev.infusion.core.model.Title
import dev.infusion.core.model.TitleId

interface SourceItemStore {

    fun upsertServerItem(item: ServerItem)

    fun upsertServerMediaVersion(version: ServerMediaVersion)

    fun upsertAll(
        items: List<ServerItem>,
        versions: List<ServerMediaVersion>,
        titles: List<Title> = emptyList(),
        availability: List<Availability> = emptyList(),
    )

    fun deleteMissingByLibrary(sourceId: SourceId, libraryId: String, retainedNativeIds: Set<String>): Int

    fun deleteNativeItems(sourceId: SourceId, nativeIds: Set<String>): Int

    fun serverItemByNativeId(sourceId: SourceId, nativeId: String): ServerItem?

    fun serverItemsBySource(sourceId: SourceId): List<ServerItem>

    fun serverItemsByTitle(titleId: TitleId): List<ServerItem>

    fun mediaVersionsForItem(serverItemId: String): List<ServerMediaVersion>

    fun deleteBySource(sourceId: SourceId): Int
}
