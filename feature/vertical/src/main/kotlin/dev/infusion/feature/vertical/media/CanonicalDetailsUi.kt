package dev.infusion.feature.vertical.media

import dev.infusion.core.domain.collections.CollectionEntityKind
import dev.infusion.core.domain.collections.CollectionEntityPage
import dev.infusion.core.domain.collections.CollectionEntityRef
import dev.infusion.core.domain.metadata.MetadataFailureReason
import dev.infusion.core.domain.metadata.MetadataSection
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.metadata.Certification
import dev.infusion.core.model.metadata.MediaIdentity
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.core.model.metadata.PersonId
import java.time.LocalDate

sealed interface SectionState<out T> {

    data object Loading : SectionState<Nothing>

    data object Absent : SectionState<Nothing>

    data class Failed(val reason: MetadataFailureReason) : SectionState<Nothing>

    data class Ready<T>(val value: T) : SectionState<T>

    fun valueOrNull(): T? = (this as? Ready)?.value
}

data class CanonicalHeroUi(
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val runtimeMinutes: Int? = null,
    val certification: Certification? = null,
    val genres: List<String> = emptyList(),
    val ratings: List<RatingUi> = emptyList(),
    val overview: String? = null,
    val mediaType: MediaType,

    val backdropLocator: String? = null,

    val posterLocator: String? = null,

    val logoLocator: String? = null,
    val tagline: String? = null,

    val seasonCount: Int? = null,

    val status: String? = null,
) {

    val metadataLine: String
        get() = buildList {
            year?.let { add(it.toString()) }
            runtimeMinutes?.let { add("${it}min") }
            certification?.let { cert ->
                add(cert.region?.let { region -> "${cert.code} · $region" } ?: cert.code)
            }
            seasonCount?.let { add(if (it == 1) "1 season" else "$it seasons") }
        }.joinToString(" · ")
}

data class RatingUi(
    val label: String,
    val text: String,
    val fraction: Double,

    val metricId: String = "",
)

data class TrailerUi(
    val site: String,
    val key: String,
    val title: String?,
    val kindLabel: String,
)

data class PersonCardUi(
    val key: String,
    val name: String,
    val role: String? = null,
    val personId: PersonId? = null,

    val imageLocator: String? = null,
) {

    val isNavigable: Boolean get() = personId != null

    val activationKey: String? get() = personId?.let(::personFocusKey)
}

data class MediaCardUi(
    val key: String,
    val title: String,
    val year: Int? = null,
    val identity: MediaIdentity,

    val imageLocator: String? = null,

    val artwork: dev.infusion.core.model.MediaArtworkRef? = null,

    val canonicalTitleId: dev.infusion.core.model.TitleId? = null,
) {

    val identityKey: String? get() = identity.cacheKey()

    val isNavigable: Boolean get() = identity.titleId != null || !identity.externalIds.isEmpty
}

data class CompanyLogoUi(
    val name: String,

    val logoLocator: String? = null,

    val id: Long? = null,

    val kind: CollectionEntityKind? = null,
) {

    val entityRef: CollectionEntityRef?
        get() {
            val id = id ?: return null
            val kind = kind ?: return null
            return CollectionEntityRef(kind, CollectionEntityPage.PROVIDER_TMDB, id.toString())
        }
}

data class CollectionUi(
    val id: Long?,
    val name: String,
    val members: List<MediaCardUi> = emptyList(),

    val currentTitleKey: String? = null,
) {

    val focusKey: String? get() = id?.let(::collectionFocusKey)

    fun isCurrentTitle(card: MediaCardUi): Boolean =
        currentTitleKey != null && card.identityKey == currentTitleKey
}

data class CardPageUi(
    val page: Int = 0,
    val totalPages: Int = 0,
    val hasMore: Boolean = false,
) {
    companion object {

        val Empty: CardPageUi = CardPageUi()
    }
}

data class ReviewUi(

    val reviewId: String,
    val author: String,
    val content: String,

    val authorRating: String? = null,

    val avatarLocator: String? = null,

    val dateLabel: String? = null,

    val providerLabel: String? = null,

    val sourceId: String = TMDB_REVIEW_SOURCE_ID,
) {

    val key: String get() = "review:$sourceId:$reviewId"
}

const val TMDB_REVIEW_SOURCE_ID: String = "tmdb"

data class SeasonChipUi(
    val key: String,
    val seasonNumber: Int,
    val label: String,
    val episodeCount: Int? = null,
    val isSpecial: Boolean = false,
)

data class EpisodeCardUi(
    val key: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val overview: String? = null,
    val airDate: LocalDate? = null,
    val runtimeMinutes: Int? = null,

    val stillLocator: String? = null,

    val ratings: List<RatingUi> = emptyList(),

    val watchLabel: String? = null,
    val watchedFraction: Double? = null,
) {
    val coordinate: String get() = "S${seasonNumber}E${episodeNumber}"

    val focusKey: String get() = episodeFocusKey(seasonNumber, episodeNumber)
}

data class CanonicalDetailsUiState(
    val titleId: TitleId,
    val mediaType: MediaType,
    val identity: MediaIdentity? = null,
    val hero: CanonicalHeroUi? = null,
    val trailer: TrailerUi? = null,
    val directors: List<PersonCardUi> = emptyList(),
    val creators: List<PersonCardUi> = emptyList(),
    val studios: List<String> = emptyList(),
    val networks: List<String> = emptyList(),

    val studioCards: List<CompanyLogoUi> = emptyList(),
    val networkCards: List<CompanyLogoUi> = emptyList(),
    val cast: List<PersonCardUi> = emptyList(),
    val collectionName: String? = null,
    val collectionId: Long? = null,
    val collection: SectionState<CollectionUi> = SectionState.Absent,
    val recommendations: SectionState<List<MediaCardUi>> = SectionState.Absent,
    val similar: SectionState<List<MediaCardUi>> = SectionState.Absent,

    val recommendationsPage: CardPageUi = CardPageUi.Empty,
    val similarPage: CardPageUi = CardPageUi.Empty,

    val reviews: SectionState<List<ReviewUi>> = SectionState.Absent,
    val reviewsPage: CardPageUi = CardPageUi.Empty,
    val reviewsPagingFailure: MetadataFailureReason? = null,

    val recommendationsPagingFailure: MetadataFailureReason? = null,
    val similarPagingFailure: MetadataFailureReason? = null,
    val seasons: List<SeasonChipUi> = emptyList(),

    val defaultSeasonNumber: Int? = null,

    val initialSeasonFocusKey: String? = null,
    val selectedSeasonNumber: Int? = null,
    val episodes: SectionState<List<EpisodeCardUi>> = SectionState.Absent,

    val episodeFocusKey: String? = null,
    val refreshing: Boolean = false,
    val metadataFailure: MetadataFailureReason? = null,
    val loadingSections: Set<MetadataSection> = emptySet(),

    val primarySettled: Boolean = false,
) {

    val hasHero: Boolean get() = hero != null

    val focusedSeasonNumber: Int? get() = selectedSeasonNumber ?: seasons.firstOrNull()?.seasonNumber

    val hasSeasons: Boolean get() = seasons.isNotEmpty()

    val recommendationsHasMore: Boolean get() = recommendationsPage.hasMore

    val similarHasMore: Boolean get() = similarPage.hasMore

    val reviewsHasMore: Boolean get() = reviewsPage.hasMore
}
