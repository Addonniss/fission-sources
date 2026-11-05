package dev.infusion.feature.configweb

import dev.infusion.core.pairing.PairingHex
import dev.infusion.core.pairing.PairingRandom
import java.security.MessageDigest

object SessionCode {

    const val ALPHABET: String = "23456789ABCDEFGH"

    fun mint(random: PairingRandom): ByteArray = random.nextBytes(ConfigWebLimits.TOKEN_BYTES)

    fun wire(token: ByteArray): String {
        require(token.size == ConfigWebLimits.TOKEN_BYTES) { "token must be ${ConfigWebLimits.TOKEN_BYTES} bytes" }
        return token.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun display(token: ByteArray): String {
        require(token.size == ConfigWebLimits.TOKEN_BYTES) { "token must be ${ConfigWebLimits.TOKEN_BYTES} bytes" }
        val text = buildString(ConfigWebLimits.CODE_CHARS) {
            for (byte in token) {
                val value = byte.toInt()
                append(ALPHABET[(value shr 4) and 0x0f])
                append(ALPHABET[value and 0x0f])
            }
        }
        return text.chunked(4).joinToString("-")
    }

    fun parse(presented: String): List<ByteArray> {
        val cleaned = presented.filterNot { it.isWhitespace() || it == '-' }
        if (cleaned.length != ConfigWebLimits.CODE_CHARS) return emptyList()
        val candidates = LinkedHashSet<String>(2)
        PairingHex.normalize(cleaned, ConfigWebLimits.TOKEN_HEX_CHARS)?.let(candidates::add)
        decodeManual(cleaned)?.let { candidates += wire(it) }
        return candidates.map(PairingHex::decode)
    }

    fun digest(token: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(token)

    fun digestHex(token: ByteArray): String =
        digest(token).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun decodeManual(cleaned: String): ByteArray? {
        val nibbles = IntArray(cleaned.length)
        for (index in cleaned.indices) {
            val nibble = ALPHABET.indexOf(cleaned[index].uppercaseChar())
            if (nibble < 0) return null
            nibbles[index] = nibble
        }
        val bytes = ByteArray(ConfigWebLimits.TOKEN_BYTES)
        for (index in bytes.indices) {
            bytes[index] = ((nibbles[index * 2] shl 4) or nibbles[index * 2 + 1]).toByte()
        }
        return bytes
    }
}
