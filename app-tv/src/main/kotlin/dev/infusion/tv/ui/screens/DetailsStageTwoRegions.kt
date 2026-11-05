package dev.infusion.tv.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.feature.vertical.media.CanonicalDetailsUiState
import dev.infusion.feature.vertical.media.CompanyLogoUi
import dev.infusion.feature.vertical.media.MediaCardUi
import dev.infusion.feature.vertical.media.PlayableEpisodeRow
import dev.infusion.feature.vertical.media.companyFocusKey
import dev.infusion.feature.vertical.media.collectionFocusKey
import dev.infusion.tv.ui.focus.ContentFocusObserver
import dev.infusion.tv.ui.focus.FocusKeyRegistry
import dev.infusion.tv.ui.presentation.TvRowNavigation
import dev.infusion.tv.ui.presentation.nextRowFirstFullyVisibleItemKey
import dev.infusion.tv.ui.presentation.previousRowRememberedItemKey

internal fun detailsOccurrenceKey(regionKey: String, contentKey: String): String =
    "$regionKey|$contentKey"

internal object DetailsStageTwoRegionKeys {
    const val SEASONS = "details:region:seasons"
    const val EPISODES = "details:region:episodes"
    const val REVIEWS = "details:region:reviews"
    const val DIRECTORS = "details:region:directors"
    const val CREATORS = "details:region:creators"
    const val CAST = "details:region:cast"
    const val NETWORKS = "details:region:networks"
    const val STUDIOS = "details:region:studios"
    const val COLLECTION_TITLE = "details:region:collection-title"
    const val COLLECTION = "details:region:collection"
    const val RECOMMENDED = "details:region:recommended"
    const val SIMILAR = "details:region:similar"

    val PEOPLE_BANDS = setOf(DIRECTORS, CREATORS, CAST)
}

internal data class DetailsStageTwoRegion(
    val key: String,
    val itemKeys: List<String>,
)

internal fun detailsStageTwoRegions(
    state: CanonicalDetailsUiState?,
    episodeRows: List<PlayableEpisodeRow>,
    collectionMembers: List<MediaCardUi>,
): List<DetailsStageTwoRegion> {
    if (state == null) return emptyList()

    val studioKeys = companyKeys(state.studioCards.ifEmpty { state.studios.map { CompanyLogoUi(it) } })
    val networkKeys = companyKeys(state.networkCards.ifEmpty { state.networks.map { CompanyLogoUi(it) } })
    val reviews = reviewRegion(state)
    val rows: List<Pair<String, List<String>>> = buildList {
        add(DetailsStageTwoRegionKeys.SEASONS to state.seasons.map { it.key })
        add(DetailsStageTwoRegionKeys.EPISODES to episodeRows.map { it.focusKey })
        reviews?.let { add(it.key to it.itemKeys) }

        add(DetailsStageTwoRegionKeys.DIRECTORS to personKeys(state.directors, DetailsStageTwoRegionKeys.DIRECTORS))
        add(DetailsStageTwoRegionKeys.CREATORS to personKeys(state.creators, DetailsStageTwoRegionKeys.CREATORS))
        add(DetailsStageTwoRegionKeys.CAST to personKeys(state.cast, DetailsStageTwoRegionKeys.CAST))

        if (state.mediaType == MediaType.SHOW) {
            add(DetailsStageTwoRegionKeys.NETWORKS to networkKeys)
            add(DetailsStageTwoRegionKeys.STUDIOS to studioKeys)
        } else {
            add(DetailsStageTwoRegionKeys.STUDIOS to studioKeys)
            add(DetailsStageTwoRegionKeys.NETWORKS to networkKeys)
        }

        add(
            DetailsStageTwoRegionKeys.COLLECTION_TITLE to
                state.collectionId?.let { listOf(collectionFocusKey(it)) }.orEmpty(),
        )
        add(
            DetailsStageTwoRegionKeys.COLLECTION to
                collectionMembers.map { cardFocusKey(CollectionFocusPrefix, it.key) },
        )
        cardRegion(
            DetailsStageTwoRegionKeys.RECOMMENDED,
            RecommendationsFocusPrefix,
            state.recommendations.valueOrNull(),
        )?.let { add(it.key to it.itemKeys) }
        cardRegion(
            DetailsStageTwoRegionKeys.SIMILAR,
            SimilarFocusPrefix,
            state.similar.valueOrNull(),
        )?.let { add(it.key to it.itemKeys) }
    }

    val owningRow = HashMap<String, Int>()
    rows.forEachIndexed { index, (_, keys) ->
        keys.distinct().forEach { key -> owningRow.putIfAbsent(key, index) }
    }
    return rows.mapIndexedNotNull { index, (key, keys) ->
        val own = keys.distinct().filter { owningRow[it] == index }
        own.takeIf { it.isNotEmpty() }?.let { DetailsStageTwoRegion(key, it) }
    }
}

private fun personKeys(
    people: List<dev.infusion.feature.vertical.media.PersonCardUi>,
    bandKey: String,
): List<String> = people.mapNotNull { person ->
    person.activationKey?.let { contentKey -> detailsOccurrenceKey(bandKey, contentKey) }
}

private fun companyKeys(companies: List<CompanyLogoUi>): List<String> =
    companies.mapNotNull { it.entityRef?.let(::companyFocusKey) }

private fun reviewRegion(state: CanonicalDetailsUiState): DetailsStageTwoRegion? {
    val comments = state.reviews.valueOrNull().orEmpty()
    if (comments.isEmpty()) return null
    val keys = buildList {

        comments.forEach { add(commentCardFocusKey(it.key)) }
        if (state.reviewsHasMore) add("$ReviewsFocusPrefix:more")
    }
    return DetailsStageTwoRegion(DetailsStageTwoRegionKeys.REVIEWS, keys)
}

private fun cardRegion(
    key: String,
    focusPrefix: String,
    cards: List<MediaCardUi>?,
): DetailsStageTwoRegion? {
    val keys = cards.orEmpty().map { cardFocusKey(focusPrefix, it.key) }
    return keys.takeIf { it.isNotEmpty() }?.let { DetailsStageTwoRegion(key, it) }
}

internal fun detailsEpisodeParentChipKeys(
    state: CanonicalDetailsUiState?,
    episodeRows: List<PlayableEpisodeRow>,
): Map<String, String> {
    if (state == null || episodeRows.isEmpty()) return emptyMap()
    val chipKeyBySeason: Map<Int, String> = state.seasons.associate { it.seasonNumber to it.key }
    return buildMap {
        episodeRows.forEach { row ->
            chipKeyBySeason[row.episode.seasonNumber]?.let { chipKey ->
                put(row.focusKey, chipKey)
            }
        }
    }
}

internal class DetailsStageTwoHierarchy(
    private val regionsProvider: () -> List<DetailsStageTwoRegion>,
    private val episodeParentChipKeys: () -> Map<String, String>,

    private val isReachable: (String) -> Boolean = { false },
) : TvRowNavigation {

    private val regionMemory = mutableMapOf<String, String>()

    private var verticalEntryRegion: String? = null

    private val regionLeadingVisible = mutableMapOf<String, String>()

    fun onFocusReported(key: String) {
        regionOf(key)?.let { regionMemory[it] = key }
    }

    fun regionOf(key: String?): String? {
        if (key == null) return null
        return regionsProvider().firstOrNull { key in it.itemKeys }?.key
    }

    fun atStageTwoTopBoundary(fromKey: String?): Boolean {
        val regions = regionsProvider()
        val first = regions.firstOrNull() ?: return false
        return regionOf(fromKey) == first.key
    }

    fun backToParent(fromKey: String?): String? =
        fromKey?.let { episodeParentChipKeys()[it] }

    private fun downEntryItem(region: DetailsStageTwoRegion): String? =
        regionLeadingVisible[region.key]?.takeIf { it in region.itemKeys }
            ?: regionMemory[region.key]?.takeIf { it in region.itemKeys }
            ?: region.itemKeys.firstOrNull { isReachable(it) }

    private fun upEntryItem(region: DetailsStageTwoRegion): String? =
        regionMemory[region.key]?.takeIf { it in region.itemKeys }
            ?: region.itemKeys.firstOrNull { isReachable(it) }

    override fun downTarget(fromKey: String?): String? {
        val regions = regionsProvider()
        val origin = regions.indexOfFirst { fromKey in it.itemKeys }
        if (origin < 0) return null
        val target = nextRowFirstFullyVisibleItemKey(
            rowItemKeys = regions.map { it.itemKeys },
            originRowIndex = origin,
            visibleItemInRow = { index -> downEntryItem(regions[index]) },
        ) ?: return null
        verticalEntryRegion = regions.firstOrNull { target in it.itemKeys }?.key
        return target
    }

    override fun upTarget(fromKey: String?): String? {

        backToParent(fromKey)?.let { parent ->
            verticalEntryRegion = regionOf(parent)
            return parent
        }
        val regions = regionsProvider()
        val origin = regions.indexOfFirst { fromKey in it.itemKeys }
        if (origin < 0) return null
        val target = previousRowRememberedItemKey(
            rowItemKeys = regions.map { it.itemKeys },
            originRowIndex = origin,
            rememberedItemInRow = { index -> upEntryItem(regions[index]) },
        ) ?: return null
        verticalEntryRegion = regions.firstOrNull { target in it.itemKeys }?.key
        return target
    }

    override fun isVerticalEntry(rowKey: Any?): Boolean =
        rowKey != null && verticalEntryRegion == rowKey

    override fun onHorizontalStep(rowKey: Any?) {
        if (rowKey != null && verticalEntryRegion == rowKey) verticalEntryRegion = null
    }

    override fun publishFirstFullyVisibleItem(rowKey: Any, itemKey: String?) {
        val key = rowKey as? String ?: return
        if (itemKey == null) {
            regionLeadingVisible.remove(key)
        } else {
            regionLeadingVisible[key] = itemKey
        }
    }
}

@Composable
internal fun rememberDetailsStageTwoHierarchy(
    regions: List<DetailsStageTwoRegion>,
    episodeParentChipKeys: Map<String, String>,
    requesters: FocusKeyRegistry,
): DetailsStageTwoHierarchy? {

    val liveRegions = rememberUpdatedState(regions)
    val liveParents = rememberUpdatedState(episodeParentChipKeys)

    val hierarchy = remember {
        DetailsStageTwoHierarchy(
            regionsProvider = { liveRegions.value },
            episodeParentChipKeys = { liveParents.value },

            isReachable = { key -> requesters.isLive(key) },
        )
    }

    val observer = remember(hierarchy) {
        ContentFocusObserver { key, _ -> hierarchy.onFocusReported(key) }
    }
    requesters.registerContentFocusObserver(observer)
    DisposableEffect(requesters, observer) {
        onDispose { requesters.unregisterContentFocusObserver(observer) }
    }

    return hierarchy.takeIf { regions.isNotEmpty() }
}
