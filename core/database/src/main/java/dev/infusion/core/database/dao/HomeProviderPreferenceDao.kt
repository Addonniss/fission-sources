package dev.infusion.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.infusion.core.database.entity.HomeProviderPreferenceEntity

@Dao
interface HomeProviderPreferenceDao {

    @Query(
        "SELECT * FROM home_provider_preferences " +
            "WHERE profile_id = :profileId ORDER BY sort_order ASC, addon_id ASC",
    )
    fun list(profileId: String): List<HomeProviderPreferenceEntity>

    @Query("DELETE FROM home_provider_preferences WHERE profile_id = :profileId")
    fun deleteForProfile(profileId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(preferences: List<HomeProviderPreferenceEntity>)
}
