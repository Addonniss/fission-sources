package dev.infusion.core.domain.jellyfin

data class JellyfinDiscoveredServer(
    val id: String,
    val name: String,
    val address: String,
    val endpointAddress: String? = null,
)

interface JellyfinDiscoveryPort {
    suspend fun discover(timeoutMs: Long): List<JellyfinDiscoveredServer>

    companion object {

        const val MAX_SERVERS: Int = 15
    }
}

object JellyfinDiscoveryProtocol {

    const val DISCOVERY_MESSAGE: String = "who is JellyfinServer?"

    const val DISCOVERY_PORT: Int = 7359

    const val RECEIVE_BUFFER: Int = 1024
}

class FakeJellyfinDiscovery : JellyfinDiscoveryPort {

    private val replies = LinkedHashMap<String, JellyfinDiscoveredServer>()

    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    var attempts: Int = 0
        private set

    fun seed(servers: List<JellyfinDiscoveredServer>) {
        for (server in servers) replies[server.id] = server
    }

    override suspend fun discover(timeoutMs: Long): List<JellyfinDiscoveredServer> {
        attempts++
        gate?.await()
        return replies.values.toList()
    }
}
