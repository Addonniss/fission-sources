package dev.infusion.source.jellyfin.catalog

import dev.infusion.core.domain.catalog.CatalogEntry
import dev.infusion.core.domain.catalog.CatalogImage
import dev.infusion.core.domain.catalog.CatalogPage
import dev.infusion.core.domain.catalog.CatalogPort
import dev.infusion.core.domain.catalog.HomeItem
import dev.infusion.core.domain.catalog.HomeShelf
import dev.infusion.core.domain.catalog.TitleDetails
import dev.infusion.core.domain.source.SourceItemStore
import dev.infusion.core.model.Source
import dev.infusion.core.model.Title
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.playback.api.ServerResumePosition
import dev.infusion.source.jellyfin.auth.JellyfinSession
import dev.infusion.source.jellyfin.client.ApiResult
import dev.infusion.source.jellyfin.client.FailureKind
import dev.infusion.source.jellyfin.client.JellyfinClient
import dev.infusion.source.jellyfin.client.JellyfinFailure
import dev.infusion.source.jellyfin.session.JellyfinForensicsLog
import dev.infusion.source.jellyfin.wire.ItemSummary
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

const val JELLYFIN_PAGE_SIZE = 100

const val JELLYFIN_TAB_ROW_LIMIT = 50

const val CARD_PROGRESS_BATCH_MAX = 20

const val NATIVE_ITEM_BATCH = 100
const val RECENT_ITEM_LIMIT = 50

private const val HIERARCHY_REUSE_TTL_MS = 60_000L

private const val HIERARCHY_REUSE_MAX_ENTRIES = 128

private const val SEASON_HIERARCHY_PAGE = 200

private const val DATE_PREFIX_LENGTH = 10

private const val JELLYFIN_TICKS_PER_MILLISECOND: Long = 10_000L

const val JELLYFIN_ITEM_FIELDS = dev.infusion.source.jellyfin.client.JELLYFIN_ITEM_FIELDS

val JELLYFIN_IMAGE_LIMITS: NetworkLimits =
    NetworkLimits.DEFAULT.copy(maxResponseBytes = 8L * 1024 * 1024)

data class JellyfinRowPage(
    val items: List<JellyfinRowItem>,
    val totalRecordCount: Int?,
)

data class JellyfinRowItem(
    val nativeItemId: String,
    val titleId: TitleId,
    val displayTitle: String,
    val kind: TitleKind,
    val year: Int?,
    val isFolder: Boolean,
    val collectionType: String?,
    val childCount: Int?,
    val seriesTitleId: TitleId?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val playedPercentage: Double?,
    val resumePositionTicks: Long?,
    val runtimeTicks: Long?,

    val seriesName: String? = null,

    val airDate: LocalDate? = null,

    val lastPlayedDate: String? = null,

    val canonicalTitleId: TitleId? = null,

    val canonicalSeriesTitleId: TitleId? = null,

    val albumName: String? = null,

    val albumArtistName: String? = null,

    val parentNativeId: String? = null,

    val nativeType: String? = null,

    val albumArtistId: String? = null,

    val imageTypes: Set<String> = emptySet(),

    val playlistItemId: String? = null,

    val unavailable: Boolean = false,
)

class JellyfinCatalogPort(
    private val source: Source,
    private val client: JellyfinClient,
    private val authenticator: dev.infusion.source.jellyfin.auth.JellyfinAuthenticator,
    private val store: SourceItemStore,
    private val mapper: JellyfinMapper,
    private val sessionProvider: () -> JellyfinSession?,
    private val scope: CoroutineScope,
    private val limits: NetworkLimits = NetworkLimits.DEFAULT,

    private val convergence: dev.infusion.core.domain.identity.CanonicalConvergenceStore? = null,

    private val ratingsEvidence: dev.infusion.core.domain.ratings.JellyfinRatingsEvidenceStore? = null,

    private val clock: () -> Long = { System.currentTimeMillis() },
) : CatalogPort {

    private val _home = MutableStateFlow<List<HomeShelf>>(emptyList())
    private val home: StateFlow<List<HomeShelf>> = _home.asStateFlow()

    private val _details = MutableStateFlow<Map<TitleId, TitleDetails>>(emptyMap())
    private val detailsCache: StateFlow<Map<TitleId, TitleDetails>> = _details.asStateFlow()

    private data class HierarchyRequestKey(
        val userId: String,
        val parentNativeId: String,
        val startIndex: Int,
        val limit: Int,
    )

    private data class HierarchyReuseEntry(val page: CatalogPage, val storedAtMs: Long)

    private data class HierarchyClaim(
        val deferred: CompletableDeferred<CatalogPage?>,
        val owner: Boolean,
    )

    private val hierarchyLock = Any()

    private val hierarchyReuse =
        LinkedHashMap<HierarchyRequestKey, HierarchyReuseEntry>(16, 0.75f, true)

    private val hierarchyInflight =
        LinkedHashMap<HierarchyRequestKey, CompletableDeferred<CatalogPage?>>()

    @Volatile
    private var hierarchyGeneration: Long = 0L

    init {

        hydrateHomeFromCache()
    }

    private fun hydrateHomeFromCache() {
        val cached = cachedTitles()
        if (cached.isEmpty()) return
        _home.value = listOf(
            HomeShelf(
                key = "jellyfin:cached",
                title = "Cached",
                sourceLabel = source.displayName,
                items = cached.map { title ->
                    HomeItem(
                        key = "jellyfin:item:${title.titleId.value}",
                        titleId = title.titleId,
                        editionId = null,
                        displayTitle = title.displayTitle,
                        kind = title.kind,
                        year = title.year,
                    )
                },
            ),
        )
    }

    override fun observeHome(): Flow<List<HomeShelf>> = home

    override fun observeTitleDetails(titleId: TitleId): Flow<TitleDetails?> =
        detailsCache.map { it[titleId] }

    override fun sources(): List<Source> = listOf(source)

    suspend fun refreshHome(): RefreshResult {

        invalidateHierarchyReuse()
        val session = sessionProvider() ?: return RefreshResult.Failed(
            JellyfinFailure(FailureKind.UNAUTHORIZED, null, "no session", retryable = false),
        )
        val token = tokenFor(session)
        if (token == null) {
            return RefreshResult.Failed(
                JellyfinFailure(FailureKind.UNAUTHORIZED, null, "stored token missing", retryable = false),
            )
        }
        return when (val views = client.userViews(session.userId, token, limits)) {
            is ApiResult.Err -> RefreshResult.Failed(views.failure)
            is ApiResult.Ok -> {
                val shelves = mutableListOf<HomeShelf>()
                var libraryCount = 0
                val libraries = views.value.items
                for (library in libraries) {
                    val libraryId = library.id ?: continue
                    val fetched = fetchLibrary(session, token, libraryId)
                    when (fetched) {
                        is LibraryFetch.Complete -> {
                            libraryCount++
                            shelves += HomeShelf(
                                key = "jellyfin:library:${libraryId}",
                                title = library.name ?: "Library",
                                sourceLabel = source.displayName,
                                items = fetched.homeItems,
                            )

                            store.deleteMissingByLibrary(source.sourceId, libraryId, fetched.nativeIds)
                        }
                        is LibraryFetch.Failed -> Unit
                    }
                }
                _home.value = shelves
                RefreshResult.Ok(libraryCount)
            }
        }
    }

    private sealed interface LibraryFetch {
        data class Complete(
            val persistedItems: List<dev.infusion.core.model.ServerItem>,
            val homeItems: List<HomeItem>,
            val nativeIds: Set<String>,
        ) : LibraryFetch

        data object Failed : LibraryFetch
    }

    private suspend fun fetchLibrary(
        session: JellyfinSession,
        token: String,
        libraryId: String,
    ): LibraryFetch {
        val fetchStart = System.currentTimeMillis()
        val persistedItems = mutableListOf<dev.infusion.core.model.ServerItem>()
        val persistedTitles = mutableListOf<dev.infusion.core.model.Title>()
        val persistedAvailability = mutableListOf<dev.infusion.core.model.Availability>()
        val homeItems = mutableListOf<HomeItem>()
        val nativeIds = mutableSetOf<String>()
        var startIndex = 0
        var total: Int? = null
        while (true) {
            val page = client.items(
                userId = session.userId,
                token = token,
                query = browseQuery(
                    parentId = libraryId,
                    startIndex = startIndex,
                    limit = JELLYFIN_PAGE_SIZE,
                    recursive = true,
                    includeItemTypes = listOf("Movie", "Series"),
                ),
                limits = limits,
            )
            when (page) {
                is ApiResult.Err -> return LibraryFetch.Failed
                is ApiResult.Ok -> {
                    val observedTotal = page.value.totalRecordCount ?: (startIndex + page.value.items.size)
                    total = observedTotal
                    val items = page.value.items.map { mapper.toServerItem(it, libraryId) }
                    persistedItems += items

                    persistedTitles += page.value.items.map(mapper::toTitle)
                    persistedAvailability += page.value.items.map(mapper::toAvailability)
                    recordRatingsEvidence(page.value.items)
                    for (item in items) {
                        nativeIds += item.nativeId
                        val titleId = item.titleId ?: continue
                        homeItems += HomeItem(
                            key = "jellyfin:item:${item.nativeId}",
                            titleId = titleId,
                            editionId = null,
                            displayTitle = item.displayTitle ?: "(untitled)",
                            kind = kindOf(item.nativeType),
                            year = item.productionYear,
                            subtitle = null,
                        )
                    }

                    val fetched = startIndex + page.value.items.size
                    if (fetched >= observedTotal) break

                    if (page.value.items.isEmpty() || fetched <= startIndex) break
                    startIndex = fetched
                }
            }
        }

        if (persistedItems.isNotEmpty()) {
            val txStart = System.currentTimeMillis()
            store.upsertAll(
                items = persistedItems,
                versions = emptyList(),
                titles = persistedTitles,
                availability = persistedAvailability,
            )
            val txEnd = System.currentTimeMillis()

            convergence?.let { convergeMirroredIdentities(persistedTitles, it) }
            val sideEnd = System.currentTimeMillis()

            mirrorTiming(
                stage = "library",
                items = persistedItems.size,
                txMs = txEnd - txStart,
                sideMs = sideEnd - txEnd,
                totalMs = sideEnd - fetchStart,
            )
        }
        return LibraryFetch.Complete(persistedItems, homeItems, nativeIds)
    }

    suspend fun refreshDetails(titleId: TitleId, refreshFromServer: Boolean = true): TitleDetails? {
        val cached = store.serverItemsByTitle(titleId)
        val session = sessionProvider()
        val token = session?.let(::tokenFor)

        val fromCache: (List<dev.infusion.core.model.ServerItem>) -> List<ItemSummary> = { rows ->
            rows.map { item ->
                ItemSummary(
                    id = item.nativeId,
                    name = item.displayTitle,
                    type = item.nativeType,
                    collectionType = null,
                    parentId = item.nativeParentId,
                    seriesId = null,
                    seasonId = null,
                    indexNumber = null,
                    parentIndexNumber = null,
                    runTimeTicks = null,
                    isFolder = null,
                    childCount = null,
                    userData = null,
                    overview = null,
                    productionYear = item.productionYear,
                    imageTags = emptyMap(),
                )
            }
        }
        val items: List<ItemSummary> = if (refreshFromServer && session != null && token != null) {
            val refreshed = cached.mapNotNull { cachedItem ->
                when (val result = client.item(session.userId, cachedItem.nativeId, token, limits)) {
                    is ApiResult.Err -> null
                    is ApiResult.Ok -> result.value
                }
            }

            if (refreshed.isEmpty() && cached.isNotEmpty()) fromCache(cached) else refreshed
        } else {
            fromCache(cached)
        }
        if (items.isEmpty()) return null
        val title = mapper.toTitle(items.first())
        val availability = items.map(mapper::toAvailability)
        val details = TitleDetails(
            title = title,
            editions = emptyList(),
            availability = availability,
        )
        _details.value = _details.value + (titleId to details)
        return details
    }

    suspend fun currentResumePosition(titleId: TitleId): ServerResumePosition? {
        for (item in store.serverItemsByTitle(titleId)) {
            val position = currentResumePositionForNativeItem(item.nativeId)
            if (position != null) return position
        }
        return null
    }

    suspend fun currentResumePositionForNativeItem(nativeId: String): ServerResumePosition? {
        val session = sessionProvider() ?: return null
        val token = tokenFor(session) ?: return null
        val requestingUserId = session.userId
        val item = when (val result = client.item(requestingUserId, nativeId, token, limits)) {
            is ApiResult.Err -> return null
            is ApiResult.Ok -> result.value
        }

        if (sessionProvider()?.userId != requestingUserId) return null
        val ticks = item.userData?.playbackPositionTicks ?: return null

        return ServerResumePosition(
            positionMs = ticks / JELLYFIN_TICKS_PER_MILLISECOND,
            observedAt = parseServerInstant(item.userData?.lastPlayedDate),
        )
    }

    suspend fun serverItemState(nativeId: String): PostPlaybackItemState? {
        val session = sessionProvider() ?: return null
        val token = tokenFor(session) ?: return null
        val requestingUserId = session.userId
        val item = when (val result = client.item(requestingUserId, nativeId, token, limits)) {
            is ApiResult.Err -> return null
            is ApiResult.Ok -> result.value
        }
        if (sessionProvider()?.userId != requestingUserId) return null
        val ticks = item.userData?.playbackPositionTicks ?: return null
        return PostPlaybackItemState(
            nativeId = nativeId,
            positionMs = ticks / JELLYFIN_TICKS_PER_MILLISECOND,
            runtimeMs = item.runTimeTicks?.let { it / JELLYFIN_TICKS_PER_MILLISECOND },
            lastPlayedAt = parseServerInstant(item.userData?.lastPlayedDate),
            played = item.userData?.played == true,
        )
    }

    suspend fun writePostPlaybackState(
        nativeId: String,
        positionMs: Long,
        played: Boolean?,
    ): ApiResult<Unit> {
        val session = sessionProvider()
            ?: return ApiResult.Err(
                dev.infusion.source.jellyfin.client.JellyfinFailure(
                    dev.infusion.source.jellyfin.client.FailureKind.UNAUTHORIZED,
                    null,
                    "no session for progress write",
                    retryable = false,
                ),
            )
        val token = tokenFor(session)
            ?: return ApiResult.Err(
                dev.infusion.source.jellyfin.client.JellyfinFailure(
                    dev.infusion.source.jellyfin.client.FailureKind.UNAUTHORIZED,
                    null,
                    "no token for progress write",
                    retryable = false,
                ),
            )
        val requestingUserId = session.userId
        val result = client.updateUserItemData(
            userId = requestingUserId,
            itemId = nativeId,
            token = token,
            positionMs = positionMs,
            played = played,
            limits = limits,
        )

        if (sessionProvider()?.userId != requestingUserId) {
            return ApiResult.Err(
                dev.infusion.source.jellyfin.client.JellyfinFailure(
                    dev.infusion.source.jellyfin.client.FailureKind.UNAUTHORIZED,
                    null,
                    "session changed during progress write",
                    retryable = false,
                ),
            )
        }
        return result
    }

    suspend fun episodeResumePositionsForSeason(
        seriesTitleId: TitleId,
        seasonNumber: Int,
    ): Map<Pair<Int, Int>, ServerResumePosition> {
        val seriesNativeId = store.serverItemsByTitle(seriesTitleId).firstOrNull()?.nativeId ?: return emptyMap()
        val seasonNativeId = children(seriesNativeId, 0, SEASON_HIERARCHY_PAGE)?.entries
            ?.firstOrNull { it.kind == TitleKind.SEASON && it.seasonNumber == seasonNumber }
            ?.nativeItemId
            ?.takeIf { it.isNotBlank() }
            ?: return emptyMap()
        val episodes = children(seasonNativeId, 0, SEASON_HIERARCHY_PAGE)?.entries.orEmpty()
        val out = LinkedHashMap<Pair<Int, Int>, ServerResumePosition>()
        for (episode in episodes) {
            val season = episode.seasonNumber ?: continue
            val number = episode.episodeNumber ?: continue
            val position = episode.serverResumeMs ?: continue
            out[season to number] = ServerResumePosition(
                positionMs = position,
                observedAt = episode.serverResumeObservedAt,
            )
        }
        return out
    }

    suspend fun postPlaybackEpisodeState(
        seriesTitleId: TitleId,
        seasonNumber: Int?,
        episodeNumber: Int?,
    ): PostPlaybackItemState? {
        val season = seasonNumber ?: return null
        val episode = episodeNumber ?: return null
        if (season <= 0 || episode <= 0) return null
        val seriesNativeIds = store.serverItemsByTitle(seriesTitleId)
            .filter { it.sourceId == source.sourceId }
            .filter { kindOf(it.nativeType) == TitleKind.SERIES }
            .map { it.nativeId }
            .distinct()
        if (seriesNativeIds.size != 1) return null
        val session = sessionProvider() ?: return null
        val seriesNativeId = seriesNativeIds.single()
        val seasons = when (val result = seasons(session, seriesNativeId)) {
            is ApiResult.Ok -> result.value.items
            is ApiResult.Err -> return null
        }
        val seasonNativeIds = seasons

            .filter { it.indexNumber == season && kindOf(it.type ?: "Unknown") == TitleKind.SEASON }
            .mapNotNull { it.id }
            .distinct()
        if (seasonNativeIds.size != 1) return null
        val episodes = when (val result = episodes(session, seriesNativeId, seasonNativeIds.single())) {
            is ApiResult.Ok -> result.value.items
            is ApiResult.Err -> return null
        }
        val episodeNativeIds = episodes
            .filter {
                kindOf(it.type ?: "Unknown") == TitleKind.EPISODE &&
                    it.parentIndexNumber == season &&
                    it.indexNumber == episode
            }
            .mapNotNull { it.id }
            .distinct()
        if (episodeNativeIds.size != 1) return null
        return serverItemState(episodeNativeIds.single())
    }

    suspend fun userProgressForNativeIds(
        nativeIds: List<String>,
    ): ApiResult<ServerProgressBatch> {
        val wanted = nativeIds.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) {
            return ApiResult.Err(
                JellyfinFailure(
                    FailureKind.BAD_REQUEST,
                    null,
                    "card progress read asked without an item id",
                    retryable = false,
                ),
            )
        }
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        val requestingUserId = session.userId

        val result = client.items(
            userId = requestingUserId,
            token = token,
            query = mapOf(
                "ids" to wanted.joinToString(","),
                "recursive" to "true",
            ),
            limits = limits,
        )
        return when (result) {
            is ApiResult.Err -> ApiResult.Err(result.failure)
            is ApiResult.Ok -> {

                if (sessionProvider()?.userId != requestingUserId) {
                    return ApiResult.Err(
                        JellyfinFailure(
                            FailureKind.UNAUTHORIZED,
                            null,
                            "session changed during card progress read",
                            retryable = false,
                        ),
                    )
                }
                val asked = wanted.toSet()
                val items = result.value.items.mapNotNull { item ->
                    val nativeId = item.id ?: return@mapNotNull null

                    if (nativeId !in asked) return@mapNotNull null

                    val ticks = item.userData?.playbackPositionTicks ?: return@mapNotNull null
                    val kind = kindOf(item.type ?: "Unknown")

                    if (kind != TitleKind.MOVIE) return@mapNotNull null
                    ServerItemProgress(
                        nativeId = nativeId,

                        titleId = canonicalTitleIdFor(nativeId),
                        kind = kind,
                        positionMs = ticks / JELLYFIN_TICKS_PER_MILLISECOND,
                        runtimeMs = item.runTimeTicks?.let { it / JELLYFIN_TICKS_PER_MILLISECOND },

                        observedAt = parseServerInstant(item.userData?.lastPlayedDate),
                    )
                }
                ApiResult.Ok(
                    ServerProgressBatch(
                        sourceId = source.sourceId.value.toString(),
                        userId = requestingUserId,
                        items = items,
                        requestedCount = wanted.size,
                    ).also { batch ->

                        JellyfinForensicsLog.event(
                            "card-progress",
                            "requested=${batch.requestedCount} answered=${batch.items.size}" +
                                " movies=${batch.items.count { item -> item.kind == TitleKind.MOVIE }}",
                        )
                    },
                    result.status,
                )
            }
        }
    }

    fun nativeItemIdsForTitles(titleIds: List<TitleId>): Map<TitleId, String> {
        if (titleIds.isEmpty()) return emptyMap()
        val out = LinkedHashMap<TitleId, String>()
        for (titleId in titleIds.distinct()) {
            val nativeIds = store.serverItemsByTitle(titleId)
                .filter { it.sourceId == source.sourceId }
                .filter { kindOf(it.nativeType) == TitleKind.MOVIE }
                .map { it.nativeId }
                .distinct()
            if (nativeIds.size == 1) out[titleId] = nativeIds.single()
        }
        return out
    }

    fun cachedTitles(): List<Title> {
        val seen = LinkedHashMap<TitleId, Title>()
        for (item in store.serverItemsBySource(source.sourceId)) {
            val titleId = item.titleId ?: continue
            if (titleId !in seen) {
                seen[titleId] = Title(
                    titleId = titleId,
                    kind = kindOf(item.nativeType),
                    displayTitle = item.displayTitle ?: "(untitled)",
                    year = item.productionYear,
                    provenance = null,
                )
            }
        }
        return seen.values.toList()
    }

    suspend fun resumeItems(
        limit: Int = JELLYFIN_TAB_ROW_LIMIT,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        val result = client.resumeItems(
            userId = session.userId,
            token = token,
            query = buildMap {
                put("limit", limit.toString())
                put("fields", JELLYFIN_ITEM_FIELDS)

                put("mediaTypes", "Video")
            },
            limits = limits,
        )
        return when (result) {
            is ApiResult.Ok -> {

                persistPage(result.value, stage = "resume")

                ApiResult.Ok(
                    result.value.resumableMediaPage().toRowPage(resolveCanonicalIdentity = true),
                    result.status,
                )
            }
            is ApiResult.Err -> ApiResult.Err(result.failure)
        }
    }

    private fun dev.infusion.source.jellyfin.wire.ItemsPage.resumableMediaPage():
        dev.infusion.source.jellyfin.wire.ItemsPage {
        val resumable = items.filter { item -> item.isFolder != true }
        if (resumable.size == items.size) return this

        JellyfinForensicsLog.event(
            "resume-row",
            "kept=${resumable.size} droppedContainers=${items.size - resumable.size}",
        )
        return copy(items = resumable, totalRecordCount = resumable.size)
    }

    suspend fun nextUp(
        limit: Int = JELLYFIN_TAB_ROW_LIMIT,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        return when (val result = client.nextUp(
            userId = session.userId,
            token = token,
            query = buildMap {
                put("limit", limit.toString())
                put("fields", JELLYFIN_ITEM_FIELDS)
            },
            limits = limits,
        )) {
            is ApiResult.Ok -> {

                persistPage(result.value, stage = "nextup")
                ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            }
            is ApiResult.Err -> ApiResult.Err(result.failure)
        }
    }

    suspend fun latestItems(
        includeItemTypes: List<String>,
        limit: Int = JELLYFIN_TAB_ROW_LIMIT,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        val query = buildMap {
            put("limit", limit.toString())
            put("fields", JELLYFIN_ITEM_FIELDS)
            if (includeItemTypes.isNotEmpty()) put("includeItemTypes", includeItemTypes.joinToString(","))
        }
        val result = client.latestItems(
            userId = session.userId,
            token = token,
            query = query,
            limits = limits,
        )

        when (result) {
            is ApiResult.Ok -> JellyfinForensicsLog.event(
                "latest-row",
                "types=${includeItemTypes.joinToString("|")} status=${result.status} items=${result.value.items.size}",
            )
            is ApiResult.Err -> JellyfinForensicsLog.event(
                "latest-row",
                "types=${includeItemTypes.joinToString("|")} error=${result.failure.kind.name} " +
                    "status=${result.failure.status ?: "-"} label=${result.failure.message}",
            )
        }
        return when (result) {
            is ApiResult.Ok -> {

                persistPage(result.value, stage = "latest")
                ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            }
            is ApiResult.Err -> ApiResult.Err(result.failure)
        }
    }

    suspend fun libraryViews(): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        return when (val views = client.userViews(session.userId, token, limits)) {
            is ApiResult.Ok -> ApiResult.Ok(views.value.toRowPage(resolveCanonicalIdentity = true), views.status)
            is ApiResult.Err -> ApiResult.Err(views.failure)
        }
    }

    private fun moviesSectionForensics(section: String, result: ApiResult<*>) {
        when (result) {
            is ApiResult.Ok -> JellyfinForensicsLog.event(
                "movies-section",
                "section=$section status=${result.status} items=${(result.value as? JellyfinRowPage)?.items?.size ?: 0}",
            )
            is ApiResult.Err -> JellyfinForensicsLog.event(
                "movies-section",
                "section=$section error=${result.failure.kind.name} status=${result.failure.status ?: "-"} label=${result.failure.message}",
            )
        }
    }

    suspend fun moviesLibraryMovies(
        libraryId: String,
        limit: Int = JELLYFIN_PAGE_SIZE,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        return when (val result = client.items(
            userId = session.userId,
            token = token,
            query = browseQuery(
                parentId = libraryId,
                startIndex = 0,
                limit = limit,
                recursive = true,
                includeItemTypes = listOf("Movie"),
            ).toMutableMap().apply {
                put("sortBy", "SortName")
                put("sortOrder", "Ascending")
            },
            limits = limits,
        )) {
            is ApiResult.Ok -> {
                moviesSectionForensics("movies", result)
                persistPage(result.value)
                ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            }
            is ApiResult.Err -> {
                moviesSectionForensics("movies", result)
                ApiResult.Err(result.failure)
            }
        }
    }

    suspend fun moviesLibrarySuggestions(
        libraryId: String,
        limit: Int = JELLYFIN_TAB_ROW_LIMIT,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        return when (val result = client.suggestionsMovies(
            userId = session.userId,
            token = token,
            parentId = libraryId,
            limit = limit,
            limits = limits,
        )) {
            is ApiResult.Ok -> {
                moviesSectionForensics("suggestions", result)
                persistPage(result.value)
                ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            }
            is ApiResult.Err -> {
                moviesSectionForensics("suggestions", result)
                ApiResult.Err(result.failure)
            }
        }
    }

    suspend fun moviesLibraryFavorites(
        libraryId: String,
        limit: Int = JELLYFIN_PAGE_SIZE,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        return when (val result = client.items(
            userId = session.userId,
            token = token,
            query = browseQuery(
                parentId = libraryId,
                startIndex = 0,
                limit = limit,
                recursive = true,
                includeItemTypes = listOf("Movie"),
            ).toMutableMap().apply {
                put("filters", "IsFavorite")
                put("sortBy", "SortName")
                put("sortOrder", "Ascending")
            },
            limits = limits,
        )) {
            is ApiResult.Ok -> {
                moviesSectionForensics("favorites", result)
                persistPage(result.value)
                ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            }
            is ApiResult.Err -> {
                moviesSectionForensics("favorites", result)
                ApiResult.Err(result.failure)
            }
        }
    }

    suspend fun moviesLibraryCollections(
        libraryId: String,
        limit: Int = JELLYFIN_PAGE_SIZE,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        return when (val result = client.items(
            userId = session.userId,
            token = token,
            query = browseQuery(
                parentId = libraryId,
                startIndex = 0,
                limit = limit,
                recursive = false,
                includeItemTypes = listOf("BoxSet"),
            ).toMutableMap().apply {
                put("sortBy", "SortName")
                put("sortOrder", "Ascending")
            },
            limits = limits,
        )) {
            is ApiResult.Ok -> {
                moviesSectionForensics("collections", result)
                persistPage(result.value)
                ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            }
            is ApiResult.Err -> {
                moviesSectionForensics("collections", result)
                ApiResult.Err(result.failure)
            }
        }
    }

    suspend fun moviesLibraryCollectionItems(
        collectionNativeId: String,
        limit: Int = JELLYFIN_PAGE_SIZE,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        return when (val result = client.items(
            userId = session.userId,
            token = token,
            query = browseQuery(
                parentId = collectionNativeId,
                startIndex = 0,
                limit = limit,
                recursive = true,
                includeItemTypes = listOf("Movie"),
            ).toMutableMap().apply {
                put("sortBy", "SortName")
                put("sortOrder", "Ascending")
            },
            limits = limits,
        )) {
            is ApiResult.Ok -> {
                moviesSectionForensics("collection-items", result)
                persistPage(result.value)
                ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            }
            is ApiResult.Err -> {
                moviesSectionForensics("collection-items", result)
                ApiResult.Err(result.failure)
            }
        }
    }

    suspend fun moviesLibraryGenres(
        libraryId: String,
        limit: Int = 200,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        return when (val result = client.genres(
            userId = session.userId,
            token = token,
            parentId = libraryId,
            limit = limit,
            limits = limits,
        )) {
            is ApiResult.Ok -> {
                moviesSectionForensics("genres", result)
                ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            }
            is ApiResult.Err -> {
                moviesSectionForensics("genres", result)
                ApiResult.Err(result.failure)
            }
        }
    }

    suspend fun moviesLibraryGenreMovies(
        libraryId: String,
        genreName: String,
        limit: Int = JELLYFIN_PAGE_SIZE,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        return when (val result = client.items(
            userId = session.userId,
            token = token,
            query = browseQuery(
                parentId = libraryId,
                startIndex = 0,
                limit = limit,
                recursive = true,
                includeItemTypes = listOf("Movie"),
            ).toMutableMap().apply {
                put("genres", genreName)
                put("sortBy", "SortName")
                put("sortOrder", "Ascending")
            },
            limits = limits,
        )) {
            is ApiResult.Ok -> {
                moviesSectionForensics("genre-movies", result)
                persistPage(result.value)
                ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            }
            is ApiResult.Err -> {
                moviesSectionForensics("genre-movies", result)
                ApiResult.Err(result.failure)
            }
        }
    }

    private fun showsSectionForensics(section: String, result: ApiResult<*>) {
        when (result) {
            is ApiResult.Ok -> JellyfinForensicsLog.event(
                "shows-section",
                "section=$section status=${result.status} items=${(result.value as? JellyfinRowPage)?.items?.size ?: 0}",
            )
            is ApiResult.Err -> JellyfinForensicsLog.event(
                "shows-section",
                "section=$section error=${result.failure.kind.name} status=${result.failure.status ?: "-"} label=${result.failure.message}",
            )
        }
    }

    suspend fun showsLibrarySeries(
        libraryId: String,
        limit: Int = JELLYFIN_PAGE_SIZE,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        return when (val result = client.items(
            userId = session.userId,
            token = token,
            query = browseQuery(
                parentId = libraryId,
                startIndex = 0,
                limit = limit,
                recursive = true,
                includeItemTypes = listOf("Series"),
            ).toMutableMap().apply {
                put("sortBy", "SortName")
                put("sortOrder", "Ascending")
            },
            limits = limits,
        )) {
            is ApiResult.Ok -> {
                showsSectionForensics("shows", result)
                persistPage(result.value)
                ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            }
            is ApiResult.Err -> {
                showsSectionForensics("shows", result)
                ApiResult.Err(result.failure)
            }
        }
    }

    suspend fun showsLibrarySuggestions(
        libraryId: String,
        limit: Int = JELLYFIN_TAB_ROW_LIMIT,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        return when (val result = client.suggestionsSeries(
            userId = session.userId,
            token = token,
            parentId = libraryId,
            limit = limit,
            limits = limits,
        )) {
            is ApiResult.Ok -> {
                showsSectionForensics("suggestions", result)
                persistPage(result.value)
                ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            }
            is ApiResult.Err -> {
                showsSectionForensics("suggestions", result)
                ApiResult.Err(result.failure)
            }
        }
    }

    suspend fun showsLibraryUpcoming(
        libraryId: String,
        limit: Int = JELLYFIN_TAB_ROW_LIMIT,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        return when (val result = client.showsUpcoming(
            userId = session.userId,
            token = token,
            parentId = libraryId,
            limit = limit,
            limits = limits,
        )) {
            is ApiResult.Ok -> {
                showsSectionForensics("upcoming", result)
                persistPage(result.value)
                ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            }
            is ApiResult.Err -> {
                showsSectionForensics("upcoming", result)
                ApiResult.Err(result.failure)
            }
        }
    }

    suspend fun showsLibraryGenres(
        libraryId: String,
        limit: Int = 200,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        return when (val result = client.genres(
            userId = session.userId,
            token = token,
            parentId = libraryId,
            limit = limit,
            limits = limits,
        )) {
            is ApiResult.Ok -> {
                showsSectionForensics("genres", result)
                ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            }
            is ApiResult.Err -> {
                showsSectionForensics("genres", result)
                ApiResult.Err(result.failure)
            }
        }
    }

    suspend fun showsLibraryNetworks(
        libraryId: String,
        limit: Int = 200,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        return when (val result = client.studios(
            userId = session.userId,
            token = token,
            parentId = libraryId,
            limit = limit,
            limits = limits,
        )) {
            is ApiResult.Ok -> {
                showsSectionForensics("networks", result)
                ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            }
            is ApiResult.Err -> {
                showsSectionForensics("networks", result)
                ApiResult.Err(result.failure)
            }
        }
    }

    suspend fun showsLibraryEpisodes(
        libraryId: String,
        limit: Int = JELLYFIN_PAGE_SIZE,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        return when (val result = client.items(
            userId = session.userId,
            token = token,
            query = browseQuery(
                parentId = libraryId,
                startIndex = 0,
                limit = limit,
                recursive = true,
                includeItemTypes = listOf("Episode"),
            ),
            limits = limits,
        )) {
            is ApiResult.Ok -> {
                showsSectionForensics("episodes", result)
                persistPage(result.value)
                ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            }
            is ApiResult.Err -> {
                showsSectionForensics("episodes", result)
                ApiResult.Err(result.failure)
            }
        }
    }

    suspend fun showsLibraryFilteredSeries(
        libraryId: String,
        genreName: String? = null,
        studioName: String? = null,
        limit: Int = JELLYFIN_PAGE_SIZE,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        val query = browseQuery(
            parentId = libraryId,
            startIndex = 0,
            limit = limit,
            recursive = true,
            includeItemTypes = listOf("Series"),
        ).toMutableMap().apply {
            if (genreName != null) put("genres", genreName)
            if (studioName != null) put("studios", studioName)
        }
        return when (val result = client.items(
            userId = session.userId,
            token = token,
            query = query,
            limits = limits,
        )) {
            is ApiResult.Ok -> {
                showsSectionForensics(if (genreName != null) "genre-series" else "network-series", result)
                persistPage(result.value)
                ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            }
            is ApiResult.Err -> {
                showsSectionForensics(if (genreName != null) "genre-series" else "network-series", result)
                ApiResult.Err(result.failure)
            }
        }
    }

    private fun <T> noSession(): ApiResult.Err<T> = ApiResult.Err(
        JellyfinFailure(FailureKind.UNAUTHORIZED, null, "no session", retryable = false),
    )

    private fun <T> noToken(): ApiResult.Err<T> = ApiResult.Err(
        JellyfinFailure(FailureKind.UNAUTHORIZED, null, "stored token missing", retryable = false),
    )

    private fun dev.infusion.source.jellyfin.wire.ItemsPage.toRowPage(

        resolveCanonicalIdentity: Boolean = false,
    ): JellyfinRowPage {

        val seen = HashSet<String>()
        val rowItems = mutableListOf<JellyfinRowItem>()
        for (item in items) {
            val nativeId = item.id ?: continue

            val occurrence = item.playlistItemId?.takeIf { it.isNotBlank() }
            val rowKey = if (occurrence != null) "$nativeId#$occurrence" else nativeId
            if (!seen.add(rowKey)) continue
            val kind = kindOf(item.type ?: "Unknown")
            rowItems += JellyfinRowItem(
                nativeItemId = nativeId,
                titleId = mapper.titleIdFor(nativeId),
                displayTitle = item.name ?: "(untitled)",
                kind = kind,
                year = item.productionYear,
                isFolder = item.isFolder == true,
                collectionType = item.collectionType,
                childCount = item.childCount,
                seriesTitleId = if (kind == TitleKind.EPISODE) {
                    item.seriesId?.let(mapper::titleIdFor)
                } else {
                    null
                },
                seasonNumber = if (kind == TitleKind.EPISODE) item.parentIndexNumber?.takeIf { it > 0 } else null,
                episodeNumber = if (kind == TitleKind.EPISODE) item.indexNumber?.takeIf { it > 0 } else null,
                playedPercentage = item.userData?.playedPercentage,
                resumePositionTicks = item.userData?.playbackPositionTicks,
                runtimeTicks = item.runTimeTicks,
                seriesName = item.seriesName,

                airDate = if (kind == TitleKind.EPISODE) premieredOn(item.premiereDate) else null,
                lastPlayedDate = item.userData?.lastPlayedDate,

                canonicalTitleId = if (resolveCanonicalIdentity) canonicalTitleIdFor(nativeId) else null,
                canonicalSeriesTitleId = if (resolveCanonicalIdentity && kind == TitleKind.EPISODE) {
                    item.seriesId?.let(::canonicalTitleIdFor)
                } else {
                    null
                },

                albumName = item.album,
                albumArtistName = item.albumArtist,
                parentNativeId = item.parentItemId,

                nativeType = item.type,
                albumArtistId = item.albumArtistId,
                imageTypes = item.imageTypes,
                playlistItemId = item.playlistItemId,
                unavailable = item.unavailable,
            )
        }
        return JellyfinRowPage(
            items = rowItems,
            totalRecordCount = totalRecordCount,
        )
    }

    private fun premieredOn(raw: String?): LocalDate? {
        val value = raw?.trim().orEmpty()
        if (value.length < DATE_PREFIX_LENGTH) return null
        return try {
            LocalDate.parse(value.substring(0, DATE_PREFIX_LENGTH))
        } catch (_: java.time.format.DateTimeParseException) {
            null
        }
    }

    override suspend fun browse(parentNativeId: String?, startIndex: Int, limit: Int): CatalogPage? {
        val session = sessionProvider() ?: return null
        return when (val result = browse(session, parentNativeId, startIndex, limit)) {
            is ApiResult.Ok -> {

                persistPage(result.value)
                result.value.toCatalogPage(startIndex, limit)
            }
            is ApiResult.Err -> null
        }
    }

    override suspend fun search(searchTerm: String, startIndex: Int, limit: Int): CatalogPage? {
        val session = sessionProvider() ?: return null
        return when (val result = search(session, searchTerm, startIndex, limit)) {
            is ApiResult.Ok -> result.value.toCatalogPage(startIndex, limit)
            is ApiResult.Err -> null
        }
    }

    override suspend fun children(parentNativeId: String, startIndex: Int, limit: Int): CatalogPage? {
        val session = sessionProvider() ?: return null
        val key = HierarchyRequestKey(
            userId = session.userId,
            parentNativeId = parentNativeId,
            startIndex = startIndex,
            limit = limit,
        )

        reusedHierarchyPage(key)?.let { reused ->
            JellyfinForensicsLog.event(
                "hierarchy-reuse",
                "parent=$parentNativeId start=$startIndex limit=$limit shared=false",
            )
            return reused
        }
        val generation = hierarchyGeneration
        val claim = claimHierarchyRequest(key)
        if (!claim.owner) {

            JellyfinForensicsLog.event(
                "hierarchy-reuse",
                "parent=$parentNativeId start=$startIndex limit=$limit shared=true",
            )
            return claim.deferred.await()
        }
        var outcome: CatalogPage? = null
        try {

            JellyfinForensicsLog.event(
                "hierarchy-parent",
                "parent=$parentNativeId start=$startIndex limit=$limit",
            )
            val page = childrenPage(session, parentNativeId, startIndex, limit)
            if (page != null) {
                JellyfinForensicsLog.event(
                    "hierarchy-result",
                    "parent=$parentNativeId items=${page.items.size} total=${page.totalRecordCount} " +
                        "firstTypes=${page.items.take(3).map { it.type ?: "?" }.joinToString("|")}",
                )
                persistPage(page)
                outcome = page.toCatalogPage(startIndex, limit)
                publishHierarchyReuse(key, generation, outcome)
            }
            return outcome
        } finally {

            releaseHierarchyRequest(key, claim.deferred, outcome)
        }
    }

    private fun reusedHierarchyPage(key: HierarchyRequestKey): CatalogPage? =
        synchronized(hierarchyLock) {
            val entry = hierarchyReuse[key] ?: return@synchronized null
            if (clock() - entry.storedAtMs >= HIERARCHY_REUSE_TTL_MS) {
                hierarchyReuse.remove(key)
                return@synchronized null
            }
            entry.page
        }

    private fun claimHierarchyRequest(key: HierarchyRequestKey): HierarchyClaim =
        synchronized(hierarchyLock) {
            val running = hierarchyInflight[key]
            if (running != null) {
                HierarchyClaim(running, owner = false)
            } else {
                val created = CompletableDeferred<CatalogPage?>()
                hierarchyInflight[key] = created
                HierarchyClaim(created, owner = true)
            }
        }

    private fun releaseHierarchyRequest(
        key: HierarchyRequestKey,
        deferred: CompletableDeferred<CatalogPage?>,
        outcome: CatalogPage?,
    ) {
        synchronized(hierarchyLock) {

            if (hierarchyInflight[key] === deferred) hierarchyInflight.remove(key)
        }
        deferred.complete(outcome)
    }

    private fun publishHierarchyReuse(key: HierarchyRequestKey, generation: Long, page: CatalogPage) {
        synchronized(hierarchyLock) {
            if (generation != hierarchyGeneration) return@synchronized
            hierarchyReuse[key] = HierarchyReuseEntry(page, clock())
            while (hierarchyReuse.size > HIERARCHY_REUSE_MAX_ENTRIES) {
                val oldest = hierarchyReuse.entries.iterator()
                if (!oldest.hasNext()) break
                oldest.next()
                oldest.remove()
            }
        }
    }

    private fun invalidateHierarchyReuse() {
        synchronized(hierarchyLock) {
            hierarchyGeneration++
            hierarchyReuse.clear()
            hierarchyInflight.clear()
        }
    }

    private suspend fun childrenPage(
        session: JellyfinSession,
        parentNativeId: String,
        startIndex: Int,
        limit: Int,
    ): dev.infusion.source.jellyfin.wire.ItemsPage? {

        val seasons = seasons(session, parentNativeId)
        when (seasons) {
            is ApiResult.Ok -> {
                val seasonsPage = seasons.value
                JellyfinForensicsLog.event(
                    "hierarchy-probe",
                    "probe=Shows/{id}/Seasons parent=$parentNativeId status=${seasons.status} " +
                        "items=${seasonsPage.items.size} total=${seasonsPage.totalRecordCount}",
                )
                if (seasonsPage.items.isNotEmpty() || seasonsPage.totalRecordCount != 0) {

                    return seasonsPage
                }

            }
            is ApiResult.Err -> {
                JellyfinForensicsLog.event(
                    "hierarchy-probe",
                    "probe=Shows/{id}/Seasons parent=$parentNativeId " +
                        "error=${seasons.failure.kind.name} status=${seasons.failure.status ?: "-"}",
                )
                if (seasons.failure.kind != FailureKind.NOT_FOUND) {

                    return null
                }

            }
        }

        val parentRow = store.serverItemByNativeId(source.sourceId, parentNativeId)
        val seriesNative = parentRow?.nativeParentId
        if (parentRow?.nativeType == "Season" && seriesNative != null && seriesNative != parentNativeId) {
            val scoped = episodes(session, seriesNative, parentNativeId)
            when (scoped) {
                is ApiResult.Ok -> {
                    JellyfinForensicsLog.event(
                        "hierarchy-probe",
                        "probe=Shows/{series}/Episodes?seasonId= parent=$parentNativeId series=$seriesNative " +
                            "status=${scoped.status} items=${scoped.value.items.size} total=${scoped.value.totalRecordCount}",
                    )
                    if (scoped.value.items.isNotEmpty() || scoped.value.totalRecordCount != 0) {
                        return scoped.value
                    }
                }
                is ApiResult.Err -> JellyfinForensicsLog.event(
                    "hierarchy-probe",
                    "probe=Shows/{series}/Episodes?seasonId= parent=$parentNativeId series=$seriesNative " +
                        "error=${scoped.failure.kind.name} status=${scoped.failure.status ?: "-"}",
                )
            }
        }

        val kids = browse(session, parentNativeId, startIndex, limit, recursive = false)
        when (kids) {
            is ApiResult.Ok -> {
                JellyfinForensicsLog.event(
                    "hierarchy-probe",
                    "probe=Items?parentId=&recursive=false parent=$parentNativeId status=${kids.status} " +
                        "items=${kids.value.items.size} total=${kids.value.totalRecordCount}",
                )
                return kids.value
            }
            is ApiResult.Err -> {
                JellyfinForensicsLog.event(
                    "hierarchy-probe",
                    "probe=Items?parentId=&recursive=false parent=$parentNativeId " +
                        "error=${kids.failure.kind.name} status=${kids.failure.status ?: "-"}",
                )
                return null
            }
        }
    }

    private suspend fun persistPage(
        page: dev.infusion.source.jellyfin.wire.ItemsPage,

        stage: String = "page",
    ) {
        val candidates = page.items.filter { item ->
            item.id != null && when (kindOf(item.type ?: "Unknown")) {
                TitleKind.MOVIE, TitleKind.SERIES, TitleKind.SEASON, TitleKind.EPISODE -> true
                else -> false
            }
        }
        if (candidates.isEmpty()) return
        val titles = candidates.map(mapper::toTitle)
        val txStart = System.currentTimeMillis()
        store.upsertAll(
            items = candidates.map { mapper.toServerItem(it) },
            versions = emptyList(),
            titles = titles,
            availability = candidates.map(mapper::toAvailability),
        )
        val txEnd = System.currentTimeMillis()
        recordRatingsEvidence(candidates)

        convergence?.let { convergeMirroredIdentities(titles, it) }
        val sideEnd = System.currentTimeMillis()
        mirrorTiming(stage, candidates.size, txMs = txEnd - txStart, sideMs = sideEnd - txEnd)
    }

    private fun mirrorTiming(
        stage: String,
        items: Int,
        readMs: Long = -1,
        txMs: Long = -1,
        sideMs: Long = -1,
        totalMs: Long = -1,
    ) {
        JellyfinForensicsLog.event(
            "mirror",
            "stage=$stage items=$items readMs=$readMs txMs=$txMs sideMs=$sideMs totalMs=$totalMs",
        )
    }

    suspend fun refreshNativeItems(nativeIds: List<String>): Int {
        val wanted = nativeIds.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return 0

        invalidateHierarchyReuse()
        val session = sessionProvider() ?: return 0
        val token = tokenFor(session) ?: return 0
        var mirrored = 0
        for (batch in wanted.chunked(NATIVE_ITEM_BATCH)) {
            val readStart = System.currentTimeMillis()
            val page = client.items(
                userId = session.userId,
                token = token,
                query = mapOf(
                    "ids" to batch.joinToString(","),
                    "recursive" to "true",
                    "includeItemTypes" to "Movie,Series,Season,Episode",
                    "fields" to JELLYFIN_ITEM_FIELDS,
                ),
                limits = limits,
            )
            val readMs = System.currentTimeMillis() - readStart
            if (page is ApiResult.Ok) mirrored += mirrorItems(page.value.items, stage = "native", readMs = readMs)
        }
        return mirrored
    }

    suspend fun mirrorRecentItems(limit: Int = RECENT_ITEM_LIMIT): Int {

        invalidateHierarchyReuse()
        val session = sessionProvider() ?: return 0
        val token = tokenFor(session) ?: return 0
        val readStart = System.currentTimeMillis()
        val page = client.latestItems(
            userId = session.userId,
            token = token,
            query = mapOf(
                "limit" to limit.toString(),
                "includeItemTypes" to "Movie,Series,Season,Episode",
                "fields" to JELLYFIN_ITEM_FIELDS,
            ),
            limits = limits,
        )
        val readMs = System.currentTimeMillis() - readStart
        return if (page is ApiResult.Ok) mirrorItems(page.value.items, stage = "recent", readMs = readMs) else 0
    }

    suspend fun removeNativeItems(nativeIds: List<String>): Int {

        if (nativeIds.any { it.isNotBlank() }) invalidateHierarchyReuse()
        return store.deleteNativeItems(
            sourceId = source.sourceId,
            nativeIds = nativeIds.filter { it.isNotBlank() }.toSet(),
        )
    }

    private suspend fun mirrorItems(
        wireItems: List<ItemSummary>,

        stage: String = "recent",

        readMs: Long = -1,
    ): Int {
        val candidates = wireItems.filter { item ->
            item.id != null && when (kindOf(item.type ?: "Unknown")) {
                TitleKind.MOVIE, TitleKind.SERIES, TitleKind.SEASON, TitleKind.EPISODE -> true
                else -> false
            }
        }
        if (candidates.isEmpty()) return 0
        val titles = candidates.map(mapper::toTitle)
        val startedAt = System.currentTimeMillis()
        val txStart = startedAt
        store.upsertAll(
            items = candidates.map { mapper.toServerItem(it) },
            versions = emptyList(),
            titles = titles,
            availability = candidates.map(mapper::toAvailability),
        )
        val txEnd = System.currentTimeMillis()
        recordRatingsEvidence(candidates)

        convergence?.let { convergeMirroredIdentities(titles, it) }
        val sideEnd = System.currentTimeMillis()
        mirrorTiming(
            stage = stage,
            items = candidates.size,
            readMs = readMs,
            txMs = txEnd - txStart,
            sideMs = sideEnd - txEnd,
            totalMs = sideEnd - startedAt + if (readMs > 0) readMs else 0,
        )
        return candidates.size
    }

    private suspend fun recordRatingsEvidence(items: List<ItemSummary>) {
        val store = ratingsEvidence ?: return
        for (item in items) {
            val key = mapper.ratingsEvidenceKey(item) ?: continue
            val evidence = mapper.ratingsEvidence(item)

            runCatching { store.record(key, evidence) }
        }
    }

    override suspend fun image(nativeItemId: String, type: String): CatalogImage? =
        image(nativeItemId, type, dev.infusion.core.model.ArtworkRendition.Unbounded)

    override suspend fun image(
        nativeItemId: String,
        type: String,
        rendition: dev.infusion.core.model.ArtworkRendition,
    ): CatalogImage? {
        val session = sessionProvider() ?: return null
        return when (val result = image(session, nativeItemId, type, rendition)) {
            is ApiResult.Ok -> CatalogImage(
                contentType = result.value.contentType,
                bytes = result.value.bytes,
            )
            is ApiResult.Err -> null
        }
    }

    override suspend fun nativeItemIdFor(titleId: TitleId): String? =
        store.serverItemsByTitle(titleId).firstOrNull()?.nativeId

    private fun canonicalTitleIdFor(nativeId: String): TitleId? =
        store.serverItemByNativeId(source.sourceId, nativeId)?.titleId

    private fun dev.infusion.source.jellyfin.wire.ItemsPage.toCatalogPage(startIndex: Int, limit: Int): CatalogPage =
        CatalogPage(
            entries = items.map { item ->
                val titleId = item.id?.let(mapper::titleIdFor)
                CatalogEntry(
                    nativeItemId = item.id ?: "",
                    nativeParentId = item.parentId ?: item.seriesId ?: item.seasonId,
                    titleId = titleId,
                    displayTitle = item.name ?: "(untitled)",
                    kind = kindOf(item.type ?: "Unknown"),
                    year = item.productionYear,
                    isFolder = item.isFolder == true,
                    childCount = item.childCount,

                    seriesTitleId = when (kindOf(item.type ?: "Unknown")) {
                        TitleKind.EPISODE, TitleKind.SEASON -> item.seriesId?.let(mapper::titleIdFor)
                        else -> null
                    },
                    seasonNumber = when (kindOf(item.type ?: "Unknown")) {
                        TitleKind.EPISODE -> item.parentIndexNumber?.takeIf { it > 0 }
                        TitleKind.SEASON -> item.indexNumber?.takeIf { it > 0 }
                        else -> null
                    },
                    episodeNumber = if (kindOf(item.type ?: "Unknown") == TitleKind.EPISODE) item.indexNumber?.takeIf { it > 0 } else null,

                    serverResumeMs = item.userData?.playbackPositionTicks?.let { it / JELLYFIN_TICKS_PER_MILLISECOND },

                    serverResumeObservedAt = parseServerInstant(item.userData?.lastPlayedDate),
                )
            },
            totalCount = totalRecordCount ?: items.size,
            startIndex = startIndex,
            limit = limit,
        )

    suspend fun browse(
        session: JellyfinSession,
        parentId: String? = null,
        startIndex: Int,
        limit: Int,
        recursive: Boolean = true,
        includeItemTypes: List<String>? = null,
    ): ApiResult<dev.infusion.source.jellyfin.wire.ItemsPage> {
        val token = tokenFor(session) ?: return ApiResult.Err(
            JellyfinFailure(FailureKind.UNAUTHORIZED, null, "stored token missing", retryable = false),
        )
        return client.items(
            userId = session.userId,
            token = token,
            query = browseQuery(parentId, startIndex, limit, recursive, includeItemTypes),
            limits = limits,
        )
    }

    suspend fun search(
        session: JellyfinSession,
        searchTerm: String,
        startIndex: Int = 0,
        limit: Int = JELLYFIN_PAGE_SIZE,
        includeItemTypes: List<String>? = null,
    ): ApiResult<dev.infusion.source.jellyfin.wire.ItemsPage> {
        val token = tokenFor(session) ?: return ApiResult.Err(
            JellyfinFailure(FailureKind.UNAUTHORIZED, null, "stored token missing", retryable = false),
        )
        val query = browseQuery(parentId = null, startIndex = startIndex, limit = limit, recursive = true, includeItemTypes = includeItemTypes)
            .toMutableMap()
            .apply { put("searchTerm", searchTerm) }
        return client.items(session.userId, token, query, limits)
    }

    suspend fun seasons(
        session: JellyfinSession,
        seriesId: String,
    ): ApiResult<dev.infusion.source.jellyfin.wire.ItemsPage> {
        val token = tokenFor(session) ?: return ApiResult.Err(
            JellyfinFailure(FailureKind.UNAUTHORIZED, null, "stored token missing", retryable = false),
        )
        return client.seasons(seriesId, session.userId, token, limits)
    }

    suspend fun episodes(
        session: JellyfinSession,
        seriesId: String,
        seasonId: String? = null,
    ): ApiResult<dev.infusion.source.jellyfin.wire.ItemsPage> {
        val token = tokenFor(session) ?: return ApiResult.Err(
            JellyfinFailure(FailureKind.UNAUTHORIZED, null, "stored token missing", retryable = false),
        )
        return client.episodes(seriesId, session.userId, token, seasonId, limits)
    }

    suspend fun image(
        session: JellyfinSession,
        itemId: String,
        type: String = "Primary",
        rendition: dev.infusion.core.model.ArtworkRendition = dev.infusion.core.model.ArtworkRendition.Unbounded,
        imageLimits: NetworkLimits = JELLYFIN_IMAGE_LIMITS,
    ): ApiResult<dev.infusion.source.jellyfin.client.ImageFetch> {
        val token = tokenFor(session) ?: return ApiResult.Err(
            JellyfinFailure(FailureKind.UNAUTHORIZED, null, "stored token missing", retryable = false),
        )
        return client.image(itemId, type, token, rendition, imageLimits)
    }

    suspend fun userImage(
        session: JellyfinSession,
        userId: String,
        tag: String?,
        imageLimits: NetworkLimits = JELLYFIN_IMAGE_LIMITS,
    ): ApiResult<dev.infusion.source.jellyfin.client.ImageFetch> {
        val token = tokenFor(session) ?: return ApiResult.Err(
            JellyfinFailure(FailureKind.UNAUTHORIZED, null, "stored token missing", retryable = false),
        )
        return client.userPrimaryImage(userId, tag, token, imageLimits)
    }

    private fun tokenFor(session: JellyfinSession): String? = authenticator.token(session)

    suspend fun libraryItemPage(
        parentId: String?,
        recursive: Boolean,
        includeItemTypes: List<String>,
        startIndex: Int,
        limit: Int,
        sortBy: List<String>,
        sortOrder: String,
        fields: String,
        parameters: Map<String, String> = emptyMap(),
        enableTotalRecordCount: Boolean = false,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        val boundedLimit = limit.coerceIn(1, JELLYFIN_PAGE_SIZE)
        val query = buildMap {
            if (parentId != null) put("parentId", parentId)
            put("startIndex", startIndex.coerceAtLeast(0).toString())
            put("limit", boundedLimit.toString())
            put("recursive", recursive.toString())
            if (includeItemTypes.isNotEmpty()) put("includeItemTypes", includeItemTypes.joinToString(","))
            if (sortBy.isNotEmpty()) put("sortBy", sortBy.joinToString(","))
            put("sortOrder", sortOrder)
            if (fields.isNotBlank()) put("fields", fields)
            put("enableTotalRecordCount", enableTotalRecordCount.toString())
            putAll(parameters)
        }
        return when (val result = client.items(session.userId, token, query, limits)) {
            is ApiResult.Ok -> {
                persistPage(result.value)
                ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            }
            is ApiResult.Err -> ApiResult.Err(result.failure)
        }
    }

    suspend fun libraryGenreCategories(
        parentId: String,
        includeItemTypes: List<String>,
        startIndex: Int,
        limit: Int,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        val boundedLimit = limit.coerceIn(1, JELLYFIN_PAGE_SIZE)
        return when (
            val result = client.genres(
                userId = session.userId,
                token = token,
                parentId = parentId,
                limit = boundedLimit,
                limits = limits,
                startIndex = startIndex.coerceAtLeast(0),
                includeItemTypes = includeItemTypes.takeIf { it.isNotEmpty() }?.joinToString(","),
                sortBy = listOf("SortName"),
                sortOrder = "Ascending",
            )
        ) {
            is ApiResult.Ok -> ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            is ApiResult.Err -> ApiResult.Err(result.failure)
        }
    }

    suspend fun libraryStudioCategories(
        parentId: String,
        includeItemTypes: List<String>,
        startIndex: Int,
        limit: Int,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        val boundedLimit = limit.coerceIn(1, JELLYFIN_PAGE_SIZE)
        return when (
            val result = client.studios(
                userId = session.userId,
                token = token,
                parentId = parentId,
                includeItemTypes = includeItemTypes.takeIf { it.isNotEmpty() }?.joinToString(","),
                startIndex = startIndex.coerceAtLeast(0),
                limit = boundedLimit,
                sortBy = "SortName",
                sortOrder = "Ascending",
                fields = "DateCreated,PrimaryImageAspectRatio",
                limits = limits,
            )
        ) {
            is ApiResult.Ok -> ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            is ApiResult.Err -> ApiResult.Err(result.failure)
        }
    }

    suspend fun libraryArtistCategories(
        parentId: String,
        albumArtists: Boolean,
        startIndex: Int,
        limit: Int,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        val boundedLimit = limit.coerceIn(1, JELLYFIN_PAGE_SIZE)
        return when (
            val result = client.artists(
                userId = session.userId,
                token = token,
                parentId = parentId,
                albumArtists = albumArtists,
                startIndex = startIndex.coerceAtLeast(0),
                limit = boundedLimit,
                sortBy = "SortName",
                sortOrder = "Ascending",
                fields = "PrimaryImageAspectRatio,SortName",
                limits = limits,
            )
        ) {
            is ApiResult.Ok -> ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            is ApiResult.Err -> ApiResult.Err(result.failure)
        }
    }

    suspend fun libraryPlaylistItems(
        playlistId: String,
        startIndex: Int,
        limit: Int,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        val boundedLimit = limit.coerceIn(1, JELLYFIN_PAGE_SIZE)
        return when (
            val result = client.playlistItems(
                userId = session.userId,
                token = token,
                playlistId = playlistId,
                startIndex = startIndex.coerceAtLeast(0),
                limit = boundedLimit,
                fields = "ParentId,Album,AlbumArtist,Artists,RunTimeTicks,SortName",
                limits = limits,
            )
        ) {
            is ApiResult.Ok -> ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            is ApiResult.Err -> ApiResult.Err(result.failure)
        }
    }

    suspend fun libraryGenreArtworkItems(
        parentId: String,
        includeItemTypes: List<String>,
        genreId: String,
        limit: Int,
        imageType: String = dev.infusion.core.model.ArtworkLocator.CATALOG_BACKDROP_TYPE,
    ): ApiResult<List<String>> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        val boundedLimit = limit.coerceIn(1, JELLYFIN_PAGE_SIZE)
        val query = buildMap {
            put("parentId", parentId)
            put("recursive", "true")
            put("startIndex", "0")
            put("limit", boundedLimit.toString())
            if (includeItemTypes.isNotEmpty()) put("includeItemTypes", includeItemTypes.joinToString(","))
            put("genreIds", genreId)
            put("sortBy", "SortName,ProductionYear")
            put("sortOrder", "Ascending")
            put("enableImageTypes", imageType)
            put("imageTypeLimit", "1")
            put("enableTotalRecordCount", "false")
            put("fields", "PrimaryImageAspectRatio")
        }
        return when (val result = client.items(session.userId, token, query, limits)) {
            is ApiResult.Ok -> ApiResult.Ok(result.value.items.mapNotNull { it.id }, result.status)
            is ApiResult.Err -> ApiResult.Err(result.failure)
        }
    }

    suspend fun libraryFilterValues(
        parentId: String?,
        includeItemTypes: List<String>,
    ): ApiResult<dev.infusion.source.jellyfin.wire.FilterValueSources> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        return client.filterValues(session.userId, token, parentId, includeItemTypes, limits)
    }

    suspend fun libraryResumeSection(parentId: String, includeItemTypes: List<String>, limit: Int = 8): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        val query = buildMap {
            put("parentId", parentId)
            put("recursive", "true")
            if (includeItemTypes.isNotEmpty()) put("includeItemTypes", includeItemTypes.joinToString(","))
            put("filters", "IsResumable")
            put("sortBy", "DatePlayed")
            put("sortOrder", "Descending")
            put("limit", limit.toString())
            put("fields", "PrimaryImageAspectRatio,MediaSourceCount")
            put("enableTotalRecordCount", "false")
        }
        return when (val result = client.items(session.userId, token, query, limits)) {
            is ApiResult.Ok -> ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            is ApiResult.Err -> ApiResult.Err(result.failure)
        }
    }

    suspend fun libraryLatestSection(parentId: String, includeItemTypes: List<String>, limit: Int = 18): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        val query = buildMap {
            put("parentId", parentId)
            if (includeItemTypes.isNotEmpty()) put("includeItemTypes", includeItemTypes.joinToString(","))
            put("limit", limit.toString())
            put("fields", "PrimaryImageAspectRatio,MediaSourceCount")
            put("enableTotalRecordCount", "false")
        }
        return when (val result = client.latestItems(session.userId, token, query, limits)) {
            is ApiResult.Ok -> ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            is ApiResult.Err -> ApiResult.Err(result.failure)
        }
    }

    suspend fun librarySection(
        parentId: String,
        includeItemTypes: List<String>,
        limit: Int,
        filters: String? = null,
        sortBy: String? = null,
        sortOrder: String? = null,
        isFavorite: Boolean = false,
        recursive: Boolean = true,

        fields: String = JELLYFIN_ITEM_FIELDS,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        val query = buildMap {
            put("parentId", parentId)
            put("recursive", recursive.toString())
            if (includeItemTypes.isNotEmpty()) put("includeItemTypes", includeItemTypes.joinToString(","))
            if (!filters.isNullOrBlank()) put("filters", filters)
            if (!sortBy.isNullOrBlank()) put("sortBy", sortBy)
            if (!sortOrder.isNullOrBlank()) put("sortOrder", sortOrder)
            if (isFavorite) put("isFavorite", "true")
            put("startIndex", "0")
            put("limit", limit.coerceIn(1, JELLYFIN_PAGE_SIZE).toString())
            put("fields", fields)
            put("enableTotalRecordCount", "false")
        }
        return when (val result = client.items(session.userId, token, query, limits)) {
            is ApiResult.Ok -> ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            is ApiResult.Err -> ApiResult.Err(result.failure)
        }
    }

    suspend fun libraryUpcomingSection(
        parentId: String,
        limit: Int = 60,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        return when (val result = client.showsUpcoming(session.userId, token, parentId, limit, limits)) {
            is ApiResult.Ok -> ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            is ApiResult.Err -> ApiResult.Err(result.failure)
        }
    }

    suspend fun libraryNextUpSection(
        parentId: String,
        limit: Int = 12,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        val query = buildMap {
            put("parentId", parentId)
            put("limit", limit.coerceIn(1, JELLYFIN_PAGE_SIZE).toString())
            put("fields", JELLYFIN_ITEM_FIELDS)
        }
        return when (val result = client.nextUp(session.userId, token, query, limits)) {
            is ApiResult.Ok -> ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            is ApiResult.Err -> ApiResult.Err(result.failure)
        }
    }

    suspend fun libraryRecommendationsSection(
        parentId: String,
        categoryLimit: Int = 6,
        itemLimit: Int = 8,
    ): ApiResult<JellyfinRowPage> {
        val session = sessionProvider() ?: return noSession()
        val token = tokenFor(session) ?: return noToken()
        return when (val result = client.moviesRecommendations(session.userId, token, parentId, categoryLimit, itemLimit, limits)) {
            is ApiResult.Ok -> ApiResult.Ok(result.value.toRowPage(resolveCanonicalIdentity = true), result.status)
            is ApiResult.Err -> ApiResult.Err(result.failure)
        }
    }

    private fun browseQuery(
        parentId: String?,
        startIndex: Int,
        limit: Int,
        recursive: Boolean,
        includeItemTypes: List<String>?,
    ): Map<String, String> = buildMap {
        if (parentId != null) put("parentId", parentId)
        put("startIndex", startIndex.toString())
        put("limit", limit.toString())
        put("recursive", recursive.toString())
        put("sortBy", "DateCreated")
        put("sortOrder", "Descending")
        put("fields", JELLYFIN_ITEM_FIELDS)
        if (!includeItemTypes.isNullOrEmpty()) put("includeItemTypes", includeItemTypes.joinToString(","))
    }

    private fun kindOf(nativeType: String): TitleKind = when (nativeType) {
        "Movie" -> TitleKind.MOVIE
        "Series" -> TitleKind.SERIES
        "Season" -> TitleKind.SEASON
        "Episode" -> TitleKind.EPISODE
        else -> TitleKind.CLIP
    }
}

sealed interface RefreshResult {
    data class Ok(val libraryCount: Int) : RefreshResult
    data class Failed(val failure: JellyfinFailure) : RefreshResult
}
