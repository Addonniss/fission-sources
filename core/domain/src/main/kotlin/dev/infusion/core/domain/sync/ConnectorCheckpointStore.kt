package dev.infusion.core.domain.sync

import dev.infusion.core.model.sync.ConnectorCheckpoint

interface ConnectorCheckpointStore {
    fun checkpoint(connectorId: String): ConnectorCheckpoint?

    fun save(checkpoint: ConnectorCheckpoint)

    fun delete(connectorId: String)

    fun deleteAllByConnectorPrefix(prefix: String)
}
