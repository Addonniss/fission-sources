package dev.infusion.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.infusion.core.database.entity.JellyfinSourceStateEntity

@Dao
interface JellyfinSourceStateDao {
    @Upsert
    fun upsert(state: JellyfinSourceStateEntity)

    @Query("SELECT * FROM jellyfin_source_state LIMIT 1")
    fun getActive(): JellyfinSourceStateEntity?

    @Query("SELECT * FROM jellyfin_source_state WHERE source_id = :sourceId")
    fun getById(sourceId: String): JellyfinSourceStateEntity?

    @Query("DELETE FROM jellyfin_source_state WHERE source_id = :sourceId")
    fun delete(sourceId: String): Int
}
