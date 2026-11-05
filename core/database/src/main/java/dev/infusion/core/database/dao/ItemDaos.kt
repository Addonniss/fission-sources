package dev.infusion.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Upsert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.infusion.core.database.entity.AvailabilityEntity
import dev.infusion.core.database.entity.PlayableCandidateEntity
import dev.infusion.core.database.entity.PlaybackAttemptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AvailabilityDao {
    @Upsert
    fun upsert(availability: AvailabilityEntity)

    @Upsert
    fun upsertAll(rows: List<AvailabilityEntity>)

    @Query("SELECT * FROM availability WHERE title_id = :titleId AND (edition_id IS NULL OR edition_id = :editionId)")
    fun listForTitle(titleId: String, editionId: String?): List<AvailabilityEntity>

    @Query("SELECT * FROM availability WHERE source_id = :sourceId")
    fun listBySource(sourceId: String): List<AvailabilityEntity>

    @Query("UPDATE availability SET title_id = :intoTitleId WHERE title_id = :fromTitleId")
    fun remapTitle(fromTitleId: String, intoTitleId: String)

    @Query("UPDATE availability SET title_id = :titleId WHERE availability_id IN (:availabilityIds)")
    fun remapTitleForRows(availabilityIds: List<String>, titleId: String)

    @Query("UPDATE availability SET edition_id = :intoEditionId WHERE edition_id = :fromEditionId")
    fun remapEdition(fromEditionId: String, intoEditionId: String)

    @Query("UPDATE availability SET edition_id = :editionId WHERE availability_id IN (:availabilityIds)")
    fun remapEditionForRows(availabilityIds: List<String>, editionId: String)

    @Query("DELETE FROM availability WHERE source_id = :sourceId")
    fun deleteBySource(sourceId: String): Int

    @Query(
        "DELETE FROM availability WHERE source_id = :sourceId AND kind = 'SERVER' " +
            "AND native_resource_id IN (:nativeResourceIds)",
    )
    fun deleteServerByNativeResourceIds(sourceId: String, nativeResourceIds: List<String>): Int

    @Query(
        "SELECT a.title_id AS titleId, t.kind AS kind, a.source_id AS sourceId " +
            "FROM availability a " +
            "JOIN titles t ON t.title_id = a.title_id " +
            "WHERE a.kind = 'SERVER'",
    )
    fun observeServerAvailability(): Flow<List<ServerAvailabilityRow>>

    @Query(
        "SELECT DISTINCT parent.title_id AS seriesTitleId, ep.source_id AS sourceId " +
            "FROM server_items ep " +
            "JOIN server_items parent ON parent.source_id = ep.source_id " +
            "AND parent.native_id = ep.native_parent_id " +
            "WHERE ep.native_type = 'Episode'",
    )
    fun observeSeriesWithEpisode(): Flow<List<ServerEpisodeSeriesRow>>

    @Query(
        "SELECT DISTINCT " +
            "  CASE WHEN parent.native_type = 'Season' THEN series_parent.title_id ELSE parent.title_id END AS seriesTitleId, " +
            "  a.source_id AS sourceId, t.season_number AS seasonNumber, t.episode_number AS episodeNumber " +
            "FROM availability a " +
            "JOIN titles t ON t.title_id = a.title_id " +
            "JOIN server_items ep ON ep.source_id = a.source_id AND ep.native_id = a.native_resource_id " +
            "JOIN server_items parent ON parent.source_id = ep.source_id AND parent.native_id = ep.native_parent_id " +
            "LEFT JOIN server_items series_parent ON series_parent.source_id = parent.source_id " +
            "  AND series_parent.native_id = parent.native_parent_id " +
            "WHERE a.kind = 'SERVER' AND t.kind = 'EPISODE' " +
            "  AND t.season_number IS NOT NULL AND t.episode_number IS NOT NULL",
    )
    fun observeServerEpisodeCoordinates(): Flow<List<ServerEpisodeCoordinateRow>>

    @Query(
        "SELECT title_id AS titleId FROM titles " +
            "WHERE superseded_by_title_id IN (:titleIds) AND superseded_by_title_id IS NOT NULL",
    )
    suspend fun supersededTitleIds(titleIds: List<String>): List<AliasLinkedTitleRow>
}

data class ServerAvailabilityRow(
    val titleId: String,
    val kind: String,
    val sourceId: String,
)

data class ServerEpisodeSeriesRow(
    val seriesTitleId: String,
    val sourceId: String,
)

data class ServerEpisodeCoordinateRow(
    val seriesTitleId: String,
    val sourceId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
)

data class AliasLinkedTitleRow(
    val titleId: String,
)

@Dao
interface PlayableCandidateDao {
    @Upsert
    fun upsert(candidate: PlayableCandidateEntity)

    @Query("SELECT * FROM playable_candidates WHERE candidate_id = :candidateId")
    fun getById(candidateId: String): PlayableCandidateEntity?

    @Query("SELECT * FROM playable_candidates WHERE title_id = :titleId ORDER BY created_at_ms DESC")
    fun listByTitle(titleId: String): List<PlayableCandidateEntity>

    @Query("SELECT * FROM playable_candidates WHERE source_id = :sourceId")
    fun listBySource(sourceId: String): List<PlayableCandidateEntity>

    @Query("UPDATE playable_candidates SET title_id = :intoTitleId WHERE title_id = :fromTitleId")
    fun remapTitle(fromTitleId: String, intoTitleId: String)

    @Query("UPDATE playable_candidates SET title_id = :titleId WHERE candidate_id IN (:candidateIds)")
    fun remapTitleForRows(candidateIds: List<String>, titleId: String)

    @Query("UPDATE playable_candidates SET edition_id = :intoEditionId WHERE edition_id = :fromEditionId")
    fun remapEdition(fromEditionId: String, intoEditionId: String)

    @Query("UPDATE playable_candidates SET edition_id = :editionId WHERE candidate_id IN (:candidateIds)")
    fun remapEditionForRows(candidateIds: List<String>, editionId: String)

    @Query("DELETE FROM playable_candidates WHERE expires_at_ms IS NOT NULL AND expires_at_ms < :nowMs")
    fun deleteExpired(nowMs: Long): Int

    @Query("DELETE FROM playable_candidates WHERE source_id = :sourceId")
    fun deleteBySource(sourceId: String): Int
}

@Dao
interface PlaybackAttemptDao {
    @Upsert
    fun upsert(attempt: PlaybackAttemptEntity)

    @Query("SELECT * FROM playback_attempts WHERE attempt_id = :attemptId")
    fun getById(attemptId: String): PlaybackAttemptEntity?

    @Query("SELECT * FROM playback_attempts WHERE profile_id = :profileId ORDER BY started_at_ms DESC LIMIT :limit")
    fun listRecent(profileId: String, limit: Int): List<PlaybackAttemptEntity>

    @Query("DELETE FROM playback_attempts WHERE profile_id = :profileId AND started_at_ms < :beforeMs")
    fun deleteOlderThan(profileId: String, beforeMs: Long): Int
}
