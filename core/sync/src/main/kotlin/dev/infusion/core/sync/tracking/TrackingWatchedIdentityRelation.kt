package dev.infusion.core.sync.tracking

import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingInboundAliasTablePort
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryResidueRecord
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryUnit
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.watch.WatchKey

internal object TrackingWatchedIdentityRelation {

    enum class State {
        PROVEN_MATCH,
        PROVEN_SEPARATION,
        UNKNOWN,
    }

    data class QuestionedUnit(
        val unit: TrackingWatchedHistoryUnit,
        val ids: TrackingExternalIds,
        val key: WatchKey?,
    ) {

        val canonicalTitleId: String? =
            key?.let { (it.seriesId ?: it.titleId)?.value?.toString() }
    }

    fun evaluate(
        residue: TrackingWatchedHistoryResidueRecord,
        questioned: QuestionedUnit,
        aliasTable: TrackingInboundAliasTablePort?,
    ): State {
        val residueAliases = runCatching { residue.externalIds.typed() }.getOrNull().orEmpty()
        if (residueAliases.isEmpty()) return State.UNKNOWN

        val questionedAliases = LinkedHashMap<String, String>()
        for (alias in runCatching { questioned.ids.typed() }.getOrNull().orEmpty()) {
            questionedAliases.putIfAbsent(alias.authority, alias.value)
        }
        val canonicalTitleId = questioned.canonicalTitleId
        if (canonicalTitleId != null && aliasTable != null) {
            val titleId = runCatching {
                dev.infusion.core.model.TitleId.fromString(canonicalTitleId)
            }.getOrNull()
            if (titleId != null) {
                for (alias in runCatching { aliasTable.aliasesOf(titleId) }.getOrNull().orEmpty()) {
                    questionedAliases.putIfAbsent(alias.authority, alias.value)
                }
            }
        }

        val questionedTitleIds = LinkedHashSet<String>()
        canonicalTitleId?.let { questionedTitleIds += it }
        if (aliasTable != null) {
            runCatching { aliasTable.titleIdsFor(questioned.ids) }.getOrNull()
                ?.forEach { questionedTitleIds += it.value.toString() }
        }

        for (alias in residueAliases) {
            if (questionedAliases[alias.authority] == alias.value) return State.PROVEN_MATCH
        }

        var separationByOwnership = false
        if (aliasTable != null) {
            for (alias in residueAliases) {
                val owners = runCatching {
                    aliasTable.titleIdsFor(
                        TrackingExternalIds.fromTyped(listOf(TypedExternalId(alias.authority, alias.value))),
                    )
                }.getOrNull().orEmpty()
                for (owner in owners) {
                    val ownerId: String = owner.value.toString()
                    if (ownerId in questionedTitleIds) return State.PROVEN_MATCH
                    separationByOwnership = true
                }
            }
        }
        if (separationByOwnership && questionedTitleIds.isNotEmpty()) return State.PROVEN_SEPARATION

        for (alias in residueAliases) {
            val questionedValue = questionedAliases[alias.authority] ?: continue
            if (questionedValue != alias.value) return State.PROVEN_SEPARATION
        }

        return State.UNKNOWN
    }
}
