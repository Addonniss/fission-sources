package dev.infusion.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.infusion.core.database.entity.RetirementIntentEntity

@Dao
interface RetirementIntentDao {
    @Upsert
    fun upsert(intent: RetirementIntentEntity)

    @Query("SELECT * FROM retirement_intents WHERE source_id = :sourceId")
    fun getBySource(sourceId: String): RetirementIntentEntity?

    @Query("SELECT source_id FROM retirement_intents ORDER BY created_at_ms ASC")
    fun listPendingSourceIds(): List<String>

    @Query("SELECT COUNT(*) FROM retirement_intents")
    fun count(): Int

    @Query("DELETE FROM retirement_intents WHERE source_id = :sourceId")
    fun deleteBySource(sourceId: String): Int

    @Query("SELECT COUNT(*) FROM retirement_intents WHERE source_id = :sourceId")
    fun countBySource(sourceId: String): Int
}
