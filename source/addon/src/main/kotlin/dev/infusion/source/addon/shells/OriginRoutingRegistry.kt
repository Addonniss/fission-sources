package dev.infusion.source.addon.shells

import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.HardenedHttpClient
import dev.infusion.core.network.NetworkPolicyException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class OriginRoutingRegistry {

    data class Owner(
        val sourceId: String,
        val attempt: String,
        val client: HardenedHttpClient,
        val sequence: Long = 0L,
    )

    companion object {

        const val SEED_ATTEMPT: String = "__seed__"

        private const val KEY_SEPARATOR = "\u0000"
    }

    private val lock = Any()

    private val bindings = ConcurrentHashMap<String, ConcurrentHashMap<String, Owner>>()

    private val sequence = AtomicLong(0L)

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
                val perOrigin = bindings.getOrPut(key) { ConcurrentHashMap() }
                val bindingKey = bindingKey(sourceId, attempt)
                val existing = perOrigin[bindingKey]
                perOrigin[bindingKey] = if (existing != null) {
                    existing.copy(client = client)
                } else {
                    Owner(sourceId, attempt, client, sequence.incrementAndGet())
                }
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
                val perOrigin = bindings.getOrPut(key) { ConcurrentHashMap() }
                val bindingKey = bindingKey(sourceId, SEED_ATTEMPT)
                if (!perOrigin.containsKey(bindingKey)) {
                    perOrigin[bindingKey] = Owner(sourceId, SEED_ATTEMPT, client, sequence.incrementAndGet())
                }
            }
        }
    }

    fun retireAttempt(sourceId: String, attempt: String) {
        synchronized(lock) {
            retireWhere { owner -> owner.sourceId == sourceId && owner.attempt == attempt }
        }
    }

    fun retireSource(sourceId: String) {
        synchronized(lock) {
            retireWhere { owner -> owner.sourceId == sourceId }
        }
    }

    fun ownerOf(origin: CanonicalUrl): Owner? = ownerOfOrigin(origin.origin)

    fun ownerOfOrigin(origin: String): Owner? {
        val perOrigin = bindings[origin] ?: return null
        return perOrigin.values.minWithOrNull(
            compareBy({ it.attempt == SEED_ATTEMPT }, { -it.sequence }),
        )
    }

    fun bindingsOf(origin: CanonicalUrl): List<Owner> {
        val perOrigin = bindings[origin.origin] ?: return emptyList()
        return perOrigin.values.sortedWith(
            compareBy({ it.attempt == SEED_ATTEMPT }, { -it.sequence }),
        )
    }

    fun snapshot(): Map<String, Owner> = bindings.keys.mapNotNull { key ->
        ownerOfOrigin(key)?.let { key to it }
    }.toMap()

    fun isEmpty(): Boolean = bindings.isEmpty()

    private fun bindingKey(sourceId: String, attempt: String): String =
        sourceId + KEY_SEPARATOR + attempt

    private fun retireWhere(predicate: (Owner) -> Boolean) {
        val origins = bindings.entries.iterator()
        while (origins.hasNext()) {
            val entry = origins.next()
            val perOrigin = entry.value
            perOrigin.entries.removeIf { (_, owner) -> predicate(owner) }
            if (perOrigin.isEmpty()) origins.remove()
        }
    }
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
