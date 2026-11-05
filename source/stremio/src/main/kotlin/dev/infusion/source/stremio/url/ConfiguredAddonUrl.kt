package dev.infusion.source.stremio.url

import dev.infusion.core.network.CanonicalUrl
import dev.infusion.source.stremio.url.AddonUrlNormalizer.AddonQuerySecret
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class ConfiguredAddonUrl(
    val publicBase: CanonicalUrl,
    val querySecret: AddonQuerySecret? = null,
) {
    init {
        require(publicBase.query == null) { "public base must not carry a query" }
        require(!publicBase.path.endsWith("/manifest.json")) {
            "public base must be the configured endpoint, not a manifest url"
        }
    }

    val scheme: String get() = publicBase.scheme
    val host: String get() = publicBase.host
    val port: Int get() = publicBase.port

    val origin: String get() = publicBase.origin

    val configuredPath: String get() = publicBase.path

    val manifestUrl: CanonicalUrl get() = manifestUrlWithQuery()

    private fun manifestUrlWithQuery(): CanonicalUrl =
        publicBase.copy(path = publicBase.path + "/manifest.json", query = querySecret?.raw)

    fun toCanonicalUrlWithQuery(): CanonicalUrl =
        publicBase.copy(path = publicBase.path + "/manifest.json", query = querySecret?.raw)

    fun identityDigest(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val baseBytes = publicBase.toString().toByteArray(StandardCharsets.UTF_8)
        digest.update(intToBytes(baseBytes.size))
        digest.update(baseBytes)
        if (querySecret != null) {
            digest.update(QUERY_PRESENT_MARKER)
            val queryBytes = querySecret.raw.toByteArray(StandardCharsets.UTF_8)
            digest.update(intToBytes(queryBytes.size))
            digest.update(queryBytes)
        } else {
            digest.update(QUERY_ABSENT_MARKER)
        }
        return digest.digest()
    }

    fun identityHex(): String = identityDigest().toHex()

    fun identityUuid(): java.util.UUID {
        val digest = identityDigest()
        val msb = java.nio.ByteBuffer.wrap(digest, 0, 8).long
        val lsb = java.nio.ByteBuffer.wrap(digest, 8, 8).long
        return java.util.UUID(msb, lsb)
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val QUERY_ABSENT_MARKER: Byte = 0x00
        const val QUERY_PRESENT_MARKER: Byte = 0x01
    }
}
