package dev.infusion.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.infusion.core.artworkimage.ArtworkRequest
import dev.infusion.core.artworkimage.ArtworkSourceVariants
import dev.infusion.core.artworkimage.LocalArtworkLoader
import dev.infusion.core.artworkimage.LocalArtworkSourceVariants
import dev.infusion.core.domain.format.AirDateFormat
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.feature.vertical.home.HomeCwCard
import dev.infusion.feature.vertical.home.HomeCwOrigin
import dev.infusion.feature.vertical.home.HomeCwRow
import dev.infusion.feature.vertical.home.HomeCwShelfKey
import dev.infusion.feature.vertical.home.HomeSpotlightSource
import dev.infusion.feature.vertical.home.UpNextRowCard
import dev.infusion.feature.vertical.home.homeSpotlightRowKey
import dev.infusion.feature.vertical.home.homeSpotlightSourceConfirmedGone
import dev.infusion.feature.vertical.media.CanonicalHeroUi
import dev.infusion.source.aggregation.discover.DiscoverItem
import dev.infusion.source.aggregation.discover.DiscoverShelf
import dev.infusion.source.aggregation.discover.ShelfState
import dev.infusion.tv.ui.focus.FocusKeyRegistry
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.presentation.IdentityMetadataState
import dev.infusion.tv.ui.theme.InfusionTvAppearance
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvDetailsMetrics
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.LocalInfusionAccent
import kotlin.random.Random
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull

internal const val HomeHeroSpotlightFocusKey = "hero:spotlight"

internal const val HomeHeroIdentityTag = "home:hero:identity"
internal const val HomeHeroGenresTag = "home:hero:genres"

private val HomeHeroIdentityGap = 8.dp

internal const val HomeHeroBlockTag = "home:hero"
internal const val HomeHeroFactsTag = "home:hero:facts"
internal const val HomeHeroSynopsisTag = "home:hero:synopsis"

internal const val HomeHeroRatingsTag = "home:hero:ratings"

internal fun homeHeroRatingChipTag(index: Int): String = "$HomeHeroRatingsTag:$index"

internal const val HomeHeroDotsTag = "home:hero:dots"

internal fun homeHeroDotTag(index: Int): String = "home:hero:dot:$index"

internal val HomeHeroDotSize = 8.dp

internal val HomeHeroDotGap = 8.dp

internal const val HomeRowItemIdentityTag = "home:row-item:identity"
internal const val HomeRowItemIdentityFactsTag = "home:row-item:facts"
internal const val HomeRowItemIdentitySynopsisTag = "home:row-item:synopsis"

internal const val HomeRowItemIdentitySynopsisLines: Int = 2

private val RowItemIdentityFactsGap = 6.dp

@Composable
internal fun homeRowItemIdentitySynopsisSlotHeight(): Dp =
    heroTextSlotHeight(
        InfusionTvScaleCalibration.Synopsis.Style,
        HomeRowItemIdentitySynopsisLines,
    )

@Composable
internal fun homeRowItemIdentityReservedHeight(): Dp =
    InfusionTvDetailsMetrics.HeroHeadingMinHeight +
        InfusionTvDetailsMetrics.ActionRowGap +
        homeHeroFactSlotHeight() +
        RowItemIdentityFactsGap +
        homeRowItemIdentitySynopsisSlotHeight() +
        InfusionTvDetailsMetrics.HeroContentBottomPadding

internal fun homeRowItemIdentityFacts(hero: CanonicalHeroUi?, preview: MediaPreview?): String? {
    val metadata = hero?.metadataLine?.takeIf { it.isNotEmpty() }
    val genres = hero?.genres.orEmpty().filter { it.isNotBlank() }.joinToString(", ")
    val canonical = listOfNotNull(metadata, genres.takeIf { it.isNotEmpty() })
        .joinToString(" · ")
        .takeIf { it.isNotEmpty() }
    return canonical ?: preview?.secondaryFact()
}

internal fun MediaPreview.secondaryFact(): String? =
    airDate?.let { AirDateFormat.format(it) } ?: year?.toString()

@Composable
internal fun TvHomeRowItemIdentity(
    hero: CanonicalHeroUi?,
    preview: MediaPreview?,
    modifier: Modifier = Modifier,

    collectionLogoLocator: String? = null,

    metadata: IdentityMetadataState = IdentityMetadataState.SETTLED,
) {
    TvCanonicalHero(
        hero = hero,
        titleFallback = preview?.displayTitle,
        modifier = modifier.testTag(HomeRowItemIdentityTag),

        minHeight = 0.dp,

        logoWidthFractionOfScreen = InfusionTvScaleCalibration.ClearLogo.MaxScreenWidthFraction,
        logoTag = HomeRowItemIdentityLogoTag,
        titleTag = HomeRowItemIdentityTitleTag,
        logoOverrideLocator = collectionLogoLocator,

        headingMetadata = metadata,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(RowItemIdentityFactsGap)) {

            val facts = homeRowItemIdentityFacts(hero, preview)
            Box(modifier = Modifier.heightIn(min = homeHeroFactSlotHeight())) {
                if (facts != null) {
                    Text(
                        facts,
                        style = InfusionTvScaleCalibration.Type.Body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(HomeRowItemIdentityFactsTag),
                    )
                }
            }

            Box(modifier = Modifier.heightIn(min = homeRowItemIdentitySynopsisSlotHeight())) {
                BoxWithConstraints {
                    val synopsisMaxWidth = InfusionTvScaleCalibration.ofScreenWidth(
                        InfusionTvScaleCalibration.Synopsis.MaxScreenWidthFraction,
                        maxWidth,
                    )
                    hero?.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                        Text(
                            overview,
                            style = InfusionTvScaleCalibration.Synopsis.Style,
                            maxLines = HomeRowItemIdentitySynopsisLines,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .widthIn(max = synopsisMaxWidth)
                                .testTag(HomeRowItemIdentitySynopsisTag),
                        )
                    }
                }
            }
        }
    }
}

private const val HomeRowItemIdentityLogoTag = "home:row-item:logo"
private const val HomeRowItemIdentityTitleTag = "home:row-item:title"

@Composable
private fun heroTextSlotHeight(style: TextStyle, lines: Int): Dp {
    val measurer = rememberTextMeasurer()
    val probe = remember(measurer, style, lines) {
        measurer.measure(
            AnnotatedString(List(lines) { HeroSlotProbeGlyph }.joinToString("\n")),
            style = style,
        ).size.height
    }
    return with(LocalDensity.current) { probe.toDp() }
}

private const val HeroSlotProbeGlyph = "X"

@Composable
internal fun homeHeroSynopsisSlotHeight(): Dp =
    heroTextSlotHeight(
        InfusionTvScaleCalibration.Synopsis.Style,
        InfusionTvScaleCalibration.Synopsis.MaxLines,
    )

@Composable
internal fun homeHeroFactSlotHeight(): Dp =
    heroTextSlotHeight(InfusionTvScaleCalibration.Type.Body, 1)

internal const val HomeHeroSpotlightSize: Int = 10

internal object HomeHeroRotation {

    const val IDLE_WINDOW_MS: Long = 5_000L

    fun intervalMs(seconds: Int): Long = seconds.coerceAtLeast(0).toLong() * 1_000L

    fun firstAdvanceMs(intervalMs: Long): Long = intervalMs * 2

    fun idleWindowMs(intervalMs: Long): Long = IDLE_WINDOW_MS.coerceAtMost(intervalMs)

    fun holdsForIdleWindow(intervalMs: Long, idleForMs: Long): Boolean =
        idleWindowMs(intervalMs) - idleForMs > 0

    fun idleHoldRemainingMs(intervalMs: Long, idleForMs: Long): Long =
        (idleWindowMs(intervalMs) - idleForMs).coerceIn(0L, intervalMs)

    fun canRotate(intervalMs: Long, setSize: Int, suspended: Boolean): Boolean =
        intervalMs > 0L && setSize > 1 && !suspended
}

internal object HomeHeroSpotlight {

    fun sample(
        seed: List<HomeHeroCandidate>,
        random: Random,
        size: Int = HomeHeroSpotlightSize,
    ): List<HomeHeroCandidate> = seed.shuffled(random).take(size)

    fun nextIndex(size: Int, current: Int): Int = move(size, current, +1)

    fun previousIndex(size: Int, current: Int): Int = move(size, current, -1)

    private fun move(size: Int, current: Int, delta: Int): Int {
        if (size <= 1) return 0
        val index = current.coerceIn(0, size - 1)
        return (index + delta + size) % size
    }
}

internal data class HomeHeroAnchor(
    val titleId: TitleId,
    val mediaType: MediaType,
)

internal sealed interface HomeHeroCandidate {

    val key: String

    val preview: MediaPreview

    val anchor: HomeHeroAnchor?

    val collectionLogoLocator: String? get() = null

    val collectionBackdropLocator: String? get() = null
}

internal data class HomeHeroCwCardCandidate(val card: HomeCwCard) : HomeHeroCandidate {
    override val key: String get() = card.key
    override val preview: MediaPreview get() = card.preview
    override val anchor: HomeHeroAnchor? get() = card.heroAnchor
}

internal data class HomeHeroAddonItemCandidate(val item: DiscoverItem) : HomeHeroCandidate {
    override val key: String get() = item.key
    override val preview: MediaPreview get() = item.preview
    override val anchor: HomeHeroAnchor?
        get() = item.preview.titleId?.let { titleId ->
            HomeHeroAnchor(
                titleId = titleId,
                mediaType = if (item.kind == TitleKind.SERIES) MediaType.SHOW else MediaType.MOVIE,
            )
        }
}

internal data class HomeHeroUpNextCandidate(val card: UpNextRowCard) : HomeHeroCandidate {
    override val key: String get() = card.key
    override val preview: MediaPreview get() = card.preview
    override val anchor: HomeHeroAnchor get() = HomeHeroAnchor(card.seriesTitleId, MediaType.SHOW)
}

internal data class HomeHeroCollectionTileCandidate(
    val focusKey: String,
    val title: String,
    val logoLocator: String?,
    val backdropLocator: String?,
) : HomeHeroCandidate {
    override val key: String get() = focusKey
    override val preview: MediaPreview
        get() = MediaPreview(
            titleId = null,
            kind = null,
            displayTitle = title,
            year = null,
            subtitle = null,
            artwork = null,
            progressFraction = null,
        )
    override val anchor: HomeHeroAnchor? get() = null
    override val collectionLogoLocator: String? get() = logoLocator
    override val collectionBackdropLocator: String? get() = backdropLocator
}

internal val HomeCwCard.heroAnchor: HomeHeroAnchor?
    get() = when (val origin = origin) {
        is HomeCwOrigin.Local -> {
            val episodic = origin.state.watchKey.isEpisode
            val titleId = if (episodic) origin.seriesTitleId else origin.titleId
            titleId?.let {
                HomeHeroAnchor(it, if (episodic) MediaType.SHOW else MediaType.MOVIE)
            }
        }
        is HomeCwOrigin.Jellyfin -> {
            val item = origin.item
            val episodic = item.kind == TitleKind.EPISODE
            val titleId = if (episodic) item.seriesTitleId else item.titleId
            titleId?.let {
                HomeHeroAnchor(
                    titleId = it,
                    mediaType = if (episodic || item.kind == TitleKind.SERIES) {
                        MediaType.SHOW
                    } else {
                        MediaType.MOVIE
                    },
                )
            }
        }
    }

internal val HomeCwCard.isHeroCandidate: Boolean
    get() = reachesDestination && when (val origin = origin) {
        is HomeCwOrigin.Local -> true
        is HomeCwOrigin.Jellyfin -> !origin.item.isFolder
    }

internal fun homeHeroPool(
    cwRows: List<HomeCwRow>,
    shelves: List<DiscoverShelf>,

    upNextCards: List<UpNextRowCard> = emptyList(),

    collectionRows: List<dev.infusion.feature.vertical.collections.CollectionGroupRow> = emptyList(),
): List<HomeHeroCandidate> = buildList {
    cwRows.forEach { row ->
        row.cards.forEach { card ->
            if (card.isHeroCandidate) add(HomeHeroCwCardCandidate(card))
        }
    }
    shelves.forEach { shelf ->
        val loaded = shelf.state as? ShelfState.Loaded ?: return@forEach
        loaded.items.forEach { item ->

            if (item.preview.titleId != null) add(HomeHeroAddonItemCandidate(item))
        }
    }
    upNextCards.forEach { add(HomeHeroUpNextCandidate(it)) }
    collectionRows.forEach { row ->
        row.tiles.forEach { tile ->
            add(
                HomeHeroCollectionTileCandidate(
                    focusKey = collectionTileFocusKey(row.widgetId, row.groupKey, tile.tileKey),
                    title = tile.title,
                    logoLocator = tile.logoLocator,

                    backdropLocator = tile.backdropLocator ?: tile.coverLocator,
                ),
            )
        }
    }
}

internal fun HomeHeroCandidate.identityKey(): String = anchor
    ?.let { anchor -> "canonical:${anchor.mediaType}:${anchor.titleId}" }
    ?: "item:$key"

internal fun homeHeroCatalogCandidates(shelves: List<DiscoverShelf>): List<HomeHeroCandidate> =
    buildList {
        shelves.forEach { shelf ->
            val loaded = shelf.state as? ShelfState.Loaded ?: return@forEach
            loaded.items.forEach { item ->
                if (item.preview.titleId != null) add(HomeHeroAddonItemCandidate(item))
            }
        }
    }.distinctBy { it.identityKey() }

internal fun homeHeroCwCandidates(cwRows: List<HomeCwRow>): List<HomeHeroCandidate> =
    buildList {
        cwRows.forEach { row ->
            row.cards.forEach { card ->
                if (card.isHeroCandidate) add(HomeHeroCwCardCandidate(card))
            }
        }
    }.distinctBy { it.identityKey() }

internal fun homeHeroSpotlightSeed(
    cwCandidates: List<HomeHeroCandidate>,
    catalogCandidates: List<HomeHeroCandidate>,
    size: Int = HomeHeroSpotlightSize,
): List<HomeHeroCandidate> {
    if (catalogCandidates.size >= size) return catalogCandidates
    val catalogIdentities = catalogCandidates.mapTo(HashSet()) { it.identityKey() }
    return catalogCandidates + cwCandidates.filterNot { it.identityKey() in catalogIdentities }
}

internal fun homeHeroSpotlightDrawReady(
    seed: List<HomeHeroCandidate>,
    catalogCandidates: List<HomeHeroCandidate>,
    rowsTerminal: Boolean,
    size: Int = HomeHeroSpotlightSize,
): Boolean = seed.isNotEmpty() && (catalogCandidates.size >= size || rowsTerminal)

internal enum class HomeInitialHero {

    Pending,

    Presented,

    NoHero,
}

internal fun homeInitialHero(
    featuredKey: String?,
    spotlightPreferenceRead: Boolean,
    spotlightEnabled: Boolean,
    spotlightSetDrawn: Boolean,
    spotlightSetEmpty: Boolean,

    spotlightDrawSettledWithoutSet: Boolean = false,
    spotlightOffEntryKey: String?,
    rowsLoading: Boolean,
): HomeInitialHero = when {

    featuredKey != null -> HomeInitialHero.Presented

    !spotlightPreferenceRead -> HomeInitialHero.Pending

    spotlightEnabled -> when {

        spotlightSetDrawn -> if (spotlightSetEmpty) HomeInitialHero.NoHero else HomeInitialHero.Pending

        spotlightDrawSettledWithoutSet -> HomeInitialHero.NoHero

        else -> HomeInitialHero.Pending
    }

    spotlightOffEntryKey != null -> HomeInitialHero.Pending
    rowsLoading -> HomeInitialHero.Pending
    else -> HomeInitialHero.NoHero
}

internal fun homeHeroShelfCandidates(shelf: DiscoverShelf): List<HomeHeroCandidate> {
    val loaded = shelf.state as? ShelfState.Loaded ?: return emptyList()
    return loaded.items
        .filter { item -> item.preview.titleId != null }
        .map { item -> HomeHeroAddonItemCandidate(item) }
        .distinctBy { candidate -> candidate.identityKey() }
}

internal data class HomeSpotlightPresentation(

    val source: HomeSpotlightSource,

    val pending: Boolean,

    val resolveStored: Boolean,

    val candidates: List<HomeHeroCandidate>,

    val drawReady: Boolean,
)

internal fun homeSpotlightPresentation(
    saved: HomeSpotlightSource,
    rows: List<DiscoverShelf>,
    declaredRowKeys: Set<String>,
    rowsSettled: Boolean,

    configuredRowKeys: Set<String>?,
    cwCandidates: List<HomeHeroCandidate>,
    catalogCandidates: List<HomeHeroCandidate>,
    cwRowsTerminal: Boolean,
    size: Int = HomeHeroSpotlightSize,
): HomeSpotlightPresentation {
    val automaticCandidates = homeHeroSpotlightSeed(cwCandidates, catalogCandidates, size)
    val automaticReady = homeHeroSpotlightDrawReady(
        seed = automaticCandidates,
        catalogCandidates = catalogCandidates,
        rowsTerminal = cwRowsTerminal,
        size = size,
    )
    fun automatic(resolveStored: Boolean) = HomeSpotlightPresentation(
        source = HomeSpotlightSource.Automatic,
        pending = false,
        resolveStored = resolveStored,
        candidates = automaticCandidates,
        drawReady = automaticReady,
    )
    if (saved is HomeSpotlightSource.Automatic) return automatic(resolveStored = false)
    val rowKey = homeSpotlightRowKey((saved as HomeSpotlightSource.Row).rowId)

    if (homeSpotlightSourceConfirmedGone(saved, configuredRowKeys)) {
        return automatic(resolveStored = true)
    }
    val shelf = rows.firstOrNull { row -> row.key == rowKey }
    if (shelf == null) {

        return when {
            rowKey in declaredRowKeys || !rowsSettled -> HomeSpotlightPresentation(
                source = saved,
                pending = true,
                resolveStored = false,
                candidates = emptyList(),
                drawReady = false,
            )
            else -> automatic(resolveStored = false)
        }
    }
    val eligible = homeHeroShelfCandidates(shelf)

    return if (eligible.isNotEmpty()) {
        HomeSpotlightPresentation(
            source = saved,
            pending = false,
            resolveStored = false,
            candidates = eligible,
            drawReady = true,
        )
    } else {
        automatic(resolveStored = false)
    }
}

internal object HomeHeroFocus {

    const val SETTLE_MS: Long = 300L

    const val RAPID_SETTLE_MS: Long = 150L

    const val RAPID_WINDOW_MS: Long = 250L

    fun isUserFocus(previousFocusedKey: String?, entryToken: Int): Boolean =
        (previousFocusedKey != null && !isTopBarKey(previousFocusedKey)) || entryToken > 0

    const val ENTRY_HOLD_SAFETY_MS: Long = 10 * 60 * 1000L

    fun settleMs(
        previousChangeAtMs: Long,
        nowMs: Long,
        previousRowKey: String?,
        rowKey: String?,
    ): Long {
        if (previousChangeAtMs <= 0L || previousRowKey == null || rowKey == null) return SETTLE_MS
        if (previousRowKey != rowKey) return SETTLE_MS
        val sinceMs = nowMs - previousChangeAtMs
        return if (sinceMs in 0..RAPID_WINDOW_MS) RAPID_SETTLE_MS else SETTLE_MS
    }
}

internal fun homeHeroRowKeys(
    cwRows: List<HomeCwRow>,
    shelves: List<DiscoverShelf>,
    upNextCards: List<UpNextRowCard> = emptyList(),

    collectionRows: List<dev.infusion.feature.vertical.collections.CollectionGroupRow> = emptyList(),
): Map<String, String> = buildMap {
    cwRows.forEach { row ->
        row.cards.forEach { card -> put(card.key, row.key) }
    }
    shelves.forEach { shelf ->
        val loaded = shelf.state as? ShelfState.Loaded ?: return@forEach
        loaded.items.forEach { item -> put(item.key, shelf.key) }
    }

    upNextCards.forEach { card -> put(card.key, HomeUpNextShelfKey) }

    collectionRows.forEach { row ->
        row.tiles.forEach { tile ->
            put(
                dev.infusion.tv.ui.screens.collectionTileFocusKey(row.widgetId, row.groupKey, tile.tileKey),
                row.rowKey,
            )
        }
    }
}

internal class HomeHeroEntryHold {
    private val candidates = linkedSetOf<String>()

    var armedKey: String? = null

    var armedInputMs: Long? = null

    fun recordCandidate(key: String) {
        candidates += key
    }

    fun isCandidate(key: String): Boolean = key in candidates
}

@Composable
internal fun HomeHeroEntryComposition(
    listState: LazyListState,
    entryKey: String?,
    focusedKey: String?,
    entryToken: Int,

    userDriven: Boolean,

    lastInputAtMs: () -> Long,
) {
    val currentFocused = rememberUpdatedState(focusedKey)

    val visit = remember(entryToken) { HomeHeroEntryHold() }
    LaunchedEffect(entryKey, entryToken, focusedKey, listState, userDriven) {
        entryKey?.let(visit::recordCandidate)
        val key = focusedKey ?: return@LaunchedEffect
        val armedInputMs = visit.armedInputMs

        if (armedInputMs != null && lastInputAtMs() != armedInputMs) return@LaunchedEffect
        val armedKey = visit.armedKey
        if (armedKey == null) {

            if (userDriven || !visit.isCandidate(key)) return@LaunchedEffect
            visit.armedKey = key
            visit.armedInputMs = lastInputAtMs()
        } else if (armedKey != key || userDriven) {

            return@LaunchedEffect
        }
        val baselineInputMs = visit.armedInputMs ?: lastInputAtMs()

        withTimeoutOrNull(HomeHeroFocus.ENTRY_HOLD_SAFETY_MS) {
            snapshotFlow {
                Triple(

                    currentFocused.value == key && lastInputAtMs() == baselineInputMs,
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset,
                )
            }
                .takeWhile { (holds, _, _) -> holds }
                .collect { (_, index, offset) ->
                    if (index != 0 || offset != 0) listState.scrollToItem(0)
                }
        }
    }
}

private fun MediaArtworkRef.resolvedLocator(variants: ArtworkSourceVariants): String =
    variants.resolve(ArtworkRequest(locator = locator, policy = policy, targetWidthPx = 0, targetHeightPx = 0))
        .locator

@Composable
internal fun rememberHomeHeroArtwork(
    incoming: MediaArtworkRef?,
    viewportTarget: IntSize,

    retention: HomeSpotlightStateHolder? = null,

    onResolved: ((ready: Boolean) -> Unit)? = null,
): MediaArtworkRef? {
    val loader = LocalArtworkLoader.current

    val sourceVariants = LocalArtworkSourceVariants.current

    val presentable = fullscreenBackdropArtwork(incoming)
    val locator = presentable?.resolvedLocator(sourceVariants)

    var resolvedReported by remember(locator) { mutableStateOf(false) }
    val resolvedSink = rememberUpdatedState(onResolved)

    var shown by remember(retention) {
        mutableStateOf(
            retention?.lastShownArtwork?.takeIf {
                it.policy == presentable?.policy &&
                    it.locator == presentable?.locator
            },
        )
    }

    var publishedLocator by remember(locator, retention) { mutableStateOf<String?>(null) }
    LaunchedEffect(locator, viewportTarget, loader) {
        if (presentable == null) {

            if (!resolvedReported) {
                resolvedReported = true
                resolvedSink.value?.invoke(false)
            }
            return@LaunchedEffect
        }
        if (shown != null && publishedLocator == locator) {
            retention?.lastShownArtwork = presentable
            if (!resolvedReported) {
                resolvedReported = true
                resolvedSink.value?.invoke(true)
            }
            return@LaunchedEffect
        }

        val ready = loader?.load(heroArtworkRequest(presentable, viewportTarget))

        val presented = if (ready != null) presentable else null
        shown = presented

        if (presented != null) {
            retention?.lastShownArtwork = presented
            publishedLocator = locator
        }

        if (!resolvedReported) {
            resolvedReported = true
            resolvedSink.value?.invoke(presented != null)
        }
    }
    return shown
}

internal class HeroCenterPressOwnership {
    private var ownsPress = false

    fun dispatch(type: KeyEventType, activate: () -> Unit): Boolean = when (type) {
        KeyEventType.KeyDown -> {
            ownsPress = true
            true
        }
        KeyEventType.KeyUp -> {
            val genuine = ownsPress
            ownsPress = false
            if (genuine) activate()
            true
        }
        else -> false
    }
}

@Composable
internal fun TvHomeHeroBlock(
    hero: CanonicalHeroUi?,
    preview: MediaPreview?,
    minHeight: Dp,
    requesters: FocusKeyRegistry,
    requester: FocusRequester,
    modifier: Modifier = Modifier,
    spotlightSize: Int = 0,
    spotlightIndex: Int = 0,
    onFocusedKeyChanged: (String) -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onOpen: () -> Unit = {},

    collectionLogoLocator: String? = null,

    collectionLogoRecolour: Boolean = false,

    heroFocusKey: String = HomeHeroSpotlightFocusKey,

    metadata: IdentityMetadataState = IdentityMetadataState.SETTLED,
) {
    val accent = LocalInfusionAccent.current

    val centerPress = remember { HeroCenterPressOwnership() }
    val spotlightModifier = Modifier
        .then(
            stableFocusModifier(
                key = heroFocusKey,
                requesters = requesters,
                topLevelContentBoundary = true,
                requester = requester,
                onFocusedKeyChanged = onFocusedKeyChanged,
            ),
        )
        .onPreviewKeyEvent { event ->

            if (event.key == Key.Enter || event.key == Key.DirectionCenter || event.key == Key.NumPadEnter) {
                return@onPreviewKeyEvent centerPress.dispatch(event.type) { onOpen() }
            }
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {

                Key.DirectionLeft -> {
                    onPrevious()
                    true
                }
                Key.DirectionRight -> {
                    onNext()
                    true
                }
                else -> false
            }
        }
        .focusable()

    Box(modifier = modifier) {
        TvCanonicalHero(
            hero = hero,
            titleFallback = preview?.displayTitle,
            modifier = Modifier.testTag(HomeHeroBlockTag),
            minHeight = minHeight,

            headingModifier = spotlightModifier,

            logoWidthFractionOfScreen = InfusionTvScaleCalibration.ClearLogo.MaxScreenWidthFraction,

            logoOverrideLocator = collectionLogoLocator,
            entityLogoRecolour = collectionLogoLocator != null && collectionLogoRecolour,

            headingMetadata = metadata,
        ) {

            Box(modifier = Modifier.testTag(HomeHeroIdentityTag)) {
                Column(verticalArrangement = Arrangement.spacedBy(HomeHeroIdentityGap)) {

                    hero?.let { heroState ->
                        genreLine(heroState.genres)?.let { line ->
                            Text(
                                line,
                                style = InfusionTvScaleCalibration.Type.Body,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag(HomeHeroGenresTag),
                            )
                        }
                    }

                    BoxWithConstraints {
                        val synopsisMaxWidth = InfusionTvScaleCalibration.ofScreenWidth(
                            InfusionTvScaleCalibration.Synopsis.MaxScreenWidthFraction,
                            maxWidth,
                        )
                        hero?.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                            Text(
                                overview,
                                style = InfusionTvScaleCalibration.Synopsis.Style,
                                maxLines = InfusionTvScaleCalibration.Synopsis.MaxLines,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .widthIn(max = synopsisMaxWidth)
                                    .testTag(HomeHeroSynopsisTag),
                            )
                        }
                    }

                    val facts = hero?.metadataLine?.takeIf { it.isNotEmpty() } ?: preview?.secondaryFact()
                    if (facts != null) {
                        Text(
                            facts,
                            style = InfusionTvScaleCalibration.Type.Body,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag(HomeHeroFactsTag),
                        )
                    }

                    hero?.ratings?.takeIf { it.isNotEmpty() }?.let { ratings ->
                        RatingFactsLine(
                            items = ratings.map(::ratingItem),
                            modifier = Modifier
                                .testTag(HomeHeroRatingsTag)
                                .focusProperties { canFocus = false },
                            itemTag = ::homeHeroRatingChipTag,
                        )
                    }

                    if (spotlightSize > 0) {
                        Row(
                            modifier = Modifier
                                .testTag(HomeHeroDotsTag)
                                .focusProperties { canFocus = false },
                            horizontalArrangement = Arrangement.spacedBy(HomeHeroDotGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            repeat(spotlightSize) { index ->
                                val current = index == spotlightIndex
                                Box(
                                    modifier = Modifier
                                        .size(HomeHeroDotSize)
                                        .clip(CircleShape)
                                        .background(
                                            if (current) {
                                                accent
                                            } else {
                                                InfusionTvAppearance.TextPrimary.copy(
                                                    alpha = InfusionTvContour.RestraintContourAlpha,
                                                )
                                            },
                                        )

                                        .semantics { selected = current }
                                        .testTag(homeHeroDotTag(index)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
