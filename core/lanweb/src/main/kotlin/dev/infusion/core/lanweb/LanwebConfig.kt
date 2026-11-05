package dev.infusion.core.lanweb

data class LanwebConfig(
    val bindAddress: String = "127.0.0.1",
    val port: Int = 0,
    val maxRequestLineBytes: Int = 1024,
    val maxHeaderBytes: Int = 4096,
    val maxBodyBytes: Int = LanwebBodyCaps.PAIRING,
    val maxConnections: Int = 8,
    val connectionWindowMs: Long = 60_000,
    val maxConnectionsPerWindow: Int = 240,
    val readTimeoutMs: Long = 10_000,
    val writeTimeoutMs: Long = 10_000,
    val connectionDeadlineMs: Long = 30_000,
    val backlog: Int = 16,
) {
    init {
        LanBindPolicy.requireLanLocalLiteral(bindAddress)
        require(maxRequestLineBytes > 0) { "maxRequestLineBytes must be positive" }
        require(maxHeaderBytes > 0) { "maxHeaderBytes must be positive" }
        require(maxBodyBytes > 0) { "maxBodyBytes must be positive" }
        require(maxConnections > 0) { "maxConnections must be positive" }
        require(maxConnectionsPerWindow > 0) { "maxConnectionsPerWindow must be positive" }
        require(readTimeoutMs > 0) { "readTimeoutMs must be positive" }
        require(writeTimeoutMs > 0) { "writeTimeoutMs must be positive" }
        require(connectionDeadlineMs > 0) { "connectionDeadlineMs must be positive" }
    }
}

object LanwebBodyCaps {

    const val PAIRING: Int = 16 * 1024

    const val MANAGEMENT: Int = 64 * 1024
}
