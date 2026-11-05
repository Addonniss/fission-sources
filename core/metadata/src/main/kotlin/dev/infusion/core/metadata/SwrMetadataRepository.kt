package dev.infusion.core.metadata

import dev.infusion.core.domain.metadata.CachedMetadataPayload
import dev.infusion.core.domain.metadata.DetailsMetadataState
import dev.infusion.core.domain.metadata.MediaIdentityResolver
import dev.infusion.core.domain.metadata.MetadataCacheStore
import dev.infusion.core.domain.metadata.MetadataCodec
import dev.infusion.core.domain.metadata.MetadataFailureException
import dev.infusion.core.domain.metadata.MetadataFailureReason
import dev.infusion.core.domain.metadata.MetadataFreshness
import dev.infusion.core.domain.metadata.MetadataLocale
import dev.infusion.core.domain.metadata.MetadataProvider
import dev.infusion.core.domain.metadata.MetadataRepository
import dev.infusion.core.domain.metadata.MetadataResult
import dev.infusion.core.domain.metadata.SeasonEvidenceDecorator
import dev.infusion.core.model.metadata.CollectionDetails
import dev.infusion.core.model.metadata.ImageRef
import dev.infusion.core.model.metadata.MediaCardPage
import dev.infusion.core.model.metadata.MediaDetails
import dev.infusion.core.model.metadata.MediaIdentity
import dev.infusion.core.model.metadata.PersonDetails
import dev.infusion.core.model.metadata.PersonId
import dev.infusion.core.model.metadata.ReviewPage
import dev.infusion.core.model.metadata.SeasonDetails
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SwrMetadataRepository(
    private val provider: MetadataProvider,
    private val cache: MetadataCacheStore,
    private val codec: MetadataCodec,
    private val policy: MetadataCachePolicy = MetadataCachePolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : MetadataRepository {

    private val detailsInFlight = mutableMapOf<String, Deferred<MediaDetails?>>()
    private val inFlightLock = Mutex()

    override fun observeDetails(identity: MediaIdentity, locale: MetadataLocale): Flow<DetailsMetadataState> = flow {
        val key = cacheKey(identity, locale)
        if (key == null) {

            emit(loadDetailsUncached(identity, locale))
            return@flow
        }
        val namespace = MetadataNamespaces.details(provider.providerId)
        val cached = readDetails(namespace, key)
        val freshness = policy.freshnessOf(namespace, cached?.storedAtMs, clock())
        val cachedDetails = cached?.payload?.let { codec.decodeDetails(it) }

            ?.takeIf { it.identity.type == identity.type }
        if (cached != null && cachedDetails == null) {
            runCatching { cache.remove(namespace, key) }
        }

        if (cachedDetails != null) {
            if (freshness == MetadataFreshness.FRESH) {
                emit(DetailsMetadataState(details = cachedDetails, freshness = MetadataFreshness.FRESH))
                return@flow
            }

            emit(
                DetailsMetadataState(
                    details = cachedDetails,
                    freshness = MetadataFreshness.STALE,
                    refreshing = true,
                ),
            )
            val refreshed = fetchDetails(identity, locale, key)
            emit(
                if (refreshed != null) {
                    DetailsMetadataState(details = refreshed, freshness = MetadataFreshness.FRESH)
                } else {

                    DetailsMetadataState(
                        details = cachedDetails,
                        freshness = MetadataFreshness.STALE,
                        refreshing = false,
                        failure = lastFailure,
                    )
                },
            )
            return@flow
        }

        emit(DetailsMetadataState(details = null, freshness = MetadataFreshness.MISS, refreshing = true))
        val loaded = fetchDetails(identity, locale, key)
        emit(
            if (loaded != null) {
                DetailsMetadataState(details = loaded, freshness = MetadataFreshness.FRESH)
            } else {
                DetailsMetadataState(
                    details = null,
                    freshness = MetadataFreshness.MISS,
                    refreshing = false,
                    failure = lastFailure,
                )
            },
        )
    }

    override suspend fun details(
        identity: MediaIdentity,
        locale: MetadataLocale,
        forceRefresh: Boolean,
    ): MediaDetails? {
        val key = cacheKey(identity, locale)
        val namespace = MetadataNamespaces.details(provider.providerId)
        if (key == null) return fetchDetailsUncached(identity, locale)
        if (!forceRefresh) {
            val cached = readDetails(namespace, key)
            if (cached != null) {
                val decoded = cached.payload.let { codec.decodeDetails(it) }

                    ?.takeIf { it.identity.type == identity.type }
                if (decoded != null) {
                    if (policy.freshnessOf(namespace, cached.storedAtMs, clock()) == MetadataFreshness.FRESH) {
                        return decoded
                    }

                    refreshInBackground(identity, locale, key)
                    return decoded
                }

                cache.remove(namespace, key)
            }
        }
        return fetchDetails(identity, locale, key)
    }

    private fun refreshInBackground(identity: MediaIdentity, locale: MetadataLocale, key: String) {
        backgroundScope.launch {
            runCatching { fetchDetails(identity, locale, key) }
        }
    }

    private suspend fun fetchDetails(
        identity: MediaIdentity,
        locale: MetadataLocale,
        key: String,
    ): MediaDetails? {
        val existing = inFlightLock.withLock { detailsInFlight[key] }
        if (existing != null) return existing.await()
        val deferred = backgroundScope.async { fetchDetailsUncached(identity, locale) }
        inFlightLock.withLock { detailsInFlight[key] = deferred }
        return try {
            deferred.await()
        } finally {
            inFlightLock.withLock { detailsInFlight.remove(key) }
        }
    }

    private var lastFailure: MetadataFailureReason? = null

    private suspend fun fetchDetailsUncached(
        identity: MediaIdentity,
        locale: MetadataLocale,
    ): MediaDetails? {
        val loaded = try {
            provider.details(identity, locale)
        } catch (e: MetadataFailureException) {
            lastFailure = e.reason
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            lastFailure = MetadataFailureReason.UNAVAILABLE
            return null
        }
        lastFailure = null
        val key = cacheKey(identity, locale)
        if (loaded != null && key != null) {

            runCatching {
                cache.store(
                    MetadataNamespaces.details(provider.providerId),
                    key,
                    codec.encodeDetails(loaded),
                    clock(),
                )
            }
        }
        return loaded
    }

    private suspend fun loadDetailsUncached(
        identity: MediaIdentity,
        locale: MetadataLocale,
    ): DetailsMetadataState {
        val loaded = fetchDetailsUncached(identity, locale)
        return if (loaded != null) {
            DetailsMetadataState(details = loaded, freshness = MetadataFreshness.FRESH)
        } else {
            DetailsMetadataState(refreshing = false, failure = lastFailure)
        }
    }

    private suspend fun readDetails(namespace: String, key: String): CachedMetadataPayload? =
        runCatching { cache.load(namespace, key) }.getOrNull()

    override suspend fun seasonDetails(
        identity: MediaIdentity,
        seasonNumber: Int,
        locale: MetadataLocale,
    ): MetadataResult<SeasonDetails> {
        val base = cacheKey(identity, locale)
        val key = base?.let { "$it#s$seasonNumber" }
        val namespace = MetadataNamespaces.season(provider.providerId)
        val result = cachedOrLoad(
            namespace = namespace,
            key = key,
            encode = { codec.encodeSeason(it) },
            decode = { codec.decodeSeason(it) },
        ) { provider.season(identity, seasonNumber, locale) }

        if (result is MetadataResult.Success) {
            val decorator = provider as? SeasonEvidenceDecorator
            if (decorator != null) {
                val decorated = runCatching {
                    decorator.decorateSeason(result.value, identity, seasonNumber, locale)
                }.getOrNull()
                if (decorated != null) {
                    return MetadataResult.Success(decorated, result.freshness)
                }
            }
        }
        return result
    }

    override suspend fun collectionDetails(
        collectionId: Long,
        locale: MetadataLocale,
    ): MetadataResult<CollectionDetails> = cachedOrLoad(
        namespace = MetadataNamespaces.collection(provider.providerId),
        key = "c$collectionId#${locale.tag}",
        encode = { codec.encodeCollection(it) },
        decode = { codec.decodeCollection(it) },
    ) { provider.collection(collectionId, locale) }

    override suspend fun personDetails(
        personId: PersonId,
        locale: MetadataLocale,
    ): MetadataResult<PersonDetails> = cachedOrLoad(
        namespace = MetadataNamespaces.person(provider.providerId),
        key = "p${personId.authority}:${personId.value}#${locale.tag}",
        encode = { codec.encodePerson(it) },
        decode = { codec.decodePerson(it) },
    ) { provider.person(personId, locale) }

    override suspend fun recommendations(
        identity: MediaIdentity,
        locale: MetadataLocale,
        page: Int,
    ): MetadataResult<MediaCardPage> = cardPage(identity, locale, page, recommendations = true)

    override suspend fun similar(
        identity: MediaIdentity,
        locale: MetadataLocale,
        page: Int,
    ): MetadataResult<MediaCardPage> = cardPage(identity, locale, page, recommendations = false)

    override suspend fun reviews(
        identity: MediaIdentity,
        locale: MetadataLocale,
        page: Int,
    ): MetadataResult<ReviewPage> {
        val base = cacheKey(identity, locale)
        val key = base?.let { "$it#p${page.coerceAtLeast(1)}" }
        return cachedOrLoad(
            namespace = MetadataNamespaces.reviews(provider.providerId),
            key = key,
            encode = { codec.encodeReviewPage(it) },
            decode = { codec.decodeReviewPage(it) },
        ) { provider.reviews(identity, locale, page) }
    }

    override suspend fun titledLandscape(
        identity: MediaIdentity,
        language: String,
    ): MetadataResult<ImageRef> {
        val wanted = language.trim().lowercase(Locale.ROOT)
        if (wanted.isEmpty()) {
            return MetadataResult.Failure(
                MetadataFailureReason.UNSUPPORTED,
                "a language-tagged artwork request needs a language",
            )
        }
        val key = identity.cacheKey()?.let { "$it#$wanted" }
        return cachedOrLoad(
            namespace = MetadataNamespaces.languageArtwork(provider.providerId),
            key = key,
            encode = { codec.encodeLanguageArtwork(it) },
            decode = { codec.decodeLanguageArtwork(it) },
        ) { provider.titledLandscape(identity, wanted) }
    }

    private suspend fun cardPage(
        identity: MediaIdentity,
        locale: MetadataLocale,
        page: Int,
        recommendations: Boolean,
    ): MetadataResult<MediaCardPage> {
        val base = cacheKey(identity, locale)
        val key = base?.let { "$it#p${page.coerceAtLeast(1)}" }
        val namespace = if (recommendations) {
            MetadataNamespaces.recommendations(provider.providerId)
        } else {
            MetadataNamespaces.similar(provider.providerId)
        }
        return cachedOrLoad(
            namespace = namespace,
            key = key,
            encode = { codec.encodeCardPage(it) },
            decode = { codec.decodeCardPage(it) },
        ) {
            if (recommendations) {
                provider.recommendations(identity, locale, page)
            } else {
                provider.similar(identity, locale, page)
            }
        }
    }

    private suspend fun <T : Any> cachedOrLoad(
        namespace: String,
        key: String?,
        encode: (T) -> String,
        decode: (String) -> T?,
        load: suspend () -> T?,
    ): MetadataResult<T> {
        if (key == null) {
            return try {
                load()?.let { MetadataResult.Success(it) } ?: notFound()
            } catch (e: MetadataFailureException) {
                MetadataResult.Failure(e.reason, e.message ?: "metadata request failed")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                MetadataResult.Failure(MetadataFailureReason.UNAVAILABLE, "metadata request failed")
            }
        }
        val cached = runCatching { cache.load(namespace, key) }.getOrNull()
        if (cached != null) {
            val decoded = decode(cached.payload)
            if (decoded != null) {
                val freshness = policy.freshnessOf(namespace, cached.storedAtMs, clock())
                if (freshness == MetadataFreshness.STALE) {
                    backgroundScope.launch { runCatching { refreshSecondary(namespace, key, encode, load) } }
                }
                return MetadataResult.Success(decoded, freshness)
            }

            cache.remove(namespace, key)
        }
        return try {
            val loaded = load()
            if (loaded == null) {
                notFound()
            } else {
                runCatching { cache.store(namespace, key, encode(loaded), clock()) }
                MetadataResult.Success(loaded, MetadataFreshness.FRESH)
            }
        } catch (e: MetadataFailureException) {
            MetadataResult.Failure(e.reason, e.message ?: "metadata request failed")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            MetadataResult.Failure(MetadataFailureReason.UNAVAILABLE, "metadata request failed")
        }
    }

    private suspend fun <T : Any> refreshSecondary(
        namespace: String,
        key: String,
        encode: (T) -> String,
        load: suspend () -> T?,
    ) {
        val loaded = load() ?: return
        cache.store(namespace, key, encode(loaded), clock())
    }

    private fun <T : Any> notFound(): MetadataResult<T> =
        MetadataResult.Failure(MetadataFailureReason.NOT_FOUND, "no such metadata at the provider")

    private fun cacheKey(identity: MediaIdentity, locale: MetadataLocale): String? =
        identity.cacheKey()?.let { "$it#${locale.tag}" }
}

class ResolvingMetadataRepository(
    private val resolver: MediaIdentityResolver,
    private val delegate: MetadataRepository,
    private val locale: () -> MetadataLocale,
) {

    suspend fun canonicalize(identity: MediaIdentity): MediaIdentity =
        runCatching { resolver.resolveIdentity(identity, locale()) }.getOrDefault(identity)

    fun repository(): MetadataRepository = delegate
}
