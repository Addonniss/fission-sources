package dev.infusion.core.pairing

import dev.infusion.core.lanweb.LanBindPolicy

data class LanEndpoint(val address: String, val port: Int) {

    init {
        require(isLanLocalLiteral(address)) {
            "rendezvous address must be a canonical LAN-local literal (loopback, private, or link-local)"
        }
        require(port in 1..65535) { "port must be in 1..65535" }
    }

    fun rendezvousUrl(code: String, nonce: String): String {
        val host = if (address.contains(':')) "[$address]" else address
        return "http://$host:$port/v1/pairing/start?code=$code&nonce=$nonce"
    }

    fun redactedUrl(rawUrl: String): String = PairingRedaction.url(rawUrl)

    companion object {

        fun fromManualEntry(address: String, port: Int?): LanEndpoint {
            val trimmed = address.trim()

            val bare = trimmed.removeSurrounding("[", "]")
            return LanEndpoint(address = bare, port = port ?: DEFAULT_PORT)
        }

        fun parseRendezvousUrl(raw: String): RendezvousPayload? {
            val url = try {
                java.net.URI(raw)
            } catch (_: Exception) {
                return null
            }
            if (url.scheme != "http") return null
            if (url.path != RENDEZVOUS_PATH) return null
            val host = url.host ?: return null

            val bareHost = host.removeSurrounding("[", "]")
            if (!isLanLocalLiteral(bareHost)) return null
            val port = if (url.port == -1) DEFAULT_PORT else url.port
            if (port !in 1..65535) return null
            val query = url.rawQuery ?: return null
            val params = query.split('&').mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
            }.toMap()
            val code = params["code"]?.let { PairingHex.normalize(it, PairingHex.CODE_HEX_LENGTH) } ?: return null
            val nonce = params["nonce"]?.let { PairingHex.normalize(it, PairingHex.NONCE_HEX_LENGTH) } ?: return null
            return RendezvousPayload(
                endpoint = LanEndpoint(bareHost, port),
                code = code,
                nonce = nonce,
            )
        }

        fun isLanLocalLiteral(address: String): Boolean = LanBindPolicy.isLanLocalLiteral(address)

        const val DEFAULT_PORT: Int = 8471
        const val RENDEZVOUS_PATH: String = "/v1/pairing/start"
    }
}

data class RendezvousPayload(
    val endpoint: LanEndpoint,
    val code: String,
    val nonce: String,
) {
    override fun toString(): String = "RendezvousPayload(endpoint=$endpoint, code=[REDACTED], nonce=[REDACTED])"
}
