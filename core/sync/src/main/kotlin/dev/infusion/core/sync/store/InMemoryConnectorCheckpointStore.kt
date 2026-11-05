package dev.infusion.core.sync.store

import dev.infusion.core.domain.sync.ConnectorCheckpointStore
import dev.infusion.core.model.sync.ConnectorCheckpoint

class InMemoryConnectorCheckpointStore : ConnectorCheckpointStore {

    private val checkpoints = mutableMapOf<String, ConnectorCheckpoint>()

    @Synchronized
    override fun checkpoint(connectorId: String): ConnectorCheckpoint? = checkpoints[connectorId]

    @Synchronized
    override fun save(checkpoint: ConnectorCheckpoint) {
        checkpoints[checkpoint.connectorId] = checkpoint
    }

    @Synchronized
    override fun delete(connectorId: String) {
        checkpoints.remove(connectorId)
    }

    @Synchronized
    override fun deleteAllByConnectorPrefix(prefix: String) {
        checkpoints.keys.removeIf { it.startsWith(prefix) }
    }
}
