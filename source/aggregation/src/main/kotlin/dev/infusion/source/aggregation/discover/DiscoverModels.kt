package dev.infusion.source.aggregation.discover

import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.MediaPreview
import dev.infusion.source.aggregation.match.AddonMatch
import dev.infusion.source.aggregation.match.AddonMetaItem

data class DiscoverShelfDeclaration(
    val key: String,
    val title: String,
    val sourceLabel: String,
)

sealed interface ShelfState {

    data class Loaded(
        val items: List<DiscoverItem>,
        val hasMore: Boolean = false,
    ) : ShelfState

    data class Failed(val reason: String) : ShelfState
}

data class DiscoverShelf(
    val key: String,
    val title: String,
    val sourceLabel: String,
    val state: ShelfState,
)

data class DiscoverShelfEmission(
    val order: Int,
    val shelf: DiscoverShelf,
)

data class DiscoverItem(
    val key: String,
    val titleId: TitleId?,
    val item: AddonMetaItem,
    val kind: TitleKind?,
    val match: AddonMatch?,
    val preview: MediaPreview,
)
