package dev.infusion.tv.ui.presentation

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.model.watch.WatchState
import dev.infusion.core.model.watch.WatchStatus
import kotlin.math.roundToInt

@Immutable
data class CardProgress(

    val permilleByUnit: Map<WatchKey, Int> = emptyMap(),
) {

    fun fractionFor(unit: WatchKey?): Double? {
        val permille = unit?.let { permilleByUnit[it] } ?: return null
        if (permille <= 0 || permille >= 1000) return null
        return permille / 1000.0
    }

    companion object {

        val None: CardProgress = CardProgress()

        fun of(
            states: List<WatchState>,
            server: CardServerProgress = CardServerProgress.None,
        ): CardProgress {
            val out = LinkedHashMap<WatchKey, Int>()

            val localObservedByTitle = LinkedHashMap<dev.infusion.core.model.TitleId, java.time.Instant?>()
            for (state in states) {
                val titleId = state.watchKey.titleId ?: continue
                val at = state.lastMeaningfulAt
                val existing = localObservedByTitle[titleId]
                if (existing == null || (at != null && at.isAfter(existing))) {
                    localObservedByTitle[titleId] = at
                }
            }
            fun serverOwns(titleId: dev.infusion.core.model.TitleId): Boolean =
                dev.infusion.feature.vertical.media.ResumeAuthority.serverDecides(
                    serverObservedAt = server.observedAtByTitleId[titleId],
                    localObservedAt = localObservedByTitle[titleId],

                    localObservationPresent = localObservedByTitle.containsKey(titleId),
                )

            for ((titleId, permille) in server.permilleByTitleId) {
                if (permille <= 0 || permille >= 1000) continue
                if (!serverOwns(titleId)) continue
                out[WatchKey.movie(titleId)] = permille
            }

            val completedTitleIds = LinkedHashSet<dev.infusion.core.model.TitleId>()
            for (state in states) {
                if (state.status != WatchStatus.COMPLETED) continue
                state.watchKey.titleId?.let { completedTitleIds += it }
            }
            for (state in states) {
                if (state.status != WatchStatus.IN_PROGRESS) continue
                val unit = state.watchKey
                if (unit in out) continue
                val titleId = unit.titleId

                if (titleId != null && titleId in completedTitleIds) continue
                val fraction = state.completionFraction
                if (!fraction.isFinite() || fraction <= 0.0 || fraction >= 1.0) continue

                if (titleId != null && server.statesNoProgress(titleId) && serverOwns(titleId)) continue

                val permille = (fraction * 1000.0).roundToInt()
                if (permille <= 0 || permille >= 1000) continue
                out[unit] = permille
            }
            return CardProgress(out)
        }
    }
}

val LocalCardProgress = compositionLocalOf { CardProgress.None }
