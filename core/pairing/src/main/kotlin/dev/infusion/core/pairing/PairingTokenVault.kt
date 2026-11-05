package dev.infusion.core.pairing

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

enum class TokenRedemption {

    ACCEPTED,

    CONSUMED,

    EXPIRED,

    UNKNOWN,
}

class PairingTokenVault(
    private val clock: Clock,
    private val tokenTtlMs: Long,
) {
    private data class Entry(
        val sessionId: String,
        val expiresAtMs: Long,
        @Volatile var consumed: Boolean = false,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun mint(token: String, sessionId: String) {
        entries[digestHex(token)] = Entry(sessionId = sessionId, expiresAtMs = clock.nowEpochMs() + tokenTtlMs)
    }

    fun redeem(token: String): TokenRedemption {
        val key = digestHex(token)
        var outcome = TokenRedemption.UNKNOWN
        entries.computeIfPresent(key) { _, entry ->
            if (entry.consumed) {
                outcome = TokenRedemption.CONSUMED
                entry
            } else if (clock.nowEpochMs() >= entry.expiresAtMs) {
                outcome = TokenRedemption.EXPIRED
                entry
            } else {
                entry.consumed = true
                outcome = TokenRedemption.ACCEPTED
                entry
            }
        }
        return outcome
    }

    fun sessionIdOf(token: String): String? = entries[digestHex(token)]?.sessionId

    fun purgeExpired(nowMs: Long): Int {
        var removed = 0
        for ((key, entry) in entries) {
            if (nowMs >= entry.expiresAtMs && entries.remove(key, entry)) removed++
        }
        return removed
    }

    fun size(): Int = entries.size

    fun clear() = entries.clear()

    private fun digestHex(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(PairingHex.decode(token))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
