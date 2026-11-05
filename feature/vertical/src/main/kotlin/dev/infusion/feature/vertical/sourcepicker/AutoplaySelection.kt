package dev.infusion.feature.vertical.sourcepicker

import dev.infusion.core.playback.api.SourceAutoplayPolicy
import dev.infusion.feature.vertical.model.PickerRow
import dev.infusion.feature.vertical.model.PickerRowState

data class AutoplaySelection(
    val policy: SourceAutoplayPolicy,
    val preferJellyfin: Boolean,
    val jellyfinSourceId: String? = null,
) {

    val enabled: Boolean get() = policy.enabled
}

sealed interface AutoplayDecision {

    data object Wait : AutoplayDecision

    data class Winner(val key: String) : AutoplayDecision

    data object None : AutoplayDecision
}

object AutoplaySelector {

    fun decide(
        plan: AutoplaySelection,
        selectionOrder: List<PickerRow>,
        allOrdered: List<PickerRow>,
        jellyfinTerminal: Boolean,
        complete: Boolean,
    ): AutoplayDecision {
        if (!plan.enabled) return AutoplayDecision.None

        val jellyfinId = plan.jellyfinSourceId
        val isJellyfin: (PickerRow) -> Boolean = { jellyfinId != null && it.providerKey == jellyfinId }

        if (plan.preferJellyfin && jellyfinId != null) {
            val winner = selectionOrder.firstOrNull { isJellyfin(it) && isEligible(it) }
            if (winner != null) return AutoplayDecision.Winner(winner.key)

            if (!jellyfinTerminal) return AutoplayDecision.Wait
        }

        return when (plan.policy) {
            SourceAutoplayPolicy.OFF -> AutoplayDecision.None

            SourceAutoplayPolicy.FIRST_RESPONDING_ADDON -> {
                val winner = selectionOrder.firstOrNull { !isJellyfin(it) && isEligible(it) }
                when {
                    winner != null -> AutoplayDecision.Winner(winner.key)

                    complete -> AutoplayDecision.None
                    else -> AutoplayDecision.Wait
                }
            }

            SourceAutoplayPolicy.FIRST_COMBINED_ADDON_RESULT -> {
                if (!complete) {
                    AutoplayDecision.Wait
                } else {
                    val winner = allOrdered.firstOrNull { !isJellyfin(it) && isEligible(it) }
                    if (winner != null) AutoplayDecision.Winner(winner.key) else AutoplayDecision.None
                }
            }
        }
    }

    private fun isEligible(row: PickerRow): Boolean = row.state is PickerRowState.Ready
}
