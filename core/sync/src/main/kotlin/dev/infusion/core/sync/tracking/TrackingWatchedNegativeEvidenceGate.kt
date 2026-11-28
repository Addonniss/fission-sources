package dev.infusion.core.sync.tracking

import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingInboundAliasTablePort
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryCoverageRecord
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryCoverageStore
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryUnit
import dev.infusion.core.domain.tracking.TrackingWatchedIncrementalEvidence
import dev.infusion.core.domain.tracking.TrackingWatchedNegativeEvidence
import dev.infusion.core.domain.tracking.TrackingWatchedNegativeEvidencePort
import dev.infusion.core.model.watch.WatchKey
import java.time.Instant

class TrackingWatchedNegativeEvidenceGate(

    override val providerId: String,

    private val coverageStore: TrackingWatchedHistoryCoverageStore,

    private val incremental: (TrackingWatchedHistoryUnit) -> TrackingWatchedIncrementalEvidence?,

    private val activeProfileId: () -> String?,

    private val accountFingerprint: () -> String?,

    private val aliasTable: TrackingInboundAliasTablePort? = null,
) : TrackingWatchedNegativeEvidencePort {

    override fun negativeEvidenceFor(
        unit: TrackingWatchedHistoryUnit,
        ids: TrackingExternalIds,
        key: WatchKey?,
    ): TrackingWatchedNegativeEvidence {

        val record = runCatching { coverageStore.coverage(providerId, unit) }.getOrNull()
            ?: return refusal(unit, TrackingWatchedNegativeEvidence.REASON_COVERAGE_ABSENT)
        if (record.invalidatedAt != null) {
            return refusal(unit, TrackingWatchedNegativeEvidence.REASON_COVERAGE_INVALIDATED, record)
        }

        val profileNow = runCatching { activeProfileId() }.getOrNull()
        if (profileNow == null || record.profileId != profileNow) {
            return refusal(unit, TrackingWatchedNegativeEvidence.REASON_PROFILE_MISMATCH, record)
        }

        val accountNow = runCatching { accountFingerprint() }.getOrNull()
        if (record.accountFingerprint == null) {
            return refusal(unit, TrackingWatchedNegativeEvidence.REASON_ACCOUNT_UNKNOWN, record)
        }
        if (accountNow == null || record.accountFingerprint != accountNow) {
            return refusal(unit, TrackingWatchedNegativeEvidence.REASON_ACCOUNT_CHANGED, record)
        }

        if (!record.walkCompleted) {
            return refusal(unit, TrackingWatchedNegativeEvidence.REASON_WALK_INCOMPLETE, record)
        }
        if (record.truncated) {
            return refusal(unit, TrackingWatchedNegativeEvidence.REASON_COVERAGE_TRUNCATED, record)
        }
        if (record.providerItems == null || record.providerCountMovedDuringWalk != false) {
            return refusal(unit, TrackingWatchedNegativeEvidence.REASON_COVERAGE_METRICS, record)
        }

        if (record.countReconciled != true) {
            return refusal(unit, TrackingWatchedNegativeEvidence.REASON_COUNT_UNRECONCILED, record)
        }
        if (!record.retrievalComplete) {
            return refusal(unit, TrackingWatchedNegativeEvidence.REASON_COVERAGE_INCOMPLETE, record)
        }

        if (record.malformed > 0) {
            return refusal(unit, TrackingWatchedNegativeEvidence.REASON_COVERAGE_ANOMALY, record)
        }
        if (record.residueState == TrackingWatchedHistoryCoverageRecord.RESIDUE_TRUNCATED) {

            return refusal(unit, TrackingWatchedNegativeEvidence.REASON_RESIDUE_TRUNCATED, record)
        }
        if (record.residueState != TrackingWatchedHistoryCoverageRecord.RESIDUE_TRUSTED) {

            return refusal(unit, TrackingWatchedNegativeEvidence.REASON_RESIDUE_UNAVAILABLE, record)
        }
        if (!record.identityEvidenceComplete) {
            return refusal(
                unit,
                TrackingWatchedNegativeEvidence.REASON_IDENTITY_EVIDENCE_INCOMPLETE,
                record,
            )
        }

        if (runCatching { ids.isEmpty }.getOrDefault(true)) {

            return refusal(unit, TrackingWatchedNegativeEvidence.REASON_IDENTITY_UNSTATED, record)
        }
        val questioned = TrackingWatchedIdentityRelation.QuestionedUnit(unit = unit, ids = ids, key = key)
        for (residue in record.residue) {
            when (TrackingWatchedIdentityRelation.evaluate(residue, questioned, aliasTable)) {
                TrackingWatchedIdentityRelation.State.PROVEN_SEPARATION -> Unit
                TrackingWatchedIdentityRelation.State.PROVEN_MATCH ->
                    return refusal(unit, TrackingWatchedNegativeEvidence.REASON_RESIDUE_MATCH, record)
                TrackingWatchedIdentityRelation.State.UNKNOWN ->
                    return refusal(unit, TrackingWatchedNegativeEvidence.REASON_RESIDUE_UNKNOWN, record)
            }
        }

        val incrementalEvidence = runCatching { incremental(unit) }.getOrNull()
            ?: return refusal(unit, TrackingWatchedNegativeEvidence.REASON_CURRENCY_ABSENT, record)
        if (!incrementalEvidence.accepted) {

            return refusal(
                unit,
                TrackingWatchedNegativeEvidence.REASON_CURRENCY_UNACCEPTED,
                record,
                incrementalEvidence,
            )
        }
        if (incrementalEvidence.accountFingerprint != null &&
            incrementalEvidence.accountFingerprint != accountNow
        ) {

            return refusal(
                unit,
                TrackingWatchedNegativeEvidence.REASON_ACCOUNT_CHANGED,
                record,
                incrementalEvidence,
            )
        }
        val consumedThrough = incrementalEvidence.consumedThrough
        if (consumedThrough == null || consumedThrough.isBefore(record.takenAt)) {

            return refusal(
                unit,
                TrackingWatchedNegativeEvidence.REASON_CURRENCY_STALE,
                record,
                incrementalEvidence,
            )
        }
        return TrackingWatchedNegativeEvidence(
            unit = unit,
            resolved = true,
            reason = TrackingWatchedNegativeEvidence.REASON_PROVEN,
            coverageTakenAt = record.takenAt,
            coverageWalkStartedAt = record.walkStartedAt,
            consumedThrough = consumedThrough,
            consumedBy = incrementalEvidence.source,
        )
    }

    override fun stillAuthorises(
        unit: TrackingWatchedHistoryUnit,
        ids: TrackingExternalIds,
        key: WatchKey?,
        coverageTakenAt: Instant?,
    ): Boolean {
        val evidence = negativeEvidenceFor(unit, ids, key)
        return evidence.resolved && evidence.coverageTakenAt == coverageTakenAt
    }

    private fun refusal(
        unit: TrackingWatchedHistoryUnit,
        reason: String,
        record: TrackingWatchedHistoryCoverageRecord? = null,
        incremental: TrackingWatchedIncrementalEvidence? = null,
    ): TrackingWatchedNegativeEvidence = TrackingWatchedNegativeEvidence(
        unit = unit,
        resolved = false,
        reason = reason,
        coverageTakenAt = record?.takenAt,
        coverageWalkStartedAt = record?.walkStartedAt,
        consumedThrough = incremental?.consumedThrough,
        consumedBy = incremental?.source,
    )
}
