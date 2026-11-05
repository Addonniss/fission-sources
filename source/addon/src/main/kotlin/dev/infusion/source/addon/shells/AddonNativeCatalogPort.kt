package dev.infusion.source.addon.shells

import dev.infusion.core.domain.catalog.CatalogEntry
import dev.infusion.core.domain.catalog.CatalogImage
import dev.infusion.core.domain.catalog.CatalogPage
import dev.infusion.core.domain.catalog.CatalogPort
import dev.infusion.core.domain.catalog.HomeShelf
import dev.infusion.core.domain.catalog.TitleDetails
import dev.infusion.core.model.Availability
import dev.infusion.core.model.AvailabilityKind
import dev.infusion.core.model.Source
import dev.infusion.core.model.SourceId
import dev.infusion.core.model.Title
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.TypedExternalId
import dev.infusion.source.aggregation.AddonAvailabilityStore
import dev.infusion.source.aggregation.AddonRegistryPort
import dev.infusion.source.aggregation.AddonRuntimeDescriptor
import dev.infusion.source.aggregation.CanonicalTitleLookup
import dev.infusion.source.aggregation.match.CanonicalTitleMaterializer
import dev.infusion.source.aggregation.match.ExternalIdExtractor
import dev.infusion.source.aggregation.resource.AddonMetaRepository
import dev.infusion.source.aggregation.resource.AddonArtworkRepository
import dev.infusion.source.aggregation.resource.ProviderResourceState
import dev.infusion.source.aggregation.resource.StremioMetaQueryResult
import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.wire.StremioMeta
import dev.infusion.source.stremio.wire.StremioMetaResponse
import dev.infusion.source.stremio.wire.StremioVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

class AddonNativeCatalogPort(
    private val base: CatalogPort,
    private val lookup: CanonicalTitleLookup,
    private val availabilityStore: AddonAvailabilityStore,
    private val registry: AddonRegistryPort,
    private val metaRepository: AddonMetaRepository,
    private val viewer: dev.infusion.core.model.profile.Profile,
    private val primary: dev.infusion.core.model.profile.Profile,

    private val addonSources: () -> List<Source> = { emptyList() },

    private val artworkRepository: AddonArtworkRepository? = null,

    private val destinationAnchor: dev.infusion.core.domain.anchor.CanonicalDestinationAnchor? = null,

    private val canonicalHierarchy: dev.infusion.core.domain.metadata.CanonicalShowHierarchy? = null,
) : CatalogPort by base {

    private val videoCacheLock = Any()
    private val videoCache = mutableMapOf<String, List<StremioVideo>>()

    @Volatile
    private var lastHierarchyFailure: HierarchyFailure? = null

    fun lastHierarchyFailure(): HierarchyFailure? = lastHierarchyFailure

    override fun observeTitleDetails(titleId: TitleId): Flow<TitleDetails?> = flow {

        val anchored: Flow<Boolean> = destinationAnchor?.materialized(titleId)
            ?: kotlinx.coroutines.flow.MutableStateFlow(false)

        combine(base.observeTitleDetails(titleId), anchored) { baseDetails, _ -> baseDetails }
            .collect { baseDetails ->
                if (baseDetails != null) {
                    emit(baseDetails)
                } else {
                    emit(withContext(Dispatchers.IO) { addonNativeDetails(titleId) })
                }
            }
    }

    private fun addonNativeDetails(titleId: TitleId): TitleDetails? {
        val title = lookup.byId(titleId) ?: return null
        val rows = availabilityStore.byTitle(titleId)
        if (rows.isEmpty()) return null
        return TitleDetails(title = title, editions = emptyList(), availability = rows)
    }

    override suspend fun nativeItemIdFor(titleId: TitleId): String? {

        lastHierarchyFailure = null
        val baseNative = base.nativeItemIdFor(titleId)
        if (baseNative != null) {
            Stage6MetaLog.log("nativeItemIdFor title=$titleId base=present")
            return baseNative
        }

        val rows = availabilityStore.byTitle(titleId).filter { it.kind == AvailabilityKind.ADDON }
        if (rows.isEmpty()) {

            val canonical = canonicalSeriesAddress(titleId)
            Stage6MetaLog.log(
                "nativeItemIdFor title=$titleId base=null addonRows=0 -> ${canonical ?: "null"}",
            )
            return canonical
        }

        val runtimes = registry.runtimesFor(viewer, primary)
        val exposed = rows.mapNotNull { row ->
            runtimes.firstOrNull { it.sourceId == row.sourceId }?.let { runtime -> row to runtime }
        }
        if (exposed.isEmpty()) {

            canonicalSeriesAddress(titleId)?.let { canonical ->
                Stage6MetaLog.log(
                    "nativeItemIdFor title=$titleId base=null series addonRows=${rows.size} " +
                        "exposed=0 -> canonical $canonical",
                )
                return canonical
            }

            return when (lookup.byId(titleId)?.kind) {
                TitleKind.SERIES -> {
                    lastHierarchyFailure = HierarchyFailure(
                        reason = "ADDON_ID_UNRESOLVED",
                        retryable = true,
                        providerReasons = listOf("ADDON_ID_UNRESOLVED:retryable=true"),
                    )
                    Stage6MetaLog.log(
                        "nativeItemIdFor title=$titleId base=null series addonRows=${rows.size} " +
                            "exposed=0 -> FAIL_CLOSED (H2 fixed)",
                    )
                    null
                }
                else -> {
                    val nativeResourceId = rows.first().nativeResourceId.takeIf { it.isNotBlank() }
                    Stage6MetaLog.log(
                        "nativeItemIdFor title=$titleId base=null kind=${lookup.byId(titleId)?.kind} " +
                            "exposed=0 -> raw ${nativeResourceId ?: "BLANK"}",
                    )
                    nativeResourceId
                }
            }
        }

        val (owner, runtime) = exposed.firstOrNull { it.first.nativeResourceId.isNotBlank() }
            ?: run {
                Stage6MetaLog.log(
                    "nativeItemIdFor title=$titleId base=null addonRows=${rows.size} exposed=${exposed.size} " +
                        "blankNative -> null",
                )
                return null
            }
        val nativeResourceId = owner.nativeResourceId

        return when (lookup.byId(titleId)?.kind) {
            TitleKind.SERIES -> {
                Stage6MetaLog.log(
                    "nativeItemIdFor title=$titleId base=null series -> " +
                        "addon-native:${runtime.addonId}:series:$nativeResourceId",
                )
                "addon-native:${runtime.addonId}:series:$nativeResourceId"
            }
            else -> {
                Stage6MetaLog.log(
                    "nativeItemIdFor title=$titleId base=null kind=${lookup.byId(titleId)?.kind} " +
                        "-> raw $nativeResourceId",
                )
                nativeResourceId
            }
        }
    }

    override fun sources(): List<Source> {
        val baseSources = base.sources()

        return (baseSources + addonSources()).distinctBy { it.sourceId }
    }

    override suspend fun children(parentNativeId: String, startIndex: Int, limit: Int): CatalogPage? {

        val canonicalKey = parseCanonicalHierarchyKey(parentNativeId)
        if (canonicalKey != null) {
            return withContext(Dispatchers.IO) {
                val rows = when (canonicalKey) {
                    is CanonicalHierarchyKey.Series -> canonicalSeasonRows(canonicalKey.titleId)
                    is CanonicalHierarchyKey.Season ->
                        canonicalEpisodeRows(canonicalKey.titleId, canonicalKey.seasonNumber)
                }

                CatalogPage(
                    entries = rows.drop(startIndex).take(limit),
                    totalCount = rows.size,
                    startIndex = startIndex,
                    limit = limit,
                )
            }
        }

        val parsed = parseSyntheticKey(parentNativeId)
        if (parsed != null) {
            return withContext(Dispatchers.IO) {
                when (parsed) {
                    is SyntheticKey.Series -> {
                        when (val videos = resolveHierarchyVideos(parsed)) {
                            is HierarchyVideos.Loaded -> {
                                val rows = projectSeasons(parsed.runtime, parsed.seriesNativeId, videos.videos)
                                CatalogPage(
                                    entries = rows.drop(startIndex).take(limit),
                                    totalCount = rows.size,
                                    startIndex = startIndex,
                                    limit = limit,
                                )
                            }

                            is HierarchyVideos.Failed -> null
                        }
                    }
                    is SyntheticKey.Season -> {
                        when (val videos = resolveHierarchyVideos(parsed)) {
                            is HierarchyVideos.Loaded -> {
                                val rows = projectEpisodes(parsed.runtime, parsed.seriesNativeId, videos.videos, parsed.seasonNumber)
                                CatalogPage(
                                    entries = rows.drop(startIndex).take(limit),
                                    totalCount = rows.size,
                                    startIndex = startIndex,
                                    limit = limit,
                                )
                            }
                            is HierarchyVideos.Failed -> null
                        }
                    }
                }
            }
        }

        Stage6MetaLog.log("children key=$parentNativeId non-synthetic -> base")
        return base.children(parentNativeId, startIndex, limit)
    }

    private sealed interface SyntheticKey {

        val runtime: AddonRuntimeDescriptor

        val seriesNativeId: String

        data class Series(
            override val runtime: AddonRuntimeDescriptor,
            override val seriesNativeId: String,
        ) : SyntheticKey

        data class Season(
            override val runtime: AddonRuntimeDescriptor,
            override val seriesNativeId: String,
            val seasonNumber: Int,
        ) : SyntheticKey
    }

    private fun parseSyntheticKey(key: String): SyntheticKey? {
        val parts = key.split(':')

        if (parts.size < 4 || parts[0] != "addon-native") {
            Stage6MetaLog.log("parseSyntheticKey key=$key not-addon-native (segments=${parts.size})")
            return null
        }
        val addonId = parts[1].takeIf { it.isNotBlank() } ?: return null
        val runtime = registry.runtimesFor(viewer, primary).firstOrNull { it.addonId == addonId }
        if (runtime == null) {
            Stage6MetaLog.log("parseSyntheticKey key=$key addonId=$addonId runtime=UNRESOLVED -> null (H2)")
            return null
        }
        return when (parts[2]) {
            "series" -> SyntheticKey.Series(runtime, parts.drop(3).joinToString(":")).also {
                Stage6MetaLog.log("parseSyntheticKey key=$key -> series native=${it.seriesNativeId}")
            }
            "season" -> {
                val seasonNumber = parts.lastOrNull()?.toIntOrNull() ?: return null
                SyntheticKey.Season(runtime, parts.drop(3).dropLast(1).joinToString(":"), seasonNumber).also {
                    Stage6MetaLog.log("parseSyntheticKey key=$key -> season native=${it.seriesNativeId} season=${it.seasonNumber}")
                }
            }
            else -> {
                Stage6MetaLog.log("parseSyntheticKey key=$key unknown-kind=${parts[2]} -> null")
                null
            }
        }
    }

    private suspend fun canonicalSeriesAddress(titleId: TitleId): String? {
        if (lookup.byId(titleId)?.kind != TitleKind.SERIES) return null
        val seasons = canonicalSeasonNumbers(titleId)
        if (seasons.isEmpty()) return null
        Stage6MetaLog.log(
            "canonicalSeriesAddress title=$titleId seasons=${seasons.size} -> ${canonicalSeriesKey(titleId)}",
        )
        return canonicalSeriesKey(titleId)
    }

    private suspend fun canonicalSeasonNumbers(seriesTitleId: TitleId): List<Int> {
        val hierarchy = canonicalHierarchy ?: return emptyList()
        return runCatching { hierarchy.seasonNumbers(seriesTitleId) }
            .getOrNull()
            .orEmpty()
            .filter { it > 0 }
            .distinct()
            .sorted()
    }

    private suspend fun canonicalSeasonRows(seriesTitleId: TitleId): List<CatalogEntry> =
        canonicalSeasonNumbers(seriesTitleId).map { seasonNumber ->
            CatalogEntry(
                nativeItemId = canonicalSeasonKey(seriesTitleId, seasonNumber),
                nativeParentId = null,
                titleId = canonicalUnitTitleId(seriesTitleId, seasonNumber, episodeNumber = null),
                displayTitle = "Season $seasonNumber",
                kind = TitleKind.SEASON,
                year = null,
                isFolder = true,
                childCount = null,
                seasonNumber = seasonNumber,
            )
        }

    private suspend fun canonicalEpisodeRows(seriesTitleId: TitleId, seasonNumber: Int): List<CatalogEntry> {
        val hierarchy = canonicalHierarchy ?: return emptyList()
        val episodes = runCatching { hierarchy.episodes(seriesTitleId, seasonNumber) }
            .getOrNull()
            .orEmpty()
            .filter { it.seasonNumber == seasonNumber && it.episodeNumber > 0 }
            .distinctBy { it.episodeNumber }
            .sortedBy { it.episodeNumber }
        return episodes.map { episode ->
            val episodeNumber = episode.episodeNumber
            val label = episode.title?.takeIf { it.isNotBlank() }
            CatalogEntry(
                nativeItemId = "canonical:${seriesTitleId.value}:$seasonNumber:$episodeNumber",
                nativeParentId = canonicalSeasonKey(seriesTitleId, seasonNumber),
                titleId = canonicalUnitTitleId(seriesTitleId, seasonNumber, episodeNumber),
                displayTitle = label ?: "Episode $episodeNumber",
                kind = TitleKind.EPISODE,
                year = parseYear(episode.released),
                isFolder = false,
                childCount = null,
                seriesTitleId = seriesTitleId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
            )
        }
    }

    private fun canonicalUnitTitleId(
        seriesTitleId: TitleId,
        seasonNumber: Int,
        episodeNumber: Int?,
    ): TitleId = CanonicalTitleMaterializer.deterministicTitleId(
        TypedExternalId(
            CANONICAL_UNIT_AUTHORITY,
            if (episodeNumber == null) {
                "season:${seriesTitleId.value}:$seasonNumber"
            } else {
                "episode:${seriesTitleId.value}:$seasonNumber:$episodeNumber"
            },
        ),
    )

    private fun canonicalSeriesKey(titleId: TitleId): String = "$CANONICAL_SERIES_PREFIX${titleId.value}"

    private fun canonicalSeasonKey(titleId: TitleId, seasonNumber: Int): String =
        "$CANONICAL_SEASON_PREFIX${titleId.value}:$seasonNumber"

    private fun parseCanonicalHierarchyKey(key: String): CanonicalHierarchyKey? {
        if (key.startsWith(CANONICAL_SEASON_PREFIX)) {
            val rest = key.removePrefix(CANONICAL_SEASON_PREFIX)
            val titleRaw = rest.substringBeforeLast(':', missingDelimiterValue = "")
            val seasonNumber = rest.substringAfterLast(':', missingDelimiterValue = "").toIntOrNull()
            if (titleRaw.isBlank() || seasonNumber == null) return null
            return runCatching { CanonicalHierarchyKey.Season(TitleId.fromString(titleRaw), seasonNumber) }
                .getOrNull()
        }
        if (key.startsWith(CANONICAL_SERIES_PREFIX)) {
            val titleRaw = key.removePrefix(CANONICAL_SERIES_PREFIX)
            if (titleRaw.isBlank()) return null
            return runCatching { CanonicalHierarchyKey.Series(TitleId.fromString(titleRaw)) }.getOrNull()
        }
        return null
    }

    private sealed interface CanonicalHierarchyKey {
        val titleId: TitleId

        data class Series(override val titleId: TitleId) : CanonicalHierarchyKey

        data class Season(override val titleId: TitleId, val seasonNumber: Int) : CanonicalHierarchyKey
    }

    private suspend fun resolveVideos(runtime: AddonRuntimeDescriptor, seriesNativeId: String): HierarchyVideos =
        resolveVideosAttempt(runtime, seriesNativeId, attempt = 1)

    private suspend fun resolveHierarchyVideos(key: SyntheticKey): HierarchyVideos {
        val fromAddOn = resolveVideos(key.runtime, key.seriesNativeId)
        if (fromAddOn is HierarchyVideos.Loaded && fromAddOn.videos.isNotEmpty()) return fromAddOn
        val canonical = canonicalHierarchyVideos(key) ?: return fromAddOn

        lastHierarchyFailure = null
        Stage6MetaLog.log(
            "resolveVideos canonical-completion id=${key.seriesNativeId} " +
                "addOn=${if (fromAddOn is HierarchyVideos.Failed) "FAILED" else "EMPTY"} -> canonical",
        )
        return canonical
    }

    private suspend fun canonicalHierarchyVideos(key: SyntheticKey): HierarchyVideos.Loaded? {
        val hierarchy = canonicalHierarchy ?: return null
        val seriesTitleId = seriesTitleIdFor(key.runtime, key.seriesNativeId) ?: return null
        return when (key) {
            is SyntheticKey.Series -> {
                val seasons = runCatching { hierarchy.seasonNumbers(seriesTitleId) }
                    .getOrNull()
                    .orEmpty()

                    .filter { it > 0 }
                    .distinct()
                    .sorted()
                seasons.map { season ->
                    canonicalVideo(
                        id = "",
                        season = season,
                        episode = null,
                        label = null,
                        released = null,
                    )
                }.takeIf { it.isNotEmpty() }?.let { HierarchyVideos.Loaded(it) }
            }
            is SyntheticKey.Season -> {
                val episodes = runCatching { hierarchy.episodes(seriesTitleId, key.seasonNumber) }
                    .getOrNull()
                    .orEmpty()
                    .filter { it.seasonNumber == key.seasonNumber && it.episodeNumber > 0 }
                    .distinctBy { it.episodeNumber }
                    .sortedBy { it.episodeNumber }
                episodes.map { episode ->
                    canonicalVideo(
                        id = canonicalVideoId(key.seriesNativeId, episode.seasonNumber, episode.episodeNumber),
                        season = episode.seasonNumber,
                        episode = episode.episodeNumber,
                        label = episode.title,
                        released = episode.released,
                    )
                }.takeIf { it.isNotEmpty() }?.let { HierarchyVideos.Loaded(it) }
            }
        }
    }

    private fun canonicalVideoId(seriesNativeId: String, seasonNumber: Int, episodeNumber: Int): String =
        "$seriesNativeId:$seasonNumber:$episodeNumber"

    private fun canonicalVideo(
        id: String,
        season: Int?,
        episode: Int?,
        label: String?,
        released: String?,
    ): StremioVideo = StremioVideo(
        raw = dev.infusion.source.stremio.wire.JsonValue.Obj(LinkedHashMap()),
        id = id,
        title = label,
        name = null,
        released = released,
        season = season,
        episode = episode,
        overview = null,
        thumbnail = null,
    )

    private suspend fun resolveVideosAttempt(
        runtime: AddonRuntimeDescriptor,
        seriesNativeId: String,
        attempt: Int,
    ): HierarchyVideos {

        if (attempt == 1) lastHierarchyFailure = null
        val result = queryVideos(runtime, seriesNativeId)
        val response = result.response
        val addonId = result.addonId
        val stateSummary = summarizeStates(result.states)
        if (response == null) {
            val retryable = result.states.values.any {
                it is ProviderResourceState.Failed && it.failure.retryable
            }
            if (retryable && attempt < MAX_VIDEO_RETRIES) {
                Stage6MetaLog.log(
                    "resolveVideos type=series id=$seriesNativeId preferred=${runtime.addonId} " +
                        "winner=NONE videos=null attempt=$attempt retryable=true -> RETRY states=[${stateSummary.joinToString(",")}]",
                )
                return resolveVideosAttempt(runtime, seriesNativeId, attempt + 1)
            }

            val failure = HierarchyFailure.fromStates(result.states)
            lastHierarchyFailure = failure
            Stage6MetaLog.log(
                "resolveVideos type=series id=$seriesNativeId preferred=${runtime.addonId} " +
                    "winner=NONE videos=null attempts=$attempt retryable=$retryable -> FAILED(${failure.reason}) " +
                    "states=[${stateSummary.joinToString(",")}]",
            )
            return HierarchyVideos.Failed(failure)
        }
        val videos = response.meta.videos
        val withSeason = videos.count { it.season != null }

        synchronized(videoCacheLock) {
            videoCache[seriesNativeId] = videos
        }
        lastHierarchyFailure = null
        Stage6MetaLog.log(
            "resolveVideos type=series id=$seriesNativeId preferred=${runtime.addonId} winner=$addonId " +
                "videos=${videos.size} withSeason=$withSeason attempts=$attempt states=[${stateSummary.joinToString(",")}]",
        )
        return HierarchyVideos.Loaded(videos)
    }

    private suspend fun queryVideos(
        runtime: AddonRuntimeDescriptor,
        seriesNativeId: String,
    ): StremioMetaQueryResult {
        val result = metaRepository.primary(
            type = StremioContentType.SERIES,
            id = seriesNativeId,
            preferredAddonId = runtime.addonId,
        )
        if (result.response == null) {
            val stale = synchronized(videoCacheLock) { videoCache[seriesNativeId] }
            if (stale != null) {
                Stage6MetaLog.log(
                    "resolveVideos type=series id=$seriesNativeId preferred=${runtime.addonId} " +
                        "live=NONE stale=SERVE (SWR) videos=${stale.size}",
                )
                return StremioMetaQueryResult(
                    response = StremioMetaResponse(
                        raw = dev.infusion.source.stremio.wire.JsonValue.Obj(linkedMapOf()),
                        meta = StremioMeta(
                            raw = dev.infusion.source.stremio.wire.JsonValue.Obj(linkedMapOf()),
                            id = seriesNativeId,
                            type = StremioContentType.SERIES,
                            name = "",
                            description = null,
                            genres = emptyList(),
                            poster = null,
                            background = null,
                            logo = null,
                            releaseInfo = null,
                            imdbRating = null,
                            videos = stale,
                            links = emptyList(),
                        ),
                    ),
                    addonId = null,
                    states = result.states,
                )
            }
        }
        return result
    }

    private fun summarizeStates(
        states: Map<String, ProviderResourceState<StremioMetaResponse>>,
    ): List<String> = states.map { (id, state) ->
        when (state) {
            is ProviderResourceState.Loaded ->
                "loaded:$id:${state.status}"
            is ProviderResourceState.Failed ->
                "failed:$id:${state.failure.reason.name}:retryable=${state.failure.retryable}"
            is ProviderResourceState.Skipped -> "skipped:$id"
            ProviderResourceState.Pending -> "pending:$id"
        }
    }

    private fun projectSeasons(
        runtime: AddonRuntimeDescriptor,
        seriesNativeId: String,
        videos: List<StremioVideo>,
    ): List<CatalogEntry> {
        val bySeason = videos.groupBy { it.season }
        return bySeason.keys
            .filterNotNull()

            .filter { it > 0 }
            .sorted()
            .mapNotNull { seasonNumber ->
                val seasonTitleId = seasonTitleIdFor(runtime, seriesNativeId, seasonNumber) ?: return@mapNotNull null
                CatalogEntry(
                    nativeItemId = "addon-native:${runtime.addonId}:season:$seriesNativeId:$seasonNumber",
                    nativeParentId = null,
                    titleId = seasonTitleId,
                    displayTitle = "Season $seasonNumber",
                    kind = TitleKind.SEASON,
                    year = null,
                    isFolder = true,
                    childCount = bySeason[seasonNumber]?.size,
                    seasonNumber = seasonNumber,
                )
            }
    }

    private fun projectEpisodes(
        runtime: AddonRuntimeDescriptor,
        seriesNativeId: String,
        videos: List<StremioVideo>,
        seasonNumber: Int,
    ): List<CatalogEntry> {
        val rows = mutableListOf<CatalogEntry>()
        val seenEpisodes = mutableSetOf<Int>()
        for (video in videos.filter { it.season == seasonNumber }.sortedBy { it.episode ?: Int.MAX_VALUE }) {
            val episodeNumber = video.episode ?: continue
            if (!seenEpisodes.add(episodeNumber)) continue
            val episodeTitleId = episodeTitleIdFor(runtime, seriesNativeId, seasonNumber, episodeNumber, video.id)
                ?: continue

            artworkRepository?.retain(runtime.addonId, video.id, video.thumbnail)
            rows += CatalogEntry(
                nativeItemId = video.id,
                nativeParentId = "addon-native:${runtime.addonId}:season:$seriesNativeId:$seasonNumber",
                titleId = episodeTitleId,

                displayTitle = video.label?.ifBlank { null } ?: "Episode $episodeNumber",
                kind = TitleKind.EPISODE,
                year = parseYear(video.released),
                isFolder = false,
                childCount = null,
                seriesTitleId = seriesTitleIdFor(runtime, seriesNativeId),
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
            )
        }
        return rows
    }

    private fun seriesTitleIdFor(runtime: AddonRuntimeDescriptor, seriesNativeId: String): TitleId? {
        val extracted = ExternalIdExtractor.extract(seriesNativeId).firstOrNull() ?: return null
        return lookup.byExternalId(extracted).firstOrNull()?.titleId
            ?: CanonicalTitleMaterializer.deterministicTitleId(extracted)
    }

    private fun seasonTitleIdFor(
        runtime: AddonRuntimeDescriptor,
        seriesNativeId: String,
        seasonNumber: Int,
    ): TitleId? {
        val seriesTitleId = seriesTitleIdFor(runtime, seriesNativeId) ?: return null
        val alias = TypedExternalId(
            "addon:${runtime.addonId}",
            "season:$seriesNativeId:$seasonNumber",
        )
        return unitTitleIdFor(
            runtime = runtime,
            seriesTitleId = seriesTitleId,
            kind = TitleKind.SEASON,
            displayTitle = "Season $seasonNumber",
            alias = alias,
            seasonNumber = seasonNumber,
            episodeNumber = null,
            nativeVideoId = null,
        )
    }

    private fun episodeTitleIdFor(
        runtime: AddonRuntimeDescriptor,
        seriesNativeId: String,
        seasonNumber: Int,
        episodeNumber: Int,
        videoId: String,
    ): TitleId? {
        val seriesTitleId = seriesTitleIdFor(runtime, seriesNativeId) ?: return null
        val alias = TypedExternalId(
            "addon:${runtime.addonId}",
            "episode:$seriesNativeId:$seasonNumber:$episodeNumber",
        )
        return unitTitleIdFor(
            runtime = runtime,
            seriesTitleId = seriesTitleId,
            kind = TitleKind.EPISODE,
            displayTitle = "S${seasonNumber}E${episodeNumber}",
            alias = alias,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            nativeVideoId = videoId,
        )
    }

    private fun unitTitleIdFor(
        runtime: AddonRuntimeDescriptor,
        seriesTitleId: TitleId,
        kind: TitleKind,
        displayTitle: String,
        alias: TypedExternalId,
        seasonNumber: Int?,
        episodeNumber: Int?,
        nativeVideoId: String?,
    ): TitleId? {
        lookup.byExternalId(alias).firstOrNull()?.let { return it.titleId }
        val titleId = CanonicalTitleMaterializer.deterministicTitleId(alias)
        val writer = projectionWriter ?: return titleId
        writer.writeUnit(
            runtime = runtime,
            seriesTitleId = seriesTitleId,
            titleId = titleId,
            kind = kind,
            displayTitle = displayTitle,
            alias = alias,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            nativeVideoId = nativeVideoId,
        )
        return titleId
    }

    private fun parseYear(released: String?): Int? {
        val raw = released?.trim()?.take(4) ?: return null
        val year = raw.toIntOrNull() ?: return null
        return year.takeIf { it in 1900..2100 }
    }

    internal var projectionWriter: AddonNativeProjectionWriter? = null

    companion object {

        const val MAX_VIDEO_RETRIES = 2

        const val CANONICAL_SERIES_PREFIX = "canonical-series:"

        const val CANONICAL_SEASON_PREFIX = "canonical-season:"

        const val CANONICAL_UNIT_AUTHORITY = "canonical"
    }
}

sealed interface HierarchyVideos {
    data class Loaded(val videos: List<StremioVideo>) : HierarchyVideos
    data class Failed(val failure: HierarchyFailure) : HierarchyVideos
}

data class HierarchyFailure(
    val reason: String,
    val retryable: Boolean,
    val providerReasons: List<String>,
) {
    companion object {

        const val MESSAGE = "Could not load seasons"

        fun fromStates(
            states: Map<String, ProviderResourceState<StremioMetaResponse>>,
        ): HierarchyFailure {
            val failures = states.values.filterIsInstance<ProviderResourceState.Failed>()
            val reasons = failures.map { it.failure.reason.name }
            val retryable = failures.any { it.failure.retryable }
            val providerSummary = reasons.mapIndexed { index, reason ->
                val retry = failures[index].failure.retryable
                "$reason:retryable=$retry"
            }.ifEmpty { listOf("no-provider") }
            return HierarchyFailure(
                reason = reasons.firstOrNull() ?: "PROVIDER_UNAVAILABLE",
                retryable = retryable,
                providerReasons = providerSummary,
            )
        }
    }
}

interface AddonNativeProjectionWriter {

    fun writeUnit(
        runtime: AddonRuntimeDescriptor,
        seriesTitleId: TitleId,
        titleId: TitleId,
        kind: TitleKind,
        displayTitle: String,
        alias: TypedExternalId,
        seasonNumber: Int?,
        episodeNumber: Int?,
        nativeVideoId: String?,
    )
}
