package dev.infusion.tv.ui.presentation

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.model.watch.WatchState
import dev.infusion.core.model.watch.WatchStatus
import dev.infusion.feature.vertical.details.WatchedStateDemand
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

@Immutable
data class CardWatchedState(

    val watchedUnits: Set<WatchKey> = emptySet(),

    val inProgressUnits: Set<WatchKey> = emptySet(),
) {

    fun isWatched(watchKey: WatchKey?): Boolean = watchKey != null && watchKey in watchedUnits

    fun isInProgress(watchKey: WatchKey?): Boolean =
        watchKey != null && watchKey in inProgressUnits

    companion object {

        val None: CardWatchedState = CardWatchedState()

        fun of(states: List<WatchState>): CardWatchedState = CardWatchedState(
            watchedUnits = states.asSequence()
                .filter { it.status == WatchStatus.COMPLETED }
                .mapTo(LinkedHashSet()) { it.watchKey },
            inProgressUnits = states.asSequence()
                .filter { it.status == WatchStatus.IN_PROGRESS }
                .mapTo(LinkedHashSet()) { it.watchKey },
        )
    }
}

val LocalCardWatchedState = compositionLocalOf { CardWatchedState.None }

fun MediaPreview.cardWatchedUnitOrNull(): WatchKey? {

    watchKey?.let { return it }

    val canonicalTitleId = titleId ?: return null
    return when (kind) {
        TitleKind.MOVIE -> WatchKey.movie(canonicalTitleId)
        else -> null
    }
}

fun ContentCardPresentation.watchedMarkUnit(
    preview: MediaPreview,
    watchedState: CardWatchedState,
    markEligible: Boolean = true,
): WatchKey? = watchedMarkUnitFor(
    unit = preview.cardWatchedUnitOrNull(),
    watchedState = watchedState,
    markEligible = markEligible,
)

fun ContentCardPresentation.watchedMarkUnitFor(
    unit: WatchKey?,
    watchedState: CardWatchedState,
    markEligible: Boolean = true,
): WatchKey? {
    if (!cardBadges || !watchedCheckmark || !markEligible) return null
    val resolved = unit ?: return null
    return resolved.takeIf { watchedState.isWatched(it) }
}

fun interface CardWatchedDemand {

    fun publish(watchKey: WatchKey)

    companion object {

        val None: CardWatchedDemand = CardWatchedDemand { }
    }
}

val LocalCardWatchedDemand = compositionLocalOf { CardWatchedDemand.None }

class CoalescingCardWatchedDemand(
    private val demand: WatchedStateDemand,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : CardWatchedDemand {

    private val incoming = Channel<WatchKey>(Channel.UNLIMITED)

    init {
        scope.launch(dispatcher) {
            val batch = LinkedHashSet<WatchKey>()
            for (first in incoming) {
                batch.add(first)

                while (true) {
                    val next = incoming.tryReceive().getOrNull() ?: break
                    batch.add(next)
                }
                demand.demandAll(batch.toList())
                batch.clear()
            }
        }
    }

    override fun publish(watchKey: WatchKey) {

        incoming.trySend(watchKey)
    }
}

fun contentCardWatchedMarkKey(cardKey: String): String = "card-watched-mark:$cardKey"
