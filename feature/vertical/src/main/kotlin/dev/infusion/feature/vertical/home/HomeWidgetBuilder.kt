package dev.infusion.feature.vertical.home

import dev.infusion.core.domain.collections.CollectionGroupRef
import dev.infusion.core.domain.home.HomeWidgetConfiguration
import dev.infusion.core.domain.home.HomeWidgetRecord
import dev.infusion.core.domain.home.HomeWidgetSourceRef
import dev.infusion.core.domain.publist.PublicListCapabilities
import dev.infusion.core.domain.publist.PublicListDirectoryPage
import dev.infusion.core.domain.publist.PublicListProvider
import dev.infusion.core.domain.publist.PublicListRef
import dev.infusion.core.domain.publist.PublicListResult
import dev.infusion.core.domain.publist.PublicListRoute
import dev.infusion.core.domain.publist.PublicListRouteSupport
import dev.infusion.core.domain.publist.PublicListSummary
import dev.infusion.core.domain.publist.PublicListUser
import dev.infusion.core.model.profile.Profile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

data class WidgetDestination(
    val id: String,
    val displayName: String,
)

object WidgetDestinations {

    val HOME: WidgetDestination = WidgetDestination(id = "home", displayName = "Home")
}

const val WIDGET_SOURCE_EXTERNAL_CATALOGS: String = "External Catalogs"

const val WIDGET_SOURCE_PUBLIC_LISTS: String = "Public Lists"

const val WIDGET_SOURCE_COLLECTIONS: String = "Collections"

enum class HomeWidgetAvailability {

    AVAILABLE,

    SOURCE_PENDING,

    SOURCE_UNAVAILABLE,

    DESTINATION_UNAVAILABLE,
}

data class HomeWidgetEntry(
    val widgetId: String,
    val destinationId: String,
    val destinationDisplayName: String,
    val record: HomeWidgetRecord,
    val availability: HomeWidgetAvailability,
    val displayTitle: String,
    val addonDisplayName: String? = null,

    val publicListSourceLabel: String? = null,

    val sourceProviderLabel: String? = null,

    val sourceOwnerLabel: String? = null,

    val collectionGroup: CollectionGroupRef? = null,

    val collectionGroupLabel: String? = null,
) {

    val catalog: HomeCatalogKey?
        get() = (record.source as? HomeWidgetSourceRef.ExternalCatalog)?.let { source ->
            HomeCatalogKey(
                addonId = source.addonId,
                catalogType = source.catalogType,
                catalogId = source.catalogId,
            )
        }

    val publicList: PublicListRef?
        get() {
            val source = record.source as? HomeWidgetSourceRef.PublicList ?: return null
            val provider = PublicListProvider.fromId(source.providerId) ?: return null
            return PublicListRef(provider = provider, listId = source.listId, owner = source.owner)
        }

    val renders: Boolean get() = availability == HomeWidgetAvailability.AVAILABLE

    val declaresRow: Boolean
        get() = availability == HomeWidgetAvailability.AVAILABLE ||
            availability == HomeWidgetAvailability.SOURCE_PENDING
}

fun homeWidgetEntries(
    configuration: HomeWidgetConfiguration,
    destinations: List<WidgetDestination>,
    availableCatalogs: List<HomeCatalogOption>,
    availablePublicProviders: List<PublicListProvider> = emptyList(),
    availableCollectionGroups: Map<CollectionGroupRef, String> = emptyMap(),

    collectionGroupsResolved: Boolean = true,
): List<HomeWidgetEntry> = configuration.records.map { record ->
    val destination = destinations.firstOrNull { it.id == record.destinationId }
    if (destination == null) {
        return@map unavailableEntry(
            record = record,
            destinationDisplayName = record.destinationId,
            availability = HomeWidgetAvailability.DESTINATION_UNAVAILABLE,
        )
    }
    val source = record.source
    if (source is HomeWidgetSourceRef.CollectionGroup && !collectionGroupsResolved) {
        return@map pendingCollectionEntry(record, destination, source)
    }
    availableEntry(record, destination, availableCatalogs, availablePublicProviders, availableCollectionGroups)
        ?: unavailableEntry(
            record = record,
            destinationDisplayName = destination.displayName,
            availability = HomeWidgetAvailability.SOURCE_UNAVAILABLE,
        )
}

private fun pendingCollectionEntry(
    record: HomeWidgetRecord,
    destination: WidgetDestination,
    source: HomeWidgetSourceRef.CollectionGroup,
): HomeWidgetEntry = HomeWidgetEntry(
    widgetId = record.widgetId,
    destinationId = destination.id,
    destinationDisplayName = destination.displayName,
    record = record,
    availability = HomeWidgetAvailability.SOURCE_PENDING,
    displayTitle = describeUnavailableSource(record),
    addonDisplayName = null,
    collectionGroup = CollectionGroupRef(source.packId, source.groupKey),
    collectionGroupLabel = null,
)

private fun availableEntry(
    record: HomeWidgetRecord,
    destination: WidgetDestination,
    availableCatalogs: List<HomeCatalogOption>,
    availablePublicProviders: List<PublicListProvider>,
    availableCollectionGroups: Map<CollectionGroupRef, String>,
): HomeWidgetEntry? = when (val source = record.source) {
    is HomeWidgetSourceRef.ExternalCatalog -> {
        val option = availableCatalogs.firstOrNull { candidate ->
            candidate.key.addonId == source.addonId &&
                candidate.key.catalogType == source.catalogType &&
                candidate.key.catalogId == source.catalogId
        } ?: return null
        HomeWidgetEntry(
            widgetId = record.widgetId,
            destinationId = destination.id,
            destinationDisplayName = destination.displayName,
            record = record,
            availability = HomeWidgetAvailability.AVAILABLE,
            displayTitle = option.displayName,
            addonDisplayName = option.addonDisplayName,
        )
    }
    is HomeWidgetSourceRef.PublicList -> {
        val provider = PublicListProvider.fromId(source.providerId) ?: return null

        if (provider !in availablePublicProviders) return null
        HomeWidgetEntry(
            widgetId = record.widgetId,
            destinationId = destination.id,
            destinationDisplayName = destination.displayName,
            record = record,
            availability = HomeWidgetAvailability.AVAILABLE,

            displayTitle = record.displayTitle
                ?: publicListRefLabel(source.owner, source.listId),
            addonDisplayName = null,
            publicListSourceLabel = publicListWidgetLabel(provider, source.owner, source.listId),

            sourceProviderLabel = provider.displayName,
            sourceOwnerLabel = source.owner?.trim()?.takeIf { it.isNotEmpty() } ?: source.listId,
        )
    }
    is HomeWidgetSourceRef.CollectionGroup -> {

        val ref = CollectionGroupRef(source.packId, source.groupKey)
        val label = availableCollectionGroups[ref] ?: return null
        HomeWidgetEntry(
            widgetId = record.widgetId,
            destinationId = destination.id,
            destinationDisplayName = destination.displayName,
            record = record,
            availability = HomeWidgetAvailability.AVAILABLE,
            displayTitle = label,
            addonDisplayName = null,
            collectionGroup = ref,
            collectionGroupLabel = label,
        )
    }
}

internal fun publicListWidgetLabel(
    provider: PublicListProvider?,
    owner: String?,
    listId: String,
): String = buildString {
    append(provider?.displayName ?: "Public")
    append(" · ")
    append(publicListRefLabel(owner, listId))
}

internal fun publicListRefLabel(owner: String?, listId: String): String = buildString {
    val handle = owner?.trim()?.takeIf { it.isNotEmpty() }
    if (handle != null && !listId.startsWith("$handle/")) append(handle).append('/')
    append(listId)
}

private fun unavailableEntry(
    record: HomeWidgetRecord,
    destinationDisplayName: String,
    availability: HomeWidgetAvailability,
): HomeWidgetEntry = HomeWidgetEntry(
    widgetId = record.widgetId,
    destinationId = record.destinationId,
    destinationDisplayName = destinationDisplayName,
    record = record,
    availability = availability,
    displayTitle = describeUnavailableSource(record),
    addonDisplayName = null,
)

private fun describeUnavailableSource(record: HomeWidgetRecord): String = when (val source = record.source) {
    is HomeWidgetSourceRef.ExternalCatalog ->
        "${source.catalogType}/${source.catalogId} · ${source.addonId}"
    is HomeWidgetSourceRef.PublicList ->
        publicListWidgetLabel(
            provider = PublicListProvider.fromId(source.providerId),
            owner = source.owner,
            listId = source.listId,
        )

    is HomeWidgetSourceRef.CollectionGroup -> "collection ${source.packId} · ${source.groupKey}"
}

fun homeWidgetRowKey(widgetId: String): String = "widget:$widgetId"

fun homeWidgetConfiguredRowKeys(configuration: HomeWidgetConfiguration): Set<String>? =
    if (configuration.configured && configuration.unreadablePayload == null) {
        configuration.records.mapTo(mutableSetOf()) { record -> homeWidgetRowKey(record.widgetId) }
    } else {
        null
    }

data class HomeWidgetRow(
    val widgetId: String,
    val rowKey: String,
    val displayTitle: String,

    val catalog: HomeCatalogKey? = null,

    val publicList: PublicListRef? = null,

    val collectionGroup: CollectionGroupRef? = null,
)

fun homeWidgetRows(
    entries: List<HomeWidgetEntry>,
    destinationId: String,
): List<HomeWidgetRow> = entries
    .filter { it.destinationId == destinationId && it.renders }
    .mapNotNull { entry ->
        val rowKey = homeWidgetRowKey(entry.widgetId)
        when {
            entry.catalog != null -> HomeWidgetRow(
                widgetId = entry.widgetId,
                rowKey = rowKey,
                displayTitle = entry.displayTitle,
                catalog = entry.catalog,
            )
            entry.publicList != null -> HomeWidgetRow(
                widgetId = entry.widgetId,
                rowKey = rowKey,
                displayTitle = entry.displayTitle,
                publicList = entry.publicList,
            )
            entry.collectionGroup != null -> HomeWidgetRow(
                widgetId = entry.widgetId,
                rowKey = rowKey,
                displayTitle = entry.displayTitle,
                collectionGroup = entry.collectionGroup,
            )
            else -> null
        }
    }

fun homeWidgetDeclaredRowOrder(
    entries: List<HomeWidgetEntry>,
    destinationId: String,
): List<String> = entries
    .filter { it.destinationId == destinationId && it.declaresRow }
    .map { homeWidgetRowKey(it.widgetId) }

enum class WidgetAddOutcome {

    ADDED,

    UNKNOWN_DESTINATION,

    UNKNOWN_CATALOG,

    UNKNOWN_PROVIDER,

    UNKNOWN_PUBLIC_LIST,

    UNKNOWN_COLLECTION_GROUP,

    LIMIT_REACHED,

    DUPLICATE,

    WIDGET_UNKNOWN,
}

interface HomeWidgetPort {

    fun changes(): Flow<Unit>

    fun collectionChanges(): Flow<Unit> = emptyFlow()

    fun destinations(): List<WidgetDestination>

    fun viewerProfile(): Profile

    fun primaryProfile(): Profile

    fun availableExternalCatalogs(viewer: Profile, primary: Profile): List<HomeCatalogOption>

    fun publicListProviders(): List<PublicListProvider>

    fun publicListCapabilities(providerId: String): PublicListCapabilities?

    fun publicListAccountConnected(providerId: String): Boolean = false

    suspend fun publicLists(
        providerId: String,
        route: PublicListRoute,
        query: String? = null,
    ): PublicListResult<List<PublicListSummary>>

    suspend fun publicListPage(
        providerId: String,
        route: PublicListRoute,
        query: String? = null,
        cursor: String? = null,
    ): PublicListResult<PublicListDirectoryPage> = if (cursor == null) {
        when (val first = publicLists(providerId, route, query)) {
            is PublicListResult.Success -> PublicListResult.Success(PublicListDirectoryPage(first.value))
            is PublicListResult.Unsupported -> first
            is PublicListResult.Failed -> first
        }
    } else {
        PublicListResult.Success(PublicListDirectoryPage(emptyList()))
    }

    suspend fun publicListUsers(providerId: String, query: String): PublicListResult<List<PublicListUser>>

    suspend fun publicListUserLists(
        providerId: String,
        username: String,
    ): PublicListResult<List<PublicListSummary>>

    fun widgetConfiguration(profileId: String): HomeWidgetConfiguration

    fun addExternalCatalogWidget(
        profileId: String,
        destinationId: String,
        addonId: String,
        catalogType: String,
        catalogId: String,
    ): WidgetAddOutcome

    fun addPublicListWidget(
        profileId: String,
        destinationId: String,
        providerId: String,
        listId: String,
        owner: String?,
        displayTitle: String? = null,
    ): WidgetAddOutcome

    fun collectionGroupOptions(profileId: String): List<WidgetBuilderCollectionGroupUi> = emptyList()

    fun availableCollectionGroups(profileId: String): Map<CollectionGroupRef, String> =
        collectionGroupOptions(profileId).associate { it.ref to it.displayLabel }

    fun addCollectionGroupWidget(
        profileId: String,
        destinationId: String,
        packId: String,
        groupKey: String,
        displayTitle: String? = null,
    ): WidgetAddOutcome = WidgetAddOutcome.UNKNOWN_PUBLIC_LIST

    fun recordPublicListTitle(
        profileId: String,
        providerId: String,
        listId: String,
        owner: String?,
        title: String,
    ): Boolean = false

    fun moveWidget(profileId: String, widgetId: String, delta: Int): WidgetAddOutcome

    fun deleteWidget(profileId: String, widgetId: String)
}

data class WidgetBuilderWidgetUi(
    val entry: HomeWidgetEntry,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

data class WidgetBuilderPageUi(
    val destinationId: String,
    val label: String,
    val available: Boolean,
    val widgets: List<WidgetBuilderWidgetUi>,
)

data class WidgetBuilderAddonUi(
    val addonId: String,
    val displayName: String,
    val catalogs: List<HomeCatalogOption>,
)

enum class WidgetBuilderSourceKind(val label: String) {

    EXTERNAL_CATALOGS(WIDGET_SOURCE_EXTERNAL_CATALOGS),

    PUBLIC_LISTS(WIDGET_SOURCE_PUBLIC_LISTS),

    COLLECTIONS(WIDGET_SOURCE_COLLECTIONS),
}

data class WidgetBuilderCollectionGroupUi(
    val packId: String,
    val groupKey: String,
    val packName: String,
    val groupTitle: String,
    val tileCount: Int,
) {
    val ref: CollectionGroupRef get() = CollectionGroupRef(packId, groupKey)

    val displayLabel: String
        get() = groupTitle.trim().takeIf { it.isNotEmpty() } ?: packName
}

data class WidgetBuilderPublicProviderUi(
    val providerId: String,
    val displayName: String,
    val routes: List<PublicListRouteSupport>,
    val accountConnected: Boolean = false,
) {

    val offeredRoutes: List<PublicListRouteSupport>
        get() = routes.filter { !it.requiresAccount || accountConnected }

    val publicRoutes: List<PublicListRouteSupport> get() = routes.filterNot { it.requiresAccount }

    val needsAccount: Boolean get() = routes.any { it.requiresAccount }

    val displayLabel: String
        get() = if (accountConnected && !publicRoutes.any()) "$displayName (account)" else displayName
}

data class WidgetBuilderUiState(
    val loading: Boolean = true,
    val destinations: List<WidgetDestination> = emptyList(),
    val destinationId: String = WidgetDestinations.HOME.id,
    val addons: List<WidgetBuilderAddonUi> = emptyList(),
    val selectedAddonId: String? = null,
    val selectedCatalogKey: HomeCatalogKey? = null,
    val pages: List<WidgetBuilderPageUi> = emptyList(),
    val actionMessage: String? = null,

    val blockedDeleteWidgetId: String? = null,
    val viewerProfileId: String = "",

    val sourceKind: WidgetBuilderSourceKind = WidgetBuilderSourceKind.EXTERNAL_CATALOGS,
    val publicProviders: List<WidgetBuilderPublicProviderUi> = emptyList(),
    val selectedPublicProviderId: String? = null,
    val selectedPublicRoute: PublicListRoute? = null,

    val publicQuery: String = "",

    val publicResults: List<PublicListSummary> = emptyList(),

    val publicResultsHasMore: Boolean = false,
    val publicNextCursor: String? = null,

    val publicLoadingMore: Boolean = false,

    val publicUsers: List<PublicListUser> = emptyList(),

    val selectedPublicUser: String? = null,

    val selectedPublicList: PublicListSummary? = null,

    val publicStatus: String? = null,

    val collectionGroups: List<WidgetBuilderCollectionGroupUi> = emptyList(),

    val selectedCollectionGroup: CollectionGroupRef? = null,

    val collectionsStatus: String? = null,
) {

    val selectedAddonCatalogs: List<HomeCatalogOption>
        get() = addons.firstOrNull { it.addonId == selectedAddonId }?.catalogs.orEmpty()

    val selectedCatalogLabel: String?
        get() = selectedCatalogKey?.let { key ->
            selectedAddonCatalogs.firstOrNull { it.key == key }?.displayName
        }

    val destinationLabel: String
        get() = destinations.firstOrNull { it.id == destinationId }?.displayName ?: destinationId

    val canAdd: Boolean
        get() = !loading &&
            selectedAddonId != null &&
            selectedCatalogKey != null &&
            selectedCatalogLabel != null &&
            destinations.any { it.id == destinationId }

    val selectedPublicProvider: WidgetBuilderPublicProviderUi?
        get() = publicProviders.firstOrNull { it.providerId == selectedPublicProviderId }

    val selectedPublicRoutes: List<PublicListRouteSupport>
        get() = selectedPublicProvider?.offeredRoutes.orEmpty()

    val selectedPublicRouteLabel: String?
        get() = selectedPublicRoute?.let { route -> selectedPublicRoutes.firstOrNull { it.route == route }?.label }

    private val selectedPublicRouteSupport: PublicListRouteSupport?
        get() = selectedPublicRoutes.firstOrNull { it.route == selectedPublicRoute }

    val publicSearchActive: Boolean
        get() = selectedPublicRoute == PublicListRoute.SEARCH_LISTS ||
            selectedPublicRoute == PublicListRoute.SEARCH_USERS ||
            selectedPublicRoute == PublicListRoute.USER_LISTS

    val publicQueryLabel: String
        get() = selectedPublicRouteSupport?.inputLabel
            ?: if (selectedPublicRoute == PublicListRoute.USER_LISTS) "Username" else "Search"

    val canAddPublicList: Boolean
        get() = !loading &&
            selectedPublicProvider != null &&
            selectedPublicRoute != null &&
            selectedPublicList != null &&
            destinations.any { it.id == destinationId }

    val selectedPublicListLabel: String?
        get() = selectedPublicList?.let { list ->
            buildString {
                append(list.title)
                list.ownerLabel?.let { append(" · ").append(it) }
                list.ref.provider.displayName.let { append(" · ").append(it) }
            }
        }

    val selectedCollectionGroupUi: WidgetBuilderCollectionGroupUi?
        get() = selectedCollectionGroup?.let { ref ->
            collectionGroups.firstOrNull { it.ref == ref }
        }

    val selectedCollectionGroupLabel: String?
        get() = selectedCollectionGroupUi?.displayLabel

    val canAddCollectionGroup: Boolean
        get() = !loading &&
            selectedCollectionGroupUi != null &&
            destinations.any { it.id == destinationId }
}

class WidgetBuilderStateHolder(
    private val port: HomeWidgetPort,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,

    private val spotlightSource: (profileId: String) -> HomeSpotlightSource? = { null },
) {
    private val _state = MutableStateFlow(WidgetBuilderUiState())
    val state: StateFlow<WidgetBuilderUiState> = _state.asStateFlow()

    private var publicReadGeneration: Int = 0
    private var publicReadJob: Job? = null

    private var lastRequestedPublicCursor: String? = null

    init {
        refresh()
        scope.launch(ioDispatcher) {
            port.changes().collectLatest { refresh() }
        }

        scope.launch(ioDispatcher) {
            port.collectionChanges().collectLatest { refresh() }
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(loading = true)
        scope.launch(ioDispatcher) { loadState() }
    }

    fun selectDestination(destinationId: String) {
        if (_state.value.destinationId == destinationId) return
        if (_state.value.destinations.none { it.id == destinationId }) return
        _state.value = _state.value.copy(destinationId = destinationId, actionMessage = null)
    }

    fun selectAddon(addonId: String) {
        val current = _state.value
        val addon = current.addons.firstOrNull { it.addonId == addonId } ?: return
        if (current.selectedAddonId == addonId) return

        _state.value = current.copy(
            selectedAddonId = addonId,
            selectedCatalogKey = null,
            actionMessage = null,
        )
    }

    fun selectCatalog(key: HomeCatalogKey) {
        val current = _state.value
        if (current.selectedCatalogKey == key) return
        if (current.selectedAddonCatalogs.none { it.key == key }) return
        _state.value = current.copy(selectedCatalogKey = key, actionMessage = null)
    }

    fun addWidget() {
        val current = _state.value
        if (current.sourceKind == WidgetBuilderSourceKind.PUBLIC_LISTS) {
            addPublicListWidget()
            return
        }
        if (current.sourceKind == WidgetBuilderSourceKind.COLLECTIONS) {
            addCollectionGroupWidget()
            return
        }
        if (!current.canAdd) {
            _state.value = current.copy(actionMessage = "Choose a content source, an add-on, a catalog and a destination.")
            return
        }
        val addonId = current.selectedAddonId ?: return
        val key = current.selectedCatalogKey ?: return
        val destinationId = current.destinationId
        scope.launch(ioDispatcher) {
            val outcome = port.addExternalCatalogWidget(
                profileId = port.viewerProfile().profileId,
                destinationId = destinationId,
                addonId = addonId,
                catalogType = key.catalogType,
                catalogId = key.catalogId,
            )
            loadState(actionMessage = widgetAddMessage(outcome, current.destinationLabel))
        }
    }

    fun selectCollectionGroup(ref: CollectionGroupRef) {
        val current = _state.value
        if (current.collectionGroups.none { it.ref == ref }) return
        if (current.selectedCollectionGroup == ref) return
        _state.value = current.copy(selectedCollectionGroup = ref, actionMessage = null, collectionsStatus = null)
    }

    fun addCollectionGroupWidget() {
        val current = _state.value
        val group = current.selectedCollectionGroupUi
        if (group == null || !current.canAddCollectionGroup) {
            _state.value = current.copy(actionMessage = "Choose an imported group and a destination.")
            return
        }
        val destinationId = current.destinationId
        scope.launch(ioDispatcher) {
            val outcome = port.addCollectionGroupWidget(
                profileId = port.viewerProfile().profileId,
                destinationId = destinationId,
                packId = group.packId,
                groupKey = group.groupKey,
                displayTitle = group.displayLabel,
            )
            loadState(actionMessage = widgetAddMessage(outcome, current.destinationLabel))
        }
    }

    fun selectSourceKind(kind: WidgetBuilderSourceKind) {
        if (_state.value.sourceKind == kind) return
        beginPublicRead()
        _state.value = _state.value.copy(
            sourceKind = kind,
            actionMessage = null,
            publicStatus = null,
            selectedPublicRoute = null,
            publicQuery = "",
            publicResults = emptyList(),
            publicResultsHasMore = false,
            publicNextCursor = null,
            publicLoadingMore = false,
            publicUsers = emptyList(),
            selectedPublicUser = null,
            selectedPublicList = null,

            selectedCollectionGroup = null,
            collectionsStatus = null,
        )
    }

    fun selectPublicProvider(providerId: String) {
        val current = _state.value
        if (current.publicProviders.none { it.providerId == providerId }) return
        if (current.selectedPublicProviderId == providerId) return

        beginPublicRead()
        _state.value = current.copy(
            selectedPublicProviderId = providerId,
            selectedPublicRoute = null,
            publicQuery = "",
            publicResults = emptyList(),
            publicResultsHasMore = false,
            publicNextCursor = null,
            publicLoadingMore = false,
            publicUsers = emptyList(),
            selectedPublicUser = null,
            selectedPublicList = null,
            publicStatus = null,
        )
    }

    fun selectPublicRoute(route: PublicListRoute) {
        val current = _state.value
        val providerId = current.selectedPublicProviderId ?: return
        if (current.selectedPublicRoutes.none { it.route == route }) return
        if (current.selectedPublicRoute == route) return
        val generation = beginPublicRead()
        _state.value = current.copy(
            selectedPublicRoute = route,
            publicQuery = "",
            publicResults = emptyList(),
            publicResultsHasMore = false,
            publicNextCursor = null,
            publicLoadingMore = false,
            publicUsers = emptyList(),
            selectedPublicUser = null,
            selectedPublicList = null,
            publicStatus = null,
        )
        when (route) {

            PublicListRoute.SEARCH_LISTS,
            PublicListRoute.SEARCH_USERS,
            PublicListRoute.USER_LISTS,
            -> Unit
            else -> launchPublicRead(generation) { loadPublicRoute(generation, providerId, route, query = null) }
        }
    }

    fun setPublicQuery(query: String) {
        _state.value = _state.value.copy(publicQuery = query, publicStatus = null)
    }

    fun submitPublicSearch() {
        val current = _state.value
        val providerId = current.selectedPublicProviderId ?: return
        val route = current.selectedPublicRoute ?: return
        val term = current.publicQuery.trim()
        if (term.isEmpty()) {
            _state.value = current.copy(
                publicStatus = if (route == PublicListRoute.USER_LISTS) {
                    "Enter a ${current.publicQueryLabel.lowercase()}."
                } else {
                    "Enter a search term."
                },
            )
            return
        }

        val generation = beginPublicRead()
        if (route == PublicListRoute.USER_LISTS) {

            _state.value = _state.value.copy(
                publicStatus = "Looking up @$term…",
                publicResults = emptyList(),
                publicResultsHasMore = false,
                publicNextCursor = null,
                publicLoadingMore = false,
            )
            launchPublicRead(generation) { loadPublicUserLists(generation, providerId, term) }
            return
        }

        _state.value = _state.value.copy(
            publicStatus = "Searching for \"$term\"…",
            publicResults = emptyList(),
            publicUsers = emptyList(),
            publicResultsHasMore = false,
            publicNextCursor = null,
            publicLoadingMore = false,
        )
        launchPublicRead(generation) { loadPublicRoute(generation, providerId, route, term) }
    }

    fun loadMorePublicResults() {
        val current = _state.value
        val providerId = current.selectedPublicProviderId ?: return
        val route = current.selectedPublicRoute ?: return
        val cursor = current.publicNextCursor ?: return
        if (current.publicLoadingMore) return

        if (lastRequestedPublicCursor == cursor) return
        lastRequestedPublicCursor = cursor
        val generation = publicReadGeneration
        _state.value = current.copy(publicLoadingMore = true, publicStatus = "Loading more…")
        launchPublicRead(generation) { loadPublicRoute(generation, providerId, route, current.publicQuery.trim(), cursor = cursor, append = true) }
    }

    private fun beginPublicRead(): Int {
        publicReadGeneration += 1
        lastRequestedPublicCursor = null
        publicReadJob?.cancel()
        publicReadJob = null
        return publicReadGeneration
    }

    private fun launchPublicRead(generation: Int, block: suspend () -> Unit) {
        if (generation != publicReadGeneration) return
        publicReadJob = scope.launch(ioDispatcher) {
            if (generation != publicReadGeneration) return@launch
            block()
        }
    }

    fun selectPublicUser(username: String) {
        val current = _state.value
        val providerId = current.selectedPublicProviderId ?: return
        if (current.publicUsers.none { it.username == username }) return
        _state.value = current.copy(
            selectedPublicUser = username,
            selectedPublicList = null,
            publicResultsHasMore = false,
            publicNextCursor = null,
            publicLoadingMore = false,
        )
        val generation = beginPublicRead()
        launchPublicRead(generation) { loadPublicUserLists(generation, providerId, username) }
    }

    private suspend fun loadPublicUserLists(generation: Int, providerId: String, username: String) {
        if (generation != publicReadGeneration) return
        _state.value = _state.value.copy(
            selectedPublicUser = username,
            selectedPublicList = null,
            publicResults = emptyList(),
            publicResultsHasMore = false,
            publicNextCursor = null,
            publicLoadingMore = false,
            publicStatus = "Looking up @$username…",
        )
        when (val result = port.publicListUserLists(providerId, username)) {
            is PublicListResult.Success -> {
                if (generation != publicReadGeneration) return

                val requested = username.trim().trimStart('@')
                val shownHandle = result.value
                    .firstOrNull { summary -> summary.ownerLabel.equals(requested, ignoreCase = true) }
                    ?.ownerLabel
                    ?: result.value.firstNotNullOfOrNull { it.ownerLabel }
                    ?: requested
                _state.value = _state.value.copy(
                    publicResults = result.value,
                    publicStatus = if (result.value.isEmpty()) {
                        "@$shownHandle has no public lists."
                    } else {
                        "@$shownHandle's lists available"
                    },
                )

                backfillPublicListTitles(result.value)
            }
            is PublicListResult.Unsupported -> _state.value = _state.value.copy(
                publicResults = emptyList(),
                publicStatus = publicResultMessage(result.reason),
            )
            is PublicListResult.Failed -> _state.value = _state.value.copy(
                publicResults = emptyList(),

                publicStatus = if (result.reason == NOT_FOUND) {
                    "No user named @$username on this provider."
                } else {
                    publicResultMessage(result.reason)
                },
            )
        }
    }

    fun selectPublicList(summary: PublicListSummary) {
        val current = _state.value
        if (current.publicResults.none { it.ref == summary.ref }) return
        _state.value = current.copy(selectedPublicList = summary, actionMessage = null)
    }

    fun addPublicListWidget() {
        val current = _state.value
        if (!current.canAddPublicList) {
            _state.value = current.copy(
                actionMessage = "Choose a provider, a route and one list, then Add widget.",
            )
            return
        }
        val providerId = current.selectedPublicProviderId ?: return
        val list = current.selectedPublicList ?: return
        val destinationId = current.destinationId
        scope.launch(ioDispatcher) {
            val outcome = port.addPublicListWidget(
                profileId = port.viewerProfile().profileId,
                destinationId = destinationId,
                providerId = providerId,
                listId = list.ref.listId,
                owner = list.ref.owner,

                displayTitle = list.title,
            )
            loadState(actionMessage = widgetAddMessage(outcome, current.destinationLabel))
        }
    }

    private suspend fun loadPublicRoute(
        generation: Int,
        providerId: String,
        route: PublicListRoute,
        query: String?,
        cursor: String? = null,
        append: Boolean = false,
    ) {
        if (generation != publicReadGeneration) return
        val searching = query?.trim().orEmpty()
        _state.value = if (append) {
            _state.value.copy(publicStatus = "Loading more…")
        } else {
            _state.value.copy(
                publicStatus = if (searching.isNotEmpty()) "Searching for \"$searching\"…" else "Loading…",
                selectedPublicList = null,
            )
        }
        if (route == PublicListRoute.SEARCH_USERS) {
            when (val users = port.publicListUsers(providerId, query.orEmpty())) {
                is PublicListResult.Success -> if (generation == publicReadGeneration) {
                    _state.value = _state.value.copy(
                        publicResults = emptyList(),
                        publicUsers = users.value,
                        publicResultsHasMore = false,
                        publicNextCursor = null,
                        publicLoadingMore = false,
                        publicStatus = if (users.value.isEmpty()) {
                            "No users found for \"$searching\"."
                        } else {
                            null
                        },
                    )
                }
                is PublicListResult.Unsupported -> if (generation == publicReadGeneration) {
                    _state.value = _state.value.copy(
                        publicResults = emptyList(),
                        publicUsers = emptyList(),
                        publicLoadingMore = false,
                        publicStatus = publicResultMessage(users.reason),
                    )
                }
                is PublicListResult.Failed -> if (generation == publicReadGeneration) {
                    _state.value = _state.value.copy(
                        publicResults = emptyList(),
                        publicUsers = emptyList(),
                        publicLoadingMore = false,
                        publicStatus = publicResultMessage(users.reason),
                    )
                }
            }
            return
        }
        when (val result = port.publicListPage(providerId, route, query, cursor)) {
            is PublicListResult.Success -> {
                if (generation != publicReadGeneration) return
                val page = result.value
                val hasMore = page.hasMore && page.nextCursor != null

                val accumulated = if (append) {
                    val known = _state.value.publicResults.mapTo(mutableSetOf()) { it.ref }
                    _state.value.publicResults + page.lists.filterNot { it.ref in known }
                } else {
                    page.lists
                }
                _state.value = _state.value.copy(
                    publicResults = accumulated,
                    publicUsers = emptyList(),
                    publicResultsHasMore = hasMore,
                    publicNextCursor = if (hasMore) page.nextCursor else null,
                    publicLoadingMore = false,

                    publicStatus = if (
                        !append && accumulated.isEmpty() && route == PublicListRoute.SEARCH_LISTS
                    ) {
                        "No lists found for \"$searching\"."
                    } else {
                        null
                    },
                )
                lastRequestedPublicCursor = null
                backfillPublicListTitles(page.lists)
            }
            is PublicListResult.Unsupported -> if (generation == publicReadGeneration) {

                _state.value = _state.value.copy(
                    publicResults = if (append) _state.value.publicResults else emptyList(),
                    publicUsers = emptyList(),
                    publicLoadingMore = false,
                    publicStatus = publicResultMessage(result.reason),
                )
            }
            is PublicListResult.Failed -> if (generation == publicReadGeneration) {

                _state.value = _state.value.copy(
                    publicResults = if (append) _state.value.publicResults else emptyList(),
                    publicUsers = emptyList(),
                    publicLoadingMore = false,
                    publicStatus = publicResultMessage(result.reason),
                )

                if (append) lastRequestedPublicCursor = null
            }
        }
    }

    private fun backfillPublicListTitles(summaries: List<PublicListSummary>) {
        if (summaries.isEmpty()) return
        val profileId = port.viewerProfile().profileId
        var written = false
        summaries.forEach { summary ->
            written = port.recordPublicListTitle(
                profileId = profileId,
                providerId = summary.ref.provider.id,
                listId = summary.ref.listId,
                owner = summary.ref.owner,
                title = summary.title,
            ) || written
        }

        if (written) loadState()
    }

    fun moveWidget(widgetId: String, delta: Int) {
        scope.launch(ioDispatcher) {
            val outcome = port.moveWidget(port.viewerProfile().profileId, widgetId, delta)
            loadState(actionMessage = if (outcome == WidgetAddOutcome.WIDGET_UNKNOWN) "That widget is no longer available." else null)
        }
    }

    fun deleteWidget(widgetId: String) {
        scope.launch(ioDispatcher) {
            val profileId = port.viewerProfile().profileId
            if (widgetIsSpotlightSource(widgetId, spotlightSource(profileId))) {

                loadState(actionMessage = null, blockedDeleteWidgetId = widgetId)
                return@launch
            }
            port.deleteWidget(profileId, widgetId)
            loadState(actionMessage = "Widget deleted")
        }
    }

    private fun loadState(
        actionMessage: String? = _state.value.actionMessage,

        blockedDeleteWidgetId: String? = null,
    ) {
        val viewer = port.viewerProfile()
        val primary = port.primaryProfile()
        val profileId = viewer.profileId
        val destinations = port.destinations()
        val options = port.availableExternalCatalogs(viewer, primary)
        val publicProviders = port.publicListProviders().mapNotNull { provider ->
            val capabilities = port.publicListCapabilities(provider.id) ?: return@mapNotNull null
            val accountConnected = port.publicListAccountConnected(provider.id)

            if (!capabilities.hasUsableRoutes(accountConnected)) return@mapNotNull null
            WidgetBuilderPublicProviderUi(
                providerId = provider.id,
                displayName = provider.displayName,
                routes = capabilities.routes,
                accountConnected = accountConnected,
            )
        }
        val configuration = port.widgetConfiguration(profileId)
        val collectionGroupOptions = port.collectionGroupOptions(profileId)
        val entries = homeWidgetEntries(
            configuration = configuration,
            destinations = destinations,
            availableCatalogs = options,
            availablePublicProviders = publicProviders.mapNotNull { provider ->
                PublicListProvider.fromId(provider.providerId)
            },
            availableCollectionGroups = collectionGroupOptions.associate { it.ref to it.displayLabel },
        )
        val addons = options
            .groupBy { it.key.addonId }
            .map { (addonId, catalogs) ->
                WidgetBuilderAddonUi(
                    addonId = addonId,
                    displayName = catalogs.first().addonDisplayName,
                    catalogs = catalogs,
                )
            }
        val selectedAddonId = _state.value.selectedAddonId?.takeIf { id -> addons.any { it.addonId == id } }
        val selectedCatalogKey = _state.value.selectedCatalogKey?.takeIf { key ->
            addons.firstOrNull { it.addonId == selectedAddonId }?.catalogs?.any { it.key == key } == true
        }
        val destinationId = _state.value.destinationId
            .takeIf { id -> destinations.any { it.id == id } }
            ?: destinations.firstOrNull()?.id
            ?: WidgetDestinations.HOME.id
        val selectedPublicProviderId = _state.value.selectedPublicProviderId
            ?.takeIf { id -> publicProviders.any { it.providerId == id } }
        val selectedPublicRoute = _state.value.selectedPublicRoute?.takeIf { route ->
            publicProviders.firstOrNull { it.providerId == selectedPublicProviderId }
                ?.offeredRoutes?.any { it.route == route } == true
        }
        _state.value = WidgetBuilderUiState(
            loading = false,
            destinations = destinations,
            destinationId = destinationId,
            addons = addons,
            selectedAddonId = selectedAddonId,
            selectedCatalogKey = selectedCatalogKey,
            pages = widgetBuilderPages(entries, destinations),
            actionMessage = actionMessage,
            blockedDeleteWidgetId = blockedDeleteWidgetId,
            viewerProfileId = profileId,

            sourceKind = _state.value.sourceKind,
            publicProviders = publicProviders,
            selectedPublicProviderId = selectedPublicProviderId,
            selectedPublicRoute = selectedPublicRoute,
            publicQuery = _state.value.publicQuery,
            publicResults = _state.value.publicResults,
            publicResultsHasMore = _state.value.publicResultsHasMore,
            publicNextCursor = _state.value.publicNextCursor,
            publicLoadingMore = _state.value.publicLoadingMore,
            publicUsers = _state.value.publicUsers,
            selectedPublicUser = _state.value.selectedPublicUser,
            selectedPublicList = _state.value.selectedPublicList,
            publicStatus = _state.value.publicStatus,

            collectionGroups = collectionGroupOptions,
            selectedCollectionGroup = _state.value.selectedCollectionGroup
                ?.takeIf { ref -> collectionGroupOptions.any { it.ref == ref } },
            collectionsStatus = _state.value.collectionsStatus,
        )
    }
}

internal fun widgetBuilderPages(
    entries: List<HomeWidgetEntry>,
    destinations: List<WidgetDestination>,
): List<WidgetBuilderPageUi> {
    val known = destinations.map { destination ->
        WidgetBuilderPageUi(
            destinationId = destination.id,
            label = destination.displayName,
            available = true,
            widgets = entryRows(entries.filter { it.destinationId == destination.id }),
        )
    }
    val knownIds = destinations.mapTo(mutableSetOf()) { it.id }
    val unknown = entries
        .filterNot { it.destinationId in knownIds }
        .groupBy { it.destinationId }
        .map { (destinationId, group) ->
            WidgetBuilderPageUi(
                destinationId = destinationId,
                label = "$destinationId (unavailable)",
                available = false,
                widgets = entryRows(group),
            )
        }
    return known + unknown
}

private fun entryRows(entries: List<HomeWidgetEntry>): List<WidgetBuilderWidgetUi> =
    entries.mapIndexed { index, entry ->
        WidgetBuilderWidgetUi(
            entry = entry,
            canMoveUp = index > 0,
            canMoveDown = index < entries.lastIndex,
        )
    }

private fun widgetAddMessage(
    outcome: WidgetAddOutcome,
    destinationLabel: String,
): String = when (outcome) {
    WidgetAddOutcome.ADDED -> "Widget added"
    WidgetAddOutcome.UNKNOWN_DESTINATION -> "That destination is not available."
    WidgetAddOutcome.UNKNOWN_CATALOG -> "That catalog is no longer available."
    WidgetAddOutcome.UNKNOWN_PROVIDER -> "That provider is not available."
    WidgetAddOutcome.UNKNOWN_PUBLIC_LIST -> "That list is no longer available."
    WidgetAddOutcome.UNKNOWN_COLLECTION_GROUP -> "That collection group is no longer available."
    WidgetAddOutcome.LIMIT_REACHED -> "The widget limit for this profile has been reached."
    WidgetAddOutcome.DUPLICATE -> "Already added to $destinationLabel"
    WidgetAddOutcome.WIDGET_UNKNOWN -> "That widget is no longer available."
}

private const val NOT_FOUND: String = "not_found"

private fun publicResultMessage(reason: String): String = when (reason) {
    "mdblist.com exposes no public user search" -> "This provider has no public user search."
    "not exposed publicly by mdblist.com" -> "This route is not public on this provider."
    "simkl exposes no user search" -> "This provider has no user search; look up a user id instead."
    "simkl serves no such list route" -> "This provider serves no such list route."
    "no such page" -> "That list has no further pages."
    "not_found" -> "That list could not be found."
    "unauthorized" -> "This route needs the provider account to be connected."

    "account_rejected" -> "This provider refused the connected account's authorization."
    "access_denied" -> "This provider refused the request without an account."
    "private_list" -> "That list is private."
    "premium_only" -> "This provider requires a paid plan for its lists."
    "rate_limit" -> "The provider is rate-limiting requests. Try again shortly."
    "provider_unavailable" -> "The provider is unavailable right now."
    "transport_failure" -> "The provider could not be reached."
    "mdblist_response_shape_changed" -> "The provider's response changed shape and could not be read."
    "trakt_response_shape_changed" -> "The provider's response changed shape and could not be read."
    "simkl_response_shape_changed" -> "The provider's response changed shape and could not be read."
    "user search needs the connected Trakt account" -> "User search needs a connected Trakt account."
    "trakt has no such list category" -> "This provider has no such list category."
    else -> "That route is not available: $reason"
}
