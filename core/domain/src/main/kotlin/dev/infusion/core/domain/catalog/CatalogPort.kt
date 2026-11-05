package dev.infusion.core.domain.catalog

import dev.infusion.core.model.Availability
import dev.infusion.core.model.Edition
import dev.infusion.core.model.EditionId
import dev.infusion.core.model.Source
import dev.infusion.core.model.Title
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import kotlinx.coroutines.flow.Flow

data class HomeShelf(
    val key: String,
    val title: String,
    val sourceLabel: String?,
    val items: List<HomeItem>,
)

data class HomeItem(
    val key: String,
    val titleId: TitleId,
    val editionId: EditionId?,
    val displayTitle: String,
    val kind: TitleKind,
    val year: Int?,
    val subtitle: String? = null,
)

data class TitleDetails(
    val title: Title,
    val editions: List<Edition>,
    val availability: List<Availability>,
)

data class CatalogEntry(
    val nativeItemId: String,
    val nativeParentId: String?,
    val titleId: TitleId?,
    val displayTitle: String,
    val kind: TitleKind,
    val year: Int?,
    val isFolder: Boolean,
    val childCount: Int?,
    val seriesTitleId: TitleId? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,

    val serverResumeMs: Long? = null,

    val serverResumeObservedAt: java.time.Instant? = null,
)

data class CatalogPage(
    val entries: List<CatalogEntry>,
    val totalCount: Int,
    val startIndex: Int,
    val limit: Int,
)

data class CatalogImage(
    val contentType: String?,
    val bytes: ByteArray,
)

interface CatalogPort {

    fun observeHome(): Flow<List<HomeShelf>>

    fun observeTitleDetails(titleId: TitleId): Flow<TitleDetails?>

    fun sources(): List<Source>

    suspend fun browse(parentNativeId: String?, startIndex: Int, limit: Int): CatalogPage?

    suspend fun search(searchTerm: String, startIndex: Int, limit: Int): CatalogPage?

    suspend fun children(parentNativeId: String, startIndex: Int, limit: Int): CatalogPage?

    suspend fun nativeItemIdFor(titleId: TitleId): String?

    suspend fun image(nativeItemId: String, type: String = "Primary"): CatalogImage?

    suspend fun image(
        nativeItemId: String,
        type: String,
        rendition: dev.infusion.core.model.ArtworkRendition,
    ): CatalogImage? = image(nativeItemId, type)
}
