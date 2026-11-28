package dev.infusion.core.domain.tracking

interface TrackingWatchedWriteBatchPort {

    val maxWatchedWriteBatch: Int

    fun isWatchedWriteBatchable(write: TrackingScrobbleEvent): Boolean

    suspend fun deliverWatchedBatch(writes: List<TrackingScrobbleEvent>): List<TrackingDeliveryResult>
}
