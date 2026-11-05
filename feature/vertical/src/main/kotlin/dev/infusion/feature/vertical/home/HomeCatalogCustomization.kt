package dev.infusion.feature.vertical.home

import dev.infusion.core.domain.catalog.ShelfDeliveryMetrics
import dev.infusion.core.domain.catalog.ShelfDeliveryRecord
import dev.infusion.core.domain.home.HomePresentationDefaults
import dev.infusion.core.domain.home.HomePresentationFamily
import dev.infusion.core.domain.home.HomePresentationPolicy
import dev.infusion.core.domain.home.HomePresentationStore
import dev.infusion.core.domain.home.HomeWidgetConfiguration
import dev.infusion.core.domain.publist.PublicListProvider
import dev.infusion.core.domain.publist.PublicListRef
import dev.infusion.core.model.profile.Profile
import dev.infusion.feature.vertical.browse.ProgressiveShelfAccumulator
import dev.infusion.feature.vertical.browse.hasVisibleContent
import dev.infusion.feature.vertical.collections.CollectionGroupRow
import dev.infusion.source.aggregation.discover.DiscoverItem
import dev.infusion.source.aggregation.discover.DiscoverShelf
import dev.infusion.source.aggregation.discover.DiscoverShelfEmission
import dev.infusion.source.aggregation.discover.ShelfState
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

data class HomeCatalogKey(
    val addonId: String,
    val catalogType: String,
    val catalogId: String,
) {
    val shelfKey: String
        get() = "addon:$addonId:catalog:$catalogType:$catalogId"
}

data class HomeCatalogOption(
    val key: HomeCatalogKey,
    val addonDisplayName: String,
    val displayName: String,
)

data class HomeProviderHomePreference(
    val addonId: String,
    val hiddenFromHome: Boolean,
)

data class HomePresentationPreferences(

    val showProviderName: Boolean = HomePresentationDefaults.SHOW_PROVIDER_NAME,

    val separateContinueWatching: Boolean = HomePresentationDefaults.SEPARATE_CONTINUE_WATCHING,

    val spotlightAutoRollSeconds: Int = HomePresentationDefaults.SPOTLIGHT_AUTO_ROLL_SECONDS,

    val showSpotlight: Boolean = HomePresentationDefaults.SHOW_SPOTLIGHT,

    val spotlightSource: HomeSpotlightSource = HomeSpotlightSource.Automatic,
)

fun homeProviderOrder(
    persisted: List<HomeProviderHomePreference>,
    knownAddonIds: List<String>,
): List<HomeProviderHomePreference> {
    val known = knownAddonIds.distinct()
    val knownSet = known.toSet()
    val ordered = persisted.filter { it.addonId in knownSet }.distinctBy { it.addonId }
    val seen = ordered.mapTo(HashSet()) { it.addonId }
    return ordered + known.filterNot { it in seen }.map { HomeProviderHomePreference(it, hiddenFromHome = false) }
}

fun orderHomeCatalogKeys(
    selected: List<HomeCatalogKey>,
    providerOrder: List<HomeProviderHomePreference>,
): List<HomeCatalogKey> {
    val hidden = providerOrder.filter { it.hiddenFromHome }.mapTo(HashSet()) { it.addonId }
    val providerRank = providerOrder.withIndex().associate { it.value.addonId to it.index }
    return selected
        .filterNot { it.addonId in hidden }
        .withIndex()
        .sortedWith(
            compareBy<IndexedValue<HomeCatalogKey>> { providerRank[it.value.addonId] ?: Int.MAX_VALUE }
                .thenBy { it.index },
        )
        .map { it.value }
}

interface HomeCatalogPort {

    fun viewerProfile(): Profile

    fun primaryProfile(): Profile

    fun changes(): Flow<Unit>

    fun availableCatalogs(viewer: Profile, primary: Profile): List<HomeCatalogOption>

    fun selectedCatalogs(profileId: String): List<HomeCatalogKey>

    fun replaceSelection(profileId: String, selected: List<HomeCatalogKey>)

    suspend fun shelves(
        viewer: Profile,
        primary: Profile,
        selected: List<HomeCatalogKey>,
    ): List<DiscoverShelf>

    fun shelfStream(
        viewer: Profile,
        primary: Profile,
        selected: List<HomeCatalogKey>,
    ): Flow<DiscoverShelfEmission>

    fun explicitShelfStream(
        viewer: Profile,
        primary: Profile,
        keys: List<HomeCatalogKey>,
    ): Flow<DiscoverShelfEmission> = shelfStream(viewer, primary, keys)

    suspend fun shelfPage(
        viewer: Profile,
        primary: Profile,
        shelfKey: String,
        skip: Int,
    ): DiscoverShelf?

    fun providerPreferences(profileId: String): List<HomeProviderHomePreference>

    fun replaceProviderPreferences(profileId: String, providers: List<HomeProviderHomePreference>)

    fun presentationPreferences(profileId: String): HomePresentationPreferences

    fun setShowProviderName(profileId: String, show: Boolean)

    fun setSeparateContinueWatching(profileId: String, separate: Boolean)

    fun setSpotlightAutoRollSeconds(profileId: String, seconds: Int)

    fun setShowSpotlight(profileId: String, show: Boolean)

    fun setSpotlightSource(profileId: String, source: HomeSpotlightSource)
}

data class HomeCatalogGroupUi(
    val addonId: String,
    val addonDisplayName: String,

    val hiddenFromHome: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val catalogs: List<HomeCatalogOptionUi>,
)

data class HomeCatalogOptionUi(
    val key: HomeCatalogKey,
    val displayName: String,
    val selected: Boolean,

    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

data class HomeCustomizationUiState(
    val groups: List<HomeCatalogGroupUi> = emptyList(),
    val selectedOrder: List<HomeCatalogKey> = emptyList(),
    val showProviderName: Boolean = HomePresentationDefaults.SHOW_PROVIDER_NAME,
    val separateContinueWatching: Boolean = HomePresentationDefaults.SEPARATE_CONTINUE_WATCHING,

    val spotlightAutoRollSeconds: Int = HomePresentationDefaults.SPOTLIGHT_AUTO_ROLL_SECONDS,

    val showSpotlight: Boolean = HomePresentationDefaults.SHOW_SPOTLIGHT,

    val spotlightSource: HomeSpotlightSource = HomeSpotlightSource.Automatic,

    val spotlightSourceOptions: List<HomeSpotlightSourceOption> = emptyList(),

    val spotlightSourceNote: String? = null,
    val loading: Boolean = false,
    val actionMessage: String? = null,
    val viewerProfileId: String = "",
)

class HomeCustomizationStateHolder(
    private val port: HomeCatalogPort,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,

    private val widgets: HomeWidgetPort? = null,
) {
    private val _state = MutableStateFlow(HomeCustomizationUiState())
    val state: StateFlow<HomeCustomizationUiState> = _state.asStateFlow()

    init {
        refresh()

        val triggers = widgets?.let { widgets -> merge(port.changes(), widgets.changes()) } ?: port.changes()
        scope.launch(ioDispatcher) {
            triggers.collectLatest { refresh() }
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(loading = true)
        scope.launch(ioDispatcher) {
            loadState()
        }
    }

    fun setSelected(key: HomeCatalogKey, selected: Boolean) {
        val current = _state.value
        val next = current.selectedOrder.toMutableList().apply {
            if (selected) {
                if (key !in this) add(key)
            } else {
                removeAll { it == key }
            }
        }
        if (next == current.selectedOrder) return
        scope.launch(ioDispatcher) {
            val viewer = port.viewerProfile()
            port.replaceSelection(viewer.profileId, next)
            loadState(actionMessage = if (selected) "Added to Home" else "Removed from Home")
        }
    }

    fun setProviderHidden(addonId: String, hidden: Boolean) {
        scope.launch(ioDispatcher) {
            val viewer = port.viewerProfile()
            val providers = effectiveProviders(viewer.profileId)
            if (providers.none { it.addonId == addonId }) return@launch
            port.replaceProviderPreferences(
                viewer.profileId,
                providers.map { provider ->
                    if (provider.addonId == addonId) provider.copy(hiddenFromHome = hidden) else provider
                },
            )
            loadState(actionMessage = if (hidden) "Hidden from Home" else "Shown on Home")
        }
    }

    fun moveProvider(addonId: String, delta: Int) {
        scope.launch(ioDispatcher) {
            val viewer = port.viewerProfile()
            val providers = effectiveProviders(viewer.profileId).toMutableList()
            val index = providers.indexOfFirst { it.addonId == addonId }
            val target = index + delta
            if (index < 0 || target !in providers.indices) return@launch
            val moved = providers.removeAt(index)
            providers.add(target, moved)
            port.replaceProviderPreferences(viewer.profileId, providers)
            loadState()
        }
    }

    fun moveCatalogRow(key: HomeCatalogKey, delta: Int) {
        scope.launch(ioDispatcher) {
            val viewer = port.viewerProfile()
            val selected = port.selectedCatalogs(viewer.profileId).toMutableList()
            val siblings = selected.filter { it.addonId == key.addonId }
            val position = siblings.indexOf(key)
            val swapWith = siblings.getOrNull(position + delta) ?: return@launch
            val a = selected.indexOf(key)
            val b = selected.indexOf(swapWith)
            if (a < 0 || b < 0) return@launch
            selected[a] = swapWith
            selected[b] = key
            port.replaceSelection(viewer.profileId, selected)
            loadState()
        }
    }

    fun setShowProviderName(show: Boolean) {
        if (_state.value.showProviderName == show) return
        scope.launch(ioDispatcher) {
            port.setShowProviderName(port.viewerProfile().profileId, show)
            loadState()
        }
    }

    fun setSeparateContinueWatching(separate: Boolean) {
        if (_state.value.separateContinueWatching == separate) return
        scope.launch(ioDispatcher) {
            port.setSeparateContinueWatching(port.viewerProfile().profileId, separate)
            loadState()
        }
    }

    fun setSpotlightAutoRollSeconds(seconds: Int) {
        scope.launch(ioDispatcher) {
            port.setSpotlightAutoRollSeconds(port.viewerProfile().profileId, seconds)
            loadState()
        }
    }

    fun setShowSpotlight(show: Boolean) {
        if (_state.value.showSpotlight == show) return
        scope.launch(ioDispatcher) {
            port.setShowSpotlight(port.viewerProfile().profileId, show)
            loadState()
        }
    }

    private fun effectiveProviders(profileId: String): List<HomeProviderHomePreference> {
        val known = port.availableCatalogs(port.viewerProfile(), port.primaryProfile())
            .map { it.key.addonId }
            .distinct()
        return homeProviderOrder(port.providerPreferences(profileId), known)
    }

    private suspend fun loadState(actionMessage: String? = null) {
        val viewer = port.viewerProfile()
        val primary = port.primaryProfile()
        val profileId = viewer.profileId
        val selected = port.selectedCatalogs(profileId)
        val selectedIndex = selected.withIndex().associate { it.value to it.index }
        val options = port.availableCatalogs(viewer, primary)
        val providers = homeProviderOrder(
            port.providerPreferences(profileId),
            options.map { it.key.addonId }.distinct(),
        )
        val visibleProviderIds = providers.filterNot { it.hiddenFromHome }.map { it.addonId }
        val byAddon = options.groupBy { it.key.addonId }

        val groups = providers.mapNotNull { provider ->
            val providerOptions = byAddon[provider.addonId]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val declaredIndex = providerOptions.withIndex().associate { it.value.key to it.index }

            val orderedOptions = providerOptions.sortedWith(
                compareBy<HomeCatalogOption> { selectedIndex[it.key] ?: Int.MAX_VALUE }
                    .thenBy { declaredIndex[it.key] ?: Int.MAX_VALUE },
            )
            val selectedInProvider = orderedOptions.filter { it.key in selectedIndex }
            val visiblePosition = visibleProviderIds.indexOf(provider.addonId)
            HomeCatalogGroupUi(
                addonId = provider.addonId,
                addonDisplayName = providerOptions.first().addonDisplayName,
                hiddenFromHome = provider.hiddenFromHome,
                canMoveUp = visiblePosition > 0,
                canMoveDown = visiblePosition >= 0 && visiblePosition < visibleProviderIds.lastIndex,
                catalogs = orderedOptions.map { option ->
                    val position = selectedInProvider.indexOfFirst { it.key == option.key }
                    HomeCatalogOptionUi(
                        key = option.key,
                        displayName = option.displayName,
                        selected = option.key in selectedIndex,
                        canMoveUp = position > 0,
                        canMoveDown = position >= 0 && position < selectedInProvider.lastIndex,
                    )
                },
            )
        }
        val presentation = port.presentationPreferences(profileId)

        val spotlightSourceOptions = if (widgets == null) {
            homeCatalogSpotlightSourceOptions(options)
        } else {
            homeWidgetSpotlightSourceOptions(
                homeWidgetEntries(
                    configuration = widgets.widgetConfiguration(profileId),
                    destinations = widgets.destinations(),
                    availableCatalogs = options,
                    availablePublicProviders = widgets.publicListProviders(),

                    collectionGroupsResolved = false,
                ),
            )
        }

        val configuredRowKeys = if (widgets == null) {
            selected.mapTo(mutableSetOf()) { key -> key.shelfKey }
        } else {
            homeWidgetConfiguredRowKeys(widgets.widgetConfiguration(profileId))
        }

        val spotlightSourceNote = when {
            (presentation.spotlightSource as? HomeSpotlightSource.Row) == null -> null
            homeSpotlightSourceConfirmedGone(presentation.spotlightSource, configuredRowKeys) ->
                "The selected row is no longer on Home, so Spotlight is using Automatic. " +
                    "Choose a source to change it."
            spotlightSourceOptions.any { option ->
                option.rowId == (presentation.spotlightSource as HomeSpotlightSource.Row).rowId
            } -> null
            else ->
                "The selected row is not available right now, so Spotlight is using Automatic. " +
                    "Your choice is kept."
        }
        _state.value = HomeCustomizationUiState(
            groups = groups,
            selectedOrder = selected,
            showProviderName = presentation.showProviderName,
            separateContinueWatching = presentation.separateContinueWatching,
            spotlightAutoRollSeconds = presentation.spotlightAutoRollSeconds,
            showSpotlight = presentation.showSpotlight,
            spotlightSource = presentation.spotlightSource,
            spotlightSourceOptions = spotlightSourceOptions,
            spotlightSourceNote = spotlightSourceNote,
            loading = false,
            actionMessage = actionMessage,
            viewerProfileId = profileId,
        )
    }

    fun setSpotlightSource(source: HomeSpotlightSource) {
        if (_state.value.spotlightSource == source) return
        scope.launch(ioDispatcher) {
            port.setSpotlightSource(port.viewerProfile().profileId, source)
            loadState()
        }
    }
}

class HomePresentationStateHolder(
    private val port: HomeCatalogPort,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(HomePresentationPreferences())
    val state: StateFlow<HomePresentationPreferences> = _state.asStateFlow()

    private val _preferencesRead = MutableStateFlow(false)
    val preferencesRead: StateFlow<Boolean> = _preferencesRead.asStateFlow()

    init {
        refresh()
        scope.launch(ioDispatcher) {
            port.changes().collectLatest { refresh() }
        }
    }

    fun refresh() {
        scope.launch(ioDispatcher) {
            try {
                _state.value = port.presentationPreferences(port.viewerProfile().profileId)
                _preferencesRead.value = true
            } catch (cancellation: CancellationException) {

                throw cancellation
            } catch (failure: Exception) {

                _preferencesRead.value = true
            }
        }
    }

    fun resolveUnavailableSpotlightSource() {
        if (_state.value.spotlightSource == HomeSpotlightSource.Automatic) return
        scope.launch(ioDispatcher) {
            port.setSpotlightSource(port.viewerProfile().profileId, HomeSpotlightSource.Automatic)
            refresh()
        }
    }
}

data class HomeCatalogRowsUiState(
    val rows: List<DiscoverShelf> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val viewerProfileId: String = "",

    val pagingFailedShelfKeys: Set<String> = emptySet(),

    val pagingFailureTokens: Map<String, Long> = emptyMap(),

    val rowDefinitions: List<HomeRowDefinition> = emptyList(),

    val collectionRows: List<dev.infusion.feature.vertical.collections.CollectionGroupRow> = emptyList(),

    val rowOrder: List<String> = emptyList(),

    val configuredRowIds: Set<String>? = null,
)

internal fun progressiveHomeCatalogState(
    previous: HomeCatalogRowsUiState,
    rows: List<DiscoverShelf>,
    viewerProfileId: String,
    rowDefinitions: List<HomeRowDefinition>,
): HomeCatalogRowsUiState = previous.copy(
    rows = rows,
    loading = true,
    error = null,
    viewerProfileId = viewerProfileId,

    rowDefinitions = rowDefinitions,
)

private class HomeWidgetDeclaration(
    val viewerProfileId: String,
    val configuration: HomeWidgetConfiguration,
    val destinations: List<WidgetDestination>,
    val availableCatalogs: List<HomeCatalogOption>,
    val availablePublicProviders: List<PublicListProvider>,
) {

    val rows: List<HomeWidgetRow>

    val rowOrder: List<String>

    val configuredRowIds: Set<String>?

    val hasPendingSource: Boolean

    init {
        val declared = homeWidgetEntries(
            configuration = configuration,
            destinations = destinations,
            availableCatalogs = availableCatalogs,
            availablePublicProviders = availablePublicProviders,
            collectionGroupsResolved = false,
        )
        rows = homeWidgetRows(declared, WidgetDestinations.HOME.id)
        rowOrder = homeWidgetDeclaredRowOrder(declared, WidgetDestinations.HOME.id)
        hasPendingSource = declared.any { it.availability == HomeWidgetAvailability.SOURCE_PENDING }
        configuredRowIds = homeWidgetConfiguredRowKeys(configuration)
    }
}

private class CollectionRowResolution(
    val rows: List<CollectionGroupRow>,
    val rowOrder: List<String>,
)

class HomeCatalogRowsStateHolder(
    private val port: HomeCatalogPort,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,

    private val metrics: ShelfDeliveryMetrics = ShelfDeliveryMetrics.None,

    private val widgetPort: HomeWidgetPort? = null,

    private val publicListRowSource: PublicListWidgetRowSource? = null,

    private val collectionPacks: ((profileId: String) -> List<dev.infusion.core.domain.collections.CollectionPack>)? = null,

    private val presentationCache: HomePresentationStore? = null,

    private val localeToken: () -> String = { "" },

    private val nowMs: () -> Long = { System.currentTimeMillis() },

    private val playbackActive: StateFlow<Boolean> = MutableStateFlow(false),
) {
    private val _state = MutableStateFlow(HomeCatalogRowsUiState())
    val state: StateFlow<HomeCatalogRowsUiState> = _state.asStateFlow()

    private val surfacePresent = MutableStateFlow(true)

    @Volatile
    private var refreshPending = false

    private fun browsingWorkAllowed(): Boolean = surfacePresent.value && !playbackActive.value

    fun onSurfacePresent() {
        surfacePresent.value = true
    }

    fun onSurfaceAbsent() {
        surfacePresent.value = false
    }

    private val accumulator = ProgressiveShelfAccumulator()
    private val publishLock = Any()
    private val generation = AtomicInteger(0)
    private var delivery: Job? = null

    private var reconciler: Job? = null

    private val refreshRequests = AtomicLong(0)

    private var declarationIdentityKey: String? = null

    private val pagingInFlight = mutableSetOf<String>()

    private val publicEnrichmentJobs = mutableMapOf<String, Job>()

    private val rowOrders = mutableMapOf<String, Int>()

    @Volatile
    private var selectedCatalogKeys: Map<String, HomeCatalogKey> = emptyMap()

    @Volatile
    private var showProviderName: Boolean = HomePresentationDefaults.SHOW_PROVIDER_NAME

    private var rowWidgets: Map<String, HomeWidgetRow> = emptyMap()

    private val provisionalRows = mutableMapOf<String, DiscoverShelf>()

    private var rowProjectionKeys: Map<String, RowProjectionKey> = emptyMap()

    @Volatile
    private var pageProfileId: String = ""

    private var rowPublicLists: Map<String, HomeWidgetRow> = emptyMap()

    init {
        refresh()

        val triggers = widgetPort?.let { widgets -> merge(port.changes(), widgets.changes()) } ?: port.changes()
        scope.launch(ioDispatcher) {
            triggers.collectLatest { refresh() }
        }

        scope.launch(ioDispatcher) {
            combine(surfacePresent, playbackActive) { present, playing -> present && !playing }
                .distinctUntilChanged()
                .collectLatest { allowed ->
                    if (!allowed) {

                        if (reconciler?.isActive == true || delivery?.isActive == true) refreshPending = true
                        reconciler?.cancel()
                        delivery?.cancel()
                        return@collectLatest
                    }
                    if (refreshPending) {
                        refreshPending = false
                        refresh()
                    }
                }
        }
    }

    fun refresh() {

        if (!browsingWorkAllowed()) {
            refreshPending = true
            return
        }
        val request = refreshRequests.incrementAndGet()

        synchronized(publishLock) {
            _state.value = _state.value.copy(loading = true)
        }

        reconciler?.cancel()
        reconciler = scope.launch(ioDispatcher) {
            val viewer = port.viewerProfile()
            val primary = port.primaryProfile()

            val widgets = widgetPort

            val declaration = widgets?.let { port -> homeWidgetDeclaration(port, viewer, primary) }
            val identity = declaration?.let { declarationIdentity(it) }

            if (refreshRequests.get() != request) return@launch
            val gen = synchronized(publishLock) {

                if (delivery?.isActive == true && identity != null && identity == declarationIdentityKey) {
                    return@launch
                }
                declarationIdentityKey = identity
                val next = generation.incrementAndGet()
                accumulator.begin()

                provisionalRows.clear()
                rowProjectionKeys = emptyMap()

                _state.value = _state.value.copy(
                    loading = true,
                    error = null,
                    pagingFailedShelfKeys = emptySet(),

                    pagingFailureTokens = emptyMap(),
                )
                next
            }

            delivery?.cancel()
            delivery = scope.launch(ioDispatcher) {
                deliver(
                    gen = gen,
                    viewer = viewer,
                    primary = primary,
                    widgets = widgets,
                    declaration = declaration,
                )
            }
        }
    }

    private suspend fun CoroutineScope.deliver(
        gen: Int,
        viewer: Profile,
        primary: Profile,
        widgets: HomeWidgetPort?,
        declaration: HomeWidgetDeclaration?,
    ) {

            pageProfileId = viewer.profileId

            if (declaration != null) {
                synchronized(publishLock) {
                    if (generation.get() == gen) {
                        _state.value = _state.value.copy(
                            rowOrder = declaration.rowOrder,

                            configuredRowIds = declaration.configuredRowIds,
                        )
                    }
                }
            }

            val collections = declaration
                ?.takeIf { pending -> pending.hasPendingSource }
                ?.let { pending ->

                    widgets?.let { port ->
                        async(ioDispatcher) {
                            val resolved = resolveCollectionRows(widgets = port, declaration = pending)

                            synchronized(publishLock) {
                                if (generation.get() == gen) {
                                    _state.value = _state.value.copy(
                                        collectionRows = resolved.rows,
                                        rowOrder = resolved.rowOrder,
                                    )
                                }
                            }
                            resolved
                        }
                    }
                }
            val widgetRows: List<HomeWidgetRow> = declaration?.rows.orEmpty()

            val presentation = port.presentationPreferences(viewer.profileId)

            restorePresentation(
                gen = gen,
                profileId = viewer.profileId,
                declaration = declaration,
                showProviderName = presentation.showProviderName,
            )

            val indexedWidgetRows = widgetRows.withIndex()
            val catalogRows = indexedWidgetRows.filter { it.value.catalog != null }
            val publicRows = indexedWidgetRows.filter { it.value.publicList != null }
            val selected = if (widgets == null) {
                port.selectedCatalogs(viewer.profileId)
            } else {
                catalogRows.mapNotNull { it.value.catalog }.distinct()
            }

            val spotlightRowKey = presentation.prioritySpotlightRowKey()
            val fetchOrderedPublicRows = if (spotlightRowKey == null) {
                publicRows
            } else {

                publicRows.sortedBy { indexed -> indexed.value.rowKey != spotlightRowKey }
            }
            val priorityCatalogKey = spotlightRowKey
                ?.let { rowKey -> catalogRows.firstOrNull { it.value.rowKey == rowKey }?.value?.catalog?.shelfKey }
            val plannedSelection = if (priorityCatalogKey == null) {
                selected
            } else {

                selected.sortedBy { key -> key.shelfKey != priorityCatalogKey }
            }

            synchronized(publishLock) {
                if (widgets == null) {
                    selectedCatalogKeys = selected.associateBy { it.shelfKey }
                    rowWidgets = emptyMap()
                    rowPublicLists = emptyMap()

                    if (generation.get() == gen) {
                        _state.value = _state.value.copy(
                            configuredRowIds = selected.mapTo(mutableSetOf()) { it.shelfKey },
                        )
                    }
                } else {
                    selectedCatalogKeys = catalogRows
                        .mapNotNull { indexed -> indexed.value.catalog?.let { indexed.value.rowKey to it } }
                        .toMap()
                    rowWidgets = catalogRows.associate { it.value.rowKey to it.value }
                    rowPublicLists = publicRows.associate { it.value.rowKey to it.value }
                }
            }
            showProviderName = presentation.showProviderName

            val widgetRowsBySource: Map<String, List<IndexedValue<HomeWidgetRow>>> =
                catalogRows.groupBy { it.value.catalog!!.shelfKey }
            val startedAtNanos = System.nanoTime()
            var firstRowMs: Long? = null
            var firstContentMs = -1L
            var failure: String? = null

            val priorityPublicRow = spotlightRowKey
                ?.let { rowKey -> fetchOrderedPublicRows.firstOrNull { indexed -> indexed.value.rowKey == rowKey } }
            val remainingPublicRows = if (priorityPublicRow == null) {
                fetchOrderedPublicRows
            } else {
                fetchOrderedPublicRows.filterNot { indexed -> indexed === priorityPublicRow }
            }

            suspend fun deliverPublicRow(indexed: IndexedValue<HomeWidgetRow>) {
                val row = indexed.value
                val ref = row.publicList ?: return
                val source = publicListRowSource
                val outcome = if (source == null) {
                    PublicListShelfOutcome.Failed(PUBLIC_LIST_SOURCE_ABSENT)
                } else {
                    try {
                        source.shelf(row.rowKey, ref, row.displayTitle)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        PublicListShelfOutcome.Failed(error.message?.take(80) ?: PUBLIC_LIST_ROW_FAILED)
                    }
                }
                val shelf = when (outcome) {
                    is PublicListShelfOutcome.Loaded -> outcome.shelf
                    is PublicListShelfOutcome.Failed -> DiscoverShelf(
                        key = row.rowKey,
                        title = row.displayTitle,
                        sourceLabel = ref.provider.displayName,
                        state = ShelfState.Failed(outcome.reason),
                    )
                }
                var publishedRows = -1
                synchronized(publishLock) {
                    if (generation.get() == gen) {

                        val publishedBefore = _state.value.rows.firstOrNull { published ->
                            published.key == shelf.key
                        }
                        accumulator.accept(
                            DiscoverShelfEmission(
                                order = indexed.index,
                                shelf = shelf.retainingPublishedArtwork(publishedBefore),
                            ),
                        )
                        rowOrders[row.rowKey] = indexed.index
                        val snapshot = rowsForPublication()
                        _state.value = _state.value.copy(
                            rows = snapshot,
                            loading = true,
                            rowDefinitions = definitionsFor(snapshot),
                        )
                        publishedRows = snapshot.size
                    }
                }
                if (publishedRows < 0) return

                persistRowPresentation(viewer.profileId, row.rowKey, shelf)

                launchPublicRowEnrichment(
                    rowGeneration = gen,
                    rowKey = row.rowKey,
                    ref = ref,
                    displayTitle = row.displayTitle,
                    shelf = shelf,
                )
                if (firstRowMs == null) {
                    val ms = elapsedMs(startedAtNanos)
                    val contentMs = if (shelf.hasVisibleContent()) ms else -1L
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

            if (priorityPublicRow != null && generation.get() == gen) {
                deliverPublicRow(priorityPublicRow)
            }
            try {

                val stream = if (widgets == null) {
                    port.shelfStream(viewer, primary, selected)
                } else {

                    port.explicitShelfStream(viewer, primary, plannedSelection)
                }
                stream.collect { emission ->

                    var publishedRows = -1

                    val publishedShelves = ArrayList<DiscoverShelf>(2)
                    synchronized(publishLock) {
                        if (generation.get() == gen) {

                            val refreshed = _state.value.viewerProfileId == viewer.profileId
                            if (widgets == null) {
                                if (refreshed) {
                                    accumulator.acceptRefreshed(emission)
                                } else {
                                    accumulator.accept(emission)
                                }

                                rowOrders[emission.shelf.key] = emission.order
                                publishedShelves += emission.shelf
                            } else {

                                widgetRowsBySource[emission.shelf.key].orEmpty().forEach { widgetRow ->
                                    val owned = emission.shelf.ownedByRow(widgetRow.value.rowKey)
                                    val stated = DiscoverShelfEmission(
                                        order = widgetRow.index,

                                        shelf = owned,
                                    )
                                    if (refreshed) {
                                        accumulator.acceptRefreshed(stated)
                                    } else {
                                        accumulator.accept(stated)
                                    }
                                    rowOrders[widgetRow.value.rowKey] = widgetRow.index
                                    publishedShelves += owned
                                }
                            }
                            val snapshot = rowsForPublication()
                            _state.value = progressiveHomeCatalogState(
                                previous = _state.value,
                                rows = snapshot,
                                viewerProfileId = viewer.profileId,
                                rowDefinitions = definitionsFor(snapshot),
                            )
                            publishedRows = snapshot.size
                        }
                    }
                    if (publishedRows < 0) return@collect

                    publishedShelves.forEach { shelf ->
                        persistRowPresentation(viewer.profileId, shelf.key, shelf)
                    }
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
            } catch (error: Throwable) {

                failure = error.message?.take(160) ?: "Home catalogs unavailable"
            }

            if (remainingPublicRows.isNotEmpty() && generation.get() == gen) {
                for (indexed in remainingPublicRows) deliverPublicRow(indexed)
            }

            val resolvedCollections = collections?.await()
            var completed: List<DiscoverShelf>? = null
            synchronized(publishLock) {
                if (generation.get() == gen) {
                    val rows = accumulator.complete()

                    provisionalRows.clear()

                    rowOrders.keys.retainAll(rows.mapTo(mutableSetOf()) { it.key })
                    _state.value = HomeCatalogRowsUiState(
                        rows = rows,
                        loading = false,
                        error = failure,
                        viewerProfileId = viewer.profileId,

                        pagingFailedShelfKeys = _state.value.pagingFailedShelfKeys,
                        pagingFailureTokens = _state.value.pagingFailureTokens,

                        rowDefinitions = definitionsFor(rows),

                        collectionRows = resolvedCollections?.rows.orEmpty(),
                        rowOrder = resolvedCollections?.rowOrder ?: declaration?.rowOrder.orEmpty(),

                        configuredRowIds = _state.value.configuredRowIds,
                    )
                    completed = rows
                }
            }
            val rows = completed ?: return
            metrics.measured(
                ShelfDeliveryRecord(
                    journey = JOURNEY,
                    generation = gen,
                    phase = ShelfDeliveryRecord.Phase.COMPLETE,
                    rows = rows.size,

                    firstRowMs = firstRowMs ?: -1L,
                    firstContentMs = firstContentMs,
                    allRowsMs = elapsedMs(startedAtNanos),
                ),
            )
    }

    private fun restorePresentation(
        gen: Int,
        profileId: String,
        declaration: HomeWidgetDeclaration?,
        showProviderName: Boolean,
    ) {
        val cache = presentationCache ?: return
        if (declaration == null) return
        val locale = localeToken()
        val presentationToken = "providerName=$showProviderName"
        val definitions = LinkedHashMap<String, RowProjectionKey>()
        val cardRowKeys = ArrayList<String>(declaration.rows.size)
        declaration.rows.forEach { row ->
            val arm = homeRowSourceIdentity(row) ?: return@forEach
            val (family, sourceIdentity) = arm
            val key = homeRowDefinitionKey(
                profileId = profileId,
                row = row,
                family = family,
                sourceIdentity = sourceIdentity,
                presentationToken = presentationToken,
                localeToken = locale,
            )
            definitions[row.rowKey] = RowProjectionKey(family, key)
            if (family.isCardRow) cardRowKeys += row.rowKey
        }
        synchronized(publishLock) {
            if (generation.get() != gen) return
            rowProjectionKeys = definitions
        }
        if (cardRowKeys.isEmpty()) return
        val stored = runCatching { cache.readRows(profileId, cardRowKeys) }.getOrDefault(emptyMap())
        if (stored.isEmpty()) return
        val now = nowMs()
        val restored = LinkedHashMap<String, DiscoverShelf>()
        stored.forEach { (rowKey, record) ->
            val expected = definitions[rowKey] ?: return@forEach
            if (!expected.family.isCardRow) return@forEach
            if (record.definitionKey != expected.definitionKey) return@forEach
            if (!HomePresentationPolicy.rowIsFresh(record, now)) return@forEach
            val shelf = record.restoredShelf() ?: return@forEach
            restored[rowKey] = shelf
        }
        if (restored.isEmpty()) return
        synchronized(publishLock) {
            if (generation.get() != gen) return
            restored.forEach { (rowKey, _) ->

                val index = declaration.rows.indexOfFirst { it.rowKey == rowKey }
                if (index >= 0) rowOrders[rowKey] = index
            }
            provisionalRows.putAll(restored)
            val rows = rowsForPublication()
            _state.value = _state.value.copy(
                rows = rows,
                rowDefinitions = definitionsFor(rows),
            )
        }
    }

    private fun persistRowPresentation(profileId: String, rowKey: String, shelf: DiscoverShelf) {
        val cache = presentationCache ?: return
        val projection = synchronized(publishLock) { rowProjectionKeys[rowKey] } ?: return
        if (!projection.family.isCardRow) return
        val loaded = shelf.state as? ShelfState.Loaded ?: return
        if (loaded.items.isEmpty()) {
            cache.clearRow(profileId, rowKey)
            return
        }
        val record = homeRowPresentationOf(
            rowKey = rowKey,
            family = projection.family,
            definitionKey = projection.definitionKey,
            shelf = shelf,
            storedAtEpochMs = nowMs(),
        ) ?: return
        cache.writeRow(profileId, record)
    }

    private fun rowsForPublication(): List<DiscoverShelf> {
        val live = accumulator.snapshot()
        if (provisionalRows.isEmpty()) return live
        live.forEach { shelf -> provisionalRows.remove(shelf.key) }
        if (provisionalRows.isEmpty()) return live
        return (live + provisionalRows.values).sortedWith(
            compareBy({ rowOrders[it.key] ?: Int.MAX_VALUE }, { it.key }),
        )
    }

    private fun elapsedMs(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / 1_000_000

    private fun HomePresentationPreferences.prioritySpotlightRowKey(): String? =
        if (!showSpotlight) null else spotlightSource.rowKeyOrNull()

    private fun declarationIdentity(declaration: HomeWidgetDeclaration): String {
        val rows = declaration.rows.joinToString(separator = "|") { row ->
            val arm = when {
                row.catalog != null -> "catalog:${row.catalog.addonId}/${row.catalog.catalogType}/${row.catalog.catalogId}"
                row.publicList != null -> "public:${row.publicList.provider.id}/${row.publicList.listId}"
                row.collectionGroup != null -> "group:${row.collectionGroup.packId}/${row.collectionGroup.groupKey}"
                else -> "none"
            }
            "${row.rowKey}#$arm#${row.displayTitle}"
        }
        return buildString {
            append("profile=").append(declaration.viewerProfileId)
            append(";order=").append(declaration.rowOrder.joinToString(","))
            append(";configured=").append(declaration.configuredRowIds?.joinToString(",") ?: "-")
            append(";rows=").append(rows)
        }
    }

    private fun homeWidgetDeclaration(
        widgets: HomeWidgetPort,
        viewer: Profile,
        primary: Profile,
    ): HomeWidgetDeclaration = HomeWidgetDeclaration(
        viewerProfileId = viewer.profileId,
        configuration = widgets.widgetConfiguration(viewer.profileId),
        destinations = widgets.destinations(),
        availableCatalogs = widgets.availableExternalCatalogs(viewer, primary),
        availablePublicProviders = widgets.publicListProviders(),
    )

    private fun resolveCollectionRows(
        widgets: HomeWidgetPort,
        declaration: HomeWidgetDeclaration,
    ): CollectionRowResolution {
        val resolvedEntries = homeWidgetEntries(
            configuration = declaration.configuration,
            destinations = declaration.destinations,
            availableCatalogs = declaration.availableCatalogs,
            availablePublicProviders = declaration.availablePublicProviders,
            availableCollectionGroups = widgets.availableCollectionGroups(declaration.viewerProfileId),
        )
        return CollectionRowResolution(
            rows = collectionGroupTileRows(
                rows = homeWidgetRows(resolvedEntries, WidgetDestinations.HOME.id),
                profileId = declaration.viewerProfileId,
            ),
            rowOrder = homeWidgetDeclaredRowOrder(resolvedEntries, WidgetDestinations.HOME.id),
        )
    }

    private fun collectionGroupTileRows(
        rows: List<HomeWidgetRow>,
        profileId: String,
    ): List<CollectionGroupRow> {
        val packs = collectionPacks ?: return emptyList()
        return dev.infusion.feature.vertical.collections.collectionGroupRows(
            widgetRows = rows.filter { it.collectionGroup != null },
            packFor = { packId -> packs.invoke(profileId).firstOrNull { it.packId == packId } },
        )
    }

    private fun definitionsFor(rows: List<DiscoverShelf>): List<HomeRowDefinition> {
        val selected = selectedCatalogKeys
        val widgets = rowWidgets
        val providerNameShown = showProviderName
        return rows
            .mapNotNull { shelf ->
                val catalogKey = selected[shelf.key] ?: return@mapNotNull null
                val order = rowOrders[shelf.key] ?: return@mapNotNull null
                val widgetRow = widgets[shelf.key]
                if (widgetRow != null) {
                    widgetRowDefinition(
                        widgetId = widgetRow.widgetId,
                        catalogKey = catalogKey,
                        publishedRowKey = shelf.key,
                        title = shelf.title,
                        providerDisplayName = shelf.sourceLabel,
                        order = order,
                        showProviderName = providerNameShown,
                    )
                } else {
                    addonCatalogRowDefinition(
                        catalogKey = catalogKey,
                        publishedRowKey = shelf.key,
                        title = shelf.title,
                        providerDisplayName = shelf.sourceLabel,
                        order = order,
                        showProviderName = providerNameShown,
                    )
                }
            }
            .sortedBy { it.order }
    }

    fun loadMore(shelfKey: String) {
        val request = synchronized(publishLock) {

            if (provisionalRows.containsKey(shelfKey)) return
            val row = _state.value.rows.firstOrNull { it.key == shelfKey } ?: return
            val loaded = row.state as? ShelfState.Loaded ?: return
            if (!loaded.hasMore) return
            if (!pagingInFlight.add(shelfKey)) return
            PagingRequest(
                pageGeneration = generation.get(),
                skip = loaded.items.size,
                sourceKey = rowWidgets[shelfKey]?.catalog?.shelfKey ?: shelfKey,
                publicRow = rowPublicLists[shelfKey],
            )
        }
        scope.launch(ioDispatcher) {
            var page: DiscoverShelf? = null

            var portThrew = false
            try {
                val publicRow = request.publicRow
                if (publicRow != null) {

                    val ref = publicRow.publicList
                    val source = publicListRowSource
                    if (ref == null || source == null) {
                        portThrew = true
                    } else {
                        when (val outcome = source.page(shelfKey, ref, publicRow.displayTitle)) {
                            is PublicListShelfOutcome.Loaded -> page = outcome.shelf
                            is PublicListShelfOutcome.Failed ->
                                if (outcome.reason == PublicListWidgetResolver.NO_MORE_PAGES) {

                                    page = DiscoverShelf(
                                        key = shelfKey,
                                        title = publicRow.displayTitle,
                                        sourceLabel = ref.provider.displayName,
                                        state = ShelfState.Loaded(items = emptyList(), hasMore = false),
                                    )
                                } else {
                                    portThrew = true
                                }
                        }
                    }
                } else {
                    page = port.shelfPage(
                        viewer = port.viewerProfile(),
                        primary = port.primaryProfile(),
                        shelfKey = request.sourceKey,
                        skip = request.skip,
                    )
                }
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

            val appendedPublicRow = request.publicRow
            val appendedRef = appendedPublicRow?.publicList
            val landed = page
            if (appendedPublicRow != null && appendedRef != null && landed != null) {
                launchPublicRowEnrichment(
                    rowGeneration = request.pageGeneration,
                    rowKey = appendedPublicRow.rowKey,
                    ref = appendedRef,
                    displayTitle = appendedPublicRow.displayTitle,
                    shelf = landed,
                )
            }
        }
    }

    private fun launchPublicRowEnrichment(
        rowGeneration: Int,
        rowKey: String,
        ref: PublicListRef,
        displayTitle: String,
        shelf: DiscoverShelf,
    ) {
        val source = publicListRowSource ?: return
        val loaded = shelf.state as? ShelfState.Loaded ?: return
        if (loaded.items.none { it.preview.artwork == null && it.titleId != null }) return

        val job = scope.launch(ioDispatcher) {
            try {
                source.enrich(rowKey, ref, displayTitle, shelf).collect { enriched ->
                    val enrichedItems = (enriched.state as? ShelfState.Loaded)?.items
                    if (enrichedItems != null) {
                        mergeRowEnrichment(rowGeneration, rowKey, enrichedItems)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {

            }
        }
        synchronized(publishLock) {
            publicEnrichmentJobs.remove(rowKey)?.cancel()
            publicEnrichmentJobs[rowKey] = job
        }
    }

    private fun mergeRowEnrichment(
        rowGeneration: Int,
        rowKey: String,
        enrichedItems: List<dev.infusion.source.aggregation.discover.DiscoverItem>,
    ) {
        var persisted: DiscoverShelf? = null
        synchronized(publishLock) {
            if (generation.get() != rowGeneration) return
            val snapshot = accumulator.snapshot()
            val current = snapshot.firstOrNull { it.key == rowKey } ?: return
            val currentLoaded = current.state as? ShelfState.Loaded ?: return
            val byKey = enrichedItems.associateBy { it.key }

            val merged = currentLoaded.items.map { item ->
                byKey[item.key]?.retainingArtworkOf(item) ?: item
            }

            accumulator.replace(current.copy(state = currentLoaded.copy(items = merged)))
            val published = rowsForPublication()
            _state.value = _state.value.copy(
                rows = published,
                rowDefinitions = definitionsFor(published),
            )
            persisted = published.firstOrNull { it.key == rowKey }
        }

        persisted?.let { shelf -> persistRowPresentation(pageProfileId, rowKey, shelf) }
    }

    private fun applyPage(
        shelfKey: String,
        pageGeneration: Int,
        page: DiscoverShelf?,
        portThrew: Boolean,
    ) {
        var appended = false
        synchronized(publishLock) {
            if (generation.get() != pageGeneration) return
            val loaded = page?.state as? ShelfState.Loaded
            if (loaded != null) {

                val items = if (rowWidgets[shelfKey]?.catalog != null) {
                    loaded.items.map { item -> item.copy(key = "$shelfKey:${item.key}") }
                } else {
                    loaded.items
                }
                if (!accumulator.append(shelfKey, items, loaded.hasMore)) return
                appended = true
                _state.value = _state.value.copy(
                    rows = rowsForPublication(),
                    pagingFailedShelfKeys = _state.value.pagingFailedShelfKeys - shelfKey,

                    pagingFailureTokens = _state.value.pagingFailureTokens - shelfKey,
                )
            } else {
                if (page != null || portThrew) {
                    val failures = _state.value.pagingFailureTokens
                    _state.value = _state.value.copy(
                        pagingFailedShelfKeys = _state.value.pagingFailedShelfKeys + shelfKey,

                        pagingFailureTokens = failures + (shelfKey to (failures[shelfKey] ?: 0L) + 1L),
                    )
                }
            }
        }

        if (!appended) return
        val current = synchronized(publishLock) {
            _state.value.rows.firstOrNull { it.key == shelfKey }
        } ?: return
        persistRowPresentation(pageProfileId, shelfKey, current)
    }

    private data class RowProjectionKey(
        val family: HomePresentationFamily,
        val definitionKey: String,
    )

    private data class PagingRequest(
        val pageGeneration: Int,
        val skip: Int,

        val sourceKey: String,

        val publicRow: HomeWidgetRow? = null,
    )

    private companion object {

        const val JOURNEY = "home"

        const val PUBLIC_LIST_SOURCE_ABSENT = "public_list_source_unavailable"

        const val PUBLIC_LIST_ROW_FAILED = "public_list_row_failed"
    }
}

internal fun DiscoverShelf.ownedByRow(rowKey: String): DiscoverShelf {
    val state = this.state
    if (state !is ShelfState.Loaded) return copy(key = rowKey)
    return copy(
        key = rowKey,

        state = state.copy(items = state.items.map { item -> item.copy(key = "$rowKey:${item.key}") }),
    )
}

internal fun DiscoverShelf.retainingPublishedArtwork(published: DiscoverShelf?): DiscoverShelf {
    val incoming = state as? ShelfState.Loaded ?: return this
    val prior = (published?.state as? ShelfState.Loaded)?.items.orEmpty()
    if (prior.isEmpty() || incoming.items.isEmpty()) return this
    val publishedByKey = prior.associateBy { it.key }
    val items = incoming.items.map { item -> item.retainingArtworkOf(publishedByKey[item.key]) }
    return if (items == incoming.items) this else copy(state = incoming.copy(items = items))
}

internal fun DiscoverItem.retainingArtworkOf(published: DiscoverItem?): DiscoverItem {
    if (preview.artwork != null) return this
    val prior = published?.preview?.artwork ?: return this

    if (published.titleId != titleId) return this
    return copy(preview = preview.copy(artwork = prior))
}
