package dev.infusion.core.database.tracking

import android.content.Context
import android.content.SharedPreferences
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.tracking.TrackingChangeFeedCategory
import dev.infusion.core.domain.tracking.TrackingChangeFeedCheckpoint
import dev.infusion.core.domain.tracking.TrackingChangeFeedCheckpointStore
import dev.infusion.core.domain.tracking.TrackingChangeFeedRemoval
import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryCoverageRecord
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryCoverageStore
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryCursor
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryCursorStore
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryImportProgress
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryResidueRecord
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryUnit
import dev.infusion.core.model.TypedExternalId
import java.time.Instant

class SharedPreferencesTrackingWatchedHistoryCursorStore(
    context: Context,
    private val profileScope: ProfileScope,
    prefsName: String = PREFS_NAME,
) : TrackingWatchedHistoryCursorStore,
    TrackingWatchedHistoryCoverageStore,
    TrackingChangeFeedCheckpointStore {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun cursor(
        providerId: String,
        unit: TrackingWatchedHistoryUnit,
    ): TrackingWatchedHistoryCursor {
        val profileId = profileScope.activeProfileId
        return TrackingWatchedHistoryCursor(
            watermark = readInstant(key(providerId, profileId, unit, KEY_WATERMARK)),
            next = prefs.getString(key(providerId, profileId, unit, KEY_NEXT), null)
                ?.takeIf { it.isNotBlank() },
        )
    }

    override fun save(
        providerId: String,
        unit: TrackingWatchedHistoryUnit,
        cursor: TrackingWatchedHistoryCursor,
    ) {
        val profileId = profileScope.activeProfileId
        prefs.edit().apply {
            val watermarkKey = key(providerId, profileId, unit, KEY_WATERMARK)
            val watermark = cursor.watermark
            if (watermark == null) {
                remove(watermarkKey)
            } else {
                putLong(watermarkKey, watermark.toEpochMilli())
            }
            val nextKey = key(providerId, profileId, unit, KEY_NEXT)
            if (cursor.next == null) remove(nextKey) else putString(nextKey, cursor.next)
        }.apply()
    }

    override fun coverage(
        providerId: String,
        unit: TrackingWatchedHistoryUnit,
    ): TrackingWatchedHistoryCoverageRecord? {
        val profileId = profileScope.activeProfileId

        val takenAtMs = prefs.getLong(coverageKey(providerId, profileId, unit, KEY_TAKEN_AT), 0L)
        if (takenAtMs <= 0L) return null
        fun k(field: String) = coverageKey(providerId, profileId, unit, field)

        val residueRaw = prefs.getString(k(KEY_RESIDUE), null)
        val decodedResidue: List<TrackingWatchedHistoryResidueRecord>? = when {
            residueRaw == null -> emptyList()
            residueRaw.isEmpty() -> emptyList()
            else -> decodeResidue(residueRaw)
        }
        val storedResidueState = prefs.getString(k(KEY_RESIDUE_STATE), null)
            ?.takeIf { it.isNotBlank() }
            ?: TrackingWatchedHistoryCoverageRecord.RESIDUE_ABSENT
        val residueState = when {
            decodedResidue == null -> TrackingWatchedHistoryCoverageRecord.RESIDUE_UNREADABLE

            storedResidueState == TrackingWatchedHistoryCoverageRecord.RESIDUE_TRUSTED &&
                residueRaw == null -> TrackingWatchedHistoryCoverageRecord.RESIDUE_UNREADABLE
            else -> storedResidueState
        }
        return TrackingWatchedHistoryCoverageRecord(
            providerId = providerId,
            profileId = profileId,
            accountFingerprint = prefs.getString(k(KEY_ACCOUNT_FINGERPRINT), null)
                ?.takeIf { it.isNotBlank() },
            unit = unit,
            complete = prefs.getBoolean(k(KEY_COMPLETE), false),
            walkCompleted = prefs.getBoolean(k(KEY_WALK_COMPLETED), false),
            truncated = prefs.getBoolean(k(KEY_TRUNCATED), false),
            pages = prefs.getInt(k(KEY_PAGES), 0),
            rows = prefs.getInt(k(KEY_ROWS), 0),
            distinctPlays = prefs.getInt(k(KEY_DISTINCT_PLAYS), 0),
            distinctUnits = prefs.getInt(k(KEY_DISTINCT_UNITS), 0),
            imported = prefs.getInt(k(KEY_IMPORTED), 0),
            providerItems = readIntOrNull(k(KEY_PROVIDER_ITEMS)),
            providerPlays = readIntOrNull(k(KEY_PROVIDER_PLAYS)),
            providerCountMovedDuringWalk = readBooleanOrNull(k(KEY_PROVIDER_COUNT_MOVED)),
            malformed = prefs.getInt(k(KEY_MALFORMED), 0),
            unresolvedIdentity = prefs.getInt(k(KEY_UNRESOLVED), 0),
            undated = prefs.getInt(k(KEY_UNDATED), 0),
            showLevelOnly = prefs.getInt(k(KEY_SHOW_LEVEL_ONLY), 0),
            alreadyCovered = prefs.getInt(k(KEY_ALREADY_COVERED), 0),
            takenAt = Instant.ofEpochMilli(takenAtMs),
            walkStartedAt = prefs.getLong(k(KEY_WALK_STARTED_AT), 0L)
                .takeIf { it > 0L }?.let(Instant::ofEpochMilli),
            reasons = prefs.getString(k(KEY_REASONS), null)
                ?.split(REASON_SEPARATOR)?.filter { it.isNotBlank() }
                ?: emptyList(),
            invalidatedAt = prefs.getLong(k(KEY_INVALIDATED_AT), 0L)
                .takeIf { it > 0L }?.let(Instant::ofEpochMilli),

            retrievalComplete = prefs.getBoolean(k(KEY_RETRIEVAL_COMPLETE), false),
            identityEvidenceComplete = prefs.getBoolean(k(KEY_IDENTITY_EVIDENCE_COMPLETE), false),
            materializationComplete = prefs.getBoolean(k(KEY_MATERIALIZATION_COMPLETE), false),
            residue = decodedResidue.orEmpty(),

            residueState = residueState,
            unresolvedDistinctItems = prefs.getInt(k(KEY_UNRESOLVED_DISTINCT_ITEMS), 0),
            seasonZero = prefs.getInt(k(KEY_SEASON_ZERO), 0),
            coordinateMissing = prefs.getInt(k(KEY_COORDINATE_MISSING), 0),
            coordinateInvalid = prefs.getInt(k(KEY_COORDINATE_INVALID), 0),
            coordinateNegative = prefs.getInt(k(KEY_COORDINATE_NEGATIVE), 0),
            seasonZeroCounted = readBooleanOrNull(k(KEY_SEASON_ZERO_COUNTED)),
            countReconciled = readBooleanOrNull(k(KEY_COUNT_RECONCILED)),
        )
    }

    override fun save(record: TrackingWatchedHistoryCoverageRecord) {

        val profileId = profileScope.activeProfileId
        val unit = record.unit
        fun k(field: String) = coverageKey(record.providerId, profileId, unit, field)
        prefs.edit().apply {
            putBoolean(k(KEY_COMPLETE), record.complete)
            putBoolean(k(KEY_WALK_COMPLETED), record.walkCompleted)
            putBoolean(k(KEY_TRUNCATED), record.truncated)
            putInt(k(KEY_PAGES), record.pages)
            putInt(k(KEY_ROWS), record.rows)
            putInt(k(KEY_DISTINCT_PLAYS), record.distinctPlays)
            putInt(k(KEY_DISTINCT_UNITS), record.distinctUnits)
            putInt(k(KEY_IMPORTED), record.imported)
            putIntOrRemove(k(KEY_PROVIDER_ITEMS), record.providerItems)
            putIntOrRemove(k(KEY_PROVIDER_PLAYS), record.providerPlays)
            putBooleanOrRemove(k(KEY_PROVIDER_COUNT_MOVED), record.providerCountMovedDuringWalk)
            putInt(k(KEY_MALFORMED), record.malformed)
            putInt(k(KEY_UNRESOLVED), record.unresolvedIdentity)
            putInt(k(KEY_UNDATED), record.undated)
            putInt(k(KEY_SHOW_LEVEL_ONLY), record.showLevelOnly)
            putInt(k(KEY_ALREADY_COVERED), record.alreadyCovered)
            putStringOrRemove(k(KEY_ACCOUNT_FINGERPRINT), record.accountFingerprint)
            putLong(k(KEY_TAKEN_AT), record.takenAt.toEpochMilli())
            putLongOrRemove(k(KEY_WALK_STARTED_AT), record.walkStartedAt?.toEpochMilli())
            putString(k(KEY_REASONS), record.reasons.joinToString(REASON_SEPARATOR.toString()))
            putLongOrRemove(k(KEY_INVALIDATED_AT), record.invalidatedAt?.toEpochMilli())

            putBoolean(k(KEY_RETRIEVAL_COMPLETE), record.retrievalComplete)
            putBoolean(k(KEY_IDENTITY_EVIDENCE_COMPLETE), record.identityEvidenceComplete)
            putBoolean(k(KEY_MATERIALIZATION_COMPLETE), record.materializationComplete)
            putString(k(KEY_RESIDUE_STATE), record.residueState)
            putString(k(KEY_RESIDUE), encodeResidue(record.residue))
            putInt(k(KEY_UNRESOLVED_DISTINCT_ITEMS), record.unresolvedDistinctItems)
            putInt(k(KEY_SEASON_ZERO), record.seasonZero)
            putInt(k(KEY_COORDINATE_MISSING), record.coordinateMissing)
            putInt(k(KEY_COORDINATE_INVALID), record.coordinateInvalid)
            putInt(k(KEY_COORDINATE_NEGATIVE), record.coordinateNegative)
            putBooleanOrRemove(k(KEY_SEASON_ZERO_COUNTED), record.seasonZeroCounted)
            putBooleanOrRemove(k(KEY_COUNT_RECONCILED), record.countReconciled)

        }.commit()
    }

    override fun invalidate(
        providerId: String,
        unit: TrackingWatchedHistoryUnit,
        reason: String,
        at: Instant,
    ) {

        val existing = coverage(providerId, unit) ?: return
        save(
            existing.copy(
                complete = false,
                reasons = (existing.reasons + reason).distinct(),
                invalidatedAt = at,
            ),
        )
    }

    override fun importProgress(
        providerId: String,
        unit: TrackingWatchedHistoryUnit,
    ): TrackingWatchedHistoryImportProgress? {
        val profileId = profileScope.activeProfileId

        val startedAtMs = prefs.getLong(importKey(providerId, profileId, unit, KEY_IMPORT_STARTED_AT), 0L)
        if (startedAtMs <= 0L) return null
        fun k(field: String) = importKey(providerId, profileId, unit, field)
        return TrackingWatchedHistoryImportProgress(
            providerId = providerId,
            profileId = profileId,
            accountFingerprint = prefs.getString(k(KEY_ACCOUNT_FINGERPRINT), null)
                ?.takeIf { it.isNotBlank() },
            unit = unit,
            startedAt = Instant.ofEpochMilli(startedAtMs),
            sweeps = prefs.getInt(k(KEY_IMPORT_SWEEPS), 0),
            pages = prefs.getInt(k(KEY_PAGES), 0),
            rows = prefs.getInt(k(KEY_ROWS), 0),
            imported = prefs.getInt(k(KEY_IMPORTED), 0),
            distinctPlays = prefs.getInt(k(KEY_DISTINCT_PLAYS), 0),
            distinctUnits = prefs.getInt(k(KEY_DISTINCT_UNITS), 0),
            malformed = prefs.getInt(k(KEY_MALFORMED), 0),
            unresolvedIdentity = prefs.getInt(k(KEY_UNRESOLVED), 0),
            undated = prefs.getInt(k(KEY_UNDATED), 0),
            showLevelOnly = prefs.getInt(k(KEY_SHOW_LEVEL_ONLY), 0),
            alreadyCovered = prefs.getInt(k(KEY_ALREADY_COVERED), 0),
            walkCompleted = prefs.getBoolean(k(KEY_WALK_COMPLETED), false),
            truncated = prefs.getBoolean(k(KEY_TRUNCATED), false),
            providerItems = readIntOrNull(k(KEY_PROVIDER_ITEMS)),
            providerPlays = readIntOrNull(k(KEY_PROVIDER_PLAYS)),
            countsTakenAt = prefs.getLong(k(KEY_IMPORT_COUNTS_TAKEN_AT), 0L)
                .takeIf { it > 0L }?.let(Instant::ofEpochMilli),

            residue = prefs.getString(k(KEY_RESIDUE), null)?.let { raw ->
                if (raw.isEmpty()) emptyList() else decodeResidue(raw)
            } ?: emptyList(),
            residueState = prefs.getString(k(KEY_RESIDUE_STATE), null)
                ?.takeIf { it.isNotBlank() }
                ?: TrackingWatchedHistoryCoverageRecord.RESIDUE_ABSENT,
            unresolvedDistinctItems = prefs.getInt(k(KEY_UNRESOLVED_DISTINCT_ITEMS), 0),
            unrepresentableDistinctItems = prefs.getInt(k(KEY_UNREPRESENTABLE_DISTINCT_ITEMS), 0),
            seasonZero = prefs.getInt(k(KEY_SEASON_ZERO), 0),
            coordinateMissing = prefs.getInt(k(KEY_COORDINATE_MISSING), 0),
            coordinateInvalid = prefs.getInt(k(KEY_COORDINATE_INVALID), 0),
            coordinateNegative = prefs.getInt(k(KEY_COORDINATE_NEGATIVE), 0),

            baselineEstablishment = prefs.getBoolean(k(KEY_IMPORT_BASELINE), false),
            baselineCursor = prefs.getString(k(KEY_IMPORT_BASELINE_CURSOR), null)
                ?.takeIf { it.isNotBlank() },
        )
    }

    override fun saveImportProgress(progress: TrackingWatchedHistoryImportProgress) {
        val profileId = profileScope.activeProfileId
        val unit = progress.unit
        fun k(field: String) = importKey(progress.providerId, profileId, unit, field)
        prefs.edit().apply {
            putLong(k(KEY_IMPORT_STARTED_AT), progress.startedAt.toEpochMilli())
            putInt(k(KEY_IMPORT_SWEEPS), progress.sweeps)
            putInt(k(KEY_PAGES), progress.pages)
            putInt(k(KEY_ROWS), progress.rows)
            putInt(k(KEY_IMPORTED), progress.imported)
            putInt(k(KEY_DISTINCT_PLAYS), progress.distinctPlays)
            putInt(k(KEY_DISTINCT_UNITS), progress.distinctUnits)
            putInt(k(KEY_MALFORMED), progress.malformed)
            putInt(k(KEY_UNRESOLVED), progress.unresolvedIdentity)
            putInt(k(KEY_UNDATED), progress.undated)
            putInt(k(KEY_SHOW_LEVEL_ONLY), progress.showLevelOnly)
            putInt(k(KEY_ALREADY_COVERED), progress.alreadyCovered)
            putBoolean(k(KEY_WALK_COMPLETED), progress.walkCompleted)
            putBoolean(k(KEY_TRUNCATED), progress.truncated)
            putIntOrRemove(k(KEY_PROVIDER_ITEMS), progress.providerItems)
            putIntOrRemove(k(KEY_PROVIDER_PLAYS), progress.providerPlays)
            putStringOrRemove(k(KEY_ACCOUNT_FINGERPRINT), progress.accountFingerprint)
            putLongOrRemove(k(KEY_IMPORT_COUNTS_TAKEN_AT), progress.countsTakenAt?.toEpochMilli())

            putString(k(KEY_RESIDUE_STATE), progress.residueState)
            putString(k(KEY_RESIDUE), encodeResidue(progress.residue))
            putInt(k(KEY_UNRESOLVED_DISTINCT_ITEMS), progress.unresolvedDistinctItems)
            putInt(k(KEY_UNREPRESENTABLE_DISTINCT_ITEMS), progress.unrepresentableDistinctItems)
            putInt(k(KEY_SEASON_ZERO), progress.seasonZero)
            putInt(k(KEY_COORDINATE_MISSING), progress.coordinateMissing)
            putInt(k(KEY_COORDINATE_INVALID), progress.coordinateInvalid)
            putInt(k(KEY_COORDINATE_NEGATIVE), progress.coordinateNegative)

            putBoolean(k(KEY_IMPORT_BASELINE), progress.baselineEstablishment)
            putStringOrRemove(k(KEY_IMPORT_BASELINE_CURSOR), progress.baselineCursor)
        }.apply()
    }

    override fun clearImportProgress(providerId: String, unit: TrackingWatchedHistoryUnit) {
        val profileId = profileScope.activeProfileId
        prefs.edit().apply {
            for (field in IMPORT_FIELDS) remove(importKey(providerId, profileId, unit, field))
        }.apply()
    }

    override fun checkpoint(providerId: String): TrackingChangeFeedCheckpoint? {
        val profileId = profileScope.activeProfileId

        val fromDateMs = prefs.getLong(journalKey(providerId, profileId, KEY_JOURNAL_FROM_DATE), 0L)
        if (fromDateMs <= 0L) return null
        fun k(field: String) = journalKey(providerId, profileId, field)
        return TrackingChangeFeedCheckpoint(
            fromDate = Instant.ofEpochMilli(fromDateMs),
            lastAppliedActionAt = prefs.getLong(k(KEY_JOURNAL_LAST_ACTION_AT), 0L)
                .takeIf { it > 0L }?.let(Instant::ofEpochMilli),
            lastAppliedEntryId = prefs.getString(k(KEY_JOURNAL_LAST_ENTRY_ID), null)
                ?.takeIf { it.isNotBlank() },
            handledEntryIds = prefs.getString(k(KEY_JOURNAL_HANDLED_IDS), null)
                ?.split(ENTRY_ID_SEPARATOR)
                ?.filter { it.isNotBlank() }
                ?.map(::decodeEntryId)
                ?.takeLast(TrackingChangeFeedCheckpoint.MAX_APPLIED_ENTRY_IDS)
                ?: emptyList(),
            droppedEntryIds = prefs.getInt(k(KEY_JOURNAL_HANDLED_DROPPED), 0),
            removals = prefs.getString(k(KEY_JOURNAL_REMOVALS), null)
                ?.split(REMOVAL_SEPARATOR)
                ?.mapNotNull(::decodeRemoval)
                ?.takeLast(TrackingChangeFeedCheckpoint.MAX_REMOVALS)
                ?: emptyList(),
            removedObserved = prefs.getInt(k(KEY_JOURNAL_REMOVED_OBSERVED), 0),
            accountFingerprint = prefs.getString(k(KEY_ACCOUNT_FINGERPRINT), null)
                ?.takeIf { it.isNotBlank() },
            retentionDays = if (prefs.contains(k(KEY_JOURNAL_RETENTION_DAYS))) {
                prefs.getInt(k(KEY_JOURNAL_RETENTION_DAYS), -1).takeIf { it >= 0 }
            } else {
                null
            },
            adoptedAt = prefs.getLong(k(KEY_JOURNAL_ADOPTED_AT), 0L)
                .takeIf { it > 0L }?.let(Instant::ofEpochMilli),
            updatedAt = prefs.getLong(k(KEY_JOURNAL_UPDATED_AT), 0L)
                .takeIf { it > 0L }?.let(Instant::ofEpochMilli) ?: Instant.EPOCH,
            lastReason = prefs.getString(k(KEY_JOURNAL_LAST_REASON), null)?.takeIf { it.isNotBlank() },
        )
    }

    override fun save(providerId: String, checkpoint: TrackingChangeFeedCheckpoint) {
        val profileId = profileScope.activeProfileId
        fun k(field: String) = journalKey(providerId, profileId, field)
        prefs.edit().apply {
            putLong(k(KEY_JOURNAL_FROM_DATE), checkpoint.fromDate.toEpochMilli())
            putLongOrRemove(k(KEY_JOURNAL_LAST_ACTION_AT), checkpoint.lastAppliedActionAt?.toEpochMilli())
            putStringOrRemove(k(KEY_JOURNAL_LAST_ENTRY_ID), checkpoint.lastAppliedEntryId)
            putString(
                k(KEY_JOURNAL_HANDLED_IDS),
                checkpoint.handledEntryIds
                    .takeLast(TrackingChangeFeedCheckpoint.MAX_APPLIED_ENTRY_IDS)
                    .joinToString(ENTRY_ID_SEPARATOR.toString(), transform = ::encodeEntryId),
            )
            putInt(k(KEY_JOURNAL_HANDLED_DROPPED), checkpoint.droppedEntryIds)
            putString(
                k(KEY_JOURNAL_REMOVALS),
                checkpoint.removals
                    .takeLast(TrackingChangeFeedCheckpoint.MAX_REMOVALS)
                    .joinToString(REMOVAL_SEPARATOR.toString(), transform = ::encodeRemoval),
            )
            putInt(k(KEY_JOURNAL_REMOVED_OBSERVED), checkpoint.removedObserved)
            putStringOrRemove(k(KEY_ACCOUNT_FINGERPRINT), checkpoint.accountFingerprint)
            putIntOrRemove(k(KEY_JOURNAL_RETENTION_DAYS), checkpoint.retentionDays)
            putLongOrRemove(k(KEY_JOURNAL_ADOPTED_AT), checkpoint.adoptedAt?.toEpochMilli())
            putLong(k(KEY_JOURNAL_UPDATED_AT), checkpoint.updatedAt.toEpochMilli())
            putStringOrRemove(k(KEY_JOURNAL_LAST_REASON), checkpoint.lastReason)
        }.apply()
    }

    override fun clearCheckpoint(providerId: String) {
        val profileId = profileScope.activeProfileId
        prefs.edit().apply {
            for (field in JOURNAL_FIELDS) remove(journalKey(providerId, profileId, field))
        }.apply()
    }

    override fun clear(providerId: String) {

        val profileId = profileScope.activeProfileId
        prefs.edit().apply {
            for (unit in TrackingWatchedHistoryUnit.entries) {
                remove(key(providerId, profileId, unit, KEY_WATERMARK))
                remove(key(providerId, profileId, unit, KEY_NEXT))
                for (field in COVERAGE_FIELDS) {
                    remove(coverageKey(providerId, profileId, unit, field))
                }
                for (field in IMPORT_FIELDS) {
                    remove(importKey(providerId, profileId, unit, field))
                }
            }

            for (field in JOURNAL_FIELDS) remove(journalKey(providerId, profileId, field))
        }.apply()
    }

    private fun readInstant(prefKey: String): Instant? {
        if (!prefs.contains(prefKey)) return null

        return prefs.getLong(prefKey, 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli)
    }

    private fun readIntOrNull(prefKey: String): Int? =
        if (!prefs.contains(prefKey)) null else prefs.getInt(prefKey, -1).takeIf { it >= 0 }

    private fun readBooleanOrNull(prefKey: String): Boolean? =
        if (!prefs.contains(prefKey)) null else prefs.getBoolean(prefKey, false)

    private fun key(
        providerId: String,
        profileId: String,
        unit: TrackingWatchedHistoryUnit,
        field: String,
    ): String = "tracking-inbound.$providerId.$profileId.watched_history.${unit.name.lowercase()}.$field"

    private fun coverageKey(
        providerId: String,
        profileId: String,
        unit: TrackingWatchedHistoryUnit,
        field: String,
    ): String = key(providerId, profileId, unit, "$COVERAGE_PREFIX.$field")

    private fun importKey(
        providerId: String,
        profileId: String,
        unit: TrackingWatchedHistoryUnit,
        field: String,
    ): String = key(providerId, profileId, unit, "$IMPORT_PREFIX.$field")

    private fun journalKey(providerId: String, profileId: String, field: String): String =
        "tracking-inbound.$providerId.$profileId.$JOURNAL_PREFIX.$field"

    companion object {

        const val PREFS_NAME: String = "infusion_tracking_inbound"

        private const val KEY_WATERMARK = "watermark_ms"
        private const val KEY_NEXT = "next_cursor"

        private const val COVERAGE_PREFIX = "coverage"
        private const val KEY_COMPLETE = "complete"
        private const val KEY_WALK_COMPLETED = "walk_completed"
        private const val KEY_TRUNCATED = "truncated"
        private const val KEY_PAGES = "pages"
        private const val KEY_ROWS = "rows"
        private const val KEY_DISTINCT_PLAYS = "distinct_plays"
        private const val KEY_DISTINCT_UNITS = "distinct_units"
        private const val KEY_IMPORTED = "imported"
        private const val KEY_PROVIDER_ITEMS = "provider_items"
        private const val KEY_PROVIDER_PLAYS = "provider_plays"
        private const val KEY_PROVIDER_COUNT_MOVED = "provider_count_moved_during_walk"
        private const val KEY_MALFORMED = "malformed"
        private const val KEY_UNRESOLVED = "unresolved_identity"
        private const val KEY_UNDATED = "undated"
        private const val KEY_SHOW_LEVEL_ONLY = "show_level_only"
        private const val KEY_ALREADY_COVERED = "already_covered"
        private const val KEY_ACCOUNT_FINGERPRINT = "account_fingerprint"
        private const val KEY_TAKEN_AT = "taken_at_ms"

        private const val KEY_WALK_STARTED_AT = "walk_started_at_ms"
        private const val KEY_REASONS = "reasons"
        private const val KEY_INVALIDATED_AT = "invalidated_at_ms"

        private const val KEY_RETRIEVAL_COMPLETE = "retrieval_complete"
        private const val KEY_IDENTITY_EVIDENCE_COMPLETE = "identity_evidence_complete"
        private const val KEY_MATERIALIZATION_COMPLETE = "materialization_complete"
        private const val KEY_RESIDUE_STATE = "residue_state"
        private const val KEY_RESIDUE = "residue"
        private const val KEY_UNRESOLVED_DISTINCT_ITEMS = "unresolved_distinct_items"
        private const val KEY_UNREPRESENTABLE_DISTINCT_ITEMS = "unrepresentable_distinct_items"
        private const val KEY_SEASON_ZERO = "season_zero"
        private const val KEY_COORDINATE_MISSING = "coordinate_missing"
        private const val KEY_COORDINATE_INVALID = "coordinate_invalid"
        private const val KEY_COORDINATE_NEGATIVE = "coordinate_negative"
        private const val KEY_SEASON_ZERO_COUNTED = "season_zero_counted"
        private const val KEY_COUNT_RECONCILED = "count_reconciled"

        private val COVERAGE_FIELDS = listOf(
            KEY_COMPLETE,
            KEY_WALK_COMPLETED,
            KEY_TRUNCATED,
            KEY_PAGES,
            KEY_ROWS,
            KEY_DISTINCT_PLAYS,
            KEY_DISTINCT_UNITS,
            KEY_IMPORTED,
            KEY_PROVIDER_ITEMS,
            KEY_PROVIDER_PLAYS,
            KEY_PROVIDER_COUNT_MOVED,
            KEY_MALFORMED,
            KEY_UNRESOLVED,
            KEY_UNDATED,
            KEY_SHOW_LEVEL_ONLY,
            KEY_ALREADY_COVERED,
            KEY_ACCOUNT_FINGERPRINT,
            KEY_TAKEN_AT,
            KEY_WALK_STARTED_AT,
            KEY_REASONS,
            KEY_INVALIDATED_AT,
            KEY_RETRIEVAL_COMPLETE,
            KEY_IDENTITY_EVIDENCE_COMPLETE,
            KEY_MATERIALIZATION_COMPLETE,
            KEY_RESIDUE_STATE,
            KEY_RESIDUE,
            KEY_UNRESOLVED_DISTINCT_ITEMS,
            KEY_UNREPRESENTABLE_DISTINCT_ITEMS,
            KEY_SEASON_ZERO,
            KEY_COORDINATE_MISSING,
            KEY_COORDINATE_INVALID,
            KEY_COORDINATE_NEGATIVE,
            KEY_SEASON_ZERO_COUNTED,
            KEY_COUNT_RECONCILED,
        )

        private const val REASON_SEPARATOR = ','

        private const val IMPORT_PREFIX = "import"
        private const val KEY_IMPORT_STARTED_AT = "started_at_ms"
        private const val KEY_IMPORT_SWEEPS = "sweeps"
        private const val KEY_IMPORT_COUNTS_TAKEN_AT = "counts_taken_at_ms"

        private const val KEY_IMPORT_BASELINE = "baseline_establishment"
        private const val KEY_IMPORT_BASELINE_CURSOR = "baseline_cursor"

        private val IMPORT_FIELDS = listOf(
            KEY_IMPORT_STARTED_AT,
            KEY_IMPORT_SWEEPS,
            KEY_IMPORT_COUNTS_TAKEN_AT,
            KEY_IMPORT_BASELINE,
            KEY_IMPORT_BASELINE_CURSOR,
            KEY_PAGES,
            KEY_ROWS,
            KEY_DISTINCT_PLAYS,
            KEY_DISTINCT_UNITS,
            KEY_IMPORTED,
            KEY_PROVIDER_ITEMS,
            KEY_PROVIDER_PLAYS,
            KEY_MALFORMED,
            KEY_UNRESOLVED,
            KEY_UNDATED,
            KEY_SHOW_LEVEL_ONLY,
            KEY_ALREADY_COVERED,
            KEY_WALK_COMPLETED,
            KEY_TRUNCATED,
            KEY_ACCOUNT_FINGERPRINT,
            KEY_RESIDUE_STATE,
            KEY_RESIDUE,
            KEY_UNRESOLVED_DISTINCT_ITEMS,
            KEY_UNREPRESENTABLE_DISTINCT_ITEMS,
            KEY_SEASON_ZERO,
            KEY_COORDINATE_MISSING,
            KEY_COORDINATE_INVALID,
            KEY_COORDINATE_NEGATIVE,
        )

        private const val JOURNAL_PREFIX = "journal"
        private const val KEY_JOURNAL_FROM_DATE = "from_date_ms"
        private const val KEY_JOURNAL_LAST_ACTION_AT = "last_action_at_ms"
        private const val KEY_JOURNAL_LAST_ENTRY_ID = "last_entry_id"
        private const val KEY_JOURNAL_HANDLED_IDS = "handled_entry_ids"
        private const val KEY_JOURNAL_HANDLED_DROPPED = "handled_dropped"
        private const val KEY_JOURNAL_REMOVALS = "removals"
        private const val KEY_JOURNAL_REMOVED_OBSERVED = "removed_observed"
        private const val KEY_JOURNAL_RETENTION_DAYS = "retention_days"
        private const val KEY_JOURNAL_ADOPTED_AT = "adopted_at_ms"
        private const val KEY_JOURNAL_UPDATED_AT = "updated_at_ms"
        private const val KEY_JOURNAL_LAST_REASON = "last_reason"

        private val JOURNAL_FIELDS = listOf(
            KEY_JOURNAL_FROM_DATE,
            KEY_JOURNAL_LAST_ACTION_AT,
            KEY_JOURNAL_LAST_ENTRY_ID,
            KEY_JOURNAL_HANDLED_IDS,
            KEY_JOURNAL_HANDLED_DROPPED,
            KEY_JOURNAL_REMOVALS,
            KEY_JOURNAL_REMOVED_OBSERVED,
            KEY_ACCOUNT_FINGERPRINT,
            KEY_JOURNAL_RETENTION_DAYS,
            KEY_JOURNAL_ADOPTED_AT,
            KEY_JOURNAL_UPDATED_AT,
            KEY_JOURNAL_LAST_REASON,
        )

        private const val ENTRY_ID_SEPARATOR = ','

        private const val REMOVAL_SEPARATOR = ','

        private fun encodeEntryId(raw: String): String =
            raw.replace("%", "%25").replace(",", "%2C").replace("|", "%7C")

        private fun decodeEntryId(raw: String): String =
            raw.replace("%2C", ",").replace("%7C", "|").replace("%25", "%")

        private fun encodeRemoval(removal: TrackingChangeFeedRemoval): String = buildString {
            append(encodeEntryId(removal.entryId))
            append('|').append(removal.category.name)
            append('|').append(removal.actionAt.toEpochMilli())
        }

        private fun decodeRemoval(raw: String): TrackingChangeFeedRemoval? {
            val parts = raw.split('|')
            if (parts.size != 3) return null
            val category = runCatching { TrackingChangeFeedCategory.valueOf(parts[1]) }.getOrNull()
                ?: return null
            val actionAt = parts[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
            val entryId = decodeEntryId(parts[0]).takeIf { it.isNotBlank() } ?: return null
            return TrackingChangeFeedRemoval(
                entryId = entryId,
                category = category,
                actionAt = Instant.ofEpochMilli(actionAt),
            )
        }

        private const val MAX_RESIDUE_ROWS = 4_096

        private const val RESIDUE_ROW_SEPARATOR = ';'
        private const val RESIDUE_FIELD_SEPARATOR = '|'
        private const val RESIDUE_ALIAS_SEPARATOR = ','
        private const val RESIDUE_AUTHORITY_SEPARATOR = ':'

        private fun encodeResidue(residue: List<TrackingWatchedHistoryResidueRecord>): String =
            residue.take(MAX_RESIDUE_ROWS).joinToString(RESIDUE_ROW_SEPARATOR.toString()) { row ->
                buildString {
                    append(escapeResidue(row.kind))
                    append(RESIDUE_FIELD_SEPARATOR)
                    append(
                        row.externalIds.typed()
                            .joinToString(RESIDUE_ALIAS_SEPARATOR.toString()) { alias ->
                                escapeResidue(alias.authority) + RESIDUE_AUTHORITY_SEPARATOR +
                                    escapeResidue(alias.value)
                            },
                    )
                    append(RESIDUE_FIELD_SEPARATOR).append(row.seasonNumber ?: "")
                    append(RESIDUE_FIELD_SEPARATOR).append(row.episodeNumber ?: "")
                }
            }

        private fun decodeResidue(raw: String): List<TrackingWatchedHistoryResidueRecord>? {
            val rows = raw.split(RESIDUE_ROW_SEPARATOR)
            if (rows.size > MAX_RESIDUE_ROWS) return null
            val decoded = ArrayList<TrackingWatchedHistoryResidueRecord>(rows.size)
            for (line in rows) {
                if (line.isEmpty()) continue
                val fields = line.split(RESIDUE_FIELD_SEPARATOR)
                if (fields.size != 4) return null
                val kind = unescapeResidue(fields[0]).takeIf { it.isNotBlank() } ?: return null
                val aliases = ArrayList<TypedExternalId>(3)
                if (fields[1].isNotEmpty()) {
                    for (pair in fields[1].split(RESIDUE_ALIAS_SEPARATOR)) {
                        val parts = pair.split(RESIDUE_AUTHORITY_SEPARATOR)
                        if (parts.size != 2) return null
                        val authority = unescapeResidue(parts[0])
                        val value = unescapeResidue(parts[1])
                        if (authority.isBlank() || value.isBlank()) return null
                        aliases += TypedExternalId(authority, value)
                    }
                }
                val ids = TrackingExternalIds.fromTyped(aliases)
                if (ids.isEmpty) return null
                val season = fields[2].let { if (it.isEmpty()) null else it.toIntOrNull() ?: return null }
                val episode = fields[3].let { if (it.isEmpty()) null else it.toIntOrNull() ?: return null }
                decoded += TrackingWatchedHistoryResidueRecord(
                    externalIds = ids,
                    kind = kind,
                    seasonNumber = season,
                    episodeNumber = episode,
                )
            }
            return decoded
        }

        private fun escapeResidue(raw: String): String = buildString(raw.length) {
            for (character in raw) {
                when (character) {
                    '%' -> append("%25")
                    ';' -> append("%3B")
                    '|' -> append("%7C")
                    ',' -> append("%2C")
                    ':' -> append("%3A")
                    else -> append(character)
                }
            }
        }

        private fun unescapeResidue(raw: String): String = buildString(raw.length) {
            var index = 0
            while (index < raw.length) {
                val character = raw[index]
                if (character == '%' && index + 2 < raw.length) {
                    when (raw.substring(index + 1, index + 3)) {
                        "25" -> { append('%'); index += 3; continue }
                        "3B" -> { append(';'); index += 3; continue }
                        "7C" -> { append('|'); index += 3; continue }
                        "2C" -> { append(','); index += 3; continue }
                        "3A" -> { append(':'); index += 3; continue }
                    }
                }
                append(character)
                index++
            }
        }
    }
}

private fun SharedPreferences.Editor.putIntOrRemove(key: String, value: Int?): SharedPreferences.Editor {
    if (value == null) remove(key) else putInt(key, value)
    return this
}

private fun SharedPreferences.Editor.putBooleanOrRemove(
    key: String,
    value: Boolean?,
): SharedPreferences.Editor {
    if (value == null) remove(key) else putBoolean(key, value)
    return this
}

private fun SharedPreferences.Editor.putStringOrRemove(
    key: String,
    value: String?,
): SharedPreferences.Editor {
    if (value == null) remove(key) else putString(key, value)
    return this
}

private fun SharedPreferences.Editor.putLongOrRemove(key: String, value: Long?): SharedPreferences.Editor {
    if (value == null) remove(key) else putLong(key, value)
    return this
}
