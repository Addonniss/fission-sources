package dev.infusion.source.jellyfin.discovery

import dev.infusion.core.domain.jellyfin.JellyfinDiscoveredServer
import dev.infusion.core.domain.jellyfin.JellyfinDiscoveryPort
import dev.infusion.core.domain.jellyfin.JellyfinDiscoveryProtocol
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import org.json.JSONObject

internal fun parseDiscoveryReply(payload: String, replyAddress: String? = null): JellyfinDiscoveredServer? {

    if (payload.isEmpty() || payload.length > JellyfinDiscoveryProtocol.RECEIVE_BUFFER) return null
    val json = try {
        JSONObject(payload)
    } catch (_: Exception) {
        return null
    }
    val address = json.optString("Address").takeIf { it.isNotBlank() } ?: return null
    val id = json.optString("Id").takeIf { it.isNotBlank() } ?: address
    val name = json.optString("Name").takeIf { it.isNotBlank() } ?: address
    val endpoint = json.optString("EndpointAddress").takeIf { it.isNotBlank() } ?: replyAddress
    return JellyfinDiscoveredServer(id = id, name = name, address = address, endpointAddress = endpoint)
}

class JellyfinUdpDiscovery(
    private val broadcastAddress: InetAddress =
        InetAddress.getByAddress(byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte())),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() },
) : JellyfinDiscoveryPort {

    override suspend fun discover(timeoutMs: Long): List<JellyfinDiscoveredServer> = withContext(dispatcher) {
        val found = LinkedHashMap<String, JellyfinDiscoveredServer>()
        val socket = try {
            socketFactory()
        } catch (_: Exception) {
            return@withContext emptyList()
        }
        try {
            socket.broadcast = true
            val message = JellyfinDiscoveryProtocol.DISCOVERY_MESSAGE.toByteArray(Charsets.UTF_8)
            socket.send(
                DatagramPacket(
                    message,
                    message.size,
                    broadcastAddress,
                    JellyfinDiscoveryProtocol.DISCOVERY_PORT,
                ),
            )
            val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(1L) * 1_000_000L
            val buffer = ByteArray(JellyfinDiscoveryProtocol.RECEIVE_BUFFER)
            while (found.size < JellyfinDiscoveryPort.MAX_SERVERS) {
                if (!currentCoroutineContext().isActive) break
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0L) break

                socket.soTimeout = remainingMs.coerceAtMost(RECEIVE_SLICE_MS).toInt()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val server = parseDiscoveryReply(text, packet.address?.hostAddress) ?: continue

                found[server.id] = server
            }
        } catch (e: Exception) {

            if (e is kotlinx.coroutines.CancellationException) throw e
        } finally {
            runCatching { socket.close() }
        }
        found.values.toList()
    }

    private companion object {

        const val RECEIVE_SLICE_MS: Long = 250L
    }
}
