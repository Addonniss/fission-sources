package dev.infusion.core.pairing

object PairingHex {

    const val CODE_HEX_LENGTH: Int = 32
    const val NONCE_HEX_LENGTH: Int = 24

    private val HEX = Regex("[0-9a-f]+")

    fun normalize(input: String, expectedLength: Int): String? {
        val cleaned = input.filter { !it.isWhitespace() && it != '-' }.lowercase()
        if (cleaned.length != expectedLength) return null
        if (!HEX.matches(cleaned)) return null
        return cleaned
    }

    fun decode(input: String): ByteArray {
        require(input.length % 2 == 0) { "hex must have even length" }
        val out = ByteArray(input.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(input[i * 2], 16)
            val lo = Character.digit(input[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "invalid hex digit" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun group(hex: String): String = hex.chunked(4).joinToString("-")
}
