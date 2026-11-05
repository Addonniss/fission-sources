package dev.infusion.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.infusion.core.database.entity.IdentityMappingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IdentityMappingDao {

    @Upsert
    fun put(mapping: IdentityMappingEntity)

    @Query("SELECT * FROM identity_mappings WHERE authority = :authority AND external_value = :externalValue ORDER BY created_at_ms ASC")
    fun byExternal(authority: String, externalValue: String): List<IdentityMappingEntity>

    @Query("SELECT * FROM identity_mappings WHERE authority = :authority AND external_value = :externalValue ORDER BY created_at_ms ASC")
    fun observeByExternal(authority: String, externalValue: String): Flow<List<IdentityMappingEntity>>

    @Query("SELECT * FROM identity_mappings WHERE canonical_title_id = :titleId ORDER BY created_at_ms ASC")
    fun byTitle(titleId: String): List<IdentityMappingEntity>

    @Query(
        "SELECT * FROM identity_mappings WHERE authority = :authority AND external_value = :externalValue " +
            "AND status = 'ACTIVE' ORDER BY created_at_ms DESC LIMIT 1",
    )
    fun activeByExternal(authority: String, externalValue: String): IdentityMappingEntity?

    @Query(
        "SELECT * FROM identity_mappings WHERE authority = :authority AND external_value IN (:externalValues) " +
            "AND status = 'ACTIVE' ORDER BY created_at_ms DESC",
    )
    fun activeByAuthorityAndExternals(authority: String, externalValues: List<String>): List<IdentityMappingEntity>

    @Query("SELECT * FROM identity_mappings ORDER BY created_at_ms ASC")
    fun all(): List<IdentityMappingEntity>

    @Query("UPDATE identity_mappings SET canonical_title_id = :intoTitleId WHERE canonical_title_id = :fromTitleId")
    fun remapTitle(fromTitleId: String, intoTitleId: String): Int

    @Query("DELETE FROM identity_mappings WHERE mapping_id = :mappingId")
    fun delete(mappingId: String)
}
