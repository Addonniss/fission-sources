package dev.infusion.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.infusion.core.database.model.CapabilityState
import dev.infusion.core.database.model.ExpectedMode
import dev.infusion.core.database.model.ReachabilityState

@Entity(
    tableName = "playable_candidates",
    foreignKeys = [
        ForeignKey(
            entity = TitleEntity::class,
            parentColumns = ["title_id"],
            childColumns = ["title_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = EditionEntity::class,
            parentColumns = ["edition_id"],
            childColumns = ["edition_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["source_id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["title_id"]),
        Index(value = ["edition_id"]),
        Index(value = ["source_id"]),
        Index(value = ["expires_at_ms"]),
    ],
)
data class PlayableCandidateEntity(
    @PrimaryKey @ColumnInfo(name = "candidate_id") val candidateId: String,
    @ColumnInfo(name = "title_id") val titleId: String,
    @ColumnInfo(name = "edition_id") val editionId: String?,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "native_item_id") val nativeItemId: String?,

    @ColumnInfo(name = "original_provider_label") val originalProviderLabel: String?,
    @ColumnInfo(name = "normalized_facts_json") val normalizedFactsJson: String?,
    @ColumnInfo(name = "expected_mode") val expectedMode: ExpectedMode,
    @ColumnInfo(name = "compatibility_state") val compatibilityState: CapabilityState,
    @ColumnInfo(name = "compatibility_reasons_json") val compatibilityReasonsJson: String?,
    @ColumnInfo(name = "security_policy_result_json") val securityPolicyResultJson: String?,
    @ColumnInfo(name = "ranking_explanation_json") val rankingExplanationJson: String?,
    @ColumnInfo(name = "transport_ref") val transportRef: String?,
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long?,
    @ColumnInfo(name = "reachability_state") val reachabilityState: ReachabilityState,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)
