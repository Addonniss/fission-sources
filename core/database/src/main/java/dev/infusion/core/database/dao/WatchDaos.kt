package dev.infusion.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.infusion.core.database.entity.WatchEventEntity
import dev.infusion.core.database.entity.WatchStateEntity
import kotlinx.coroutines.flow.Flow

private const val WATCHED_SORT_INSTANT =
    "CASE WHEN kind = 'EXPLICIT_RESET' OR origin = 'LOCAL' THEN occurred_at_ms ELSE observed_at_ms END"

@Dao
interface WatchEventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun append(event: WatchEventEntity): Long

    @Query("SELECT * FROM watch_events WHERE event_id = :eventId")
    fun getById(eventId: String): WatchEventEntity?

    @Query(
        "SELECT * FROM watch_events WHERE profile_id = :profileId AND watch_key = :watchKey " +
            "ORDER BY occurred_at_ms ASC, event_id ASC",
    )
    fun listForKey(profileId: String, watchKey: String): List<WatchEventEntity>

    @Query("SELECT * FROM watch_events WHERE profile_id = :profileId ORDER BY occurred_at_ms DESC LIMIT :limit")
    fun listRecent(profileId: String, limit: Int): List<WatchEventEntity>

    @Query("SELECT * FROM watch_events WHERE profile_id = :profileId ORDER BY received_at_ms ASC, event_id ASC")
    fun allForProfile(profileId: String): List<WatchEventEntity>

    @Query("SELECT * FROM watch_events WHERE session_id = :sessionId ORDER BY occurred_at_ms ASC")
    fun listBySession(sessionId: String): List<WatchEventEntity>

    @Query("SELECT DISTINCT watch_key FROM watch_events WHERE profile_id = :profileId")
    fun listKeys(profileId: String): List<String>

    @Query("SELECT COUNT(*) FROM watch_events WHERE anchor_source_id = :sourceId")
    fun countByAnchorSource(sourceId: String): Int

    @Query("SELECT DISTINCT profile_id FROM watch_events WHERE watch_key LIKE :prefix || '%'")
    fun listProfilesWithKeyPrefix(prefix: String): List<String>

    @Query("SELECT DISTINCT watch_key FROM watch_events WHERE profile_id = :profileId AND watch_key LIKE :prefix || '%'")
    fun listKeysWithPrefix(profileId: String, prefix: String): List<String>

    @Query(
        "SELECT * FROM watch_events WHERE profile_id = :profileId AND watch_key LIKE :prefix || '%' " +
            "AND (kind IN ('COMPLETED', 'EXPLICIT_WATCHED') OR kind = 'EXPLICIT_RESET' OR " +
            "(kind = 'PROGRESS' AND connector_id IS NOT NULL AND position_ms IS NOT NULL " +
            "AND duration_ms IS NOT NULL AND position_ms * 10 >= duration_ms * 9)) " +
            "AND (:afterSet = 0 OR (" +
            "(:afterUndated = 0 AND (($WATCHED_SORT_INSTANT) IS NULL " +
            "OR ($WATCHED_SORT_INSTANT) < :afterMs " +
            "OR (($WATCHED_SORT_INSTANT) = :afterMs AND (watch_key < :afterKey " +
            "OR (watch_key = :afterKey AND event_id < :afterId))))) " +
            "OR (:afterUndated = 1 AND ($WATCHED_SORT_INSTANT) IS NULL AND (watch_key < :afterKey " +
            "OR (watch_key = :afterKey AND event_id < :afterId))))) " +
            "ORDER BY (($WATCHED_SORT_INSTANT) IS NULL) ASC, ($WATCHED_SORT_INSTANT) DESC, " +
            "watch_key DESC, event_id DESC LIMIT :limit",
    )
    suspend fun readWatchedHistoryOfShape(
        profileId: String,
        prefix: String,
        afterSet: Int,
        afterUndated: Int,
        afterMs: Long?,
        afterKey: String?,
        afterId: String?,
        limit: Int,
    ): List<WatchEventEntity>

    @Query("UPDATE watch_events SET watch_key = :toKey WHERE profile_id = :profileId AND watch_key = :fromKey")
    fun rekey(profileId: String, fromKey: String, toKey: String): Int

    @Query("DELETE FROM watch_events WHERE anchor_source_id = :sourceId")
    fun deleteByAnchorSource(sourceId: String): Int

    @Query("DELETE FROM watch_events WHERE profile_id = :profileId")
    fun deleteByProfile(profileId: String): Int

    @Query("UPDATE watch_events SET profile_id = :toProfileId WHERE profile_id = :fromProfileId")
    fun moveToProfile(fromProfileId: String, toProfileId: String): Int
}

data class WatchStateKeyRow(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "watch_key") val watchKey: String,
)

@Dao
interface WatchStateDao {
    @Upsert
    fun upsert(state: WatchStateEntity)

    @Query("SELECT * FROM watch_states WHERE profile_id = :profileId AND watch_key = :watchKey")
    fun get(profileId: String, watchKey: String): WatchStateEntity?

    @Query("SELECT * FROM watch_states WHERE profile_id = :profileId ORDER BY last_meaningful_at_ms DESC")
    fun listByProfile(profileId: String): List<WatchStateEntity>

    @Query("SELECT * FROM watch_states WHERE profile_id = :profileId AND status = :status ORDER BY last_meaningful_at_ms DESC")
    fun listByStatus(profileId: String, status: String): List<WatchStateEntity>

    @Query("SELECT * FROM watch_states WHERE profile_id = :profileId AND watch_key = :watchKey")
    fun getFlow(profileId: String, watchKey: String): Flow<WatchStateEntity?>

    @Query("SELECT * FROM watch_states WHERE profile_id = :profileId ORDER BY last_meaningful_at_ms DESC")
    fun observeAll(profileId: String): Flow<List<WatchStateEntity>>

    @Query("DELETE FROM watch_states WHERE profile_id = :profileId AND watch_key = :watchKey")
    fun deleteByKey(profileId: String, watchKey: String)

    @Query("SELECT * FROM watch_states WHERE watch_key LIKE :prefix || '%'")
    fun listByKeyPrefix(prefix: String): List<WatchStateEntity>

    @Query("DELETE FROM watch_states WHERE profile_id = :profileId AND watch_key IN (:watchKeys)")
    fun deleteByKeys(profileId: String, watchKeys: List<String>): Int

    @Query("DELETE FROM watch_states WHERE profile_id = :profileId")
    fun deleteAllByProfile(profileId: String): Int

    @Query(
        "DELETE FROM watch_states WHERE profile_id = :fromProfileId AND watch_key IN " +
            "(SELECT watch_key FROM watch_states WHERE profile_id = :toProfileId)",
    )
    fun deleteCollidingStates(fromProfileId: String, toProfileId: String): Int

    @Query("UPDATE watch_states SET profile_id = :toProfileId WHERE profile_id = :fromProfileId")
    fun moveToProfile(fromProfileId: String, toProfileId: String): Int

    @Query(
        "UPDATE watch_states SET anchor_source_id = NULL, anchor_native_id = NULL, " +
            "anchor_family_id = NULL, anchor_version_id = NULL, anchor_position_ms = NULL, " +
            "anchor_anchored_at_ms = NULL WHERE anchor_source_id = :sourceId",
    )
    fun nullAnchorsForSource(sourceId: String): Int
}
