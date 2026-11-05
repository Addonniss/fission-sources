package dev.infusion.core.domain.collections

import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.TitleKind

data class CollectionBrowseItem(

    val providerItemId: String,
    val kind: TitleKind,

    val title: String,
    val year: Int? = null,
    val tmdbId: Long? = null,
    val imdbId: String? = null,

    val artwork: MediaArtworkRef? = null,
)

data class CollectionBrowsePage(
    val items: List<CollectionBrowseItem>,
    val hasMore: Boolean = false,

    val nextCursor: String? = null,
)

sealed interface CollectionBrowseOutcome {
    data class Loaded(val page: CollectionBrowsePage) : CollectionBrowseOutcome
    data class Unsupported(val reason: String) : CollectionBrowseOutcome
    data class Failed(val reason: String) : CollectionBrowseOutcome
}

interface CollectionRowPageSource {
    suspend fun page(
        definition: CollectionSourceDefinition,
        cursor: String? = null,
        ownerKey: String? = null,
    ): CollectionBrowseOutcome
}
