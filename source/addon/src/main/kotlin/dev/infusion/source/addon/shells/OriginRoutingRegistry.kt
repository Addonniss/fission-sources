package dev.infusion.source.addon.shells

import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.HardenedHttpClient
import dev.infusion.core.network.NetworkPolicyException
import java.util.concurrent.ConcurrentHashMap

class OriginRoutingRegistry {

    data class Owner(
        val sourceId: String,
        val attempt: String,
        val client: HardenedHttpClient,
    )

    companion object {
        const val SEED_ATTEMPT: String = "__seed__"
    }

    private val lock = Any()
    private val owners = ConcurrentHashMap<String, Owner>()

    fun registerForAttempt(
        sourceId: String,
        attempt: String,
        client: HardenedHttpClient,
        origins: Collection<CanonicalUrl>,
    ): Set<String> {
        require(attempt != SEED_ATTEMPT) { "attempt must not be the seed marker" }
        val keys = origins.map { it.origin }.distinct()
        synchronized(lock) {

            for (key in keys) {
                val existing = owners[key]
                if (existing == null) continue
                val sameOwner = existing.sourceId == sourceId &&
                    (existing.attempt == attempt || existing.attempt == SEED_ATTEMPT)
                if (!sameOwner) {
                    throw IllegalStateException(
                        "cross-attempt exact-origin collision for $key " +
                            "(owned by ${existing.sourceId}/${existing.attempt}; " +
                            "refusing to route a delayed request through another source's client)",
                    )
                }
            }

            for (key in keys) {
                val existing = owners[key]
                if (existing != null && existing.sourceId == sourceId && existing.attempt == SEED_ATTEMPT) {
                    continue
                }
                owners[key] = Owner(sourceId, attempt, client)
            }
            return keys.toSet()
        }
    }

    fun registerSeed(
        sourceId: String,
        client: HardenedHttpClient,
        origins: Collection<CanonicalUrl>,
    ) {
        synchronized(lock) {
            origins.map { it.origin }.distinct().forEach { key ->
                owners.putIfAbsent(key, Owner(sourceId, SEED_ATTEMPT, client))
            }
        }
    }

    fun retireAttempt(sourceId: String, attempt: String) {
        synchronized(lock) {
            owners.entries.removeIf { (_, owner) ->
                owner.sourceId == sourceId && owner.attempt == attempt
            }
        }
    }

    fun retireSource(sourceId: String) {
        synchronized(lock) {
            owners.entries.removeIf { (_, owner) -> owner.sourceId == sourceId }
        }
    }

    fun ownerOf(origin: CanonicalUrl): Owner? = owners[origin.origin]

    fun ownerOfOrigin(origin: String): Owner? = owners[origin]

    fun snapshot(): Map<String, Owner> = owners.toMap()

    fun isEmpty(): Boolean = owners.isEmpty()
}

class OriginRoutingHardenedClient(
    private val registry: OriginRoutingRegistry,
) : HardenedHttpClient {

    override fun execute(request: dev.infusion.core.network.HardenedRequest): dev.infusion.core.network.HardenedResponse {
        val owner = registry.ownerOf(request.url)
            ?: throw NetworkPolicyException(
                reason = dev.infusion.core.network.DiagnosticReason.PRIVATE_DESTINATION,
                message = "no hardened transport wired for this exact origin",
                origin = request.url.origin,
            )
        return owner.client.execute(request)
    }
}
