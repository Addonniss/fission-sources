package dev.infusion.source.addon.resource

import dev.infusion.source.addon.repository.InstalledAddonRuntime
import dev.infusion.source.aggregation.resource.AddonMetaRepository
import dev.infusion.source.addon.resource.AddonResourceMatcher
import dev.infusion.source.addon.resource.AddonResourceRuntimeAccessor
import dev.infusion.source.aggregation.resource.ProviderResourceState
import dev.infusion.source.aggregation.resource.StremioMetaQueryResult
import dev.infusion.source.stremio.StremioFailure
import dev.infusion.source.stremio.StremioResult
import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.wire.Resource
import dev.infusion.source.stremio.wire.StremioMetaResponse
import java.util.LinkedHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

class DefaultAddonMetaRepository(
    private val runtimes: AddonResourceRuntimeAccessor,
    private val providerTimeoutMs: Long = DEFAULT_PROVIDER_TIMEOUT_MS,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val cacheMaxEntries: Int = DEFAULT_CACHE_MAX_ENTRIES,
    private val now: () -> Long = System::currentTimeMillis,
) : AddonMetaRepository {

    private data class CacheEntry(
        val response: StremioMetaResponse,
        val addonId: String?,
        val storedAtMs: Long,
    )

    private data class QueryKey(
        val type: StremioContentType,
        val id: String,
        val preferredAddonId: String?,
    )

    private data class OwnershipKey(val query: QueryKey, val speculative: Boolean)

    private fun neutralKey(type: StremioContentType, id: String) =
        QueryKey(type, id, preferredAddonId = null)

    private fun preferredKey(type: StremioContentType, id: String, addonId: String) =
        QueryKey(type, id, preferredAddonId = addonId).also {
            require(addonId.isNotBlank()) { "preferredAddonId must not be blank" }
        }

    private val cacheLock = Any()
    private val cache = LinkedHashMap<QueryKey, CacheEntry>(16, 0.75f, true)

    private val inflightLock = Any()
    private val inflight = LinkedHashMap<OwnershipKey, kotlinx.coroutines.CompletableDeferred<StremioMetaQueryResult>>()

    @Volatile
    private var lastStates: Map<String, ProviderResourceState<StremioMetaResponse>> = emptyMap()

    override fun lastStates(): Map<String, ProviderResourceState<StremioMetaResponse>> = lastStates

    private fun cachedEntry(key: QueryKey): CacheEntry? {
        synchronized(cacheLock) {
            val entry = cache[key] ?: return null
            if (now() - entry.storedAtMs >= cacheTtlMs) {
                cache.remove(key)
                return null
            }
            return entry
        }
    }

    override fun cached(type: StremioContentType, id: String): StremioMetaResponse? =
        cachedEntry(neutralKey(type, id))?.response

    override fun clear() {
        synchronized(cacheLock) { cache.clear() }
        synchronized(inflightLock) { inflight.clear() }
    }

    override suspend fun specific(
        addonId: String,
        type: StremioContentType,
        id: String,
    ): StremioResult<StremioMetaResponse> {
        val runtime = runtimes.runtimeFor(addonId)
            ?: return StremioResult.Err(notInstalled())
        if (!AddonResourceMatcher.eligible(runtime.installed.snapshot, Resource.META, type, id)) {
            return StremioResult.Err(notEligible())
        }

        return executeProvider(runtime, type, id, speculative = false)
    }

    override suspend fun all(type: StremioContentType, id: String): StremioMetaQueryResult =
        query(type, id, mode = QueryMode.ALL)

    override suspend fun primary(
        type: StremioContentType,
        id: String,
        preferredAddonId: String?,
        speculative: Boolean,
    ): StremioMetaQueryResult =
        query(type, id, mode = QueryMode.PRIMARY, preferredAddonId = preferredAddonId, speculative = speculative)

    private enum class QueryMode { ALL, PRIMARY }

    private suspend fun query(
        type: StremioContentType,
        id: String,
        mode: QueryMode,
        preferredAddonId: String? = null,
        speculative: Boolean = false,
    ): StremioMetaQueryResult {

        val key = when {
            mode == QueryMode.PRIMARY && preferredAddonId != null ->
                preferredKey(type, id, preferredAddonId)
            else -> neutralKey(type, id)
        }
        cachedEntry(key)?.let { entry ->
            return StremioMetaQueryResult(entry.response, entry.addonId, emptyMap())
        }

        val ownership = OwnershipKey(key, speculative)
        val created = synchronized(inflightLock) {
            if (inflight.containsKey(ownership)) {
                false
            } else {
                val deferred = kotlinx.coroutines.CompletableDeferred<StremioMetaQueryResult>()
                inflight[ownership] = deferred
                true
            }
        }
        if (!created) {
            val shared = synchronized(inflightLock) { inflight[ownership] }
                ?: return executeQuery(type, id, mode, preferredAddonId, speculative)
            return withTimeoutOrNull(AWAIT_INFLIGHT_MS) { shared.await() }
                ?: StremioMetaQueryResult(null, null, emptyMap())
        }

        val deferred = synchronized(inflightLock) { inflight[ownership] }!!
        return try {
            val result = executeQuery(type, id, mode, preferredAddonId, speculative)
            val response = result.response
            if (response != null) {
                synchronized(cacheLock) {
                    cache[key] = CacheEntry(response, result.addonId, now())
                    if (cache.size > cacheMaxEntries) {
                        val it = cache.entries.iterator()
                        if (it.hasNext()) it.next()
                        it.remove()
                    }
                }
            }
            lastStates = result.states
            deferred.complete(result)
            result
        } finally {
            synchronized(inflightLock) { inflight.remove(ownership) }
        }
    }

    private suspend fun executeQuery(
        type: StremioContentType,
        id: String,
        mode: QueryMode,
        preferredAddonId: String? = null,
        speculative: Boolean = false,
    ): StremioMetaQueryResult = coroutineScope {
        val installed = runtimes.enabledInstalled()
        val eligible = installed.mapNotNull { addon ->
            if (!AddonResourceMatcher.eligible(addon.snapshot, Resource.META, type, id)) return@mapNotNull null
            runtimes.runtimeFor(addon.addonId)
        }

        if (mode == QueryMode.PRIMARY && preferredAddonId != null) {
            val preferred = eligible.firstOrNull { it.addonId == preferredAddonId }
            if (preferred != null) {
                val preferredResult = executeProvider(preferred, type, id, speculative)
                val states = LinkedHashMap<String, ProviderResourceState<StremioMetaResponse>>()
                when (preferredResult) {
                    is StremioResult.Ok -> {
                        states[preferred.addonId] = ProviderResourceState.Loaded(preferredResult.value, preferredResult.status)
                        return@coroutineScope StremioMetaQueryResult(
                            response = preferredResult.value,
                            addonId = preferred.addonId,
                            states = states,
                        )
                    }
                    is StremioResult.Err -> states[preferred.addonId] = ProviderResourceState.Failed(preferredResult.failure)
                }

                val fallback = eligible.filterNot { it.addonId == preferredAddonId }
                val jobs = fallback.map { runtime ->

                    async { runtime to executeProvider(runtime, type, id, speculative) }
                }
                val results = jobs.awaitAll()
                var winnerRuntime: InstalledAddonRuntime? = null
                var winnerResponse: StremioMetaResponse? = null
                for ((runtime, result) in results) {
                    states[runtime.addonId] = when (result) {
                        is StremioResult.Ok -> {
                            if (winnerRuntime == null) {
                                winnerRuntime = runtime
                                winnerResponse = result.value
                            }
                            ProviderResourceState.Loaded(result.value, result.status)
                        }
                        is StremioResult.Err -> ProviderResourceState.Failed(result.failure)
                    }
                }
                val wRuntime = winnerRuntime ?: return@coroutineScope StremioMetaQueryResult(null, null, states)
                return@coroutineScope StremioMetaQueryResult(
                    response = winnerResponse,
                    addonId = wRuntime.addonId,
                    states = states,
                )
            }
        }

        val jobs = eligible.map { runtime ->
            async { runtime to executeProvider(runtime, type, id, speculative) }
        }
        val results = jobs.awaitAll()
        val states = LinkedHashMap<String, ProviderResourceState<StremioMetaResponse>>()
        var winnerRuntime: InstalledAddonRuntime? = null
        var winnerResponse: StremioMetaResponse? = null
        for ((runtime, result) in results) {
            states[runtime.addonId] = when (result) {
                is StremioResult.Ok -> {
                    if (winnerRuntime == null) {
                        winnerRuntime = runtime
                        winnerResponse = result.value
                    }
                    ProviderResourceState.Loaded(result.value, result.status)
                }
                is StremioResult.Err -> ProviderResourceState.Failed(result.failure)
            }
        }
        val wRuntime = winnerRuntime ?: return@coroutineScope StremioMetaQueryResult(null, null, states)
        StremioMetaQueryResult(
            response = winnerResponse,
            addonId = wRuntime.addonId,
            states = states,
        )
    }

    private suspend fun executeProvider(
        runtime: InstalledAddonRuntime,
        type: StremioContentType,
        id: String,
        speculative: Boolean = false,
    ): StremioResult<StremioMetaResponse> {
        if (!runtime.providerState.isEnabled) return StremioResult.Err(cancelled())
        return withTimeoutOrNull(providerTimeoutMs) {
            runtime.useClient {
                it.meta(
                    type,
                    id,
                    cancellation = runtime.providerState.currentScope(),
                    speculative = speculative,
                )
            }
        } ?: StremioResult.Err(timeout())
    }

    private fun notInstalled(): StremioFailure =
        StremioFailure(
            reason = dev.infusion.core.network.DiagnosticReason.NOT_FOUND,
            status = null,
            message = "meta add-on not installed",
            retryable = false,
            diagnostic = dev.infusion.core.network.RedactedDiagnostic(
                correlationId = dev.infusion.core.network.CorrelationIds.newId(),
                reason = dev.infusion.core.network.DiagnosticReason.NOT_FOUND,
                origin = null,
            ),
        )

    private fun notEligible(): StremioFailure =
        StremioFailure(
            reason = dev.infusion.core.network.DiagnosticReason.SCHEMA_INVALID,
            status = null,
            message = "add-on does not support meta for this type/id",
            retryable = false,
            diagnostic = dev.infusion.core.network.RedactedDiagnostic(
                correlationId = dev.infusion.core.network.CorrelationIds.newId(),
                reason = dev.infusion.core.network.DiagnosticReason.SCHEMA_INVALID,
                origin = null,
            ),
        )

    private fun cancelled(): StremioFailure =
        StremioFailure(
            reason = dev.infusion.core.network.DiagnosticReason.CANCELLED,
            status = null,
            message = "request cancelled",
            retryable = false,
            diagnostic = dev.infusion.core.network.RedactedDiagnostic(
                correlationId = dev.infusion.core.network.CorrelationIds.newId(),
                reason = dev.infusion.core.network.DiagnosticReason.CANCELLED,
                origin = null,
            ),
        )

    private fun timeout(): StremioFailure =
        StremioFailure(
            reason = dev.infusion.core.network.DiagnosticReason.TIMEOUT,
            status = null,
            message = "meta provider timed out",
            retryable = true,
            diagnostic = dev.infusion.core.network.RedactedDiagnostic(
                correlationId = dev.infusion.core.network.CorrelationIds.newId(),
                reason = dev.infusion.core.network.DiagnosticReason.TIMEOUT,
                origin = null,
            ),
        )

    companion object {
        const val DEFAULT_PROVIDER_TIMEOUT_MS = 10_000L
        const val DEFAULT_CACHE_TTL_MS = 5L * 60 * 1000
        const val DEFAULT_CACHE_MAX_ENTRIES = 128
        private const val AWAIT_INFLIGHT_MS = 15_000L
    }
}
