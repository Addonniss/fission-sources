package dev.infusion.core.pairing

import java.security.MessageDigest

object ConstantTime {

    fun matches(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun matchesHex(a: String, b: String): Boolean =
        matches(hexDecode(a), hexDecode(b))

    private fun hexDecode(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
