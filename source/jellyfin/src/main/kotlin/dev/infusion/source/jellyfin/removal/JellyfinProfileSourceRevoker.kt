package dev.infusion.source.jellyfin.removal

import dev.infusion.core.database.dao.JellyfinSourceStateDao
import dev.infusion.core.domain.profile.ProfileSourceCredentialRevoker
import dev.infusion.core.network.LanTrustRegistry
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.network.OkHttpHardenedClient
import dev.infusion.core.network.UrlParser
import dev.infusion.core.security.SecretReference
import dev.infusion.core.security.SecretStore
import dev.infusion.source.jellyfin.JellyfinServerConfig
import dev.infusion.source.jellyfin.JellyfinSource
import dev.infusion.source.jellyfin.auth.JellyfinSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JellyfinProfileSourceRevoker(
    private val stateDao: JellyfinSourceStateDao,
    private val secretStore: () -> SecretStore,
    private val lanTrust: LanTrustRegistry,
    private val limits: NetworkLimits = NetworkLimits.DEFAULT,

    private val transportFactory: (() -> OkHttpHardenedClient)? = null,
) : ProfileSourceCredentialRevoker {

    override suspend fun revoke(sourceIds: List<String>) {
        val store = secretStore()
        sourceIds.forEach { revokeOne(it, store) }
    }

    private suspend fun revokeOne(sourceId: String, secretStore: SecretStore) {
        val state = stateDao.getById(sourceId) ?: return
        val tokenRefId = state.tokenRefId
        if (tokenRefId == null) {

            try {
                val host = UrlParser.parse(state.origin).host
                lanTrust.revoke(host)
            } catch (_: Exception) {

            }
            return
        }
        try {
            val config = JellyfinServerConfig.parse(state.origin, state.username)
            val source = JellyfinSource(
                config = config,
                lanTrust = lanTrust,
                secretStore = secretStore,
                limits = limits,
                deviceId = state.deviceId,
                transportFactory = transportFactory,
            )
            val session = JellyfinSession(
                sourceId = sourceId,
                userId = state.userId,
                serverId = state.serverId,
                userName = state.username,
                tokenRef = SecretReference(tokenRefId),
            )

            withContext(Dispatchers.IO) {
                source.authenticator.logoutAllowLocalRevocation(session)
            }

            source.revokeLan()
        } catch (_: Exception) {

            try {
                secretStore.delete(SecretReference(tokenRefId))
            } catch (_: Exception) {

            }
            try {
                val host = UrlParser.parse(state.origin).host
                lanTrust.revoke(host)
            } catch (_: Exception) {

            }
        }
    }
}
