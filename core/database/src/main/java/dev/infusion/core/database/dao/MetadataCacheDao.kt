package dev.infusion.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.infusion.core.database.entity.MetadataCacheEntity

@Dao
interface MetadataCacheDao {

    @Query("SELECT * FROM metadata_cache WHERE namespace = :namespace AND cache_key = :cacheKey")
    fun get(namespace: String, cacheKey: String): MetadataCacheEntity?

    @Upsert
    fun upsert(entry: MetadataCacheEntity)

    @Query("DELETE FROM metadata_cache WHERE namespace = :namespace AND cache_key = :cacheKey")
    fun delete(namespace: String, cacheKey: String)

    @Query("DELETE FROM metadata_cache WHERE namespace = :namespace")
    fun deleteNamespace(namespace: String)

    @Query("DELETE FROM metadata_cache WHERE stored_at_ms <= :cutoffMs")
    fun deleteOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM metadata_cache WHERE payload LIKE '%' || :titleId || '%'")
    fun deleteReferencingTitle(titleId: String): Int

    @Query("SELECT COUNT(*) FROM metadata_cache")
    fun count(): Int
}
