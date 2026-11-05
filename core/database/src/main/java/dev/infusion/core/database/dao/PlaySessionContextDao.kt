package dev.infusion.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.infusion.core.database.entity.PlaySessionContextEntity

@Dao
interface PlaySessionContextDao {
    @Upsert
    fun upsert(context: PlaySessionContextEntity)

    @Query("SELECT * FROM play_session_contexts WHERE source_id = :sourceId AND watch_session_id = :watchSessionId")
    fun get(sourceId: String, watchSessionId: String): PlaySessionContextEntity?

    @Query("SELECT * FROM play_session_contexts WHERE source_id = :sourceId ORDER BY created_at_ms")
    fun active(sourceId: String): List<PlaySessionContextEntity>

    @Query("DELETE FROM play_session_contexts WHERE source_id = :sourceId AND watch_session_id = :watchSessionId")
    fun remove(sourceId: String, watchSessionId: String): Int

    @Query("DELETE FROM play_session_contexts WHERE source_id = :sourceId")
    fun deleteBySource(sourceId: String): Int
}
