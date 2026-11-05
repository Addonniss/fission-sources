package dev.infusion.feature.vertical.media

import dev.infusion.core.domain.collections.CollectionEntityRef
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.metadata.ImageRef
import dev.infusion.core.model.metadata.PersonId

internal fun metadataArtworkLocator(
    ref: ImageRef?,
    policy: ArtworkPolicy,
    fallbackProvider: String = "tmdb",
): String? = ref?.let {
    MediaArtworkRef.metadata(it.provider.ifBlank { fallbackProvider }, it.path, policy).locator
}

fun personFocusKey(personId: PersonId): String = "cast:${personId.authority}:${personId.value}"

fun collectionFocusKey(collectionId: Long): String = "collection:$collectionId"

fun episodeFocusKey(seasonNumber: Int, episodeNumber: Int): String =
    "canonical:episode:S${seasonNumber}E$episodeNumber"

fun companyFocusKey(ref: CollectionEntityRef): String =
    "company:${ref.kind.name.lowercase()}:${ref.provider}:${ref.id}"
