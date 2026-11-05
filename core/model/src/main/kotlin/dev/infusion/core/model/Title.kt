package dev.infusion.core.model

import java.time.Instant

enum class TitleKind {
    MOVIE,
    SERIES,
    SEASON,
    EPISODE,
    CLIP,
}

data class Title(
    val titleId: TitleId,
    val kind: TitleKind,
    val displayTitle: String,
    val aliases: Set<TypedExternalId> = emptySet(),
    val parentTitleId: TitleId? = null,
    val originalTitle: String? = null,
    val year: Int? = null,
    val provenance: Provenance? = null,
    val lastRefreshedAt: Instant? = null,

    val synopsis: String? = null,

    val synopsisProvenance: Provenance? = null,

    val genres: List<String> = emptyList(),

    val genresProvenance: Provenance? = null,

    val rating: String? = null,

    val ratingProvenance: Provenance? = null,

    val seasonNumber: Int? = null,

    val episodeNumber: Int? = null,
) {
    init {
        require(displayTitle.isNotBlank()) { "displayTitle must not be blank" }
        require(year == null || year > 0) { "year must be positive when known" }
        require(seasonNumber == null || seasonNumber > 0) { "seasonNumber must be positive when known" }
        require(episodeNumber == null || episodeNumber > 0) { "episodeNumber must be positive when known" }
        require((synopsis == null) == (synopsisProvenance == null)) {
            "synopsis and its provenance must be present together"
        }
        require(genres.isEmpty() == (genresProvenance == null)) {
            "genres and its provenance must be present together"
        }
        require((rating == null) == (ratingProvenance == null)) {
            "rating and its provenance must be present together"
        }
    }
}
