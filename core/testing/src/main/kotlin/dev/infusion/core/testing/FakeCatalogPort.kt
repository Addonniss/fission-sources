package dev.infusion.core.testing

import dev.infusion.core.domain.catalog.CatalogEntry
import dev.infusion.core.domain.catalog.CatalogImage
import dev.infusion.core.domain.catalog.CatalogPage
import dev.infusion.core.domain.catalog.CatalogPort
import dev.infusion.core.domain.catalog.HomeItem
import dev.infusion.core.domain.catalog.HomeShelf
import dev.infusion.core.domain.catalog.TitleDetails
import dev.infusion.core.model.Source
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeCatalogPort(
    private val fixtures: FakeFixtures = FakeFixtures,
) : CatalogPort {

    private val home = MutableStateFlow(buildHome())

    private fun buildHome(): List<HomeShelf> = listOf(
        HomeShelf(
            key = "shelf:recently-added",
            title = "Recently Added",
            sourceLabel = "Demo Server",
            items = fixtures.titles
                .filter { it.kind == TitleKind.MOVIE || it.kind == TitleKind.SERIES }
                .map { title ->
                    HomeItem(
                        key = "item:${title.titleId.value}",
                        titleId = title.titleId,
                        editionId = null,
                        displayTitle = title.displayTitle,
                        kind = title.kind,
                        year = title.year,
                    )
                },
        ),
        HomeShelf(
            key = "shelf:demo-addon",
            title = "From the Demo Add-on",
            sourceLabel = "Demo Add-on",
            items = fixtures.titles
                .filter { fixtures.availabilityFor(it.titleId).any { a -> a.sourceId == fixtures.addonSource.sourceId } }
                .map { title ->
                    HomeItem(
                        key = "item:${title.titleId.value}",
                        titleId = title.titleId,
                        editionId = null,
                        displayTitle = title.displayTitle,
                        kind = title.kind,
                        year = title.year,
                    )
                },
        ),
    )

    override fun observeHome(): Flow<List<HomeShelf>> = home

    override fun observeTitleDetails(titleId: TitleId): Flow<TitleDetails?> = home.map {
        val title = fixtures.allTitles.firstOrNull { it.titleId == titleId } ?: return@map null
        TitleDetails(
            title = title,
            editions = fixtures.editionsFor(titleId),
            availability = fixtures.availabilityFor(titleId),
        )
    }

    override fun sources(): List<Source> = fixtures.sources

    override suspend fun browse(parentNativeId: String?, startIndex: Int, limit: Int): CatalogPage {
        val all = when (parentNativeId) {

            fixtures.signalLostTitle.titleId.value.toString() -> fixtures.signalLostSeasons.map { it.toCatalogEntry() }
            else -> {
                val season = fixtures.signalLostSeasons.firstOrNull { it.titleId.value.toString() == parentNativeId }
                if (season != null) {
                    fixtures.seasonEpisodes(season.titleId).map { it.toCatalogEntry() }
                } else {
                    fixtures.titles
                        .filter { parentNativeId == null || it.displayTitle.contains(parentNativeId, ignoreCase = true) }
                        .map { it.toCatalogEntry() }
                }
            }
        }
        return all.toPage(startIndex, limit)
    }

    override suspend fun search(searchTerm: String, startIndex: Int, limit: Int): CatalogPage {
        val all = fixtures.titles
            .filter { searchTerm.isBlank() || it.displayTitle.contains(searchTerm, ignoreCase = true) }
            .map { it.toCatalogEntry() }
        return all.toPage(startIndex, limit)
    }

    override suspend fun children(parentNativeId: String, startIndex: Int, limit: Int): CatalogPage {
        val children = when (parentNativeId) {
            DEMO_FOLDER_ID -> fixtures.titles.map { it.toCatalogEntry() }
            fixtures.signalLostTitle.titleId.value.toString() ->
                fixtures.signalLostSeasons.map { it.toCatalogEntry() }
            else -> {
                val season = fixtures.signalLostSeasons.firstOrNull { it.titleId.value.toString() == parentNativeId }
                if (season != null) {
                    fixtures.seasonEpisodes(season.titleId).map { it.toCatalogEntry() }
                } else {
                    emptyList()
                }
            }
        }
        return children.toPage(startIndex, limit)
    }

    override suspend fun nativeItemIdFor(titleId: TitleId): String? =
        fixtures.allTitles.firstOrNull { it.titleId == titleId }?.titleId?.value?.toString()

    override suspend fun image(nativeItemId: String, type: String): CatalogImage? {

        val known = fixtures.allTitles.any {
            it.titleId.value.toString() == nativeItemId ||
                it.displayTitle.contains(nativeItemId, ignoreCase = true)
        }
        if (!known) return null
        return CatalogImage(
            contentType = "image/png",
            bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
        )
    }

    private fun dev.infusion.core.model.Title.toCatalogEntry(): CatalogEntry = CatalogEntry(
        nativeItemId = titleId.value.toString(),
        nativeParentId = parentTitleId?.value?.toString(),
        titleId = titleId,
        displayTitle = displayTitle,
        kind = kind,
        year = year,
        isFolder = kind == TitleKind.SERIES || kind == TitleKind.SEASON,
        childCount = null,

        seriesTitleId = parentTitleId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )

    private fun List<CatalogEntry>.toPage(startIndex: Int, limit: Int): CatalogPage = CatalogPage(
        entries = drop(startIndex).take(limit),
        totalCount = size,
        startIndex = startIndex,
        limit = limit,
    )

    companion object {

        const val DEMO_FOLDER_ID = "demo-folder"
    }
}

fun FakeFixtures.editionsFor(titleId: TitleId): List<dev.infusion.core.model.Edition> = when (titleId) {
    longWalkTitle.titleId -> listOf(longWalkTheatrical, longWalkDirectors)
    else -> emptyList()
}
