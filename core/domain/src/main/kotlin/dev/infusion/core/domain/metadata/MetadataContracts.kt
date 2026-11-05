package dev.infusion.core.domain.metadata

import dev.infusion.core.model.SourceId
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.identity.MappingOrigin
import dev.infusion.core.model.metadata.CollectionDetails
import dev.infusion.core.model.metadata.ExternalIds
import dev.infusion.core.model.metadata.ImageRef
import dev.infusion.core.model.metadata.MediaCardPage
import dev.infusion.core.model.metadata.MediaDetails
import dev.infusion.core.model.metadata.MediaIdentity
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.core.model.metadata.PersonDetails
import dev.infusion.core.model.metadata.PersonId
import dev.infusion.core.model.metadata.ReviewPage
import dev.infusion.core.model.metadata.SeasonDetails
import kotlinx.coroutines.flow.Flow

data class MetadataLocale(
    val language: String,
    val region: String? = null,
) {
    init {
        require(language.isNotBlank()) { "language must not be blank" }
    }

    val tag: String get() = if (region.isNullOrBlank()) language else "$language-$region"

    companion object {

        val Default: MetadataLocale = MetadataLocale("en")

        fun of(tag: String?): MetadataLocale {
            val raw = tag?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return Default
            val parts = raw.split('-')
            val language = parts[0].lowercase().takeIf { it.isNotEmpty() } ?: return Default
            val region = parts.getOrNull(1)?.uppercase()?.takeIf { it.length == 2 }
            return MetadataLocale(language, region)
        }
    }
}

enum class MetadataSection {
    HERO,
    RATINGS,
    TRAILER,
    CAST,
    COLLECTION,
    RECOMMENDATIONS,
    SIMILAR,

    REVIEWS,
    SEASONS,
    PERSON,
}

enum class MetadataFailureReason {

    UNAVAILABLE,

    TIMEOUT,

    UNAUTHORIZED,

    NOT_FOUND,

    RATE_LIMITED,

    MALFORMED,

    UNSUPPORTED,
}

class MetadataFailureException(
    val reason: MetadataFailureReason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

enum class MetadataFreshness {

    FRESH,

    STALE,

    MISS,
}

data class DetailsMetadataState(
    val details: MediaDetails? = null,
    val freshness: MetadataFreshness = MetadataFreshness.MISS,
    val refreshing: Boolean = false,

    val failure: MetadataFailureReason? = null,
) {

    val hasUsableDetails: Boolean get() = details != null
}

sealed interface MetadataResult<out T> {
    data class Success<T>(
        val value: T,
        val freshness: MetadataFreshness = MetadataFreshness.FRESH,
    ) : MetadataResult<T>

    data class Failure(
        val reason: MetadataFailureReason,
        val message: String,
    ) : MetadataResult<Nothing>

    fun valueOrNull(): T? = (this as? Success)?.value
}

interface MetadataProvider {

    val providerId: String

    suspend fun details(identity: MediaIdentity, locale: MetadataLocale): MediaDetails?

    suspend fun titledLandscape(
        identity: MediaIdentity,
        language: String,
    ): ImageRef? = null

    suspend fun season(
        identity: MediaIdentity,
        seasonNumber: Int,
        locale: MetadataLocale,
    ): SeasonDetails? = null

    suspend fun collection(collectionId: Long, locale: MetadataLocale): CollectionDetails? = null

    suspend fun person(personId: PersonId, locale: MetadataLocale): PersonDetails? = null

    suspend fun recommendations(
        identity: MediaIdentity,
        locale: MetadataLocale,
        page: Int,
    ): MediaCardPage? = null

    suspend fun similar(
        identity: MediaIdentity,
        locale: MetadataLocale,
        page: Int,
    ): MediaCardPage? = null

    suspend fun reviews(
        identity: MediaIdentity,
        locale: MetadataLocale,
        page: Int,
    ): ReviewPage? = null

    suspend fun resolveExternalId(
        external: TypedExternalId,
        type: MediaType,
        locale: MetadataLocale,
    ): ExternalIds? = null

    suspend fun search(
        title: String,
        year: Int?,
        type: MediaType,
        locale: MetadataLocale,
    ): List<MediaSearchCandidate> = emptyList()

    suspend fun searchPeople(
        term: String,
        locale: MetadataLocale,
    ): List<PersonSearchCandidate> = emptyList()
}

interface SeasonEvidenceDecorator {

    suspend fun decorateSeason(
        season: SeasonDetails,
        identity: MediaIdentity,
        seasonNumber: Int,
        locale: MetadataLocale,
    ): SeasonDetails
}

data class MediaSearchCandidate(
    val identity: MediaIdentity,
    val title: String,
    val year: Int?,

    val artwork: ImageRef? = null,

    val landscapeArtwork: ImageRef? = null,
)

data class PersonSearchCandidate(
    val id: PersonId,
    val name: String,
    val knownForDepartment: String? = null,
    val profile: ImageRef? = null,
)

interface MetadataRepository {

    fun observeDetails(identity: MediaIdentity, locale: MetadataLocale): Flow<DetailsMetadataState>

    suspend fun details(
        identity: MediaIdentity,
        locale: MetadataLocale,
        forceRefresh: Boolean = false,
    ): MediaDetails?

    suspend fun seasonDetails(
        identity: MediaIdentity,
        seasonNumber: Int,
        locale: MetadataLocale,
    ): MetadataResult<SeasonDetails>

    suspend fun collectionDetails(
        collectionId: Long,
        locale: MetadataLocale,
    ): MetadataResult<CollectionDetails>

    suspend fun personDetails(
        personId: PersonId,
        locale: MetadataLocale,
    ): MetadataResult<PersonDetails>

    suspend fun titledLandscape(
        identity: MediaIdentity,
        language: String,
    ): MetadataResult<ImageRef>

    suspend fun recommendations(
        identity: MediaIdentity,
        locale: MetadataLocale,
        page: Int = 1,
    ): MetadataResult<MediaCardPage>

    suspend fun similar(
        identity: MediaIdentity,
        locale: MetadataLocale,
        page: Int = 1,
    ): MetadataResult<MediaCardPage>

    suspend fun reviews(
        identity: MediaIdentity,
        locale: MetadataLocale,
        page: Int = 1,
    ): MetadataResult<ReviewPage>
}

interface MediaIdentityStore {

    suspend fun externalIdsFor(titleId: TitleId): List<TypedExternalId>

    suspend fun externalIdsForAll(titleIds: List<TitleId>): Map<TitleId, List<TypedExternalId>> =
        titleIds.distinct().associateWith { externalIdsFor(it) }

    suspend fun titleIdFor(external: TypedExternalId): TitleId?

    suspend fun canonicalKindOf(titleId: TitleId): dev.infusion.core.model.TitleKind? = null

    suspend fun canonicalKindsOf(titleIds: List<TitleId>): Map<TitleId, dev.infusion.core.model.TitleKind?> =
        titleIds.distinct().associateWith { canonicalKindOf(it) }

    suspend fun externalIdsForSource(sourceId: SourceId, nativeItemId: String): List<TypedExternalId>

    suspend fun bind(external: TypedExternalId, titleId: TitleId, origin: MappingOrigin)
}

interface MediaIdentityResolver {

    suspend fun resolve(titleId: TitleId, type: MediaType, locale: MetadataLocale): MediaIdentity?

    suspend fun resolveIdentity(identity: MediaIdentity, locale: MetadataLocale): MediaIdentity

    suspend fun resolveHint(hint: MetadataIdentityHint, locale: MetadataLocale): MediaIdentity

    suspend fun titleIdFor(identity: MediaIdentity): TitleId?

    suspend fun titleIdsFor(identities: List<MediaIdentity>): List<TitleId?> =
        identities.map { titleIdFor(it) }
}

data class CachedMetadataPayload(
    val payload: String,
    val storedAtMs: Long,
)

interface MetadataCacheStore {

    suspend fun load(namespace: String, key: String): CachedMetadataPayload?

    suspend fun store(namespace: String, key: String, payload: String, storedAtMs: Long)

    suspend fun remove(namespace: String, key: String)

    suspend fun clearNamespace(namespace: String)

    suspend fun purgeOlderThan(nowMs: Long, maxAgeMs: Long): Int
}

interface MetadataCodec {

    fun encodeDetails(details: MediaDetails): String

    fun decodeDetails(payload: String): MediaDetails?

    fun encodeSeason(season: SeasonDetails): String

    fun decodeSeason(payload: String): SeasonDetails?

    fun encodeCollection(collection: CollectionDetails): String

    fun decodeCollection(payload: String): CollectionDetails?

    fun encodePerson(person: PersonDetails): String

    fun decodePerson(payload: String): PersonDetails?

    fun encodeCardPage(page: MediaCardPage): String

    fun decodeCardPage(payload: String): MediaCardPage?

    fun encodeReviewPage(page: ReviewPage): String

    fun decodeReviewPage(payload: String): ReviewPage?

    fun encodeLanguageArtwork(image: ImageRef): String

    fun decodeLanguageArtwork(payload: String): ImageRef?
}

interface MetadataEnricher<T> {

    val enricherId: String

    suspend fun enrich(value: T, locale: MetadataLocale): T
}

data class MetadataIdentityHint(
    val type: MediaType,
    val externalIds: ExternalIds = ExternalIds(),
    val title: String? = null,
    val year: Int? = null,
    val sourceId: SourceId? = null,
    val nativeItemId: String? = null,
) {

    fun toIdentity(titleId: TitleId? = null): MediaIdentity {
        val sourceIds = if (sourceId != null && nativeItemId != null) {
            mapOf(sourceId to nativeItemId)
        } else {
            emptyMap()
        }
        return MediaIdentity(type = type, titleId = titleId, externalIds = externalIds, sourceIds = sourceIds)
    }
}
