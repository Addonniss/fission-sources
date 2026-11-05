package dev.infusion.tv.ui.presentation

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.source.jellyfin.catalog.CARD_PROGRESS_BATCH_MAX
import dev.infusion.source.jellyfin.catalog.ServerItemProgress
import dev.infusion.source.jellyfin.catalog.ServerProgressBatch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class CardServerProgress(

    val permilleByTitleId: Map<TitleId, Int> = emptyMap(),

    val settledTitleIds: Set<TitleId> = emptySet(),

    val observedAtByTitleId: Map<TitleId, java.time.Instant?> = emptyMap(),
) {

    fun fractionFor(titleId: TitleId?): Double? {
        val permille = titleId?.let { permilleByTitleId[it] } ?: return null
        if (permille <= 0 || permille >= 1000) return null
        return permille / 1000.0
    }

    fun statesNoProgress(titleId: TitleId?): Boolean = titleId != null && titleId in settledTitleIds

    companion object {

        val None: CardServerProgress = CardServerProgress()

        fun of(items: List<ServerItemProgress>): CardServerProgress {
            val permilles = LinkedHashMap<TitleId, Int>()
            val settled = LinkedHashSet<TitleId>()
            val observed = LinkedHashMap<TitleId, java.time.Instant?>()
            for (item in items) {
                if (item.kind != TitleKind.MOVIE) continue
                val titleId = item.titleId ?: continue
                if (item.positionMs <= 0L) {

                    settled += titleId
                    permilles.remove(titleId)
                    observed[titleId] = item.observedAt
                    continue
                }
                val runtimeMs = item.runtimeMs?.takeIf { it > 0L }
                if (runtimeMs == null) {

                    continue
                }
                val fraction = item.positionMs.toDouble() / runtimeMs.toDouble()
                if (fraction >= 1.0) {

                    settled += titleId
                    permilles.remove(titleId)
                    observed[titleId] = item.observedAt
                    continue
                }

                permilles[titleId] = (fraction * 1000.0).roundToInt().coerceIn(1, 999)
                observed[titleId] = item.observedAt
                settled.remove(titleId)
            }
            return CardServerProgress(
                permilleByTitleId = permilles,
                settledTitleIds = settled,
                observedAtByTitleId = observed,
            )
        }
    }
}

interface CardResumeDemand {

    fun publish(titleId: TitleId)

    fun withdraw(titleId: TitleId) {}

    companion object {

        val None: CardResumeDemand = object : CardResumeDemand {
            override fun publish(titleId: TitleId) = Unit
        }
    }
}

val LocalCardResumeDemand = compositionLocalOf { CardResumeDemand.None }

data class ServerProgressIdentity(
    val sourceId: String,
    val userId: String,
)

class JellyfinServerProgressOwner(
    private val scope: CoroutineScope,
    private val identity: () -> ServerProgressIdentity?,
    private val resolveNativeIds: suspend (List<TitleId>) -> Map<TitleId, String>,
    private val read: suspend (List<String>) -> ServerProgressBatch?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val freshnessMs: Long = FRESH_MS,
    private val batchSize: Int = CARD_PROGRESS_BATCH_MAX,
    private val maxCached: Int = MAX_CACHED,
    private val maxVisible: Int = MAX_VISIBLE,
    private val batchIntervalMs: Long = MIN_BATCH_INTERVAL_MS,
    private val failureBackoffMs: Long = FAILURE_BACKOFF_MS,
) : CardResumeDemand {

    private val _progress = MutableStateFlow(CardServerProgress.None)

    val progress: StateFlow<CardServerProgress> = _progress.asStateFlow()

    private val demands = Channel<Unit>(Channel.CONFLATED)

    private val visible = ConcurrentHashMap<TitleId, Long>()

    private val cached = object : LinkedHashMap<TitleId, CachedUnit>(16, 0.75f, true) {}

    private var lastBatchAtMs = Long.MIN_VALUE
    private var backoffUntilMs = Long.MIN_VALUE

    init {
        scope.launch(dispatcher) {
            for (unused in demands) {
                val askedFor = identity() ?: continue

                val now = clock()
                val wanted = visible.keys.filterNot { isFresh(cached[it], now) }
                if (wanted.isNotEmpty()) {
                    val nativeIds = resolveNativeIds(wanted)
                    if (identity() != askedFor) continue
                    val batches = nativeIds.values.distinct().chunked(batchSize.coerceAtLeast(1))
                    for (batch in batches) {
                        waitForSlot()
                        if (identity() != askedFor) break
                        lastBatchAtMs = clock()
                        val answer = read(batch)
                        if (answer == null) {

                            backoffUntilMs = clock() + failureBackoffMs
                            continue
                        }
                        if (answer.sourceId != askedFor.sourceId || answer.userId != askedFor.userId) {
                            continue
                        }

                        if (identity() != askedFor) break
                        val at = clock()
                        for (item in answer.items) {
                            val titleId = item.titleId ?: continue
                            cached[titleId] = CachedUnit(item, at, askedFor)
                        }
                        prune(at)
                        publishValue(at, askedFor)
                    }
                }

                val at = clock()
                publishValue(at, askedFor)
            }
        }
    }

    override fun publish(titleId: TitleId) {
        val at = clock()
        visible[titleId] = at
        if (visible.size > maxVisible) {

            visible.entries.minByOrNull { it.value }?.let { visible.remove(it.key, it.value) }
        }
        demands.trySend(Unit)
    }

    override fun withdraw(titleId: TitleId) {
        visible.remove(titleId)
    }

    fun invalidate() {
        cached.clear()
        backoffUntilMs = Long.MIN_VALUE
        lastBatchAtMs = Long.MIN_VALUE
        _progress.value = CardServerProgress.None
        if (visible.isNotEmpty()) demands.trySend(Unit)
    }

    fun refreshVisible() {
        if (visible.isEmpty()) return
        cached.clear()
        backoffUntilMs = Long.MIN_VALUE
        demands.trySend(Unit)
    }

    private fun isFresh(unit: CachedUnit?, now: Long): Boolean {
        if (unit == null) return false
        if (now - unit.atMs > freshnessMs) return false
        return unit.identity == identity()
    }

    private suspend fun waitForSlot() {
        val now = clock()
        val notBefore = maxOf(
            if (lastBatchAtMs == Long.MIN_VALUE) Long.MIN_VALUE else lastBatchAtMs + batchIntervalMs,
            backoffUntilMs,
        )
        if (notBefore > now) delay(notBefore - now)
    }

    private fun prune(now: Long) {
        val expired = cached.entries.filter { now - it.value.atMs > freshnessMs }.map { it.key }
        expired.forEach { cached.remove(it) }
        while (cached.size > maxCached) {
            val oldest = cached.entries.firstOrNull() ?: break
            cached.remove(oldest.key)
        }
    }

    private fun publishValue(now: Long, askedFor: ServerProgressIdentity) {
        val items = visible.keys.mapNotNull { titleId ->
            val unit = cached[titleId] ?: return@mapNotNull null
            if (now - unit.atMs > freshnessMs) return@mapNotNull null
            if (unit.identity != askedFor) return@mapNotNull null
            unit.item
        }
        _progress.value = CardServerProgress.of(items)
    }

    private class CachedUnit(
        val item: ServerItemProgress,
        val atMs: Long,
        val identity: ServerProgressIdentity,
    )

    companion object {

        const val FRESH_MS = 30_000L

        const val MAX_CACHED = 256

        const val MAX_VISIBLE = 128

        const val MIN_BATCH_INTERVAL_MS = 250L

        const val FAILURE_BACKOFF_MS = 5_000L
    }
}
