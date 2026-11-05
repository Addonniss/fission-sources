package dev.infusion.core.pairing

import java.security.SecureRandom

fun interface PairingRandom {

    fun nextBytes(byteCount: Int): ByteArray
}

class SecureRandomPairingRandom(
    private val random: SecureRandom = SecureRandom(),
) : PairingRandom {
    override fun nextBytes(byteCount: Int): ByteArray {
        require(byteCount > 0) { "byteCount must be positive" }
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return bytes
    }
}

object PairingRandomness {
    val DEFAULT: PairingRandom = SecureRandomPairingRandom()
}
