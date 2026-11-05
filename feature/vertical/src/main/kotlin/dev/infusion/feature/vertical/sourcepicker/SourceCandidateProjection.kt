package dev.infusion.feature.vertical.sourcepicker

import dev.infusion.core.model.CapabilityState
import dev.infusion.core.model.ExpectedMode
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.Reachability
import dev.infusion.feature.vertical.model.PickerRow
import dev.infusion.feature.vertical.model.PickerRowState
import java.time.Instant

object SourceCandidateProjection {

    fun toRowState(candidate: PlayableCandidate): PickerRowState = when {
        candidate.expectedMode == ExpectedMode.EXTERNAL_LINK -> PickerRowState.ExternalLink(candidate)
        candidate.reachability == Reachability.UNREACHABLE -> PickerRowState.TimedOut(candidate)
        candidate.expiresAt != null && candidate.expiresAt!!.isBefore(Instant.now()) ->
            PickerRowState.Expired(candidate)
        candidate.capability?.state == CapabilityState.UNSUPPORTED ->
            PickerRowState.Unsupported(candidate, "Not compatible with this device.")
        else -> PickerRowState.Ready(candidate)
    }

    fun toPickerRow(candidate: PlayableCandidate): PickerRow = PickerRow(
        key = candidate.candidateId.value.toString(),
        state = toRowState(candidate),
        providerKey = candidate.sourceId.value.toString(),
        providerLabel = candidate.providerName,

        sizeBytes = candidate.facts.sizeBytes,
    )

    fun isEligible(row: PickerRow): Boolean = row.state is PickerRowState.Ready

    fun mergeRows(
        existing: List<PickerRow>,
        incoming: List<PlayableCandidate>,
        complete: Boolean,
    ): List<PickerRow> {
        val byKey = LinkedHashMap<String, PickerRow>()
        if (complete) {
            incoming.forEach { candidate ->
                byKey[candidate.candidateId.value.toString()] = toPickerRow(candidate)
            }
            existing.forEach { row -> if (!byKey.containsKey(row.key)) byKey[row.key] = row }
        } else {
            existing.forEach { row -> byKey[row.key] = row }
            incoming.forEach { candidate ->
                byKey[candidate.candidateId.value.toString()] = toPickerRow(candidate)
            }
        }
        return byKey.values.toList()
    }

    fun allOrderedBySizeDescending(rows: List<PickerRow>): List<PickerRow> =
        rows.sortedWith(
            compareByDescending<PickerRow> { it.sizeBytes != null }
                .thenByDescending { it.sizeBytes ?: 0L },
        )
}
