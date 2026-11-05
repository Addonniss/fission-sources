package dev.infusion.feature.vertical.home

import dev.infusion.core.domain.home.HomeHeroPresentation
import dev.infusion.core.domain.home.HomeHeroRating
import dev.infusion.core.domain.home.HomePresentationPolicy
import dev.infusion.core.domain.home.HomePresentationStore
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.metadata.Certification
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.feature.vertical.media.CanonicalHeroUi
import dev.infusion.feature.vertical.media.RatingUi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface HomeHeroPresentationCache {

    suspend fun restore(titleId: TitleId, mediaType: MediaType): CanonicalHeroUi?

    fun publish(titleId: TitleId, mediaType: MediaType, hero: CanonicalHeroUi)
}

fun homeHeroPresentationIdentityKey(
    titleId: TitleId,
    mediaType: MediaType,
    localeToken: String,
): String = "$titleId#k=${mediaType.name}#l=$localeToken"

class PersistedHomeHeroPresentationCache(
    private val store: HomePresentationStore,
    private val profileId: () -> String,
    private val localeToken: () -> String,

    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HomeHeroPresentationCache {

    override suspend fun restore(titleId: TitleId, mediaType: MediaType): CanonicalHeroUi? =
        withContext(ioDispatcher) {
            val profile = profileId()
            val key = homeHeroPresentationIdentityKey(titleId, mediaType, localeToken())
            val record = runCatching { store.readHero(profile, key) }.getOrNull() ?: return@withContext null
            if (!HomePresentationPolicy.heroIsFresh(record, nowMs())) return@withContext null
            record.toCanonicalHeroUi()
        }

    override fun publish(titleId: TitleId, mediaType: MediaType, hero: CanonicalHeroUi) {
        val profile = profileId()
        val key = homeHeroPresentationIdentityKey(titleId, mediaType, localeToken())
        val record = hero.toHomeHeroPresentation(key, nowMs())
        runCatching { store.writeHero(profile, record) }
    }
}

internal fun CanonicalHeroUi.toHomeHeroPresentation(
    identityKey: String,
    storedAtEpochMs: Long,
): HomeHeroPresentation = HomeHeroPresentation(
    identityKey = identityKey,
    mediaType = mediaType.name,
    title = title,
    originalTitle = originalTitle,
    year = year,
    runtimeMinutes = runtimeMinutes,
    certificationCode = certification?.code,
    certificationRegion = certification?.region,
    genres = genres,
    ratings = ratings.map { rating -> rating.toHomeHeroRating() },
    overview = overview,
    tagline = tagline,
    seasonCount = seasonCount,
    status = status,

    backdropLocator = HomePresentationPolicy.reusableArtworkLocator(backdropLocator),
    posterLocator = HomePresentationPolicy.reusableArtworkLocator(posterLocator),
    logoLocator = HomePresentationPolicy.reusableArtworkLocator(logoLocator),
    storedAtEpochMs = storedAtEpochMs,
)

internal fun RatingUi.toHomeHeroRating(): HomeHeroRating = HomeHeroRating(
    label = label,
    text = text,
    fraction = fraction,
    metricId = metricId,
)

internal fun HomeHeroPresentation.toCanonicalHeroUi(): CanonicalHeroUi? {
    if (isEmpty()) return null
    val type = MediaType.entries.firstOrNull { it.name == mediaType } ?: return null
    return CanonicalHeroUi(
        title = title,
        originalTitle = originalTitle,
        year = year,
        runtimeMinutes = runtimeMinutes,
        certification = certificationCode?.let { code ->
            Certification(code = code, region = certificationRegion)
        },
        genres = genres,
        ratings = ratings.map { rating ->
            RatingUi(
                label = rating.label,
                text = rating.text,
                fraction = rating.fraction,
                metricId = rating.metricId,
            )
        },
        overview = overview,
        mediaType = type,

        backdropLocator = HomePresentationPolicy.reusableArtworkLocator(backdropLocator),
        posterLocator = HomePresentationPolicy.reusableArtworkLocator(posterLocator),
        logoLocator = HomePresentationPolicy.reusableArtworkLocator(logoLocator),
        tagline = tagline,
        seasonCount = seasonCount,
        status = status,
    )
}
