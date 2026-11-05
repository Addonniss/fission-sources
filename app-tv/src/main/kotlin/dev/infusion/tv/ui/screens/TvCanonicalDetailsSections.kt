@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package dev.infusion.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlin.math.roundToInt
import dev.infusion.core.artworkimage.ArtworkImage
import dev.infusion.core.artworkimage.ArtworkRequest
import dev.infusion.core.domain.metadata.MetadataFailureReason
import dev.infusion.core.domain.metadata.MetadataSection
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.core.model.metadata.RatingAppearance
import dev.infusion.feature.vertical.media.CanonicalDetailsUiState
import dev.infusion.feature.vertical.media.CollectionUi
import dev.infusion.feature.vertical.media.CompanyLogoUi
import dev.infusion.feature.vertical.media.MediaCardUi
import dev.infusion.feature.vertical.media.PersonCardUi
import dev.infusion.feature.vertical.media.PlayableEpisodeRow
import dev.infusion.feature.vertical.media.ReviewUi
import dev.infusion.feature.vertical.media.SectionState
import dev.infusion.feature.vertical.media.TrailerUi
import dev.infusion.feature.vertical.media.canonicalKind
import dev.infusion.feature.vertical.media.canonicalTitleIdOrNull
import dev.infusion.feature.vertical.media.collectionFocusKey
import dev.infusion.feature.vertical.media.companyFocusKey
import dev.infusion.feature.vertical.media.sectionFailureCopy
import dev.infusion.tv.ui.presentation.InfusionCardAvailabilityBadge
import dev.infusion.tv.ui.presentation.InfusionCardContextMenuHint
import dev.infusion.tv.ui.presentation.InfusionCardProgressBar
import dev.infusion.tv.ui.presentation.InfusionCardWatchlistBadge
import dev.infusion.tv.ui.presentation.InfusionCardWatchedMark
import dev.infusion.core.domain.watchlist.CardActionSurface
import dev.infusion.core.model.MediaPreview
import dev.infusion.tv.ui.presentation.InfusionMediaCard
import dev.infusion.tv.ui.presentation.LocalCardAvailability
import dev.infusion.tv.ui.presentation.LocalCardWatchedState
import dev.infusion.tv.ui.presentation.LocalCardWatchlistMembership
import dev.infusion.tv.ui.presentation.LocalContentCardPresentation
import dev.infusion.tv.ui.presentation.LocalRatingAppearance
import dev.infusion.tv.ui.presentation.LocalTvRowKey
import dev.infusion.tv.ui.presentation.MediaCardArtwork
import dev.infusion.tv.ui.presentation.RowPagingState
import dev.infusion.tv.ui.presentation.TvBrowseContentAlignmentInset
import dev.infusion.tv.ui.presentation.artworkTargetPx
import dev.infusion.tv.ui.presentation.cardWatchedUnitOrNull
import dev.infusion.tv.ui.presentation.rememberSharedRailLaw
import dev.infusion.tv.ui.presentation.cardContextActionModifier
import dev.infusion.tv.ui.presentation.contextMenuHintVisible
import dev.infusion.tv.ui.presentation.rememberCardActionRequest
import dev.infusion.tv.ui.presentation.contentCardArtworkKey
import dev.infusion.tv.ui.presentation.contentCardArtworkTarget
import dev.infusion.tv.ui.presentation.contentCardAvailabilityBadgeKey
import dev.infusion.tv.ui.presentation.contentCardContextMenuHintKey
import dev.infusion.tv.ui.presentation.contentCardProgressBarKey
import dev.infusion.tv.ui.presentation.contentCardWatchedMarkKey
import dev.infusion.tv.ui.presentation.contentCardWatchlistBadgeKey
import dev.infusion.tv.ui.presentation.entityLogoColorFilter
import dev.infusion.tv.ui.presentation.hideWatchedEpisodeRows
import dev.infusion.tv.ui.presentation.hideWatchedMediaCards
import dev.infusion.tv.ui.presentation.rememberRowPaging
import dev.infusion.tv.ui.presentation.tvBrowseNonRailInset
import dev.infusion.tv.ui.presentation.tvRailContentPadding
import dev.infusion.tv.ui.presentation.jellyfinAvailableFor
import dev.infusion.tv.ui.presentation.watchedMarkUnitFor
import dev.infusion.tv.ui.presentation.watchlistBadgeFor
import dev.infusion.tv.ui.presentation.watchlistEntryKey
import dev.infusion.tv.ui.theme.InfusionTvAppearance
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.focus.LocalTvZoneEntry
import dev.infusion.tv.ui.focus.RequestFocusOnKey
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvFilterChip
import dev.infusion.tv.ui.theme.InfusionTvHeroContextualAction
import dev.infusion.tv.ui.theme.InfusionTvHeroFactChip
import dev.infusion.tv.ui.theme.InfusionTvHeroIconAction
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.LocalInfusionAccent
import dev.infusion.tv.ui.theme.infusionTvCardBorder
import dev.infusion.tv.ui.theme.infusionTvCardColors
import dev.infusion.tv.ui.theme.infusionTvCardScale
import dev.infusion.tv.ui.theme.infusionTvCardShape

private val PersonCardWidth = 70.dp
private val PersonCardImageHeight = PersonCardWidth * 1.5f

private val PersonCardPadding = 4.dp

private val EpisodeCardWidth = 236.dp
private val EpisodeCardImageHeight = EpisodeCardWidth * 9f / 16f

private val CompanyLogoWidth = 120.dp
private val CompanyLogoHeight = 48.dp

internal val CompanyLogoPlateColor: Color = InfusionTvAppearance.Panel

internal const val HeroRatingChipsTag = "canonical:hero:ratings"
internal const val HeroGenreChipsTag = "canonical:hero:genres"

internal const val CreditsDirectorsRailTag = "canonical:credits:directors"

internal const val HeroTaglineTag = "canonical:hero:tagline"

internal fun heroRatingChipTag(index: Int): String = "$HeroRatingChipsTag:$index"

internal const val StudiosNetworksTag = "canonical:studios-networks"
internal const val StudiosRowTag = "canonical:studios-networks:studio"
internal const val NetworksRowTag = "canonical:studios-networks:network"
internal const val CompanyLogoBoxTag = "canonical:studios-networks:logo-box"
internal const val CompanyLogoPlateTag = "canonical:studios-networks:logo-plate"

internal const val CompanyLogoFocusFrameTag = "canonical:studios-networks:focus-frame"

internal val StudioArtworkContentScale: ContentScale = ContentScale.Fit

@Composable
fun TvCanonicalHeroFacts(state: CanonicalDetailsUiState) {
    TvCanonicalHeroRatings(state)
    TvCanonicalHeroMeta(state)
}

@Composable
fun TvCanonicalHeroRatings(state: CanonicalDetailsUiState) {
    val hero = state.hero ?: return
    if (hero.ratings.isEmpty()) return

    RatingFactsLine(
        items = hero.ratings.map(::ratingItem),
        modifier = Modifier.testTag(HeroRatingChipsTag),
        itemTag = ::heroRatingChipTag,
    )
}

@Composable
fun TvCanonicalHeroMeta(state: CanonicalDetailsUiState) {
    if (state.hero == null) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TvCanonicalHeroTitleFacts(state)
        TvCanonicalHeroGenreRow(state)
        TvCanonicalHeroQuietFacts(state)
    }
}

@Composable
internal fun TvCanonicalHeroTitleFacts(state: CanonicalDetailsUiState) {
    val hero = state.hero ?: return
    val original = hero.originalTitle
    if (original != null && original != hero.title) {
        Text(original, style = InfusionTvScaleCalibration.Type.Body)
    }
    if (hero.metadataLine.isNotEmpty()) {
        Text(hero.metadataLine, style = InfusionTvScaleCalibration.Type.Body)
    }
}

internal const val GenreSeparator: String = " · "

internal fun genreLine(genres: List<String>): String? =
    genres.map { it.trim() }
        .filter { it.isNotEmpty() }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(GenreSeparator)

@Composable
internal fun TvCanonicalHeroGenreRow(state: CanonicalDetailsUiState) {
    val hero = state.hero ?: return
    val line = genreLine(hero.genres) ?: return
    Text(
        line,
        style = InfusionTvScaleCalibration.Type.Body,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag(HeroGenreChipsTag),
    )
}

@Composable
internal fun TvCanonicalHeroTagline(state: CanonicalDetailsUiState) {
    val hero = state.hero ?: return
    val tagline = hero.tagline?.takeIf { it.isNotBlank() } ?: return
    Text(
        tagline,
        style = InfusionTvScaleCalibration.Type.Body,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag(HeroTaglineTag),
    )
}

@Composable
internal fun TvCanonicalHeroQuietFacts(state: CanonicalDetailsUiState) {
    if (state.hero == null) return
    if (state.refreshing) {

        Text("Refreshing details…", style = InfusionTvScaleCalibration.Type.Body)
    }
    if (state.metadataFailure != null) {
        Text("Couldn't refresh details.", style = InfusionTvScaleCalibration.Type.Body)
    }
}

@Composable
fun TvCanonicalOverview(state: CanonicalDetailsUiState, showOverview: Boolean = true) {
    if (!showOverview) return
    val hero = state.hero ?: return
    val overview = hero.overview
    if (overview != null) {
        DetailsSynopsisText(
            text = overview,
            style = InfusionTvScaleCalibration.Synopsis.Style,
            maxLines = InfusionTvScaleCalibration.Synopsis.MaxLines,
        )
    } else {
        DetailsSynopsisText(
            text = "No overview available.",
            style = InfusionTvScaleCalibration.Type.Body,

            maxLines = Int.MAX_VALUE,
        )
    }
}

@Composable
private fun DetailsSynopsisText(text: String, style: TextStyle, maxLines: Int) {
    BoxWithConstraints {
        Text(
            text,
            style = style,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(
                max = InfusionTvScaleCalibration.ofScreenWidth(
                    InfusionTvScaleCalibration.Synopsis.MaxScreenWidthFraction,
                    maxWidth,
                ),
            ),
        )
    }
}

private val CreditsLineRhythm = 6.dp

@Composable
fun TvCanonicalCredits(
    state: CanonicalDetailsUiState,
    requesters: MutableMap<String, FocusRequester> = mutableMapOf(),
    onOpenPerson: (PersonCardUi) -> Unit = {},

    modifier: Modifier = Modifier,

    directorsBandKey: String? = null,
    creatorsBandKey: String? = null,

    onOpenPersonOccurrence: ((PersonCardUi, String) -> Unit)? = null,
) {
    if (state.directors.isEmpty() && state.creators.isEmpty()) {
        return
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(CreditsLineRhythm)) {

        DirectorsRail(
            label = if (state.directors.size > 1) "Directors" else "Director",
            people = state.directors,
            requesters = requesters,
            onOpenPerson = onOpenPerson,
            bandKey = directorsBandKey,
            onOpenPersonOccurrence = onOpenPersonOccurrence,
        )
        PersonCreditLine(
            label = "Created by",
            people = state.creators,
            requesters = requesters,
            onOpenPerson = onOpenPerson,
            bandKey = creatorsBandKey,
            onOpenPersonOccurrence = onOpenPersonOccurrence,
        )
    }
}

internal fun peopleBandFocusKey(bandKey: String?, person: PersonCardUi): String? =
    person.activationKey?.let { contentKey ->
        bandKey?.let { detailsOccurrenceKey(it, contentKey) } ?: contentKey
    }

@Composable
fun TvCanonicalStudiosNetworks(
    state: CanonicalDetailsUiState,

    modifier: Modifier = Modifier,

    requesters: MutableMap<String, FocusRequester> = mutableMapOf(),

    onFocusedKeyChanged: (String) -> Unit = {},

    onOpenEntity: (CompanyLogoUi) -> Unit = {},
) {

    val studios = state.studioCards.ifEmpty { state.studios.map { CompanyLogoUi(it) } }
    val networks = state.networkCards.ifEmpty { state.networks.map { CompanyLogoUi(it) } }
    if (studios.isEmpty() && networks.isEmpty()) return
    Column(
        modifier = modifier.testTag(StudiosNetworksTag),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.mediaType == MediaType.SHOW) {
            CompanyLogoRow("Network", networks, NetworksRowTag, requesters, onFocusedKeyChanged, onOpenEntity)
            CompanyLogoRow("Studio", studios, StudiosRowTag, requesters, onFocusedKeyChanged, onOpenEntity)
        } else {
            CompanyLogoRow("Studio", studios, StudiosRowTag, requesters, onFocusedKeyChanged, onOpenEntity)
            CompanyLogoRow("Network", networks, NetworksRowTag, requesters, onFocusedKeyChanged, onOpenEntity)
        }
    }
}

@Composable
private fun CompanyLogoRow(
    label: String,
    companies: List<CompanyLogoUi>,
    tag: String,
    requesters: MutableMap<String, FocusRequester>,
    onFocusedKeyChanged: (String) -> Unit,
    onOpenEntity: (CompanyLogoUi) -> Unit,
) {
    if (companies.isEmpty()) return
    FlowRow(
        modifier = Modifier.testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("$label:", style = InfusionTvScaleCalibration.Type.Body)
        companies.forEach { company ->
            CompanyLogo(company, requesters, onFocusedKeyChanged, onOpenEntity)
        }
    }
}

@Composable
internal fun CompanyLogoFocusFrameOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .testTag(CompanyLogoFocusFrameTag)
            .border(
                InfusionTvContour.CardFocusWidth,
                LocalInfusionAccent.current,
                InfusionTvContour.Shape,
            ),
    )
}

@Composable
private fun CompanyLogo(
    company: CompanyLogoUi,
    requesters: MutableMap<String, FocusRequester>,
    onFocusedKeyChanged: (String) -> Unit,
    onOpenEntity: (CompanyLogoUi) -> Unit,
) {
    val locator = company.logoLocator

    val request = locator?.let { value ->
        ArtworkRequest.of(
            value,
            ArtworkPolicy.LOGO,
            artworkTargetPx(DpSize(CompanyLogoWidth, CompanyLogoHeight)),
        )
    }

    val logoColorFilter = entityLogoColorFilter(locator, request)
    val focusKey = company.entityRef?.let { ref -> companyFocusKey(ref) }

    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val focusControl = if (focusKey != null) {
        stableFocusModifier(
            key = focusKey,
            requesters = requesters,
            onFocusedKeyChanged = onFocusedKeyChanged,
        )

            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) { onOpenEntity(company) }
    } else {
        Modifier
    }

    Box(modifier = Modifier.then(focusControl)) {
        Box(
            modifier = Modifier
                .size(CompanyLogoWidth, CompanyLogoHeight)
                .testTag(CompanyLogoBoxTag),
            contentAlignment = Alignment.Center,
        ) {
            if (request == null) {

                Text(
                    company.name,
                    style = InfusionTvScaleCalibration.Type.Body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            } else {

                var markDrawn by remember(locator) { mutableStateOf(false) }
                if (markDrawn) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(InfusionTvContour.Shape)
                            .background(CompanyLogoPlateColor)
                            .testTag(CompanyLogoPlateTag),
                    )
                }
                ArtworkImage(
                    request = request,
                    contentDescription = company.name,
                    modifier = Modifier.matchParentSize(),

                    contentScale = StudioArtworkContentScale,
                    alignment = Alignment.Center,
                    onLoaded = { markDrawn = true },
                    colorFilter = logoColorFilter,
                )
            }
        }

        if (focusKey != null && focused) {
            CompanyLogoFocusFrameOverlay(Modifier.matchParentSize())
        }
    }
}

@Composable
private fun PersonCreditLine(
    label: String,
    people: List<PersonCardUi>,
    requesters: MutableMap<String, FocusRequester>,
    onOpenPerson: (PersonCardUi) -> Unit,

    bandKey: String? = null,
    onOpenPersonOccurrence: ((PersonCardUi, String) -> Unit)? = null,
) {
    if (people.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(CreditsChipGap),
        verticalArrangement = Arrangement.spacedBy(CreditsLineRhythm),
    ) {
        Text("$label:", style = InfusionTvScaleCalibration.Type.Body)
        people.forEach { person ->
            PersonCreditChip(
                person = person,
                requesters = requesters,
                onOpenPerson = onOpenPerson,
                bandKey = bandKey,
                onOpenPersonOccurrence = onOpenPersonOccurrence,
            )
        }
    }
}

@Composable
private fun DirectorsRail(
    label: String,
    people: List<PersonCardUi>,
    requesters: MutableMap<String, FocusRequester>,
    onOpenPerson: (PersonCardUi) -> Unit,

    bandKey: String? = null,
    onOpenPersonOccurrence: ((PersonCardUi, String) -> Unit)? = null,
) {
    if (people.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CreditsDirectorsRailTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CreditsChipGap),
    ) {
        Text("$label:", style = InfusionTvScaleCalibration.Type.Body)
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(CreditsChipGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(people, key = { person -> peopleBandFocusKey(bandKey, person) ?: "cast:${person.key}" }) { person ->
                PersonCreditChip(
                    person = person,
                    requesters = requesters,
                    onOpenPerson = onOpenPerson,
                    bandKey = bandKey,
                    onOpenPersonOccurrence = onOpenPersonOccurrence,
                )
            }
        }
    }
}

@Composable
private fun PersonCreditChip(
    person: PersonCardUi,
    requesters: MutableMap<String, FocusRequester>,
    onOpenPerson: (PersonCardUi) -> Unit,
    bandKey: String? = null,
    onOpenPersonOccurrence: ((PersonCardUi, String) -> Unit)? = null,
) {
    val focusKey = peopleBandFocusKey(bandKey, person)
    if (focusKey == null) {

        Text(person.name, style = InfusionTvScaleCalibration.Type.Body)
    } else {
        InfusionTvButton(
            onClick = { onOpenPersonOccurrence?.invoke(person, focusKey) ?: onOpenPerson(person) },
            modifier = stableFocusModifier(
                key = focusKey,
                requesters = requesters,
                onFocusedKeyChanged = {},
            ),
        ) { Text(person.name) }
    }
}

private val CreditsChipGap = 8.dp

@Composable
fun TvCanonicalOverviewBlock(
    synopsis: String?,
    canonical: CanonicalDetailsUiState?,
    synopsisMaxLines: Int = InfusionTvScaleCalibration.Synopsis.MaxLines,
) {
    if (canonical?.hero == null) {
        synopsis?.let {
            DetailsSynopsisText(
                text = it,
                style = InfusionTvScaleCalibration.Synopsis.Style,
                maxLines = synopsisMaxLines,
            )
        }
        return
    }
    if (synopsis != null) {
        DetailsSynopsisText(
            text = synopsis,
            style = InfusionTvScaleCalibration.Synopsis.Style,
            maxLines = synopsisMaxLines,
        )
    } else {
        TvCanonicalOverview(canonical)
    }
}

internal const val DetailsCastRailTag: String = "details:rail:cast"

internal const val DetailsCollectionRailTag: String = "details:rail:collection"

internal const val DetailsEpisodeRailTag: String = "details:rail:episodes"

internal const val DetailsReviewsRailTag: String = "details:rail:reviews"

internal const val DetailsSeasonStripTag: String = "details:chip-strip:seasons"

internal fun detailsCardRailTag(focusPrefix: String): String = "details:rail:$focusPrefix"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvCanonicalCastRow(
    cast: List<PersonCardUi>,
    requesters: MutableMap<String, FocusRequester>,
    onOpenPerson: (PersonCardUi) -> Unit = {},

    bandKey: String? = null,

    onOpenPersonOccurrence: ((PersonCardUi, String) -> Unit)? = null,
) {
    if (cast.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Cast",
            style = InfusionTvScaleCalibration.Type.Emphasis,
            modifier = Modifier.tvBrowseNonRailInset(),
        )

        CompositionLocalProvider(LocalTvRowKey provides DetailsStageTwoRegionKeys.CAST) {
            val castItemKeys = cast.mapNotNull { person -> peopleBandFocusKey(bandKey, person) }
            val railLaw = rememberSharedRailLaw(castItemKeys)
            CompositionLocalProvider(LocalBringIntoViewSpec provides railLaw.spec) {
                LazyRow(
                    state = railLaw.listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(DetailsCastRailTag)
                        .then(railLaw.modifier),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = tvRailContentPadding(vertical = 8.dp),
                ) {
                    items(
                        cast,
                        key = { person ->
                            peopleBandFocusKey(bandKey, person) ?: "cast:${person.key}"
                        },
                    ) { person ->
                        PersonCard(person, requesters, onOpenPerson, bandKey, onOpenPersonOccurrence)
                    }
                }
            }
        }
    }
}

@Composable
internal fun PersonCard(
    person: PersonCardUi,
    requesters: MutableMap<String, FocusRequester>,
    onOpenPerson: (PersonCardUi) -> Unit,
    bandKey: String? = null,
    onOpenPersonOccurrence: ((PersonCardUi, String) -> Unit)? = null,

    onFocusedKeyChanged: (String) -> Unit = {},
) {
    val target = artworkTargetPx(DpSize(PersonCardWidth, PersonCardImageHeight))

    val activationKey = peopleBandFocusKey(bandKey, person)
    Card(
        onClick = {
            if (activationKey != null) {
                onOpenPersonOccurrence?.invoke(person, activationKey) ?: onOpenPerson(person)
            }
        },
        modifier = (
            if (activationKey == null) {
                Modifier.focusProperties { canFocus = false }
            } else {
                stableFocusModifier(
                    key = activationKey,
                    requesters = requesters,
                    onFocusedKeyChanged = onFocusedKeyChanged,
                )
            }
            ),

        colors = infusionTvCardColors(),
        shape = infusionTvCardShape(),
        scale = infusionTvCardScale(),
        border = infusionTvCardBorder(),
    ) {

        Column(
            modifier = Modifier.padding(PersonCardPadding).size(PersonCardWidth, PersonCardImageHeight + 56.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {

            val imageModifier = Modifier
                .size(PersonCardWidth, PersonCardImageHeight)
                .clip(InfusionTvContour.ArtworkShape)
            val imageLocator = person.imageLocator
            if (imageLocator != null) {
                ArtworkImage(
                    request = ArtworkRequest.of(imageLocator, ArtworkPolicy.POSTER, target),
                    contentDescription = person.name,
                    modifier = imageModifier,
                )
            } else {
                PersonInitialsBlock(person.name, imageModifier)
            }
            Text(person.name, style = InfusionTvScaleCalibration.Type.Emphasis, maxLines = 2)
            person.role?.let {
                Text(it, style = InfusionTvScaleCalibration.Type.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

internal fun cardFocusKey(focusPrefix: String, cardKey: String): String = "$focusPrefix:$cardKey"

internal const val RecommendationsFocusPrefix = "canonical:recommendations"

internal const val SimilarFocusPrefix = "canonical:similar"

internal const val CollectionFocusPrefix = "collection"

internal const val InThisCollectionLabel: String = "In this collection"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvCanonicalCardRow(
    title: String,
    focusPrefix: String,
    section: SectionState<List<MediaCardUi>>,
    requesters: MutableMap<String, FocusRequester>,
    sectionId: MetadataSection,
    onOpenCard: (MediaCardUi) -> Unit,
    onRetry: (MetadataSection) -> Unit,

    onOpenCardOccurrence: ((MediaCardUi, String) -> Unit)? = null,
    onLoadMore: ((MetadataSection) -> Unit)? = null,
    hasMore: Boolean = false,

    pagingFailure: MetadataFailureReason? = null,

    restoreFocusKey: String? = null,

    onRestoredRowBounds: ((topPx: Int, heightPx: Int) -> Unit)? = null,
) {
    when (section) {

        SectionState.Absent -> Unit

        SectionState.Loading -> SectionRowShell(title, Modifier.tvBrowseNonRailInset()) {
            Text("Loading…", style = InfusionTvScaleCalibration.Type.Body)
        }

        is SectionState.Failed -> SectionRowShell(title, Modifier.tvBrowseNonRailInset()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {

                Text(sectionFailureCopy(sectionId), style = InfusionTvScaleCalibration.Type.Body)
                InfusionTvButton(
                    onClick = { onRetry(sectionId) },
                    modifier = stableFocusModifier(
                        key = "$focusPrefix:retry",
                        requesters = requesters,
                        onFocusedKeyChanged = {},
                    ),
                ) { Text("Retry") }
            }
        }

        is SectionState.Ready -> {

            val cards = hideWatchedMediaCards(
                cards = section.value,
                hideWatchedTitles = LocalContentCardPresentation.current.hideWatchedTitles,
                watchedState = LocalCardWatchedState.current,
            )
            if (cards.isEmpty()) return

            val itemKeys = remember(cards, focusPrefix) {
                cards.map { cardFocusKey(focusPrefix, it.key) }
            }
            val paging = rememberRowPaging(
                itemKeys = itemKeys,
                hasMore = hasMore,
                onLoadMore = onLoadMore?.let { loadMore -> { loadMore(sectionId) } },
                pagingFailed = pagingFailure != null,
            )

            val armedRowRestore = LocalTvZoneEntry.current?.armedKey
                ?.takeIf { it.startsWith("$focusPrefix:") }
            val armedRowRestoreMissing = armedRowRestore != null && armedRowRestore !in itemKeys

            val ownsRestoredOccurrence = restoreFocusKey != null &&
                restoreFocusKey.startsWith("$focusPrefix:")
            LaunchedEffect(armedRowRestoreMissing, itemKeys, hasMore) {
                if (!armedRowRestoreMissing) return@LaunchedEffect
                paging.onEndBoundary()
            }
            Column(

                modifier = Modifier.onGloballyPositioned { coordinates ->
                    if (!ownsRestoredOccurrence) return@onGloballyPositioned
                    onRestoredRowBounds?.invoke(
                        coordinates.positionInRoot().y.roundToInt(),
                        coordinates.size.height,
                    )
                },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    title,
                    style = InfusionTvScaleCalibration.Type.Emphasis,
                    modifier = Modifier.tvBrowseNonRailInset(),
                )

                val regionKey = when (focusPrefix) {
                    RecommendationsFocusPrefix -> DetailsStageTwoRegionKeys.RECOMMENDED
                    SimilarFocusPrefix -> DetailsStageTwoRegionKeys.SIMILAR
                    else -> null
                }
                CompositionLocalProvider(LocalTvRowKey provides regionKey) {
                    val railLaw = rememberSharedRailLaw(itemKeys, paging)

                    val armedIndex = armedRowRestore?.let { key -> itemKeys.indexOf(key) }
                    LaunchedEffect(armedRowRestore, armedIndex, railLaw.listState) {
                        val index = armedIndex ?: return@LaunchedEffect
                        if (index < 0) return@LaunchedEffect
                        railLaw.listState.scrollToItem(index)
                    }

                    val returnedIndex = restoreFocusKey
                        ?.takeIf { it.startsWith("$focusPrefix:") }
                        ?.let { key -> itemKeys.indexOf(key) }
                    LaunchedEffect(restoreFocusKey, returnedIndex, railLaw.listState) {
                        val index = returnedIndex ?: return@LaunchedEffect
                        if (index < 0) return@LaunchedEffect
                        railLaw.listState.scrollToItem(index)
                    }
                    CompositionLocalProvider(LocalBringIntoViewSpec provides railLaw.spec) {
                        LazyRow(
                            state = railLaw.listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(detailsCardRailTag(focusPrefix))
                                .then(railLaw.modifier),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = tvRailContentPadding(vertical = 8.dp),
                        ) {
                            items(cards, key = { cardFocusKey(focusPrefix, it.key) }) { card ->
                                DetailsSharedMediaCard(
                                    card,
                                    focusPrefix,
                                    requesters,
                                    onOpenCard,
                                    onOpenCardOccurrence = onOpenCardOccurrence,
                                    rowPaging = paging,
                                )
                            }
                        }
                    }
                }

                if (pagingFailure != null) {
                    Row(
                        modifier = Modifier.tvBrowseNonRailInset(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(sectionFailureCopy(sectionId), style = InfusionTvScaleCalibration.Type.Body)
                        InfusionTvButton(
                            onClick = { onRetry(sectionId) },
                            modifier = stableFocusModifier(
                                key = "$focusPrefix:more-retry",
                                requesters = requesters,
                                onFocusedKeyChanged = {},
                            ),
                        ) { Text("Retry") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DetailsSharedMediaCard(
    card: MediaCardUi,
    focusPrefix: String,
    requesters: MutableMap<String, FocusRequester>,
    onOpenCard: (MediaCardUi) -> Unit,

    onOpenCardOccurrence: ((MediaCardUi, String) -> Unit)? = null,
    rowPaging: RowPagingState? = null,

    artworkLabel: String? = null,
) {
    val focusKey = cardFocusKey(focusPrefix, card.key)

    val canonicalKind = card.canonicalKind

    val artworkRef = card.artwork
        ?: card.imageLocator?.let { MediaArtworkRef(it, ArtworkPolicy.POSTER) }
    val preview = MediaPreview(
        titleId = card.canonicalTitleIdOrNull(),

        kind = canonicalKind,
        displayTitle = card.title,
        year = card.year,
        subtitle = null,
        artwork = artworkRef,

        progressFraction = null,
    )
    val artworkTarget = contentCardArtworkTarget(preview.kind)
    InfusionMediaCard(
        onClick = {
            if (card.isNavigable) {
                onOpenCardOccurrence?.invoke(card, focusKey) ?: onOpenCard(card)
            }
        },
        focusKey = focusKey,
        kind = preview.kind,
        preview = preview,
        requesters = requesters,
        onFocusedKeyChanged = { key -> rowPaging?.onFocusedKey(key) },
        focusable = card.isNavigable,
        contextSurface = CardActionSurface.DETAILS,
        artworkTag = contentCardArtworkKey(focusKey),
        artworkLabel = artworkLabel,
        artwork = {

            MediaCardArtwork(
                preview = preview,
                targetSize = artworkTarget,
                contentDescription = card.title,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}

@Composable
private fun SectionRowShell(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = InfusionTvScaleCalibration.Type.Emphasis)
        content()
    }
}

internal const val ReviewsSectionTag = "canonical:reviews"

internal fun commentCardFocusKey(reviewKey: String): String = reviewKey

internal fun reviewCardTag(reviewKey: String): String = commentCardFocusKey(reviewKey)

internal const val ReviewsFocusPrefix = "canonical:reviews"

private const val ReviewsHeading = "Reviews"

private const val ReviewContentMaxLines = 6

private val CommentCardPadding = 12.dp

private val CommentCardGap = 12.dp

@Composable
fun TvCanonicalReviewsSection(
    section: SectionState<List<ReviewUi>>,
    requesters: MutableMap<String, FocusRequester>,
    onRetry: (MetadataSection) -> Unit,
    onLoadMore: (() -> Unit)? = null,
    hasMore: Boolean = false,

    pagingFailure: MetadataFailureReason? = null,

    contentAlignmentInset: Dp = TvBrowseContentAlignmentInset,
) {
    when (section) {

        SectionState.Absent -> Unit

        SectionState.Loading -> SectionRowShell(
            ReviewsHeading,
            Modifier.padding(horizontal = contentAlignmentInset),
        ) {
            Text("Loading…", style = InfusionTvScaleCalibration.Type.Body)
        }

        is SectionState.Failed -> SectionRowShell(
            ReviewsHeading,
            Modifier.padding(horizontal = contentAlignmentInset),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {

                Text(sectionFailureCopy(MetadataSection.REVIEWS), style = InfusionTvScaleCalibration.Type.Body)
                InfusionTvButton(
                    onClick = { onRetry(MetadataSection.REVIEWS) },
                    modifier = stableFocusModifier(
                        key = "$ReviewsFocusPrefix:retry",
                        requesters = requesters,
                        onFocusedKeyChanged = {},
                    ),
                ) { Text("Retry") }
            }
        }

        is SectionState.Ready -> {
            val comments = section.value
            if (comments.isEmpty()) return

            var readerKey by remember { mutableStateOf<String?>(null) }

            var readerRestoreKey by remember { mutableStateOf<String?>(null) }
            RequestFocusOnKey(key = readerRestoreKey, requesters = requesters)

            var keysShownBefore by remember { mutableStateOf<List<String>>(emptyList()) }
            var moreActivated by remember { mutableStateOf(false) }

            var moreFocusKey by remember { mutableStateOf<String?>(null) }
            RequestFocusOnKey(key = moreFocusKey, requesters = requesters)
            LaunchedEffect(comments) {
                val current = comments.map { it.key }
                val added = current.filter { key -> key !in keysShownBefore }
                keysShownBefore = current
                if (moreActivated && added.isNotEmpty()) {
                    moreActivated = false

                    moreFocusKey = added.first()
                }
            }
            Column(
                modifier = Modifier.testTag(ReviewsSectionTag),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {

                Text(
                    ReviewsHeading,
                    style = InfusionTvScaleCalibration.Type.Emphasis,
                    modifier = Modifier.padding(horizontal = contentAlignmentInset),
                )
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {

                    val cardWidth = maxWidth * InfusionTvScaleCalibration.Synopsis.MaxScreenWidthFraction
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())

                            .testTag(DetailsReviewsRailTag)
                            .padding(start = contentAlignmentInset),
                        horizontalArrangement = Arrangement.spacedBy(CommentCardGap),
                        verticalAlignment = Alignment.Top,
                    ) {
                        comments.forEachIndexed { index, review ->
                            CommentCard(
                                review = review,
                                width = cardWidth,
                                requesters = requesters,
                                onOpen = {
                                    readerRestoreKey = null
                                    readerKey = review.key
                                },
                            )
                        }

                        if (hasMore && onLoadMore != null) {
                            InfusionTvButton(
                                onClick = {

                                    moreActivated = true
                                    onLoadMore()
                                },
                                modifier = stableFocusModifier(
                                    key = "$ReviewsFocusPrefix:more",
                                    requesters = requesters,
                                    onFocusedKeyChanged = {},
                                ),
                            ) { Text("More") }
                        }
                    }
                }

                if (pagingFailure != null) {
                    Row(
                        modifier = Modifier.padding(horizontal = contentAlignmentInset),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(sectionFailureCopy(MetadataSection.REVIEWS), style = InfusionTvScaleCalibration.Type.Body)
                        InfusionTvButton(
                            onClick = { onRetry(MetadataSection.REVIEWS) },
                            modifier = stableFocusModifier(
                                key = "$ReviewsFocusPrefix:more-retry",
                                requesters = requesters,
                                onFocusedKeyChanged = {},
                            ),
                        ) { Text("Retry") }
                    }
                }
            }

            val openKey = readerKey
            if (openKey != null && comments.any { it.key == openKey }) {
                TvCommentReaderPopup(
                    reviews = comments,
                    initialKey = openKey,
                    requesters = requesters,
                    onDismiss = { displayedKey ->
                        readerKey = null

                        readerRestoreKey = comments.firstOrNull { it.key == displayedKey }?.key
                            ?: reviewCardTag(displayedKey)
                    },
                )
            }
        }
    }
}

@Composable
private fun CommentCard(
    review: ReviewUi,
    width: Dp,
    requesters: MutableMap<String, FocusRequester>,
    onOpen: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier
            .width(width)
            .then(
                stableFocusModifier(
                    key = commentCardFocusKey(review.key),
                    requesters = requesters,
                    onFocusedKeyChanged = {},
                ),
            ),

        colors = infusionTvCardColors(),
        shape = infusionTvCardShape(),
        scale = infusionTvCardScale(),
        border = infusionTvCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(CommentCardPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {

            ReviewAttribution(
                review = review,
                authorStyle = InfusionTvScaleCalibration.Type.Emphasis,
                supportingStyle = InfusionTvScaleCalibration.Type.Body,
                modifier = Modifier.fillMaxWidth(),
            )
            if (review.content.isNotBlank()) {
                Text(
                    review.content,

                    style = InfusionTvScaleCalibration.Type.Body,
                    maxLines = ReviewContentMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvCanonicalCollectionSection(
    collectionName: String?,
    collectionId: Long?,
    collection: SectionState<CollectionUi>,
    requesters: MutableMap<String, FocusRequester>,
    onOpenCollection: (Long) -> Unit,
    onOpenCard: (MediaCardUi) -> Unit,
    onRetry: (MetadataSection) -> Unit,
) {
    if (collectionName == null) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (collectionId == null) {
            Text(
                collectionName,
                style = InfusionTvScaleCalibration.Type.Emphasis,
                modifier = Modifier.tvBrowseNonRailInset(),
            )
        } else {
            InfusionTvButton(
                onClick = { onOpenCollection(collectionId) },
                modifier = stableFocusModifier(
                    key = collectionFocusKey(collectionId),
                    requesters = requesters,
                    onFocusedKeyChanged = {},
                ).tvBrowseNonRailInset(),
            ) { Text(collectionName, style = InfusionTvScaleCalibration.Type.Emphasis) }
        }
        when (collection) {

            SectionState.Absent -> Unit
            SectionState.Loading ->
                Text(
                    "Collection: loading…",
                    style = InfusionTvScaleCalibration.Type.Body,
                    modifier = Modifier.tvBrowseNonRailInset(),
                )
            is SectionState.Failed -> Row(
                modifier = Modifier.tvBrowseNonRailInset(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {

                Text(sectionFailureCopy(MetadataSection.COLLECTION), style = InfusionTvScaleCalibration.Type.Body)
                InfusionTvButton(
                    onClick = { onRetry(MetadataSection.COLLECTION) },
                    modifier = stableFocusModifier(
                        key = "collection:retry",
                        requesters = requesters,
                        onFocusedKeyChanged = {},
                    ),
                ) { Text("Retry") }
            }
            is SectionState.Ready -> {
                val value = collection.value

                val members = hideWatchedMediaCards(
                    cards = value.members,
                    hideWatchedTitles = LocalContentCardPresentation.current.hideWatchedTitles,
                    watchedState = LocalCardWatchedState.current,
                )

                CompositionLocalProvider(LocalTvRowKey provides DetailsStageTwoRegionKeys.COLLECTION) {
                    val memberKeys = members.map { cardFocusKey(CollectionFocusPrefix, it.key) }
                    val railLaw = rememberSharedRailLaw(memberKeys)
                    CompositionLocalProvider(LocalBringIntoViewSpec provides railLaw.spec) {
                        LazyRow(
                            state = railLaw.listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(DetailsCollectionRailTag)
                                .then(railLaw.modifier),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = tvRailContentPadding(vertical = 8.dp),
                        ) {
                            items(members, key = { "$CollectionFocusPrefix:${it.key}" }) { card ->

                                DetailsSharedMediaCard(
                                    card = card,
                                    focusPrefix = CollectionFocusPrefix,
                                    requesters = requesters,
                                    onOpenCard = onOpenCard,
                                    artworkLabel = if (value.isCurrentTitle(card)) InThisCollectionLabel else null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EpisodeMetadataCard(
    row: PlayableEpisodeRow,
    requesters: MutableMap<String, FocusRequester>,
    onOpen: (() -> Unit)?,
    onEpisodeFocused: (String) -> Unit = {},

    seriesTitleId: dev.infusion.core.model.TitleId? = null,

    seriesLandscapeLocator: String? = null,
) {
    val episode = row.episode
    val target = artworkTargetPx(DpSize(EpisodeCardWidth, EpisodeCardImageHeight))

    val markUnit = LocalContentCardPresentation.current.watchedMarkUnitFor(
        unit = seriesTitleId?.let { row.cardWatchedUnitOrNull(it) },
        watchedState = LocalCardWatchedState.current,
    )

    var artworkSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    var episodeCardFocused by remember { mutableStateOf(false) }

    val actionRequest = rememberCardActionRequest(
        surface = dev.infusion.core.domain.watchlist.CardActionSurface.DETAILS,
        displayTitle = "${episode.coordinate} — ${episode.title}",
        titleId = row.playableTitleId,
        kind = dev.infusion.core.model.TitleKind.EPISODE,
        watchKey = seriesTitleId?.let { row.cardWatchedUnitOrNull(it) },

        playableTitleId = row.playableTitleId,
    )

    val badgePolicy = LocalContentCardPresentation.current
    val episodeUnit = seriesTitleId?.let { row.cardWatchedUnitOrNull(it) }
    val episodeUnitTitleId = (row.playableTitleId ?: episodeUnit?.titleId)?.value?.toString()
    val seriesIdValue = seriesTitleId?.value?.toString()
    val jellyfinAvailable = badgePolicy.jellyfinAvailableFor(
        unitTitleId = episodeUnitTitleId,
        seriesTitleId = seriesIdValue,
        seasonNumber = episode.seasonNumber,
        episodeNumber = episode.episodeNumber,
        availability = LocalCardAvailability.current,
    )
    val watchlistMembership = LocalCardWatchlistMembership.current
    val watchlistVariant = badgePolicy.watchlistBadgeFor(
        entryKey = seriesIdValue?.let {
            dev.infusion.tv.ui.presentation.watchlistEntryKey(
                dev.infusion.core.domain.watchlist.WatchlistKind.SHOW,
                it,
            )
        },
        membership = watchlistMembership,
    )

    val progressFraction = episode.watchedFraction
        ?.toFloat()
        ?.takeIf { badgePolicy.cardBadges && badgePolicy.progressBar && it > 0f }

    val showContextMenuHint = contextMenuHintVisible(
        focused = episodeCardFocused,
        menuAvailable = actionRequest != null,
        cardBadges = badgePolicy.cardBadges,
        contextMenuHint = badgePolicy.contextMenuBadge,
    )
    Card(
        onClick = { onOpen?.invoke() },
        modifier = Modifier

            .width(EpisodeCardWidth)

            .then(
                if (!row.isPlayable) {
                    Modifier.semantics { stateDescription = "No playable source is available." }
                } else {
                    Modifier
                },
            )
            .then(
                stableFocusModifier(
                    key = episode.focusKey,
                    requesters = requesters,
                    onFocusedKeyChanged = onEpisodeFocused,
                ),
            )
            .then(cardContextActionModifier(episode.focusKey, actionRequest))

            .onFocusChanged { state -> episodeCardFocused = state.isFocused },
        colors = infusionTvCardColors(),
        shape = infusionTvCardShape(),
        scale = infusionTvCardScale(),
        border = infusionTvCardBorder(),
    ) {
        Column(

            modifier = Modifier.fillMaxWidth(),
        ) {

            Box(
                modifier = Modifier
                    .size(EpisodeCardWidth, EpisodeCardImageHeight)
                    .clip(InfusionTvContour.ArtworkShape)
                    .onSizeChanged { artworkSize = it },
            ) {

                if (seriesLandscapeLocator != null) {
                    ArtworkImage(
                        request = ArtworkRequest.of(
                            seriesLandscapeLocator,
                            ArtworkPolicy.BACKDROP,
                            target,
                        ),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),

                        contentScale = ContentScale.Fit,
                    )
                }
                ArtworkImage(
                    request = episode.stillLocator?.let { ArtworkRequest.of(it, ArtworkPolicy.STILL, target) },
                    contentDescription = episode.title,
                    modifier = Modifier.fillMaxSize(),
                )

                if (markUnit != null) {
                    InfusionCardWatchedMark(
                        artworkSize = artworkSize,
                        tag = contentCardWatchedMarkKey(episode.focusKey),
                    )
                }

                if (progressFraction != null) {
                    InfusionCardProgressBar(
                        artworkSize = artworkSize,
                        fraction = progressFraction,
                        tag = contentCardProgressBarKey(episode.focusKey),
                    )
                }
                if (watchlistVariant != null) {
                    InfusionCardWatchlistBadge(
                        artworkSize = artworkSize,
                        variant = watchlistVariant,
                        providerId = watchlistMembership.activeTracker,
                        tag = contentCardWatchlistBadgeKey(episode.focusKey),
                    )
                }
                if (jellyfinAvailable) {
                    InfusionCardAvailabilityBadge(
                        artworkSize = artworkSize,
                        tag = contentCardAvailabilityBadgeKey(episode.focusKey),
                    )
                }

                if (showContextMenuHint) {
                    InfusionCardContextMenuHint(
                        artworkSize = artworkSize,
                        tag = contentCardContextMenuHintKey(episode.focusKey),
                    )
                }
            }
            Column(

                modifier = Modifier.padding(
                    start = InfusionTvContour.CardFocusWidth,
                    end = InfusionTvContour.CardFocusWidth,
                    top = dev.infusion.tv.ui.presentation.ContentCardPresentation.ArtworkTitleSpacing,
                    bottom = dev.infusion.tv.ui.presentation.ContentCardPresentation.ContainerPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(
                    dev.infusion.tv.ui.presentation.ContentCardPresentation.TextLineSpacing,
                ),
            ) {

                Text(
                    "${episode.coordinate} — ${episode.title}",
                    style = InfusionTvScaleCalibration.Type.Emphasis,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    episode.overview.orEmpty(),
                    style = InfusionTvScaleCalibration.Type.Body,
                    minLines = 3,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(
                    modifier = Modifier.height(
                        with(androidx.compose.ui.platform.LocalDensity.current) {
                            InfusionTvScaleCalibration.Type.Body.lineHeight.toDp()
                        },
                    ),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        episode.runtimeMinutes?.let { "${it}min" }.orEmpty(),
                        style = InfusionTvScaleCalibration.Type.Body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (episode.ratings.isNotEmpty()) {

                            val globalAppearance = LocalRatingAppearance.current
                            val episodeAppearance = if (globalAppearance == RatingAppearance.TEXT) {
                                RatingAppearance.ABBREVIATED_TEXT
                            } else {
                                globalAppearance
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                episode.ratings.forEachIndexed { index, rating ->
                                    if (index > 0) {
                                        Text(
                                            RatingFactSeparator,
                                            style = InfusionTvScaleCalibration.Type.Body,
                                            color = InfusionTvContour.ControlContentColor.copy(alpha = 0.55f),
                                        )
                                    }
                                    RatingFactChip(item = ratingItem(rating), appearanceOverride = episodeAppearance)
                                }
                            }
                        } else {

                            Text(" ", style = InfusionTvScaleCalibration.Type.Body)
                        }
                    }
                    Text(
                        episode.airDate?.toString().orEmpty(),
                        style = InfusionTvScaleCalibration.Type.Body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvCanonicalSeasonsSection(
    state: CanonicalDetailsUiState,
    rows: List<PlayableEpisodeRow>,
    requesters: MutableMap<String, FocusRequester>,
    onSelectSeason: (Int) -> Unit,
    onOpenEpisode: (PlayableEpisodeRow) -> Unit,
    onRetry: () -> Unit,
    onEpisodeFocused: (seasonNumber: Int, focusKey: String) -> Unit,
    sectionTitle: String = "Seasons",

    rowModifier: Modifier = Modifier,
) {
    if (state.seasons.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            sectionTitle,
            style = InfusionTvScaleCalibration.Type.Emphasis,
            modifier = Modifier.tvBrowseNonRailInset(),
        )
        TvCanonicalSeasonStrip(state = state, requesters = requesters, onSelectSeason = onSelectSeason)
        when (val episodes = state.episodes) {

            SectionState.Absent -> Text(
                if (state.selectedSeasonNumber == null) {
                    "Select a season to see its episodes."
                } else {
                    "No episodes in this season."
                },
                style = InfusionTvScaleCalibration.Type.Body,
                modifier = Modifier.tvBrowseNonRailInset(),
            )

            SectionState.Loading -> Text(
                "Loading episodes…",
                style = InfusionTvScaleCalibration.Type.Body,
                modifier = Modifier.tvBrowseNonRailInset(),
            )

            is SectionState.Failed -> Row(
                modifier = Modifier.tvBrowseNonRailInset(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {

                Text(sectionFailureCopy(MetadataSection.SEASONS), style = InfusionTvScaleCalibration.Type.Body)
                InfusionTvButton(
                    onClick = onRetry,
                    modifier = stableFocusModifier(
                        key = "canonical:episodes:retry",
                        requesters = requesters,
                        onFocusedKeyChanged = {},
                    ),
                ) { Text("Retry") }
            }

            is SectionState.Ready -> {

                val visibleRows = hideWatchedEpisodeRows(
                    rows = rows,
                    hideWatchedTitles = LocalContentCardPresentation.current.hideWatchedTitles,
                    watchedState = LocalCardWatchedState.current,
                    seriesTitleId = state.titleId,
                )
                if (visibleRows.isEmpty()) {
                    Text(
                        "No episodes in this season.",
                        style = InfusionTvScaleCalibration.Type.Body,
                        modifier = Modifier.tvBrowseNonRailInset(),
                    )
                } else {

                    CompositionLocalProvider(LocalTvRowKey provides DetailsStageTwoRegionKeys.EPISODES) {
                        val episodeItemKeys = visibleRows.map { it.focusKey }
                        val railLaw = rememberSharedRailLaw(episodeItemKeys)
                        CompositionLocalProvider(LocalBringIntoViewSpec provides railLaw.spec) {
                            LazyRow(
                                state = railLaw.listState,
                                modifier = rowModifier
                                    .fillMaxWidth()
                                    .testTag(DetailsEpisodeRailTag)
                                    .then(railLaw.modifier),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = tvRailContentPadding(),
                            ) {
                                items(visibleRows, key = { it.focusKey }) { row ->
                                EpisodeMetadataCard(
                                    row = row,
                                    requesters = requesters,

                                    onOpen = { onOpenEpisode(row) },
                                    onEpisodeFocused = { key -> onEpisodeFocused(row.episode.seasonNumber, key) },

                                    seriesTitleId = state.titleId,

                                    seriesLandscapeLocator = state.hero?.backdropLocator,
                                )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TvCanonicalSeasonStrip(
    state: CanonicalDetailsUiState,
    requesters: MutableMap<String, FocusRequester>,
    onSelectSeason: (Int) -> Unit,
) {
    if (state.seasons.isEmpty()) return

    LazyRow(
        modifier = Modifier.tvBrowseNonRailInset().testTag(DetailsSeasonStripTag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.seasons, key = { it.key }) { season ->

            val seasonRequest = rememberCardActionRequest(
                surface = dev.infusion.core.domain.watchlist.CardActionSurface.DETAILS,
                displayTitle = season.label,
                titleId = state.titleId,
                kind = dev.infusion.core.model.TitleKind.SEASON,
                watchKey = null,
                seasonNumber = season.seasonNumber,
            )
            InfusionTvFilterChip(
                selected = season.seasonNumber == state.selectedSeasonNumber,
                onClick = { onSelectSeason(season.seasonNumber) },

                contextMenuAvailable = seasonRequest != null,
                modifier = stableFocusModifier(
                    key = season.key,
                    requesters = requesters,
                    onFocusedKeyChanged = {},
                ).then(cardContextActionModifier(season.key, seasonRequest)),
            ) {
                Text(season.label, style = InfusionTvScaleCalibration.Type.Emphasis)
            }
        }
    }
}

@Composable
internal fun PersonInitialsBlock(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(personInitials(name), style = InfusionTvScaleCalibration.Type.Emphasis)
    }
}

internal fun personInitials(name: String): String =
    name.trim()
        .split(' ')
        .filter { it.isNotEmpty() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")

internal const val TrailerActionKey = "trailer"

@Composable
fun TvCanonicalTrailerAction(
    trailer: TrailerUi?,
    requesters: MutableMap<String, FocusRequester>,
    onTrailerClick: (TrailerUi) -> Unit,
) {
    TvCanonicalTrailerIconAction(
        trailer = trailer,
        requesters = requesters,
        onClick = { trailer?.let(onTrailerClick) },
    )
}

@Composable
internal fun TvCanonicalTrailerIconAction(
    trailer: TrailerUi?,
    requesters: MutableMap<String, FocusRequester>,
    onClick: () -> Unit,
) {
    if (trailer == null) return
    InfusionTvHeroIconAction(
        onClick = onClick,
        modifier = stableFocusModifier(
            key = TrailerActionKey,
            requesters = requesters,
            onFocusedKeyChanged = {},
        ),
        icon = Icons.Filled.PlayArrow,
    ) { Text(TvDetailsActionLabels.Trailer) }
}
