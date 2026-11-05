package dev.infusion.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Upsert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.infusion.core.database.entity.LocalAssetEntity
import dev.infusion.core.database.entity.ServerItemEntity
import dev.infusion.core.database.entity.ServerMediaVersionEntity

@Dao
interface ServerItemDao {
    @Upsert
    fun upsert(item: ServerItemEntity)

    @Query("SELECT * FROM server_items WHERE source_id = :sourceId AND native_id = :nativeId")
    fun getByNativeId(sourceId: String, nativeId: String): ServerItemEntity?

    @Query("SELECT * FROM server_items WHERE server_item_id = :serverItemId")
    fun getById(serverItemId: String): ServerItemEntity?

    @Query("SELECT * FROM server_items WHERE source_id = :sourceId ORDER BY created_at_ms")
    fun listBySource(sourceId: String): List<ServerItemEntity>

    @Query("SELECT * FROM server_items WHERE title_id = :titleId")
    fun listByTitle(titleId: String): List<ServerItemEntity>

    @Query("UPDATE server_items SET title_id = :intoTitleId WHERE title_id = :fromTitleId")
    fun remapTitle(fromTitleId: String, intoTitleId: String)

    @Query("UPDATE server_items SET title_id = :titleId WHERE server_item_id IN (:serverItemIds)")
    fun remapTitleForItems(serverItemIds: List<String>, titleId: String)

    @Query("UPDATE server_items SET edition_id = :intoEditionId WHERE edition_id = :fromEditionId")
    fun remapEdition(fromEditionId: String, intoEditionId: String)

    @Query("UPDATE server_items SET edition_id = :editionId WHERE server_item_id IN (:serverItemIds)")
    fun remapEditionForItems(serverItemIds: List<String>, editionId: String)

    @Query("DELETE FROM server_items WHERE source_id = :sourceId")
    fun deleteBySource(sourceId: String): Int

    @Query("DELETE FROM server_items WHERE source_id = :sourceId AND library_id = :libraryId")
    fun deleteByLibrary(sourceId: String, libraryId: String): Int

    @Query("DELETE FROM server_items WHERE source_id = :sourceId AND native_id = :nativeId")
    fun deleteByNativeId(sourceId: String, nativeId: String): Int

    @Query("DELETE FROM server_items WHERE source_id = :sourceId AND native_id IN (:nativeIds)")
    fun deleteByNativeIds(sourceId: String, nativeIds: List<String>): Int

    @Query(
        "SELECT native_id FROM server_items WHERE source_id = :sourceId " +
            "AND library_id = :libraryId AND native_id NOT IN (:retainedNativeIds)",
    )
    fun listNativeIdsMissing(sourceId: String, libraryId: String, retainedNativeIds: List<String>): List<String>

    @Query("SELECT native_id FROM server_items WHERE source_id = :sourceId AND library_id = :libraryId")
    fun listNativeIdsByLibrary(sourceId: String, libraryId: String): List<String>

    @Query("DELETE FROM server_items WHERE source_id = :sourceId AND library_id = :libraryId AND native_id NOT IN (:retainedNativeIds)")
    fun deleteMissingByLibrary(sourceId: String, libraryId: String, retainedNativeIds: List<String>): Int

    @Query("SELECT COUNT(*) FROM server_items WHERE source_id = :sourceId")
    fun countBySource(sourceId: String): Int
}

@Dao
interface ServerMediaVersionDao {
    @Upsert
    fun upsert(version: ServerMediaVersionEntity)

    @Query("SELECT * FROM server_media_versions WHERE server_item_id = :serverItemId ORDER BY created_at_ms")
    fun listByServerItem(serverItemId: String): List<ServerMediaVersionEntity>
}

@Dao
interface LocalAssetDao {
    @Upsert
    fun upsert(asset: LocalAssetEntity)

    @Query("SELECT * FROM local_assets WHERE source_id = :sourceId AND uri_identity = :uriIdentity")
    fun getByUri(sourceId: String, uriIdentity: String): LocalAssetEntity?

    @Query("SELECT * FROM local_assets WHERE source_id = :sourceId ORDER BY created_at_ms")
    fun listBySource(sourceId: String): List<LocalAssetEntity>

    @Query("SELECT * FROM local_assets WHERE title_id = :titleId")
    fun listByTitle(titleId: String): List<LocalAssetEntity>

    @Query("UPDATE local_assets SET title_id = :intoTitleId WHERE title_id = :fromTitleId")
    fun remapTitle(fromTitleId: String, intoTitleId: String)

    @Query("UPDATE local_assets SET title_id = :titleId WHERE local_asset_id IN (:assetIds)")
    fun remapTitleForAssets(assetIds: List<String>, titleId: String)

    @Query("UPDATE local_assets SET edition_id = :intoEditionId WHERE edition_id = :fromEditionId")
    fun remapEdition(fromEditionId: String, intoEditionId: String)

    @Query("UPDATE local_assets SET edition_id = :editionId WHERE local_asset_id IN (:assetIds)")
    fun remapEditionForAssets(assetIds: List<String>, editionId: String)

    @Query("UPDATE local_assets SET access_state = :accessState, last_checked_ms = :checkedAtMs WHERE local_asset_id = :localAssetId")
    fun updateAccessState(localAssetId: String, accessState: String, checkedAtMs: Long)

    @Query("DELETE FROM local_assets WHERE source_id = :sourceId")
    fun deleteBySource(sourceId: String): Int
}
