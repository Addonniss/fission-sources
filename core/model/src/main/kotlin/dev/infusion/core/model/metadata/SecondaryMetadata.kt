package dev.infusion.core.model.metadata

import dev.infusion.core.model.MediaPreview
import java.time.LocalDate

data class SeasonSummary(
    val seasonNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    val airDate: LocalDate? = null,
    val episodeCount: Int? = null,
    val poster: ImageRef? = null,
) {

    val displayLabel: String
        get() = name?.takeIf { it.isNotBlank() } ?: if (seasonNumber == 0) "Specials" else "Season $seasonNumber"
}

data class SeasonDetails(
    val seasonNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    val poster: ImageRef? = null,
    val episodes: List<EpisodeDetails> = emptyList(),
) {
    val displayLabel: String
        get() = name?.takeIf { it.isNotBlank() } ?: if (seasonNumber == 0) "Specials" else "Season $seasonNumber"
}

data class EpisodeDetails(
    val identity: MediaIdentity,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    val airDate: LocalDate? = null,
    val runtimeMinutes: Int? = null,
    val still: ImageRef? = null,
    val rating: MediaRating? = null,

    val ratings: List<MediaRating> = emptyList(),
    val externalIds: ExternalIds = ExternalIds(),
) {
    init {
        require(seasonNumber >= 0) { "season number must not be negative" }
        require(episodeNumber >= 0) { "episode number must not be negative" }
    }

    val coordinate: String get() = "S${seasonNumber}E$episodeNumber"

    val displayTitle: String get() = name?.takeIf { it.isNotBlank() } ?: coordinate
}

data class CollectionDetails(
    val id: Long?,
    val name: String,
    val overview: String? = null,
    val poster: ImageRef? = null,
    val backdrop: ImageRef? = null,

    val members: List<MediaCard> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "collection name must not be blank" }
    }
}

data class MediaCard(
    val identity: MediaIdentity,
    val preview: MediaPreview,
) {
    val title: String get() = preview.displayTitle
    val year: Int? get() = preview.year
}

data class MediaCardPage(
    val items: List<MediaCard> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1,
    val totalResults: Int? = null,
) {

    val hasMore: Boolean get() = page < totalPages
}

data class PersonCredit(
    val card: MediaCard,
    val mediaType: MediaType,

    val character: String? = null,

    val job: String? = null,
    val releaseDate: LocalDate? = null,
) {
    val year: Int? get() = releaseDate?.year ?: card.year
}

data class PersonDetails(
    val id: PersonId,
    val name: String,
    val biography: String? = null,
    val birthday: LocalDate? = null,
    val deathday: LocalDate? = null,
    val placeOfBirth: String? = null,
    val knownForDepartment: String? = null,
    val profile: ImageRef? = null,
    val credits: List<PersonCredit> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "person name must not be blank" }
    }

    fun creditsFor(type: MediaType): List<PersonCredit> =
        credits.filter { it.mediaType == type }
            .sortedWith(compareByDescending<PersonCredit> { it.releaseDate }.thenBy { it.card.title })
}
