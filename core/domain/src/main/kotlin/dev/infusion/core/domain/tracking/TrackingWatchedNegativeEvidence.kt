package dev.infusion.core.domain.tracking

import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.watch.WatchKey
import java.time.Instant

data class TrackingWatchedNegativeEvidence(

    val unit: TrackingWatchedHistoryUnit,

    val resolved: Boolean,

    val reason: String,

    val coverageTakenAt: Instant? = null,

    val coverageWalkStartedAt: Instant? = null,

    val consumedThrough: Instant? = null,

    val consumedBy: String? = null,
) {
    companion object {

        const val REASON_PROVEN: String = "proven"

        const val REASON_COVERAGE_ABSENT: String = "coverage_absent"

        const val REASON_COVERAGE_INVALIDATED: String = "coverage_invalidated"

        const val REASON_PROFILE_MISMATCH: String = "profile_mismatch"

        const val REASON_ACCOUNT_UNKNOWN: String = "account_unknown"

        const val REASON_ACCOUNT_CHANGED: String = "account_changed"

        const val REASON_WALK_INCOMPLETE: String = "walk_incomplete"

        const val REASON_COVERAGE_TRUNCATED: String = "coverage_truncated"

        const val REASON_COVERAGE_ANOMALY: String = "coverage_anomaly"

        const val REASON_COVERAGE_METRICS: String = "coverage_metrics_unproven"

        const val REASON_COUNT_UNRECONCILED: String = "count_unreconciled"

        const val REASON_RESIDUE_UNAVAILABLE: String = "residue_unavailable"

        const val REASON_RESIDUE_TRUNCATED: String = "residue_truncated"

        const val REASON_COVERAGE_INCOMPLETE: String = "coverage_incomplete"

        const val REASON_IDENTITY_EVIDENCE_INCOMPLETE: String = "identity_evidence_incomplete"

        const val REASON_IDENTITY_UNSTATED: String = "identity_unstated"

        const val REASON_RESIDUE_MATCH: String = "residue_match"

        const val REASON_RESIDUE_UNKNOWN: String = "residue_unknown"

        const val REASON_CURRENCY_ABSENT: String = "currency_absent"

        const val REASON_CURRENCY_UNACCEPTED: String = "currency_unaccepted"

        const val REASON_CURRENCY_STALE: String = "currency_stale"

        const val CONSUMED_BY_CHANGE_FEED: String = "change_feed"

        const val CONSUMED_BY_HISTORY_SWEEP: String = "history_sweep"
    }
}

data class TrackingWatchedIncrementalEvidence(

    val consumedThrough: Instant?,

    val accountFingerprint: String? = null,

    val accepted: Boolean,

    val source: String,
)

interface TrackingWatchedNegativeEvidencePort {

    val providerId: String

    fun negativeEvidenceFor(
        unit: TrackingWatchedHistoryUnit,
        ids: TrackingExternalIds,
        key: WatchKey? = null,
    ): TrackingWatchedNegativeEvidence

    fun stillAuthorises(
        unit: TrackingWatchedHistoryUnit,
        ids: TrackingExternalIds,
        key: WatchKey?,
        coverageTakenAt: Instant?,
    ): Boolean = true
}

interface TrackingInboundAliasTablePort {

    fun titleIdsFor(ids: TrackingExternalIds): Set<TitleId>

    fun aliasesOf(titleId: TitleId): List<TypedExternalId>
}
