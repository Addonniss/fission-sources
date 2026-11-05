package dev.infusion.source.aggregation.search

import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.metadata.MediaIdentity
import dev.infusion.source.aggregation.match.AddonMatch
import dev.infusion.source.aggregation.match.AddonMetaItem

enum class AvailabilityBadge {

    METADATA,

    LOCAL,

    SERVER,

    ADDON,
}

data class SearchGroup(

    val key: String,
    val titleId: TitleId?,
    val displayTitle: String,
    val year: Int?,
    val badges: Set<AvailabilityBadge>,

    val items: List<SearchHit>,

    val kind: dev.infusion.core.model.TitleKind? = null,

    val destination: SearchDestination? = null,
) {

    val destinationTitleId: TitleId? get() = destination?.titleId ?: titleId

    fun withMatcherDestination(): SearchGroup {
        if (destination != null) return this
        val id = titleId ?: return this
        val destinationKind = kind ?: TitleKind.MOVIE
        return copy(
            destination = SearchDestination(
                titleId = id,
                kind = destinationKind,
                preview = mediaPreview(id, destinationKind, displayTitle, year),
            ),
        )
    }
}

data class SearchDestination(
    val titleId: TitleId,
    val kind: TitleKind,
    val preview: MediaPreview,
)

internal fun mediaPreview(
    titleId: TitleId,
    kind: TitleKind,
    displayTitle: String,
    year: Int?,
): MediaPreview = MediaPreview(
    titleId = titleId,
    kind = kind,
    displayTitle = displayTitle,
    year = year,
    subtitle = null,

    artwork = null,
    progressFraction = null,
)

data class SearchHit(
    val key: String,

    val addonId: String,
    val addonDisplayName: String,
    val item: AddonMetaItem,
    val match: AddonMatch,

    val artwork: MediaArtworkRef? = null,
)

data class CanonicalSearchResult(
    val key: String,
    val titleId: TitleId,
    val identity: MediaIdentity,
    val kind: TitleKind,
    val displayTitle: String,
    val year: Int?,
    val preview: MediaPreview,
    val badges: Set<AvailabilityBadge> = setOf(AvailabilityBadge.METADATA),
    val addonHits: List<SearchHit> = emptyList(),
)

data class SearchResults(
    val canonical: List<CanonicalSearchResult> = emptyList(),
    val groups: List<SearchGroup> = emptyList(),
    val canonicalFailed: Boolean = false,
    val addonFailed: Boolean = false,

    val sections: List<SearchSection> = emptyList(),

    val people: List<SearchPersonResult> = emptyList(),

    val peopleFailed: Boolean = false,
) {

    val isEmpty: Boolean get() = canonical.isEmpty() && groups.isEmpty() && people.isEmpty()

    val degraded: Boolean get() = canonicalFailed || addonFailed
}
