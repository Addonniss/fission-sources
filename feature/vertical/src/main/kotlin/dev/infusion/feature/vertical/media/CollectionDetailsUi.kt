package dev.infusion.feature.vertical.media

import dev.infusion.core.domain.metadata.MetadataFailureReason

data class CollectionDetailsUi(
    val id: Long?,
    val name: String,
    val overview: String? = null,

    val posterLocator: String? = null,

    val backdropLocator: String? = null,

    val members: List<MediaCardUi> = emptyList(),
    val currentTitleKey: String? = null,
) {

    fun isCurrentTitle(card: MediaCardUi): Boolean =
        currentTitleKey != null && card.identityKey == currentTitleKey

    val currentTitle: MediaCardUi? get() = members.firstOrNull(::isCurrentTitle)
}

typealias CollectionDetailsState = SectionState<CollectionDetailsUi>

fun collectionFailureCopy(reason: MetadataFailureReason?): String =
    if (reason == MetadataFailureReason.NOT_FOUND) "No details for this collection." else sectionFailureCopy(
        dev.infusion.core.domain.metadata.MetadataSection.COLLECTION,
    )
