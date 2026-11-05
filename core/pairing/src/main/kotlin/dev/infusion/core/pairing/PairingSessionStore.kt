package dev.infusion.core.pairing

import java.util.concurrent.ConcurrentHashMap

class PairingSessionStore(private val clock: Clock) {

    private val sessions = ConcurrentHashMap<String, PairingSession>()

    private val codeIndex = ConcurrentHashMap<String, String>()

    fun create(
        id: String,
        codeDigest: ByteArray,
        nonceDigest: ByteArray,
        ttlMs: Long,
        maxPolls: Int,
    ): PairingSession {
        val session = PairingSession(
            id = id,
            codeDigest = codeDigest,
            nonceDigest = nonceDigest,
            createdAtEpochMs = clock.nowEpochMs(),
            ttlMs = ttlMs,
            maxPolls = maxPolls,
        )
        val previous = sessions.putIfAbsent(id, session)
        require(previous == null) { "duplicate session id" }
        codeIndex[codeDigestHex(codeDigest)] = id
        return session
    }

    fun get(id: String): PairingSession? = sessions[id]

    fun findByCodeDigest(codeDigestHex: String): PairingSession? {
        val id = codeIndex[codeDigestHex] ?: return null
        return sessions[id]
    }

    fun remove(id: String): PairingSession? {
        val session = sessions.remove(id) ?: return null
        codeIndex.entries.removeIf { it.value == id }
        return session
    }

    fun all(): List<PairingSession> = sessions.values.toList()

    fun gcExpired(nowMs: Long): Int {
        var expired = 0
        for (session in sessions.values) {
            if (session.expireIfPastTtl(nowMs)) {
                remove(session.id)
                expired++
            }
        }
        return expired
    }

    fun size(): Int = sessions.size

    fun clear() {
        sessions.clear()
        codeIndex.clear()
    }

    private fun codeDigestHex(digest: ByteArray): String =
        digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
