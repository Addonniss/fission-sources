package dev.infusion.feature.vertical.media

import dev.infusion.core.domain.metadata.MetadataSection

fun sectionFailureCopy(section: MetadataSection): String = when (section) {
    MetadataSection.RECOMMENDATIONS -> "Couldn't load recommendations."
    MetadataSection.SIMILAR -> "Couldn't load similar titles."

    MetadataSection.REVIEWS -> "Couldn't load reviews."
    MetadataSection.COLLECTION -> "Couldn't load the collection."
    MetadataSection.SEASONS -> "Couldn't load episodes."
    MetadataSection.CAST -> "Couldn't load cast."
    MetadataSection.PERSON -> "Couldn't load this person."

    MetadataSection.HERO, MetadataSection.RATINGS, MetadataSection.TRAILER ->
        "Couldn't load details."
}
