package dev.infusion.tv.ui.presentation

import dev.infusion.source.aggregation.search.SearchSection

fun presentableSearchSections(
    sections: List<SearchSection>,
    presentation: ContentCardPresentation,
): List<SearchSection> = sections.mapNotNull { section ->
    val presentable = section.items.filter { item ->
        presentation.drawableArtworkRefFor(item.preview) != null
    }

    if (presentable.isEmpty()) null else section.copy(items = presentable)
}
