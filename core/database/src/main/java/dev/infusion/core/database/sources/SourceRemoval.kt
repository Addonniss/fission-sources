package dev.infusion.core.database.sources

import dev.infusion.core.database.Clock
import dev.infusion.core.database.InfusionDatabase
import dev.infusion.core.database.dao.AvailabilityDao
import dev.infusion.core.database.dao.LocalAssetDao
import dev.infusion.core.database.dao.PlayableCandidateDao
import dev.infusion.core.database.dao.PlaySessionContextDao
import dev.infusion.core.database.dao.SecretReferenceDao
import dev.infusion.core.database.dao.ServerItemDao
import dev.infusion.core.database.dao.SourceDao
import dev.infusion.core.database.dao.WatchEventDao
import dev.infusion.core.database.dao.WatchStateDao
import dev.infusion.core.database.model.RemovalScope
import dev.infusion.core.domain.candidate.CandidateHandoffStore
import dev.infusion.core.model.SourceId

class SourceNotFoundException(sourceId: String) :
    IllegalArgumentException("source not found: $sourceId")

class SourceRemoval(
    private val database: InfusionDatabase,
    private val sourceDao: SourceDao,
    private val secretDao: SecretReferenceDao,
    private val serverItemDao: ServerItemDao,
    private val assetDao: LocalAssetDao,
    private val availabilityDao: AvailabilityDao,
    private val candidateDao: PlayableCandidateDao,
    private val watchEventDao: WatchEventDao,
    private val watchStateDao: WatchStateDao,
    private val playSessionContextDao: PlaySessionContextDao,
    private val candidateHandoffStore: CandidateHandoffStore,
    private val clock: Clock = Clock.SYSTEM,
) {

    fun remove(sourceId: String, scope: RemovalScope): SourceRemovalReport {
        var report: SourceRemovalReport? = null
        database.runInTransaction {
            val source = sourceDao.getById(sourceId) ?: throw SourceNotFoundException(sourceId)
            val now = clock.nowMs()

            val activeRefs = secretDao.listActiveBySource(sourceId)
            secretDao.revokeAllForSource(sourceId, now)

            sourceDao.tombstone(sourceId, now)

            playSessionContextDao.deleteBySource(sourceId)
            candidateHandoffStore.clearForSource(SourceId.fromString(sourceId))

            var deletedItems = 0
            var deletedAssets = 0
            var deletedAvailability = 0
            var deletedCandidates = 0
            if (scope.ordinal >= RemovalScope.INCLUDING_CACHED_DATA.ordinal) {
                deletedItems = serverItemDao.deleteBySource(sourceId)
                deletedAssets = assetDao.deleteBySource(sourceId)
                deletedAvailability = availabilityDao.deleteBySource(sourceId)
                deletedCandidates = candidateDao.deleteBySource(sourceId)
            }

            var deletedEvents = 0
            var deletedStates = 0
            var preservedEvents = 0
            if (scope == RemovalScope.INCLUDING_WATCH_HISTORY) {
                deletedEvents = watchEventDao.deleteByAnchorSource(sourceId)
                val remainingKeys = watchEventDao.listKeys(source.ownerProfileId).toSet()
                val orphaned = watchStateDao
                    .listByProfile(source.ownerProfileId)
                    .filter { it.watchKey !in remainingKeys }
                    .map { it.watchKey }
                if (orphaned.isNotEmpty()) {
                    deletedStates = watchStateDao.deleteByKeys(source.ownerProfileId, orphaned)
                }
            } else {
                watchStateDao.nullAnchorsForSource(sourceId)
                preservedEvents = watchEventDao.countByAnchorSource(sourceId)
            }

            SourceRemovalReport(
                sourceId = sourceId,
                removed = true,
                revokedSecrets = activeRefs.size,
                revokedSecretAliases = activeRefs.map { it.keyAlias },
                deletedServerItems = deletedItems,
                deletedLocalAssets = deletedAssets,
                deletedAvailability = deletedAvailability,
                deletedCandidates = deletedCandidates,
                deletedWatchEvents = deletedEvents,
                deletedWatchStates = deletedStates,
                preservedWatchEvents = preservedEvents,
            ).also { report = it }
        }
        return checkNotNull(report)
    }

    fun purge(sourceId: String, includeWatchHistory: Boolean): SourceRemovalReport {
        var report: SourceRemovalReport? = null
        database.runInTransaction {
            val source = sourceDao.getById(sourceId) ?: throw SourceNotFoundException(sourceId)
            val activeRefs = secretDao.listActiveBySource(sourceId)

            secretDao.hardDeleteBySource(sourceId)
            val deletedItems = serverItemDao.deleteBySource(sourceId)
            val deletedAssets = assetDao.deleteBySource(sourceId)
            val deletedAvailability = availabilityDao.deleteBySource(sourceId)
            val deletedCandidates = candidateDao.deleteBySource(sourceId)

            playSessionContextDao.deleteBySource(sourceId)
            candidateHandoffStore.clearForSource(SourceId.fromString(sourceId))

            var deletedEvents = 0
            var deletedStates = 0
            var preservedEvents = 0
            if (includeWatchHistory) {
                deletedEvents = watchEventDao.deleteByAnchorSource(sourceId)
                val remainingKeys = watchEventDao.listKeys(source.ownerProfileId).toSet()
                val orphaned = watchStateDao
                    .listByProfile(source.ownerProfileId)
                    .filter { it.watchKey !in remainingKeys }
                    .map { it.watchKey }
                if (orphaned.isNotEmpty()) {
                    deletedStates = watchStateDao.deleteByKeys(source.ownerProfileId, orphaned)
                }
            } else {
                watchStateDao.nullAnchorsForSource(sourceId)
                preservedEvents = watchEventDao.countByAnchorSource(sourceId)
            }

            sourceDao.hardDelete(sourceId)

            SourceRemovalReport(
                sourceId = sourceId,
                removed = true,
                revokedSecrets = activeRefs.size,
                revokedSecretAliases = activeRefs.map { it.keyAlias },
                deletedServerItems = deletedItems,
                deletedLocalAssets = deletedAssets,
                deletedAvailability = deletedAvailability,
                deletedCandidates = deletedCandidates,
                deletedWatchEvents = deletedEvents,
                deletedWatchStates = deletedStates,
                preservedWatchEvents = preservedEvents,
            ).also { report = it }
        }
        return checkNotNull(report)
    }
}

data class SourceRemovalReport(
    val sourceId: String,
    val removed: Boolean,
    val revokedSecrets: Int,
    val revokedSecretAliases: List<String>,
    val deletedServerItems: Int,
    val deletedLocalAssets: Int,
    val deletedAvailability: Int,
    val deletedCandidates: Int,
    val deletedWatchEvents: Int,
    val deletedWatchStates: Int,
    val preservedWatchEvents: Int,
)
