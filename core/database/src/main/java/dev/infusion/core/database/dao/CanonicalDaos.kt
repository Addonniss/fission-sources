package dev.infusion.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Upsert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.infusion.core.database.entity.EditionEntity
import dev.infusion.core.database.entity.ExternalIdEntity
import dev.infusion.core.database.entity.ProfileEntity
import dev.infusion.core.database.entity.TitleEntity
import dev.infusion.core.database.entity.TitleLinkEntity

@Dao
interface ProfileDao {
    @Upsert
    fun upsert(profile: ProfileEntity)

    @Query("SELECT * FROM profiles WHERE profile_id = :profileId")
    fun getById(profileId: String): ProfileEntity?

    @Query("SELECT * FROM profiles ORDER BY profile_index ASC")
    fun list(): List<ProfileEntity>

    @Query("SELECT COUNT(*) FROM profiles")
    fun count(): Int

    @Query("SELECT * FROM profiles WHERE is_default = 1 LIMIT 1")
    fun getDefault(): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE profile_index = 1 LIMIT 1")
    fun getPrimaryByIndex(): ProfileEntity?

    @Query("UPDATE profiles SET name = :name WHERE profile_id = :profileId")
    fun rename(profileId: String, name: String): Int

    @Query("UPDATE profiles SET is_default = 1 WHERE profile_id = :profileId")
    fun promoteToDefault(profileId: String): Int

    @Query(
        "UPDATE profiles SET pin_hash = :pinHash, failed_pin_attempts = 0, pin_locked_until_ms = NULL " +
            "WHERE profile_id = :profileId",
    )
    fun setPin(profileId: String, pinHash: String): Int

    @Query("UPDATE profiles SET pin_hash = NULL, failed_pin_attempts = 0, pin_locked_until_ms = NULL WHERE profile_id = :profileId")
    fun clearPin(profileId: String): Int

    @Query(
        "UPDATE profiles SET failed_pin_attempts = :attempts, pin_locked_until_ms = :lockedUntilMs " +
            "WHERE profile_id = :profileId",
    )
    fun recordFailedPinAttempt(profileId: String, attempts: Int, lockedUntilMs: Long?): Int

    @Query("UPDATE profiles SET pin_locked_until_ms = :lockedUntilMs WHERE profile_id = :profileId")
    fun clampPinLockout(profileId: String, lockedUntilMs: Long): Int

    @Query("UPDATE profiles SET failed_pin_attempts = 0, pin_locked_until_ms = NULL WHERE profile_id = :profileId")
    fun resetFailedPinAttempts(profileId: String): Int

    @Query("DELETE FROM profiles WHERE profile_id = :profileId")
    fun delete(profileId: String)
}

@Dao
interface TitleDao {
    @Upsert
    fun upsert(title: TitleEntity)

    @Query("SELECT * FROM titles WHERE title_id = :titleId")
    fun getById(titleId: String): TitleEntity?

    @Query("SELECT * FROM titles WHERE title_id IN (:titleIds)")
    fun listByIds(titleIds: List<String>): List<TitleEntity>

    @Query("SELECT * FROM titles WHERE superseded_by_title_id IS NULL ORDER BY primary_title COLLATE NOCASE")
    fun listCanonical(): List<TitleEntity>

    @Query("SELECT * FROM titles ORDER BY primary_title COLLATE NOCASE")
    fun listAll(): List<TitleEntity>

    @Query("SELECT * FROM titles WHERE kind = :kind ORDER BY primary_title COLLATE NOCASE")
    fun listByKind(kind: String): List<TitleEntity>

    @Query("SELECT t.* FROM titles t JOIN external_ids e ON e.title_id = t.title_id " +
        "WHERE e.authority = :authority AND e.native_id = :nativeId")
    fun getByExternalId(authority: String, nativeId: String): TitleEntity?

    @Query("UPDATE titles SET superseded_by_title_id = :survivorTitleId WHERE title_id = :loserTitleId AND superseded_by_title_id IS NULL")
    fun markSuperseded(loserTitleId: String, survivorTitleId: String): Int

    @Query("DELETE FROM titles WHERE title_id = :titleId")
    fun delete(titleId: String)
}

@Dao
interface TitleLinkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(link: TitleLinkEntity)

    @Query("SELECT * FROM title_links WHERE parent_title_id = :parentTitleId ORDER BY relation, child_title_id")
    fun listChildren(parentTitleId: String): List<TitleLinkEntity>

    @Query("SELECT * FROM title_links WHERE parent_title_id = :titleId OR child_title_id = :titleId")
    fun listForTitle(titleId: String): List<TitleLinkEntity>

    @Query("DELETE FROM title_links WHERE parent_title_id = :parentTitleId AND child_title_id = :childTitleId")
    fun deleteLink(parentTitleId: String, childTitleId: String)

    @Query("DELETE FROM title_links WHERE parent_title_id = :titleId OR child_title_id = :titleId")
    fun deleteForTitle(titleId: String)

    @Query("UPDATE title_links SET parent_title_id = :intoTitleId WHERE parent_title_id = :fromTitleId")
    fun remapParent(fromTitleId: String, intoTitleId: String)

    @Query("UPDATE title_links SET child_title_id = :intoTitleId WHERE child_title_id = :fromTitleId")
    fun remapChild(fromTitleId: String, intoTitleId: String)

    @Query("DELETE FROM title_links WHERE parent_title_id = child_title_id")
    fun deleteSelfLoops()

    @Query("SELECT * FROM title_links WHERE parent_title_id = :parentTitleId AND child_title_id = :childTitleId")
    fun get(parentTitleId: String, childTitleId: String): TitleLinkEntity?
}

@Dao
interface EditionDao {
    @Upsert
    fun upsert(edition: EditionEntity)

    @Query("SELECT * FROM editions WHERE edition_id = :editionId")
    fun getById(editionId: String): EditionEntity?

    @Query("SELECT * FROM editions WHERE title_id = :titleId ORDER BY created_at_ms ASC")
    fun listByTitle(titleId: String): List<EditionEntity>

    @Query("UPDATE editions SET title_id = :intoTitleId WHERE title_id = :fromTitleId")
    fun remapTitle(fromTitleId: String, intoTitleId: String)

    @Query("UPDATE editions SET title_id = :titleId WHERE edition_id IN (:editionIds)")
    fun remapTitleForEditions(editionIds: List<String>, titleId: String)

    @Query("DELETE FROM editions WHERE edition_id = :editionId")
    fun delete(editionId: String)
}

@Dao
interface ExternalIdDao {
    @Upsert
    fun upsert(externalId: ExternalIdEntity)

    @Query("SELECT * FROM external_ids WHERE authority = :authority AND native_id = :nativeId")
    fun get(authority: String, nativeId: String): ExternalIdEntity?

    @Query("SELECT * FROM external_ids WHERE title_id = :titleId")
    fun listByTitle(titleId: String): List<ExternalIdEntity>

    @Query("SELECT * FROM external_ids WHERE authority = :authority AND native_id IN (:nativeIds)")
    fun listByAuthorityAndNativeIds(authority: String, nativeIds: List<String>): List<ExternalIdEntity>

    @Query("SELECT * FROM external_ids WHERE title_id IN (:titleIds)")
    fun listByTitleIds(titleIds: List<String>): List<ExternalIdEntity>

    @Query("SELECT * FROM external_ids WHERE edition_id = :editionId")
    fun listByEdition(editionId: String): List<ExternalIdEntity>

    @Query("UPDATE external_ids SET title_id = :intoTitleId WHERE title_id = :fromTitleId")
    fun remapTitle(fromTitleId: String, intoTitleId: String)

    @Query("UPDATE external_ids SET edition_id = :intoEditionId WHERE edition_id = :fromEditionId")
    fun remapEdition(fromEditionId: String, intoEditionId: String)

    @Query("UPDATE external_ids SET title_id = :titleId WHERE authority = :authority AND native_id = :nativeId")
    fun remapPairToTitle(authority: String, nativeId: String, titleId: String)

    @Query("UPDATE external_ids SET edition_id = :editionId WHERE authority = :authority AND native_id = :nativeId")
    fun remapPairToEdition(authority: String, nativeId: String, editionId: String)
}
