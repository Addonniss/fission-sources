package dev.infusion.source.jellyfin.removal

import dev.infusion.core.database.sources.SourceRemoval
import dev.infusion.core.database.sources.SourceRemovalReport
import dev.infusion.core.database.model.RemovalScope
import dev.infusion.core.domain.sync.ConnectorCheckpointStore
import dev.infusion.core.domain.sync.WatchOutbox
import dev.infusion.core.network.LanTrustRegistry
import dev.infusion.core.security.SecretStore
import dev.infusion.source.jellyfin.auth.JellyfinAuthenticator
import dev.infusion.source.jellyfin.auth.JellyfinSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JellyfinSourceRemoval(
    private val sourceRemoval: SourceRemoval,
    private val secretStore: SecretStore,
    private val lanTrust: LanTrustRegistry,
    private val authenticator: JellyfinAuthenticator?,
    private val serverHost: String?,
    private val scope: CoroutineScope,
    private val outbox: WatchOutbox,
    private val checkpointStore: ConnectorCheckpointStore,
    private val sessionProvider: () -> JellyfinSession? = { null },
) {

    suspend fun remove(sourceId: String, removalScope: RemovalScope): SourceRemovalReport {

        val session = sessionProvider()
        if (session != null) {
            try {
                withContext(Dispatchers.IO) {
                    authenticator?.logoutAllowLocalRevocation(session)
                }
            } catch (_: Exception) {

            }

            session.tokenRef.let { secretStore.delete(it) }
        }

        if (serverHost != null) {
            try {
                lanTrust.revoke(serverHost)
            } catch (_: Exception) {

            }
        }

        try {
            outbox.deleteByConnector("jellyfin:$sourceId")
        } catch (_: Exception) {

        }
        try {
            checkpointStore.deleteAllByConnectorPrefix("jellyfin:$sourceId")
        } catch (_: Exception) {

        }

        return sourceRemoval.remove(sourceId, removalScope)
    }
}
