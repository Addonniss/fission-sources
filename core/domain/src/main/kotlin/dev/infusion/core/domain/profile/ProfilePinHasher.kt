package dev.infusion.core.domain.profile

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

interface ProfilePinHasher {

    fun hash(pin: String): String

    fun verify(pin: String, encodedHash: String): Boolean
}

class Pbkdf2ProfilePinHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
    private val saltBytes: Int = SALT_BYTES,
) : ProfilePinHasher {

    override fun hash(pin: String): String {
        require(pin.length in 4..64) { "PIN must be 4..64 characters" }
        val salt = ByteArray(saltBytes).also(random::nextBytes)
        val derived = pbkdf2(pin, salt)
        return "pbkdf2-sha256$$iterations$${Base64.getEncoder().encodeToString(salt)}$${Base64.getEncoder().encodeToString(derived)}"
    }

    override fun verify(pin: String, encodedHash: String): Boolean {
        val parts = encodedHash.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256") return false
        val storedIterations = parts[1].toIntOrNull() ?: return false

        if (storedIterations < 1) return false
        val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return false
        val derived = pbkdf2(pin, salt, storedIterations)
        return MessageDigest.isEqual(expected, derived)
    }

    private fun pbkdf2(pin: String, salt: ByteArray, rounds: Int = iterations): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, rounds, DERIVED_BITS)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private companion object {
        const val ALGORITHM = "PBKDF2WithHmacSHA256"
        const val DEFAULT_ITERATIONS = 100_000
        const val SALT_BYTES = 16
        const val DERIVED_BITS = 256
    }
}
