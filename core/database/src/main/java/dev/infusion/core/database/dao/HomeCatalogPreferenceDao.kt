package dev.infusion.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.infusion.core.database.entity.HomeCatalogPreferenceEntity

@Dao
interface HomeCatalogPreferenceDao {

    @Query(
        "SELECT * FROM home_catalog_preferences " +
            "WHERE profile_id = :profileId ORDER BY sort_order ASC, addon_id ASC, catalog_type ASC, catalog_id ASC",
    )
    fun list(profileId: String): List<HomeCatalogPreferenceEntity>

    @Query("DELETE FROM home_catalog_preferences WHERE profile_id = :profileId")
    fun deleteForProfile(profileId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(preferences: List<HomeCatalogPreferenceEntity>)
}
