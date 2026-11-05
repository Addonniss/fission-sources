package dev.infusion.core.database.store

import dev.infusion.core.database.Clock
import dev.infusion.core.database.dao.CheckpointDao
import dev.infusion.core.database.mapper.CheckpointMapper
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.sync.ConnectorCheckpointStore
import dev.infusion.core.model.sync.ConnectorCheckpoint

class RoomConnectorCheckpointStore(
    private val checkpointDao: CheckpointDao,
    private val scope: ProfileScope,
    private val clock: Clock = Clock.SYSTEM,
) : ConnectorCheckpointStore {

    override fun checkpoint(connectorId: String): ConnectorCheckpoint? =
        checkpointDao.get(scope.activeProfileId, connectorId)?.let(CheckpointMapper::toCanonical)

    override fun save(checkpoint: ConnectorCheckpoint) {
        checkpointDao.put(CheckpointMapper.toEntity(scope.activeProfileId, checkpoint))
    }

    override fun delete(connectorId: String) {
        checkpointDao.delete(scope.activeProfileId, connectorId)
    }

    override fun deleteAllByConnectorPrefix(prefix: String) {
        checkpointDao.deleteByConnectorPrefix(prefix)
    }
}
