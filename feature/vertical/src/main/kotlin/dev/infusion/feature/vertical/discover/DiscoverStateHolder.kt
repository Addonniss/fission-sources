package dev.infusion.feature.vertical.discover

import dev.infusion.core.domain.catalog.ShelfDeliveryMetrics
import dev.infusion.core.domain.catalog.ShelfDeliveryRecord
import dev.infusion.core.model.profile.Profile
import dev.infusion.feature.vertical.browse.ProgressiveShelfAccumulator
import dev.infusion.feature.vertical.browse.hasVisibleContent
import dev.infusion.source.aggregation.discover.DiscoverShelf
import dev.infusion.source.aggregation.discover.DiscoverShelfDeclaration
import dev.infusion.source.aggregation.discover.DiscoverShelfEmission
import dev.infusion.source.aggregation.discover.ShelfState
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface DiscoverPort {

    fun viewerProfile(): Profile

    fun primaryProfile(): Profile

    suspend fun shelves(viewer: Profile, primary: Profile): List<DiscoverShelf>

    fun shelfStream(viewer: Profile, primary: Profile): Flow<DiscoverShelfEmission>

    suspend fun shelfPage(
        viewer: Profile,
        primary: Profile,
        shelfKey: String,
        skip: Int,
    ): DiscoverShelf?

    fun declaredShelfKeys(viewer: Profile, primary: Profile): List<String>

    fun declaredShelfDeclarations(viewer: Profile, primary: Profile): List<DiscoverShelfDeclaration> =
        declaredShelfKeys(viewer, primary).map { key ->
            DiscoverShelfDeclaration(key = key, title = "", sourceLabel = "")
        }

    suspend fun shelfFirstPage(viewer: Profile, primary: Profile, shelfKey: String): DiscoverShelf? = null
}

data class DiscoverUiState(
    val shelves: List<DiscoverShelf> = emptyList(),
    val loading: Boolean = false,
    val viewerProfileId: String = "",

    val pagingFailedShelfKeys: Set<String> = emptySet(),

    val pagingFailureTokens: Map<String, Long> = emptyMap(),

    val declaredShelfKeys: List<String> = emptyList(),

    val configurationGeneration: Int = 0,
)

class DiscoverStateHolder(
    private val port: DiscoverPort,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,

    private val metrics: ShelfDeliveryMetrics = ShelfDeliveryMetrics.None,
) {

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    private val accumulator = ProgressiveShelfAccumulator()

    private val publishLock = Any()

    private val generation = AtomicInteger(0)

    private var delivery: Job? = null

    private var appliedConfiguration: List<DiscoverShelfDeclaration>? = null

    private val owedRowsInFlight = mutableSetOf<String>()

    private val pagingInFlight = mutableSetOf<String>()

    init {
        refresh()
    }

    fun refresh() {
        val gen = synchronized(publishLock) {
            val next = generation.incrementAndGet()
            accumulator.begin()

            _state.value = _state.value.copy(
                loading = true,
                pagingFailedShelfKeys = emptySet(),

                pagingFailureTokens = emptyMap(),
            )
            next
        }

        delivery?.cancel()
        delivery = scope.launch(ioDispatcher) {
            val viewer = port.viewerProfile()
            val primary = port.primaryProfile()

            val declarations = port.declaredShelfDeclarations(viewer, primary)
            val declaredKeys = declarations.map { declaration -> declaration.key }
            val startedAtNanos = System.nanoTime()
            var firstRowMs: Long? = null
            var firstContentMs = -1L
            synchronized(publishLock) {
                if (generation.get() == gen) {

                    appliedConfiguration = declarations
                    _state.value = _state.value.copy(declaredShelfKeys = declaredKeys)
                }
            }
            try {
                port.shelfStream(viewer, primary).collect { emission ->

                    var publishedRows = -1
                    synchronized(publishLock) {
                        if (generation.get() == gen) {
                            accumulator.accept(emission)
                            val snapshot = accumulator.snapshot()
                            _state.value = DiscoverUiState(
                                shelves = snapshot,
                                loading = true,
                                viewerProfileId = viewer.profileId,

                                pagingFailedShelfKeys = _state.value.pagingFailedShelfKeys,

                                pagingFailureTokens = _state.value.pagingFailureTokens,

                                declaredShelfKeys = declaredKeys,

                                configurationGeneration = _state.value.configurationGeneration,
                            )
                            publishedRows = snapshot.size
                        }
                    }
                    if (publishedRows < 0) return@collect
                    if (firstRowMs == null) {
                        val ms = elapsedMs(startedAtNanos)
                        val contentMs = if (emission.shelf.hasVisibleContent()) ms else -1L
                        firstRowMs = ms
                        firstContentMs = contentMs
                        metrics.measured(
                            ShelfDeliveryRecord(
                                journey = JOURNEY,
                                generation = gen,
                                phase = ShelfDeliveryRecord.Phase.FIRST_ROW,
                                rows = publishedRows,
                                firstRowMs = ms,
                                firstContentMs = contentMs,
                                allRowsMs = null,
                            ),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {

            }
            var completed: List<DiscoverShelf>? = null
            synchronized(publishLock) {
                if (generation.get() == gen) {
                    val shelves = accumulator.complete()
                    _state.value = DiscoverUiState(
                        shelves = shelves,
                        loading = false,
                        viewerProfileId = viewer.profileId,

                        pagingFailedShelfKeys = _state.value.pagingFailedShelfKeys,
                        pagingFailureTokens = _state.value.pagingFailureTokens,

                        declaredShelfKeys = declaredKeys,

                        configurationGeneration = _state.value.configurationGeneration,
                    )
                    completed = shelves
                }
            }
            val shelves = completed ?: return@launch
            metrics.measured(
                ShelfDeliveryRecord(
                    journey = JOURNEY,
                    generation = gen,
                    phase = ShelfDeliveryRecord.Phase.COMPLETE,
                    rows = shelves.size,

                    firstRowMs = firstRowMs ?: -1L,
                    firstContentMs = firstContentMs,
                    allRowsMs = elapsedMs(startedAtNanos),
                ),
            )
        }
    }

    fun onEnter() {
        scope.launch(ioDispatcher) {
            val viewer = port.viewerProfile()
            val primary = port.primaryProfile()

            val effective = port.declaredShelfDeclarations(viewer, primary)
            var owed: List<String> = emptyList()
            synchronized(publishLock) {

                val applied = appliedConfiguration ?: return@launch

                if (effective == applied) return@launch

                generation.incrementAndGet()
                owed = accumulator.reconcile(effective)
                appliedConfiguration = effective
                _state.value = _state.value.copy(

                    loading = false,
                    shelves = accumulator.snapshot(),
                    declaredShelfKeys = effective.map { declaration -> declaration.key },

                    configurationGeneration = _state.value.configurationGeneration + 1,
                )
            }

            val superseded = delivery
            delivery = coroutineContext[Job]
            superseded?.cancel()
            owed.forEach { key -> fetchOwedRow(key, viewer, primary) }
        }
    }

    private suspend fun fetchOwedRow(
        shelfKey: String,
        viewer: Profile,
        primary: Profile,
    ) {

        if (!synchronized(publishLock) { owedRowsInFlight.add(shelfKey) }) return
        var shelf: DiscoverShelf? = null
        try {
            shelf = port.shelfFirstPage(viewer, primary, shelfKey)
        } catch (cancelled: CancellationException) {
            synchronized(publishLock) { owedRowsInFlight.remove(shelfKey) }
            throw cancelled
        } catch (failure: Throwable) {

            shelf = null
        }
        val resolved = shelf
        synchronized(publishLock) {
            owedRowsInFlight.remove(shelfKey)
            if (resolved == null) return
            val order = appliedConfiguration?.indexOfFirst { it.key == shelfKey } ?: -1
            if (order < 0) return
            accumulator.publish(order, resolved)
            _state.value = _state.value.copy(shelves = accumulator.snapshot())
        }
    }

    private fun elapsedMs(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / 1_000_000

    fun loadMore(shelfKey: String) {
        val request = synchronized(publishLock) {
            val shelf = _state.value.shelves.firstOrNull { it.key == shelfKey } ?: return
            val loaded = shelf.state as? ShelfState.Loaded ?: return
            if (!loaded.hasMore) return
            if (!pagingInFlight.add(shelfKey)) return
            PagingRequest(pageGeneration = generation.get(), skip = loaded.items.size)
        }
        scope.launch(ioDispatcher) {
            var page: DiscoverShelf? = null

            var portThrew = false
            try {
                page = port.shelfPage(
                    viewer = port.viewerProfile(),
                    primary = port.primaryProfile(),
                    shelfKey = shelfKey,
                    skip = request.skip,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {

                portThrew = true
            } finally {

                synchronized(publishLock) { pagingInFlight.remove(shelfKey) }
            }
            applyPage(
                shelfKey = shelfKey,
                pageGeneration = request.pageGeneration,
                page = page,
                portThrew = portThrew,
            )
        }
    }

    private fun applyPage(
        shelfKey: String,
        pageGeneration: Int,
        page: DiscoverShelf?,
        portThrew: Boolean,
    ) {
        synchronized(publishLock) {
            if (generation.get() != pageGeneration) return
            val loaded = page?.state as? ShelfState.Loaded
            if (loaded != null) {

                if (!accumulator.append(shelfKey, loaded.items, loaded.hasMore)) return
                _state.value = _state.value.copy(
                    shelves = accumulator.snapshot(),
                    pagingFailedShelfKeys = _state.value.pagingFailedShelfKeys - shelfKey,

                    pagingFailureTokens = _state.value.pagingFailureTokens - shelfKey,
                )
                return
            }
            if (page != null || portThrew) {
                val failures = _state.value.pagingFailureTokens
                _state.value = _state.value.copy(
                    pagingFailedShelfKeys = _state.value.pagingFailedShelfKeys + shelfKey,

                    pagingFailureTokens = failures + (shelfKey to (failures[shelfKey] ?: 0L) + 1L),
                )
            }
        }
    }

    private data class PagingRequest(val pageGeneration: Int, val skip: Int)

    private companion object {

        const val JOURNEY = "discover"
    }
}
