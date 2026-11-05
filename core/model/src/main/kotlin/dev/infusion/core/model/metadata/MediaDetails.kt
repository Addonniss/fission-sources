package dev.infusion.core.model.metadata

import java.time.LocalDate

sealed interface MediaDetails {
    val identity: MediaIdentity
    val title: String
    val originalTitle: String?
    val overview: String?
    val tagline: String?
    val year: Int?
    val genres: List<Genre>
    val artwork: MediaArtwork
    val ratings: List<MediaRating>
    val certification: Certification?
    val runtimeMinutes: Int?
    val videos: List<MediaVideo>
    val cast: List<CastMember>
    val crew: List<CrewMember>
    val directors: List<PersonRef>
    val creators: List<PersonRef>
    val studios: List<Company>
    val networks: List<Company>
    val keywords: List<String>
    val originalLanguage: String?
    val originCountries: List<String>
    val status: String?

    val mediaType: MediaType get() = identity.type

    val releaseDate: LocalDate?

    val primaryTrailer: MediaVideo?
        get() = videos.firstOrNull { it.kind == VideoKind.TRAILER } ?: videos.firstOrNull()

    val primaryRating: MediaRating?
        get() = ratings.firstOrNull { it.source.equals(ExternalIds.AUTHORITY_TMDB, ignoreCase = true) }
            ?: ratings.firstOrNull()

    val hasHeroContent: Boolean get() = title.isNotBlank()
}

data class MovieDetails(
    override val identity: MediaIdentity,
    override val title: String,
    override val originalTitle: String? = null,
    override val overview: String? = null,
    override val tagline: String? = null,
    override val year: Int? = null,
    override val genres: List<Genre> = emptyList(),
    override val artwork: MediaArtwork = MediaArtwork.None,
    override val ratings: List<MediaRating> = emptyList(),
    override val certification: Certification? = null,
    override val runtimeMinutes: Int? = null,
    override val videos: List<MediaVideo> = emptyList(),
    override val cast: List<CastMember> = emptyList(),
    override val crew: List<CrewMember> = emptyList(),
    override val directors: List<PersonRef> = emptyList(),
    override val creators: List<PersonRef> = emptyList(),
    override val studios: List<Company> = emptyList(),
    override val networks: List<Company> = emptyList(),
    override val keywords: List<String> = emptyList(),
    override val originalLanguage: String? = null,
    override val originCountries: List<String> = emptyList(),
    override val status: String? = null,
    override val releaseDate: LocalDate? = null,

    val collection: MovieCollectionSummary? = null,
) : MediaDetails {
    init {
        require(identity.type == MediaType.MOVIE) {
            "MovieDetails requires a MOVIE identity (was ${identity.type})"
        }
    }
}

data class MovieCollectionSummary(
    val id: Long?,
    val name: String,
    val poster: ImageRef? = null,
    val backdrop: ImageRef? = null,
) {
    init {
        require(name.isNotBlank()) { "collection name must not be blank" }
    }
}

data class ShowDetails(
    override val identity: MediaIdentity,
    override val title: String,
    override val originalTitle: String? = null,
    override val overview: String? = null,
    override val tagline: String? = null,
    override val year: Int? = null,
    override val genres: List<Genre> = emptyList(),
    override val artwork: MediaArtwork = MediaArtwork.None,
    override val ratings: List<MediaRating> = emptyList(),
    override val certification: Certification? = null,
    override val runtimeMinutes: Int? = null,
    override val videos: List<MediaVideo> = emptyList(),
    override val cast: List<CastMember> = emptyList(),
    override val crew: List<CrewMember> = emptyList(),
    override val directors: List<PersonRef> = emptyList(),
    override val creators: List<PersonRef> = emptyList(),
    override val studios: List<Company> = emptyList(),
    override val networks: List<Company> = emptyList(),
    override val keywords: List<String> = emptyList(),
    override val originalLanguage: String? = null,
    override val originCountries: List<String> = emptyList(),
    override val status: String? = null,
    override val releaseDate: LocalDate? = null,

    val seasons: List<SeasonSummary> = emptyList(),
    val lastAirDate: LocalDate? = null,
    val numberOfSeasons: Int? = null,
    val numberOfEpisodes: Int? = null,
    val inProduction: Boolean? = null,
) : MediaDetails {
    init {
        require(identity.type == MediaType.SHOW) {
            "ShowDetails requires a SHOW identity (was ${identity.type})"
        }
    }

    val defaultSeasonNumber: Int?
        get() = seasons.filterNot { it.seasonNumber == 0 }.minByOrNull { it.seasonNumber }?.seasonNumber
            ?: seasons.minByOrNull { it.seasonNumber }?.seasonNumber
}
