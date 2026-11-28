package dev.infusion.core.domain.tracking

import java.time.Instant

data class TrackingWatchedHistoryProviderCount(
    val items: Int? = null,
    val plays: Int? = null,
)

data class TrackingWatchedHistoryProviderCounts(
    val movies: TrackingWatchedHistoryProviderCount = TrackingWatchedHistoryProviderCount(),
    val episodes: TrackingWatchedHistoryProviderCount = TrackingWatchedHistoryProviderCount(),
    val observedAt: Instant = Instant.EPOCH,
) {
    fun forUnit(unit: TrackingWatchedHistoryUnit): TrackingWatchedHistoryProviderCount =
        when (unit) {
            TrackingWatchedHistoryUnit.MOVIE -> movies
            TrackingWatchedHistoryUnit.EPISODE -> episodes
        }
}

interface TrackingWatchedHistoryCoveragePort {

    val providerId: String

    suspend fun readProviderCounts(): TrackingInboundOutcome<TrackingWatchedHistoryProviderCounts>
}

data class TrackingWatchedHistoryCoverageRecord(
    val providerId: String,

    val profileId: String,

    val accountFingerprint: String?,
    val unit: TrackingWatchedHistoryUnit,

    val complete: Boolean,

    val walkCompleted: Boolean,

    val truncated: Boolean,

    val pages: Int,

    val rows: Int,

    val distinctPlays: Int,

    val distinctUnits: Int,

    val imported: Int,

    val providerItems: Int?,

    val providerPlays: Int?,

    val providerCountMovedDuringWalk: Boolean?,
    val malformed: Int,
    val unresolvedIdentity: Int,
    val undated: Int,
    val showLevelOnly: Int,
    val alreadyCovered: Int,

    val takenAt: Instant,

    val retrievalComplete: Boolean = false,

    val identityEvidenceComplete: Boolean = false,

    val materializationComplete: Boolean = false,

    val residue: List<TrackingWatchedHistoryResidueRecord> = emptyList(),

    val residueState: String = RESIDUE_ABSENT,

    val unresolvedDistinctItems: Int = 0,

    val seasonZero: Int = 0,

    val coordinateMissing: Int = 0,

    val coordinateInvalid: Int = 0,

    val coordinateNegative: Int = 0,

    val seasonZeroCounted: Boolean? = null,

    val countReconciled: Boolean? = null,

    val walkStartedAt: Instant? = null,

    val reasons: List<String> = emptyList(),

    val invalidatedAt: Instant? = null,
) {

    val isCompatibleBaselineFormat: Boolean
        get() = residueState != RESIDUE_ABSENT

    companion object {

        const val REASON_WALK_FAILED: String = "walk_failed"

        const val REASON_WALK_INCOMPLETE: String = "walk_incomplete"

        const val REASON_PROVIDER_COUNT_UNAVAILABLE: String = "provider_count_unavailable"

        const val REASON_PROVIDER_COUNT_MOVED: String = "provider_count_moved"

        const val REASON_MALFORMED_ROWS: String = "malformed_rows"

        const val REASON_UNRESOLVED_IDENTITY: String = "unresolved_identity"

        const val REASON_UNDATED_ROWS: String = "undated_rows"

        const val REASON_SHOW_LEVEL_ONLY: String = "show_level_only"

        const val REASON_COUNT_MISMATCH: String = "count_unreconciled"

        const val REASON_RESIDUE_TRUNCATED: String = "residue_truncated"

        const val REASON_RESIDUE_UNREADABLE: String = "residue_unreadable"

        const val REASON_RESIDUE_STALE: String = "residue_stale"

        const val REASON_COVERAGE_NOT_PERSISTED: String = "coverage_not_persisted"

        const val REASON_ACCOUNT_CHANGED: String = "account_changed"

        const val REASON_ACCOUNT_UNKNOWN: String = "account_unknown"

        const val MAX_RESIDUE_ALIAS_PAIRS: Int = 2_048

        const val RESIDUE_ABSENT: String = "absent"

        const val RESIDUE_TRUSTED: String = "trusted"

        const val RESIDUE_TRUNCATED: String = "truncated"

        const val RESIDUE_UNREADABLE: String = "unreadable"
    }
}

data class TrackingWatchedHistoryCoverageScope(
    val profileId: String,
    val accountFingerprint: String?,
)

interface TrackingWatchedHistoryCoverageStore {

    fun coverage(providerId: String, unit: TrackingWatchedHistoryUnit): TrackingWatchedHistoryCoverageRecord?

    fun save(record: TrackingWatchedHistoryCoverageRecord)

    fun invalidate(providerId: String, unit: TrackingWatchedHistoryUnit, reason: String, at: Instant)

    fun importProgress(providerId: String, unit: TrackingWatchedHistoryUnit): TrackingWatchedHistoryImportProgress?

    fun saveImportProgress(progress: TrackingWatchedHistoryImportProgress)

    fun clearImportProgress(providerId: String, unit: TrackingWatchedHistoryUnit)

    fun clear(providerId: String)
}

data class TrackingWatchedHistoryImportProgress(
    val providerId: String,
    val profileId: String,
    val accountFingerprint: String?,
    val unit: TrackingWatchedHistoryUnit,

    val startedAt: Instant,

    val sweeps: Int,
    val pages: Int,
    val rows: Int,
    val imported: Int,
    val distinctPlays: Int,
    val distinctUnits: Int,
    val malformed: Int,
    val unresolvedIdentity: Int,
    val undated: Int,
    val showLevelOnly: Int,
    val alreadyCovered: Int,

    val walkCompleted: Boolean,

    val truncated: Boolean,

    val providerItems: Int? = null,

    val providerPlays: Int? = null,

    val countsTakenAt: Instant? = null,

    val residue: List<TrackingWatchedHistoryResidueRecord> = emptyList(),

    val residueState: String = TrackingWatchedHistoryCoverageRecord.RESIDUE_ABSENT,

    val unresolvedDistinctItems: Int = 0,

    val unrepresentableDistinctItems: Int = 0,

    val seasonZero: Int = 0,
    val coordinateMissing: Int = 0,
    val coordinateInvalid: Int = 0,
    val coordinateNegative: Int = 0,

    val baselineEstablishment: Boolean = false,

    val baselineCursor: String? = null,
)

enum class TrackingWatchedHistoryBaselineState {

    MISSING,

    LEGACY,

    INTERRUPTED,

    COMPATIBLE,
    ;

    val requiresBaselineWalk: Boolean
        get() = this != COMPATIBLE
}

fun trackingWatchedHistoryBaselineState(
    record: TrackingWatchedHistoryCoverageRecord?,
    importOpen: Boolean,
): TrackingWatchedHistoryBaselineState = when {

    importOpen -> TrackingWatchedHistoryBaselineState.INTERRUPTED
    record == null -> TrackingWatchedHistoryBaselineState.MISSING
    !record.isCompatibleBaselineFormat -> TrackingWatchedHistoryBaselineState.LEGACY
    else -> TrackingWatchedHistoryBaselineState.COMPATIBLE
}
