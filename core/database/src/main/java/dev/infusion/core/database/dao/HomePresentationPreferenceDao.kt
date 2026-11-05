package dev.infusion.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.infusion.core.database.entity.HomePresentationPreferenceEntity

@Dao
interface HomePresentationPreferenceDao {

    @Query("SELECT * FROM home_presentation_preferences WHERE profile_id = :profileId")
    fun get(profileId: String): HomePresentationPreferenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(preference: HomePresentationPreferenceEntity)
}
