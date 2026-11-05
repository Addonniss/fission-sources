package dev.infusion.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Upsert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.infusion.core.database.entity.SecretReferenceEntity
import dev.infusion.core.database.entity.SourceEntity
import dev.infusion.core.database.model.SecretKind

@Dao
interface SourceDao {
    @Upsert
    fun upsert(source: SourceEntity)

    @Query("SELECT * FROM sources WHERE source_id = :sourceId")
    fun getById(sourceId: String): SourceEntity?

    @Query("SELECT * FROM sources WHERE owner_profile_id = :profileId AND removed_at_ms IS NULL ORDER BY display_name COLLATE NOCASE")
    fun listActive(profileId: String): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE removed_at_ms IS NULL")
    fun listAllActive(): List<SourceEntity>

    @Query("SELECT source_id FROM sources WHERE removed_at_ms IS NOT NULL")
    fun listRemovedSourceIds(): List<String>

    @Query("UPDATE sources SET enabled = :enabled WHERE source_id = :sourceId")
    fun setEnabled(sourceId: String, enabled: Boolean)

    @Query("UPDATE sources SET display_name = :displayName WHERE source_id = :sourceId")
    fun setDisplayName(sourceId: String, displayName: String)

    @Query("UPDATE sources SET health_state = :healthState, last_health_check_ms = :checkedAtMs WHERE source_id = :sourceId")
    fun updateHealth(sourceId: String, healthState: String, checkedAtMs: Long)

    @Query("UPDATE sources SET removed_at_ms = :removedAtMs, enabled = 0 WHERE source_id = :sourceId")
    fun tombstone(sourceId: String, removedAtMs: Long)

    @Query("DELETE FROM sources WHERE source_id = :sourceId")
    fun hardDelete(sourceId: String)

    @Query("DELETE FROM sources WHERE owner_profile_id = :profileId")
    fun hardDeleteByOwner(profileId: String): Int

    @Query("SELECT source_id FROM sources WHERE owner_profile_id = :profileId")
    fun listIdsByOwner(profileId: String): List<String>
}

@Dao
interface SecretReferenceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(reference: SecretReferenceEntity)

    @Query("SELECT * FROM secret_references WHERE source_id = :sourceId ORDER BY created_at_ms")
    fun listBySource(sourceId: String): List<SecretReferenceEntity>

    @Query("SELECT * FROM secret_references WHERE source_id = :sourceId AND revoked_at_ms IS NULL")
    fun listActiveBySource(sourceId: String): List<SecretReferenceEntity>

    @Query("SELECT * FROM secret_references WHERE key_alias = :keyAlias")
    fun getByAlias(keyAlias: String): SecretReferenceEntity?

    @Query("UPDATE secret_references SET revoked_at_ms = :revokedAtMs WHERE source_id = :sourceId AND revoked_at_ms IS NULL")
    fun revokeAllForSource(sourceId: String, revokedAtMs: Long): Int

    @Query("DELETE FROM secret_references WHERE source_id = :sourceId")
    fun hardDeleteBySource(sourceId: String)

    @Query("DELETE FROM secret_references WHERE key_alias = :keyAlias")
    fun hardDeleteByAlias(keyAlias: String): Int

    @Query("DELETE FROM secret_references WHERE source_id = :sourceId AND revoked_at_ms IS NOT NULL")
    fun deleteRevokedBySource(sourceId: String)

    @Query(
        "DELETE FROM secret_references WHERE source_id = :sourceId AND kind = :kind " +
            "AND ifnull(scope, '') = ifnull(:scope, '')",
    )
    fun hardDeleteBySourceKindScope(sourceId: String, kind: SecretKind, scope: String?): Int

    @Query("SELECT COUNT(*) FROM secret_references WHERE source_id = :sourceId AND revoked_at_ms IS NULL")
    fun countActive(sourceId: String): Int
}
