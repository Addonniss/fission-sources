package dev.infusion.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.infusion.core.database.entity.CandidateHandoffEntity

@Dao
interface CandidateHandoffDao {
    @Upsert
    fun upsert(row: CandidateHandoffEntity)

    @Query("SELECT * FROM candidate_handoff WHERE candidate_id = :candidateId")
    fun getById(candidateId: String): CandidateHandoffEntity?

    @Query("SELECT * FROM candidate_handoff")
    fun all(): List<CandidateHandoffEntity>

    @Query("DELETE FROM candidate_handoff WHERE candidate_id = :candidateId")
    fun deleteById(candidateId: String): Int

    @Query("DELETE FROM candidate_handoff")
    fun deleteAll(): Int
}
