package dev.infusion.source.aggregation.search

import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleKind

sealed interface SearchSectionOrigin {

    data object Canonical : SearchSectionOrigin

    data class Addon(val addonId: String, val displayName: String) : SearchSectionOrigin

    val label: String
        get() = when (this) {
            Canonical -> CANONICAL_SEARCH_ORIGIN_LABEL
            is Addon -> displayName
        }
}

const val CANONICAL_SEARCH_ORIGIN_LABEL: String = "TMDB"

data class SearchSectionItem(
    val key: String,
    val destination: SearchDestination,
    val preview: MediaPreview,
)

data class SearchSection(
    val key: String,
    val title: String,
    val origin: SearchSectionOrigin,
    val kind: TitleKind,
    val items: List<SearchSectionItem>,
)

object SearchSectionComposition {

    fun compose(
        canonical: List<CanonicalSearchResult>,
        groups: List<SearchGroup>,
    ): List<SearchSection> {
        val builder = Builder()

        canonical.forEach { row ->
            builder.add(
                origin = SearchSectionOrigin.Canonical,
                originKey = CANONICAL_ORIGIN_KEY,
                kind = rowKindOf(row.kind),
                key = row.key,
                destination = SearchDestination(
                    titleId = row.titleId,
                    kind = row.kind,
                    preview = row.preview,
                ),
                preview = row.preview,
            )
        }

        groups.forEach { group ->

            val destination = group.destination ?: return@forEach
            val kind = rowKindOf(destination.kind)
            group.items.forEach { hit ->
                builder.add(
                    origin = SearchSectionOrigin.Addon(
                        addonId = hit.addonId,
                        displayName = hit.addonDisplayName,
                    ),
                    originKey = "addon:${hit.addonId}",
                    kind = kind,
                    key = hit.key,
                    destination = destination,

                    preview = MediaPreview(
                        titleId = destination.titleId,
                        kind = destination.kind,
                        displayTitle = hit.item.displayTitle,
                        year = hit.item.year,
                        subtitle = null,
                        artwork = hit.artwork,
                        progressFraction = null,
                    ),
                )
            }
        }

        return builder.build()
    }

    fun rowKindOf(kind: TitleKind?): TitleKind =
        if (kind == TitleKind.SERIES) TitleKind.SERIES else TitleKind.MOVIE

    fun sectionTitle(originLabel: String, kind: TitleKind): String =
        "$originLabel ${if (kind == TitleKind.SERIES) "Shows" else "Movies"}"

    fun sectionKey(originKey: String, kind: TitleKind): String =
        "search-section:$originKey:${kind.name}"

    const val CANONICAL_ORIGIN_KEY: String = "canonical"

    fun addonOriginKey(addonId: String): String = "addon:$addonId"

    private val KINDS_IN_ORDER = listOf(TitleKind.MOVIE, TitleKind.SERIES)

    private class Builder {
        private val items = LinkedHashMap<String, MutableList<SearchSectionItem>>()
        private val sections = LinkedHashMap<String, SearchSection>()
        private val originOrder = LinkedHashMap<String, SearchSectionOrigin>()

        fun add(
            origin: SearchSectionOrigin,
            originKey: String,
            kind: TitleKind,
            key: String,
            destination: SearchDestination,
            preview: MediaPreview,
        ) {
            val sectionKey = sectionKey(originKey, kind)
            val cards = items.getOrPut(sectionKey) { mutableListOf() }
            originOrder.putIfAbsent(originKey, origin)
            sections.putIfAbsent(
                sectionKey,
                SearchSection(
                    key = sectionKey,
                    title = sectionTitle(origin.label, kind),
                    origin = origin,
                    kind = kind,
                    items = cards,
                ),
            )

            if (cards.any { it.destination.titleId == destination.titleId }) return
            cards += SearchSectionItem(key = key, destination = destination, preview = preview)
        }

        fun build(): List<SearchSection> {
            val orderedOriginKeys = originOrder.keys.sortedBy { originKey ->
                if (originKey == CANONICAL_ORIGIN_KEY) 0 else 1
            }
            val built = mutableListOf<SearchSection>()
            for (originKey in orderedOriginKeys) {
                for (kind in KINDS_IN_ORDER) {
                    val section = sections[sectionKey(originKey, kind)] ?: continue
                    if (section.items.isEmpty()) continue
                    built += section
                }
            }
            return built
        }
    }
}
