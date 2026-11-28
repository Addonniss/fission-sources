package dev.infusion.core.sync.tracking

import dev.infusion.core.domain.tracking.TrackingInboundIdentityCompletionPort
import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingInboundIdentityPort
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.domain.tracking.TrackingMediaKind
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryCoveragePort
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryCoverageRecord
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryCoverageScope
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryCoverageStore
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryCursor
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryCursorStore
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryImportProgress
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryPort
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryProviderCounts
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryRecord
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryRequest
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryResidueRecord
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryPage
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryUnit
import dev.infusion.core.domain.tracking.TrackingWatchedItemName
import dev.infusion.core.domain.tracking.TrackingWatchedItemNamePort
import dev.infusion.core.domain.tracking.TrackingWatchKeyLookup
import dev.infusion.core.model.watch.WatchEvent
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.sync.WatchSyncCoordinator
import java.time.Instant

class TrackingWatchedHistoryImporter(

    private val providerId: String,
    private val reader: TrackingWatchedHistoryPort,
    private val identity: TrackingInboundIdentityPort,

    private val identityCompletion: TrackingInboundIdentityCompletionPort? = null,
    private val cursorStore: TrackingWatchedHistoryCursorStore,
    private val coordinator: WatchSyncCoordinator,

    private val isAlreadyImported: (WatchKey, Instant, String?) -> Boolean = { _, _, _ -> false },

    private val maxPages: Int = TrackingWatchedHistoryRequest.MAX_PAGES_PER_SWEEP,

    private val pageLimit: Int? = null,

    private val coveragePort: TrackingWatchedHistoryCoveragePort? = null,

    private val coverageStore: TrackingWatchedHistoryCoverageStore? = null,

    private val coverageScope: (() -> TrackingWatchedHistoryCoverageScope?)? = null,

    private val now: () -> Instant = Instant::now,

    private val onIdentityDiagnostic: ((String) -> Unit)? = null,

    private val itemNames: TrackingWatchedItemNamePort? = null,

    private val maxNamePages: Int = MAX_NAME_PAGES,
) {

    suspend fun sweep(
        units: List<TrackingWatchedHistoryUnit> = listOf(
            TrackingWatchedHistoryUnit.MOVIE,
            TrackingWatchedHistoryUnit.EPISODE,
        ),

        baselineUnits: Set<TrackingWatchedHistoryUnit> = emptySet(),
    ): TrackingWatchedHistorySweepResult {

        nameIndexes.clear()
        if (!reader.accountConnected()) {
            return TrackingWatchedHistorySweepResult(
                providerId = providerId,
                failures = units.associateWith { NO_AUTHORIZATION },
            )
        }

        val sweepStartedAt = now()
        var imported = 0
        var pages = 0
        var skippedIdentity = 0
        var undated = 0
        var showLevelOnly = 0
        var malformed = 0
        var alreadyCovered = 0
        var distinctPlays = 0
        var truncated = false
        val failures = LinkedHashMap<TrackingWatchedHistoryUnit, String>()
        val completed = LinkedHashSet<TrackingWatchedHistoryUnit>()

        val seen = HashSet<String>()

        val measurements = LinkedHashMap<TrackingWatchedHistoryUnit, UnitMeasurement>()

        val starts = units.associateWith { cursorStore.cursor(providerId, it) }

        val evidencePort = coveragePort
        val evidenceStore = coverageStore

        val evidenceScope =
            if (evidencePort != null && evidenceStore != null && units.isNotEmpty()) {
                runCatching { coverageScope?.invoke() }.getOrNull()
            } else {
                null
            }
        val counting = evidencePort != null && evidenceStore != null && evidenceScope != null

        val carried = LinkedHashMap<TrackingWatchedHistoryUnit, TrackingWatchedHistoryImportProgress>()
        val baselineWalk = LinkedHashSet<TrackingWatchedHistoryUnit>()
        if (counting && evidenceStore != null && evidenceScope != null) {
            val fingerprint = evidenceScope.accountFingerprint
            for (unit in units) {

                val previous = runCatching { evidenceStore.importProgress(providerId, unit) }.getOrNull()
                val usable = previous?.takeIf {
                    it.accountFingerprint == null || it.accountFingerprint == fingerprint
                }
                if (previous != null && usable == null) {
                    runCatching { evidenceStore.clearImportProgress(providerId, unit) }
                }
                if (usable != null) carried[unit] = usable

                if (unit in baselineUnits || usable?.baselineEstablishment == true) {
                    baselineWalk += unit
                }
            }
        }
        val importUnits = units.filterTo(LinkedHashSet()) {
            starts[it]?.watermark == null || it in baselineWalk
        }
        if (counting && evidenceStore != null && evidenceScope != null) {
            val fingerprint = evidenceScope.accountFingerprint
            for (unit in importUnits) {
                val stored = runCatching { evidenceStore.coverage(providerId, unit) }.getOrNull()
                if (stored != null && stored.accountFingerprint != null &&
                    stored.accountFingerprint != fingerprint
                ) {
                    runCatching {
                        evidenceStore.invalidate(
                            providerId,
                            unit,
                            TrackingWatchedHistoryCoverageRecord.REASON_ACCOUNT_CHANGED,
                            now(),
                        )
                    }
                }
            }
        }

        if (evidenceStore != null) {
            for (unit in units) {
                if (starts.getValue(unit).watermark != null && unit !in baselineWalk) {
                    runCatching { evidenceStore.clearImportProgress(providerId, unit) }
                }
            }
        }

        var countsBefore: TrackingWatchedHistoryProviderCounts? = null
        var countsFailure: String? = null
        if (evidencePort != null && counting && importUnits.any { carried[it]?.countsTakenAt == null }) {
            when (val outcome = evidencePort.readProviderCounts()) {
                is TrackingInboundOutcome.Ok -> countsBefore = outcome.value
                is TrackingInboundOutcome.Unauthorized -> countsFailure = outcome.reason
                is TrackingInboundOutcome.Unavailable -> countsFailure = outcome.reason
                is TrackingInboundOutcome.Rejected -> countsFailure = outcome.reason
            }
        }

        val limit = (pageLimit ?: reader.historyPageSize)
            .coerceIn(1, TrackingWatchedHistoryRequest.MAX_LIMIT)

        for (unit in units) {
            val saved = starts.getValue(unit)
            val baseline = unit in baselineWalk

            var position = if (baseline) {
                TrackingWatchedHistoryCursor(
                    watermark = null,
                    next = carried[unit]?.baselineCursor
                        ?: saved.next.takeIf { saved.watermark == null },
                )
            } else {
                saved
            }
            val measurement = UnitMeasurement()
            measurement.baseline = baseline
            measurements[unit] = measurement
            while (measurement.pages < maxPages) {
                val outcome = reader.readHistory(
                    TrackingWatchedHistoryRequest(
                        unit = unit,
                        cursor = position.next,
                        since = position.watermark,
                        limit = limit,
                    ),
                )
                val page = when (outcome) {
                    is TrackingInboundOutcome.Ok -> outcome.value
                    is TrackingInboundOutcome.Unauthorized -> {
                        failures[unit] = outcome.reason
                        break
                    }
                    is TrackingInboundOutcome.Unavailable -> {
                        failures[unit] = outcome.reason
                        break
                    }
                    is TrackingInboundOutcome.Rejected -> {
                        failures[unit] = outcome.reason
                        break
                    }
                }
                measurement.pages++
                pages++
                measurement.rows += page.records.size + page.malformed + page.undated + page.showLevelOnly
                measurement.malformed += page.malformed
                measurement.undated += page.undated
                measurement.showLevelOnly += page.showLevelOnly
                measurement.alreadyCovered += page.alreadyCovered
                malformed += page.malformed
                undated += page.undated
                showLevelOnly += page.showLevelOnly
                alreadyCovered += page.alreadyCovered

                measurement.absorbPageResidue(page)

                for (record in page.records) {

                    record.providerPlayId?.takeIf { it.isNotBlank() }?.let { measurement.playIds += it }
                    val key = dedupeKey(record)
                    if (!seen.add(key)) {
                        alreadyCovered++
                        measurement.alreadyCovered++
                        continue
                    }
                    val watchKey = resolve(record)
                    if (watchKey == null) {
                        skippedIdentity++
                        measurement.unresolvedIdentity++

                        measurement.residue(KIND_UNRESOLVED, record)
                        continue
                    }
                    measurement.units += watchKey
                    val observedAt = record.watchedAt
                    if (observedAt == null) {

                        undated++
                        measurement.undated++
                        measurement.residue(KIND_UNDATED, record)
                        continue
                    }
                    if (isAlreadyImported(watchKey, observedAt, record.providerPlayId)) {
                        alreadyCovered++
                        measurement.alreadyCovered++
                        continue
                    }
                    coordinator.importObservation(
                        connectorId = providerId,
                        watchKey = watchKey,
                        kind = WatchEvent.Kind.COMPLETED,

                        observedAt = observedAt,

                        positionMs = null,

                        providerPlayId = record.providerPlayId,
                    )
                    imported++
                    measurement.imported++
                }

                if (page.next == null) {

                    if (page.truncating) {

                        truncated = true
                        measurement.truncated = true
                    }

                    if (!baseline && evidenceStore != null && evidenceScope != null &&
                        saved.watermark != null
                    ) {
                        foldDeltaResidueIntoEvidence(unit, measurement, evidenceStore, evidenceScope)
                    }

                    position = TrackingWatchedHistoryCursor(
                        watermark = page.completedThrough ?: saved.watermark,
                        next = null,
                    )
                    cursorStore.save(providerId, unit, position)
                    completed += unit
                    measurement.walkCompleted = true
                    break
                }

                position = position.copy(next = page.next)

                if (baseline) {
                    measurement.baselineCursor = page.next
                } else {
                    cursorStore.save(providerId, unit, position)
                }
            }
            if (measurement.pages >= maxPages && unit !in completed && unit !in failures) {

                truncated = true
                measurement.truncated = true
            }
            distinctPlays += measurement.playIds.size
        }

        var countsAfter: TrackingWatchedHistoryProviderCounts? = null
        if (evidencePort != null && counting && importUnits.any { measurements[it]?.walkCompleted == true }) {
            countsAfter = when (val outcome = evidencePort.readProviderCounts()) {
                is TrackingInboundOutcome.Ok -> outcome.value
                else -> null
            }
        }

        val coverageRecords = LinkedHashMap<TrackingWatchedHistoryUnit, TrackingWatchedHistoryCoverageRecord>()
        if (counting && evidenceStore != null && evidenceScope != null) {
            val takenAt = now()
            for (unit in importUnits) {
                val measurement = measurements[unit] ?: continue

                val progress = accumulate(
                    unit = unit,
                    carried = carried[unit],
                    measurement = measurement,
                    countsBefore = countsBefore,
                    scope = evidenceScope,
                    takenAt = takenAt,
                    sweepStartedAt = sweepStartedAt,
                )
                val record = coverageRecord(
                    progress = progress,
                    countsAfter = countsAfter,
                    countsFailure = countsFailure,
                    failure = failures[unit],
                    fingerprint = evidenceScope.accountFingerprint,
                    takenAt = takenAt,
                )
                val finished = measurement.walkCompleted && !measurement.truncated && failures[unit] == null
                if (finished) {

                    if (!persistVerified(unit, evidenceStore, record)) {
                        invalidate(unit, evidenceStore, TrackingWatchedHistoryCoverageRecord.REASON_COVERAGE_NOT_PERSISTED)
                    }
                    runCatching { evidenceStore.clearImportProgress(providerId, unit) }
                } else {

                    runCatching { evidenceStore.saveImportProgress(progress) }
                    if (!persistVerified(unit, evidenceStore, record)) {
                        invalidate(unit, evidenceStore, TrackingWatchedHistoryCoverageRecord.REASON_COVERAGE_NOT_PERSISTED)
                    }
                }
                coverageRecords[unit] = record
            }
        }

        return TrackingWatchedHistorySweepResult(
            providerId = providerId,
            imported = imported,
            pages = pages,
            skippedIdentity = skippedIdentity,
            undated = undated,
            showLevelOnly = showLevelOnly,
            malformed = malformed,
            alreadyCovered = alreadyCovered,
            distinctPlays = distinctPlays,
            truncated = truncated,
            failures = failures,
            completed = completed,
            coverage = coverageRecords,

            baselineRequested = baselineWalk,
            baselineEstablished = baselineWalk.filterTo(LinkedHashSet()) { it in completed },
        )
    }

    private fun persistVerified(
        unit: TrackingWatchedHistoryUnit,
        store: TrackingWatchedHistoryCoverageStore,
        record: TrackingWatchedHistoryCoverageRecord,
    ): Boolean {
        if (runCatching { store.save(record) }.isFailure) return false
        val readBack = runCatching { store.coverage(providerId, unit) }.getOrNull() ?: return false

        return readBack == record.persistedForm()
    }

    private fun TrackingWatchedHistoryCoverageRecord.persistedForm(): TrackingWatchedHistoryCoverageRecord =
        copy(
            takenAt = Instant.ofEpochMilli(takenAt.toEpochMilli()),
            walkStartedAt = walkStartedAt?.let { Instant.ofEpochMilli(it.toEpochMilli()) },
            invalidatedAt = invalidatedAt?.let { Instant.ofEpochMilli(it.toEpochMilli()) },
        )

    private fun invalidate(
        unit: TrackingWatchedHistoryUnit,
        store: TrackingWatchedHistoryCoverageStore,
        reason: String,
    ) {
        runCatching { store.invalidate(providerId, unit, reason, now()) }
    }

    private fun foldDeltaResidueIntoEvidence(
        unit: TrackingWatchedHistoryUnit,
        measurement: UnitMeasurement,
        store: TrackingWatchedHistoryCoverageStore,
        scope: TrackingWatchedHistoryCoverageScope,
    ) {
        val added = measurement.residue.values.toList()
        val unreadable = measurement.malformed > 0 || measurement.residueUndescribed
        if (added.isEmpty() && !unreadable) return
        val stored = runCatching { store.coverage(providerId, unit) }.getOrNull() ?: return

        if (stored.accountFingerprint != null && stored.accountFingerprint != scope.accountFingerprint) return
        if (stored.invalidatedAt != null) return
        if (unreadable) {
            invalidate(unit, store, TrackingWatchedHistoryCoverageRecord.REASON_RESIDUE_STALE)
            return
        }
        val merged = mergeResidue(stored.residue, added)
        val pairs = merged.sumOf { it.externalIds.typed().size }
        val state = when {

            stored.residueState != TrackingWatchedHistoryCoverageRecord.RESIDUE_TRUSTED -> stored.residueState
            pairs > TrackingWatchedHistoryCoverageRecord.MAX_RESIDUE_ALIAS_PAIRS ->
                TrackingWatchedHistoryCoverageRecord.RESIDUE_TRUNCATED
            else -> TrackingWatchedHistoryCoverageRecord.RESIDUE_TRUSTED
        }
        val updated = stored.copy(
            residue = if (state == TrackingWatchedHistoryCoverageRecord.RESIDUE_TRUNCATED) {
                emptyList()
            } else {
                merged
            },
            residueState = state,
            identityEvidenceComplete = stored.identityEvidenceComplete &&
                state == TrackingWatchedHistoryCoverageRecord.RESIDUE_TRUSTED,
            materializationComplete = false,
            complete = false,
            reasons = (stored.reasons + added.map(::residueReason)).distinct(),
        )
        if (!persistVerified(unit, store, updated)) {
            invalidate(unit, store, TrackingWatchedHistoryCoverageRecord.REASON_COVERAGE_NOT_PERSISTED)
        }
    }

    private fun residueReason(row: TrackingWatchedHistoryResidueRecord): String = when (row.kind) {
        KIND_UNRESOLVED -> TrackingWatchedHistoryCoverageRecord.REASON_UNRESOLVED_IDENTITY
        KIND_UNDATED -> TrackingWatchedHistoryCoverageRecord.REASON_UNDATED_ROWS
        else -> TrackingWatchedHistoryCoverageRecord.REASON_SHOW_LEVEL_ONLY
    }

    private fun mergeResidue(
        existing: List<TrackingWatchedHistoryResidueRecord>,
        added: List<TrackingWatchedHistoryResidueRecord>,
    ): List<TrackingWatchedHistoryResidueRecord> {
        val merged = LinkedHashMap<String, TrackingWatchedHistoryResidueRecord>()
        for (row in existing + added) {
            merged.putIfAbsent(residueKeyOf(row), row)
        }
        return merged.values.toList()
    }

    private fun residueKeyOf(row: TrackingWatchedHistoryResidueRecord): String = buildString {
        append(row.kind)
        for (alias in row.externalIds.typed().sortedWith(compareBy({ it.authority }, { it.value }))) {
            append('|').append(alias.authority).append(':').append(alias.value)
        }
        append("|s=").append(row.seasonNumber ?: "")
        append("|e=").append(row.episodeNumber ?: "")
    }

    private fun accumulate(
        unit: TrackingWatchedHistoryUnit,
        carried: TrackingWatchedHistoryImportProgress?,
        measurement: UnitMeasurement,
        countsBefore: TrackingWatchedHistoryProviderCounts?,
        scope: TrackingWatchedHistoryCoverageScope,
        takenAt: Instant,
        sweepStartedAt: Instant,
    ): TrackingWatchedHistoryImportProgress {
        val observed = countsBefore?.forUnit(unit)

        val residue = mergeResidue(carried?.residue.orEmpty(), measurement.residue.values.toList())
        val worstSoFar = worseResidueState(
            carried?.residueState ?: TrackingWatchedHistoryCoverageRecord.RESIDUE_TRUSTED,
            if (measurement.residueUndescribed) {
                TrackingWatchedHistoryCoverageRecord.RESIDUE_UNREADABLE
            } else {
                TrackingWatchedHistoryCoverageRecord.RESIDUE_TRUSTED
            },
        )
        val pairs = residue.sumOf { it.externalIds.typed().size }
        val residueState = when {
            worstSoFar != TrackingWatchedHistoryCoverageRecord.RESIDUE_TRUSTED -> worstSoFar
            pairs > TrackingWatchedHistoryCoverageRecord.MAX_RESIDUE_ALIAS_PAIRS ->
                TrackingWatchedHistoryCoverageRecord.RESIDUE_TRUNCATED
            else -> TrackingWatchedHistoryCoverageRecord.RESIDUE_TRUSTED
        }
        return TrackingWatchedHistoryImportProgress(
            providerId = providerId,
            profileId = scope.profileId,
            accountFingerprint = scope.accountFingerprint,
            unit = unit,
            startedAt = carried?.startedAt ?: sweepStartedAt,
            sweeps = (carried?.sweeps ?: 0) + 1,
            pages = (carried?.pages ?: 0) + measurement.pages,
            rows = (carried?.rows ?: 0) + measurement.rows,
            imported = (carried?.imported ?: 0) + measurement.imported,
            distinctPlays = (carried?.distinctPlays ?: 0) + measurement.playIds.size,
            distinctUnits = (carried?.distinctUnits ?: 0) + measurement.units.size,
            malformed = (carried?.malformed ?: 0) + measurement.malformed,
            unresolvedIdentity = (carried?.unresolvedIdentity ?: 0) + measurement.unresolvedIdentity,
            undated = (carried?.undated ?: 0) + measurement.undated,
            showLevelOnly = (carried?.showLevelOnly ?: 0) + measurement.showLevelOnly,
            alreadyCovered = (carried?.alreadyCovered ?: 0) + measurement.alreadyCovered,
            walkCompleted = measurement.walkCompleted,
            truncated = measurement.truncated,
            providerItems = carried?.providerItems ?: observed?.items,
            providerPlays = carried?.providerPlays ?: observed?.plays,
            countsTakenAt = countsBefore?.observedAt ?: carried?.countsTakenAt,

            residue = residue,
            residueState = residueState,
            unresolvedDistinctItems = (carried?.unresolvedDistinctItems ?: 0) +
                measurement.unresolvedItems.size,
            unrepresentableDistinctItems = (carried?.unrepresentableDistinctItems ?: 0) +
                measurement.unrepresentableItems.size,
            seasonZero = (carried?.seasonZero ?: 0) + measurement.seasonZero,
            coordinateMissing = (carried?.coordinateMissing ?: 0) + measurement.coordinateMissing,
            coordinateInvalid = (carried?.coordinateInvalid ?: 0) + measurement.coordinateInvalid,
            coordinateNegative = (carried?.coordinateNegative ?: 0) + measurement.coordinateNegative,

            baselineEstablishment = measurement.baseline,
            baselineCursor = if (measurement.walkCompleted) null else measurement.baselineCursor,
        )
    }

    private fun worseResidueState(first: String, second: String): String =
        if (residueStateRank(second) > residueStateRank(first)) second else first

    private fun residueStateRank(state: String): Int = when (state) {
        TrackingWatchedHistoryCoverageRecord.RESIDUE_TRUSTED -> 0
        TrackingWatchedHistoryCoverageRecord.RESIDUE_ABSENT -> 1
        TrackingWatchedHistoryCoverageRecord.RESIDUE_TRUNCATED -> 2
        else -> 3
    }

    private fun coverageRecord(
        progress: TrackingWatchedHistoryImportProgress,
        countsAfter: TrackingWatchedHistoryProviderCounts?,
        countsFailure: String?,
        failure: String?,
        fingerprint: String?,
        takenAt: Instant,
    ): TrackingWatchedHistoryCoverageRecord {
        val beforeItems = progress.providerItems
        val after = countsAfter?.forUnit(progress.unit)
        val moved: Boolean? = if (beforeItems != null && after?.items != null) {
            beforeItems != after.items
        } else {
            null
        }
        val reasons = ArrayList<String>()
        if (failure != null) reasons += TrackingWatchedHistoryCoverageRecord.REASON_WALK_FAILED
        if (!progress.walkCompleted || progress.truncated) {
            reasons += TrackingWatchedHistoryCoverageRecord.REASON_WALK_INCOMPLETE
        }
        if (beforeItems == null) {
            reasons += TrackingWatchedHistoryCoverageRecord.REASON_PROVIDER_COUNT_UNAVAILABLE
        } else if (progress.walkCompleted && moved == null) {

            reasons += TrackingWatchedHistoryCoverageRecord.REASON_PROVIDER_COUNT_UNAVAILABLE
        }
        if (moved == true) reasons += TrackingWatchedHistoryCoverageRecord.REASON_PROVIDER_COUNT_MOVED
        if (progress.malformed > 0) reasons += TrackingWatchedHistoryCoverageRecord.REASON_MALFORMED_ROWS
        if (progress.unresolvedIdentity > 0) {
            reasons += TrackingWatchedHistoryCoverageRecord.REASON_UNRESOLVED_IDENTITY
        }
        if (progress.undated > 0) reasons += TrackingWatchedHistoryCoverageRecord.REASON_UNDATED_ROWS
        if (progress.showLevelOnly > 0) reasons += TrackingWatchedHistoryCoverageRecord.REASON_SHOW_LEVEL_ONLY

        val resolvedItems = progress.distinctUnits
        val unresolvedItems = progress.unresolvedDistinctItems
        val unrepresentableItems = progress.unrepresentableDistinctItems
        val reconciledExcluding = beforeItems != null &&
            resolvedItems + unresolvedItems == beforeItems
        val reconciledIncluding = beforeItems != null &&
            resolvedItems + unresolvedItems + unrepresentableItems == beforeItems
        val countReconciled: Boolean? = when {
            beforeItems == null -> null
            reconciledExcluding || reconciledIncluding -> true
            else -> false
        }
        val seasonZeroCounted: Boolean? = when {
            beforeItems == null -> null
            reconciledExcluding && reconciledIncluding -> null
            reconciledExcluding -> false
            reconciledIncluding -> true
            else -> null
        }
        if (beforeItems != null && countReconciled != true) {
            reasons += TrackingWatchedHistoryCoverageRecord.REASON_COUNT_MISMATCH
        }

        if (progress.residueState == TrackingWatchedHistoryCoverageRecord.RESIDUE_TRUNCATED) {
            reasons += TrackingWatchedHistoryCoverageRecord.REASON_RESIDUE_TRUNCATED
        } else if (progress.residueState != TrackingWatchedHistoryCoverageRecord.RESIDUE_TRUSTED) {

            reasons += TrackingWatchedHistoryCoverageRecord.REASON_RESIDUE_UNREADABLE
        }
        if (fingerprint == null) {
            reasons += TrackingWatchedHistoryCoverageRecord.REASON_ACCOUNT_UNKNOWN
        }
        if (countsFailure != null) {

            if (TrackingWatchedHistoryCoverageRecord.REASON_PROVIDER_COUNT_UNAVAILABLE !in reasons) {
                reasons += TrackingWatchedHistoryCoverageRecord.REASON_PROVIDER_COUNT_UNAVAILABLE
            }
        }

        val retrievalComplete = failure == null &&
            progress.walkCompleted &&
            !progress.truncated &&
            beforeItems != null &&
            moved == false &&
            countReconciled == true
        val identityEvidenceComplete = progress.malformed == 0 &&
            progress.residueState == TrackingWatchedHistoryCoverageRecord.RESIDUE_TRUSTED
        val materializationComplete = progress.malformed == 0 && progress.residue.isEmpty()
        val residueState = if (progress.residueState == TrackingWatchedHistoryCoverageRecord.RESIDUE_TRUNCATED) {

            TrackingWatchedHistoryCoverageRecord.RESIDUE_TRUNCATED
        } else {
            progress.residueState
        }
        return TrackingWatchedHistoryCoverageRecord(
            providerId = providerId,
            profileId = progress.profileId,
            accountFingerprint = fingerprint,
            unit = progress.unit,
            complete = reasons.isEmpty() && retrievalComplete && identityEvidenceComplete &&
                materializationComplete,
            walkCompleted = progress.walkCompleted,
            truncated = progress.truncated,
            pages = progress.pages,
            rows = progress.rows,
            distinctPlays = progress.distinctPlays,
            distinctUnits = progress.distinctUnits,
            imported = progress.imported,
            providerItems = beforeItems,
            providerPlays = progress.providerPlays,
            providerCountMovedDuringWalk = moved,
            malformed = progress.malformed,
            unresolvedIdentity = progress.unresolvedIdentity,
            undated = progress.undated,
            showLevelOnly = progress.showLevelOnly,
            alreadyCovered = progress.alreadyCovered,
            takenAt = takenAt,
            retrievalComplete = retrievalComplete,
            identityEvidenceComplete = identityEvidenceComplete,
            materializationComplete = materializationComplete,
            residue = if (residueState == TrackingWatchedHistoryCoverageRecord.RESIDUE_TRUSTED) {
                progress.residue
            } else {
                emptyList()
            },
            residueState = residueState,
            unresolvedDistinctItems = progress.unresolvedDistinctItems,
            seasonZero = progress.seasonZero,
            coordinateMissing = progress.coordinateMissing,
            coordinateInvalid = progress.coordinateInvalid,
            coordinateNegative = progress.coordinateNegative,
            seasonZeroCounted = seasonZeroCounted,
            countReconciled = countReconciled,
            walkStartedAt = progress.startedAt,
            reasons = reasons,
        )
    }

    private val nameIndexes =
        LinkedHashMap<TrackingWatchedHistoryUnit, Map<Pair<String, String>, TrackingWatchedItemName>>()

    private suspend fun statedNamesFor(
        unit: TrackingWatchedHistoryUnit,
    ): Map<Pair<String, String>, TrackingWatchedItemName> {
        nameIndexes[unit]?.let { return it }
        val port = itemNames
        if (port == null || maxNamePages <= 0) {
            val empty = emptyMap<Pair<String, String>, TrackingWatchedItemName>()
            nameIndexes[unit] = empty
            return empty
        }
        val index = LinkedHashMap<Pair<String, String>, TrackingWatchedItemName>()
        var page = 1
        while (page <= maxNamePages) {
            val outcome = port.readStatedNames(unit, page, NAME_PAGE_LIMIT)
            val names = when (outcome) {
                is TrackingInboundOutcome.Ok -> outcome.value
                is TrackingInboundOutcome.Unauthorized,
                is TrackingInboundOutcome.Unavailable,
                is TrackingInboundOutcome.Rejected,
                -> {
                    onIdentityDiagnostic?.invoke(Diagnostic.NAMES_UNAVAILABLE)
                    break
                }
            }
            onIdentityDiagnostic?.invoke(Diagnostic.NAMES_PAGE_READ)
            for (name in names.names) {
                for (alias in name.externalIds.typed()) {
                    index.putIfAbsent(alias.authority to alias.value, name)
                }
            }
            if (!names.hasMore) break
            page++
        }
        nameIndexes[unit] = index
        return index
    }

    private suspend fun statedNameFor(
        unit: TrackingWatchedHistoryUnit,
        ids: TrackingExternalIds,
    ): TrackingWatchedItemName? {
        val index = statedNamesFor(unit)
        if (index.isEmpty()) return null
        for (alias in ids.typed()) {
            index[alias.authority to alias.value]?.let { return it }
        }
        return null
    }

    private suspend fun resolve(record: TrackingWatchedHistoryRecord): WatchKey? {
        val kind = when (record.unit) {
            TrackingWatchedHistoryUnit.MOVIE -> TrackingMediaKind.MOVIE
            TrackingWatchedHistoryUnit.EPISODE -> TrackingMediaKind.SHOW
        }
        when (
            val lookup = identity.lookUp(
                ids = record.externalIds,
                kind = kind,
                seasonNumber = record.seasonNumber,
                episodeNumber = record.episodeNumber,
            )
        ) {
            is TrackingWatchKeyLookup.Resolved -> return lookup.watchKey
            is TrackingWatchKeyLookup.Ambiguous -> {
                onIdentityDiagnostic?.invoke(Diagnostic.AMBIGUOUS)
                return null
            }
            TrackingWatchKeyLookup.Unproven -> Unit
        }
        val completion = identityCompletion ?: run {
            onIdentityDiagnostic?.invoke(Diagnostic.NO_COMPLETION_SEAM)
            return null
        }

        var named = record.title
        var namedYear = record.year
        if (named.isNullOrBlank() && itemNames != null) {
            val stated = statedNameFor(record.unit, record.externalIds)
            if (stated != null) {
                named = stated.title
                namedYear = record.year ?: stated.year
            } else {
                onIdentityDiagnostic?.invoke(Diagnostic.NAMES_MISSING)
            }
        }
        val completed = completion.complete(
            ids = record.externalIds,
            kind = kind,
            seasonNumber = record.seasonNumber,
            episodeNumber = record.episodeNumber,

            title = named,
            year = namedYear,
        )
        if (completed is TrackingWatchKeyLookup.Resolved) {

            if (!named.isNullOrBlank() && record.title.isNullOrBlank()) {
                onIdentityDiagnostic?.invoke(Diagnostic.NAMES_RESOLVED)
            }
            return completed.watchKey
        }

        onIdentityDiagnostic?.invoke(
            if (record.title.isNullOrBlank()) Diagnostic.SKIP_NO_STATED_NAME else Diagnostic.SKIP_STATED_NAME,
        )
        onIdentityDiagnostic?.invoke(Diagnostic.aliasShape(record.externalIds))
        return null
    }

    private fun dedupeKey(record: TrackingWatchedHistoryRecord): String {
        record.providerPlayId?.takeIf { it.isNotBlank() }?.let { return "play|$it" }
        return buildString {
            append(record.unit.name)
            append('|').append(record.externalIds.imdbId ?: "")
            append('|').append(record.externalIds.tmdbId ?: "")
            append('|').append(record.externalIds.tvdbId ?: "")
            append('|').append(record.seasonNumber ?: "")
            append('|').append(record.episodeNumber ?: "")
            append('|').append(record.watchedAt ?: "")
        }
    }

    private class UnitMeasurement {
        var pages: Int = 0
        var rows: Int = 0
        var imported: Int = 0
        var malformed: Int = 0
        var undated: Int = 0
        var showLevelOnly: Int = 0
        var alreadyCovered: Int = 0
        var unresolvedIdentity: Int = 0
        var walkCompleted: Boolean = false
        var truncated: Boolean = false
        val units: MutableSet<WatchKey> = LinkedHashSet()
        val playIds: MutableSet<String> = LinkedHashSet()

        var baseline: Boolean = false
        var baselineCursor: String? = null

        val residue: LinkedHashMap<String, TrackingWatchedHistoryResidueRecord> = LinkedHashMap()

        var residueUndescribed: Boolean = false

        var seasonZero: Int = 0
        var coordinateMissing: Int = 0
        var coordinateInvalid: Int = 0
        var coordinateNegative: Int = 0

        val unresolvedItems: MutableSet<String> = LinkedHashSet()

        val unrepresentableItems: MutableSet<String> = LinkedHashSet()

        private val components = AliasComponents()

        fun residue(
            kind: String,
            ids: TrackingExternalIds,
            seasonNumber: Int? = null,
            episodeNumber: Int? = null,
        ) {
            if (ids.isEmpty) {

                residueUndescribed = true
                return
            }
            residue.putIfAbsent(
                residueKey(kind, ids, seasonNumber, episodeNumber),
                TrackingWatchedHistoryResidueRecord(
                    externalIds = ids,
                    kind = kind,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                ),
            )
            val item = itemKey(ids, seasonNumber, episodeNumber)
            when (kind) {
                KIND_UNRESOLVED -> unresolvedItems += item

                else -> unrepresentableItems += item
            }
        }

        fun residue(kind: String, record: TrackingWatchedHistoryRecord) {
            residue(kind, record.externalIds, record.seasonNumber, record.episodeNumber)
        }

        fun absorbPageResidue(page: TrackingWatchedHistoryPage) {
            if (page.residue.size != page.undated + page.showLevelOnly) residueUndescribed = true
            for (row in page.residue) {
                residue(row.kind, row.externalIds, row.seasonNumber, row.episodeNumber)
            }
            val split = page.seasonZero + page.coordinateMissing + page.coordinateInvalid +
                page.coordinateNegative
            seasonZero += page.seasonZero
            coordinateInvalid += page.coordinateInvalid
            coordinateNegative += page.coordinateNegative

            coordinateMissing += page.coordinateMissing +
                (page.showLevelOnly - split).coerceAtLeast(0)
        }

        private fun itemKey(
            ids: TrackingExternalIds,
            seasonNumber: Int?,
            episodeNumber: Int?,
        ): String = components.representative(ids) + "|" + (seasonNumber ?: "") + "|" + (episodeNumber ?: "")

        private fun residueKey(
            kind: String,
            ids: TrackingExternalIds,
            seasonNumber: Int?,
            episodeNumber: Int?,
        ): String = "$kind|${itemKey(ids, seasonNumber, episodeNumber)}"
    }

    private class AliasComponents {
        private val parent = HashMap<String, String>()

        fun representative(ids: TrackingExternalIds): String {
            val keys = ids.typed().map { "${it.authority}:${it.value}" }
            if (keys.isEmpty()) return ""
            var root = find(keys.first())
            for (key in keys.drop(1)) {
                val other = find(key)
                root = if (other < root) other else root
            }
            for (key in keys) union(key, root)
            return find(root)
        }

        private fun find(key: String): String {
            var current = key
            var seen = 0
            while (true) {
                val next = parent[current] ?: return current
                if (next == current || seen++ > 16) return current
                current = next
            }
        }

        private fun union(a: String, b: String) {
            val rootA = find(a)
            val rootB = find(b)
            if (rootA == rootB) return
            if (rootA < rootB) parent[rootB] = rootA else parent[rootA] = rootB
        }
    }

    companion object {

        const val NO_AUTHORIZATION: String = "tracker_not_connected"

        const val KIND_UNRESOLVED: String = TrackingWatchedHistoryResidueRecord.KIND_UNRESOLVED_IDENTITY
        const val KIND_UNDATED: String = TrackingWatchedHistoryResidueRecord.KIND_UNDATED

        const val NAME_PAGE_LIMIT: Int = 100

        const val MAX_NAME_PAGES: Int = 20

        object Diagnostic {

            const val AMBIGUOUS = "lookup_ambiguous"

            const val NO_COMPLETION_SEAM = "no_completion_seam"

            const val SKIP_NO_STATED_NAME = "skip_no_stated_name"

            const val SKIP_STATED_NAME = "skip_stated_name"

            const val NAMES_PAGE_READ = "names_page_read"

            const val NAMES_UNAVAILABLE = "names_unavailable"

            const val NAMES_MISSING = "names_missing"

            const val NAMES_RESOLVED = "names_resolved"

            fun aliasShape(ids: TrackingExternalIds): String = when {
                ids.imdbId != null && ids.tmdbId != null && ids.tvdbId != null -> "skip_alias_imdb_tmdb_tvdb"
                ids.imdbId != null && ids.tmdbId != null -> "skip_alias_imdb_tmdb"
                ids.imdbId != null && ids.tvdbId != null -> "skip_alias_imdb_tvdb"
                ids.tmdbId != null && ids.tvdbId != null -> "skip_alias_tmdb_tvdb"
                ids.imdbId != null -> "skip_alias_imdb_only"
                ids.tmdbId != null -> "skip_alias_tmdb_only"
                ids.tvdbId != null -> "skip_alias_tvdb_only"
                else -> "skip_alias_none"
            }
        }
    }
}

data class TrackingWatchedHistorySweepResult(
    val providerId: String,

    val imported: Int = 0,

    val pages: Int = 0,

    val truncated: Boolean = false,

    val skippedIdentity: Int = 0,

    val undated: Int = 0,

    val showLevelOnly: Int = 0,

    val malformed: Int = 0,

    val alreadyCovered: Int = 0,

    val distinctPlays: Int = 0,

    val failures: Map<TrackingWatchedHistoryUnit, String> = emptyMap(),

    val completed: Set<TrackingWatchedHistoryUnit> = emptySet(),

    val coverage: Map<TrackingWatchedHistoryUnit, TrackingWatchedHistoryCoverageRecord> = emptyMap(),

    val baselineRequested: Set<TrackingWatchedHistoryUnit> = emptySet(),
    val baselineEstablished: Set<TrackingWatchedHistoryUnit> = emptySet(),
) {
    val isEmpty: Boolean get() = imported == 0 && pages == 0

    fun describe(): String = buildString {
        append("provider=").append(providerId)
        append(" imported=").append(imported)
        append(" pages=").append(pages)
        append(" truncated=").append(truncated)
        append(" distinctPlays=").append(distinctPlays)
        append(" alreadyCovered=").append(alreadyCovered)
        append(" undated=").append(undated)
        append(" showLevelOnly=").append(showLevelOnly)
        append(" skippedIdentity=").append(skippedIdentity)
        append(" malformed=").append(malformed)
        if (failures.isNotEmpty()) {
            append(" failures=").append(failures.entries.joinToString(",") { "${it.key.name}:${it.value}" })
        }
        append(" completed=").append(completed.joinToString(",") { it.name })

        append(" baseline=").append(baselineRequested.joinToString(",") { it.name }.ifEmpty { "NONE" })
        append(" baselineEstablished=")
            .append(baselineEstablished.joinToString(",") { it.name }.ifEmpty { "NONE" })
        if (coverage.isNotEmpty()) {
            append(" coverage=").append(
                coverage.entries.joinToString(",") { (unit, record) ->
                    "${unit.name}:${if (record.complete) "COMPLETE" else "INCOMPLETE"}" +
                        "/units=${record.distinctUnits}/provider=${record.providerItems ?: "-"}" +
                        "/plays=${record.distinctPlays}" +

                        "/retrieval=${record.retrievalComplete}" +
                        "/identity=${record.identityEvidenceComplete}" +
                        "/materialized=${record.materializationComplete}" +
                        "/residue=${record.residueState}:${record.residue.size}" +
                        "/unresolvedItems=${record.unresolvedDistinctItems}" +
                        "/reconciled=${record.countReconciled ?: "-"}" +
                        "/seasonZeroCounted=${record.seasonZeroCounted ?: "-"}" +
                        "/seasonZero=${record.seasonZero}" +
                        "/coordMissing=${record.coordinateMissing}" +
                        "/coordInvalid=${record.coordinateInvalid}" +
                        "/coordNegative=${record.coordinateNegative}" +
                        if (record.reasons.isEmpty()) "" else "/reasons=${record.reasons.joinToString("+")}"
                },
            )
        }
    }
}
