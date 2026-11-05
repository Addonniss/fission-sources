package dev.infusion.core.network

import java.util.concurrent.ConcurrentHashMap

data class LanTrustGrant(
    val host: String,
    val port: Int? = null,
    val grantedAtEpochMs: Long = System.currentTimeMillis(),
    val networkProfile: String? = null,
)

interface LanTrustRegistry {
    fun isApproved(host: String): Boolean
    fun grants(): List<LanTrustGrant>
    fun approve(grant: LanTrustGrant)
    fun revoke(host: String)
}

class InMemoryLanTrustRegistry : LanTrustRegistry {
    private val approved = ConcurrentHashMap.newKeySet<String>()

    override fun isApproved(host: String): Boolean = approved.contains(UrlParser.canonicalizeHostname(host))

    override fun grants(): List<LanTrustGrant> = approved.map { LanTrustGrant(it) }

    override fun approve(grant: LanTrustGrant) {
        approved.add(UrlParser.canonicalizeHostname(grant.host))
    }

    override fun revoke(host: String) {
        approved.remove(UrlParser.canonicalizeHostname(host))
    }
}
