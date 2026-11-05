@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package dev.infusion.tv.ui.screens

import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.infusion.core.artworkimage.ArtworkImage
import dev.infusion.core.artworkimage.ArtworkRequest
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.feature.vertical.media.PersonCreditUi
import dev.infusion.feature.vertical.media.PersonDetailsStateHolder
import dev.infusion.feature.vertical.media.PersonDetailsUi
import dev.infusion.feature.vertical.media.SectionState
import dev.infusion.feature.vertical.media.personFailureCopy
import dev.infusion.tv.ui.focus.LocalTvZoneEntry
import dev.infusion.tv.ui.focus.RequestFocusOnKey
import dev.infusion.tv.ui.focus.artworkFocusAnchor
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.focus.rememberTvZoneEntry
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.presentation.LocalCardWatchedState
import dev.infusion.tv.ui.presentation.LocalContentCardPresentation
import dev.infusion.tv.ui.presentation.LocalTvRowKey
import dev.infusion.tv.ui.presentation.LocalTvRowNavigation
import dev.infusion.tv.ui.presentation.artworkTargetPx
import dev.infusion.tv.ui.presentation.hideWatchedPersonCredits
import dev.infusion.tv.ui.presentation.rememberSharedRailLaw
import dev.infusion.tv.ui.presentation.tvBrowseNonRailInset
import dev.infusion.tv.ui.presentation.tvRailContentPadding
import dev.infusion.tv.ui.presentation.tvRailLawPagePadding
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import kotlin.math.floor

private val ProfileWidth = 220.dp
private val ProfileHeight = ProfileWidth * 1.5f

private val PersonBodyGap = 8.dp

internal const val PersonBiographyMinLines = 10

internal fun personBiographyMaxLines(

    availableHeight: Dp,

    headerLineBoxes: List<Dp>,

    lineBox: Dp,

    gap: Dp,

    minimumLines: Int = PersonBiographyMinLines,
): Int {
    if (lineBox <= 0.dp) return minimumLines
    val consumed = headerLineBoxes.fold(0.dp) { total, box -> total + box } + gap * headerLineBoxes.size
    val textHeight = availableHeight - consumed

    if (textHeight <= 0.dp) return minimumLines
    val wholeLines = floor(textHeight.value / lineBox.value).toInt()
    return maxOf(wholeLines, minimumLines)
}

internal const val PersonBackKey = "person:back"

internal const val PersonArtworkFocusKey = "person:artwork"

internal const val PersonRetryKey = "person:retry"
internal const val PersonCreditsMoviesTitle = "Movies"
internal const val PersonCreditsShowsTitle = "TV Shows"

internal const val PersonCreditsMovieFocusPrefix = "person-credit:movie"
internal const val PersonCreditsShowFocusPrefix = "person-credit:show"

internal object PersonRegionKeys {

    const val ARTWORK = "person:region:artwork"

    const val MOVIES = "person:region:movies"

    const val SHOWS = "person:region:shows"
}

internal fun personRegionDeclaration(
    artworkKey: String?,
    visibleMovies: List<PersonCreditUi>,
    visibleShows: List<PersonCreditUi>,
): List<DetailsStageTwoRegion> = buildList {
    artworkKey?.let { add(DetailsStageTwoRegion(PersonRegionKeys.ARTWORK, listOf(it))) }
    creditRegion(PersonRegionKeys.MOVIES, PersonCreditsMovieFocusPrefix, visibleMovies)
        ?.let { add(it) }
    creditRegion(PersonRegionKeys.SHOWS, PersonCreditsShowFocusPrefix, visibleShows)
        ?.let { add(it) }
}

private fun creditRegion(
    regionKey: String,
    focusPrefix: String,
    credits: List<PersonCreditUi>,
): DetailsStageTwoRegion? {
    val keys = credits.mapNotNull { credit ->
        credit.card.key
            .takeIf { credit.card.isNavigable }
            ?.let { cardFocusKey(focusPrefix, it) }
    }
    return keys.takeIf { it.isNotEmpty() }?.let { DetailsStageTwoRegion(regionKey, it) }
}

internal fun personEntryFocusKey(
    state: SectionState<PersonDetailsUi>,

    focusRestoreKey: String? = null,

    declaredCreditKeys: Set<String>? = null,
): String? = when (state) {
    is SectionState.Failed -> PersonRetryKey
    is SectionState.Ready -> focusRestoreKey
        ?.takeIf { declaredCreditKeys == null || it in declaredCreditKeys }
        ?: PersonArtworkFocusKey

    SectionState.Loading -> focusRestoreKey
    SectionState.Absent -> null
}

internal fun personCreditFocusKey(credit: PersonCreditUi): String? =
    credit.card.key
        .takeIf { credit.card.isNavigable }
        ?.let { cardFocusKey(personCreditFocusPrefix(credit.mediaType), it) }

internal fun personCreditFocusPrefix(mediaType: MediaType): String =
    if (mediaType == MediaType.SHOW) PersonCreditsShowFocusPrefix else PersonCreditsMovieFocusPrefix

internal fun personFirstCreditFocusKey(person: PersonDetailsUi): String? =
    person.movieCredits.firstOrNull { it.card.isNavigable }
        ?.let { cardFocusKey(PersonCreditsMovieFocusPrefix, it.card.key) }
        ?: person.showCredits.firstOrNull { it.card.isNavigable }
            ?.let { cardFocusKey(PersonCreditsShowFocusPrefix, it.card.key) }

internal fun personCreditRailTag(focusPrefix: String): String = "person:rail:$focusPrefix"

private val PersonPageVerticalPadding = 48.dp

@Composable
fun TvPersonScreen(
    stateHolder: PersonDetailsStateHolder,
    onOpenCredit: (PersonCreditUi) -> Unit,
    onBack: () -> Unit,

    focusRestoreKey: String? = null,
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    val requesters = rememberFocusRequesters()

    val hideWatchedTitles = LocalContentCardPresentation.current.hideWatchedTitles
    val watchedState = LocalCardWatchedState.current
    val ready = (state as? SectionState.Ready)?.value
    val visibleMovies = remember(ready, hideWatchedTitles, watchedState) {
        hideWatchedPersonCredits(ready?.movieCredits.orEmpty(), hideWatchedTitles, watchedState)
    }
    val visibleShows = remember(ready, hideWatchedTitles, watchedState) {
        hideWatchedPersonCredits(ready?.showCredits.orEmpty(), hideWatchedTitles, watchedState)
    }

    val regions = remember(state, visibleMovies, visibleShows) {
        personRegionDeclaration(
            artworkKey = PersonArtworkFocusKey.takeIf { ready != null },
            visibleMovies = visibleMovies,
            visibleShows = visibleShows,
        )
    }

    val declaredCreditKeys = remember(regions) { regions.flatMap { it.itemKeys }.toSet() }
    RequestFocusOnKey(
        key = personEntryFocusKey(
            state = state,
            focusRestoreKey = focusRestoreKey,
            declaredCreditKeys = declaredCreditKeys,
        ),
        requesters = requesters,

        restoration = focusRestoreKey != null,
    )
    val hierarchy = rememberDetailsStageTwoHierarchy(
        regions = regions,

        episodeParentChipKeys = emptyMap(),
        requesters = requesters,
    )

    val zone = rememberTvZoneEntry(requesters)

    CompositionLocalProvider(
        LocalTvZoneEntry provides zone,
        LocalTvRowNavigation provides hierarchy,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())

                .padding(tvRailLawPagePadding(PersonPageVerticalPadding)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val current = state) {
                SectionState.Loading -> Text(
                    "Loading…",
                    style = InfusionTvScaleCalibration.Type.Body,
                    modifier = Modifier.tvBrowseNonRailInset(),
                )

                is SectionState.Failed -> Row(
                    modifier = Modifier.tvBrowseNonRailInset(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {

                    Text(personFailureCopy(current.reason), style = InfusionTvScaleCalibration.Type.Body)
                    InfusionTvButton(
                        onClick = { stateHolder.retry() },
                        modifier = stableFocusModifier(
                            key = PersonRetryKey,
                            requesters = requesters,
                            onFocusedKeyChanged = {},
                        ),
                    ) { Text("Retry") }
                }

                SectionState.Absent -> Text(
                    "No details for this person.",
                    style = InfusionTvScaleCalibration.Type.Body,
                    modifier = Modifier.tvBrowseNonRailInset(),
                )

                is SectionState.Ready -> PersonBody(
                    person = current.value,
                    visibleMovies = visibleMovies,
                    visibleShows = visibleShows,
                    requesters = requesters,
                    onOpenCredit = onOpenCredit,

                    restoreFocusKey = focusRestoreKey,
                )
            }
        }
    }
}

@Composable
private fun PersonBody(
    person: PersonDetailsUi,

    visibleMovies: List<PersonCreditUi>,

    visibleShows: List<PersonCreditUi>,
    requesters: MutableMap<String, FocusRequester>,
    onOpenCredit: (PersonCreditUi) -> Unit,

    restoreFocusKey: String? = null,
) {
    Row(
        modifier = Modifier.tvBrowseNonRailInset(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        val target = artworkTargetPx(DpSize(ProfileWidth, ProfileHeight))
        val imageModifier = Modifier
            .size(ProfileWidth, ProfileHeight)
            .clip(InfusionTvContour.ArtworkShape)
        val profileLocator = person.profileLocator

        Box(
            modifier = Modifier.artworkFocusAnchor(
                key = PersonArtworkFocusKey,
                requesters = requesters,
            ),
        ) {
            if (profileLocator != null) {
                ArtworkImage(
                    request = ArtworkRequest.of(profileLocator, ArtworkPolicy.POSTER, target),
                    contentDescription = person.name,
                    modifier = imageModifier,
                )
            } else {

                PersonInitialsBlock(person.name, imageModifier)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(PersonBodyGap)) {

            Text(person.name, style = InfusionTvScaleCalibration.Type.HeadlineNormal)
            person.knownForDepartment?.let {
                Text("Known for $it", style = InfusionTvScaleCalibration.Type.Body)
            }
            person.lifeLine?.let { Text(it, style = InfusionTvScaleCalibration.Type.Body) }
            person.biography?.let {

                val biographyMaxLines = with(LocalDensity.current) {
                    personBiographyMaxLines(
                        availableHeight = ProfileHeight,
                        headerLineBoxes = buildList {
                            add(InfusionTvScaleCalibration.Type.HeadlineNormal.lineHeight.toDp())
                            if (person.knownForDepartment != null) {
                                add(InfusionTvScaleCalibration.Type.Body.lineHeight.toDp())
                            }
                            if (person.lifeLine != null) {
                                add(InfusionTvScaleCalibration.Type.Body.lineHeight.toDp())
                            }
                        },
                        lineBox = InfusionTvScaleCalibration.Type.Title.lineHeight.toDp(),
                        gap = PersonBodyGap,
                    )
                }
                Text(
                    it,
                    style = InfusionTvScaleCalibration.Type.Title,
                    maxLines = biographyMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    CreditRow(
        title = PersonCreditsMoviesTitle,
        credits = visibleMovies,
        focusPrefix = PersonCreditsMovieFocusPrefix,
        regionKey = PersonRegionKeys.MOVIES,
        requesters = requesters,
        onOpenCredit = onOpenCredit,
        restoreFocusKey = restoreFocusKey,
    )
    CreditRow(
        title = PersonCreditsShowsTitle,
        credits = visibleShows,
        focusPrefix = PersonCreditsShowFocusPrefix,
        regionKey = PersonRegionKeys.SHOWS,
        requesters = requesters,
        onOpenCredit = onOpenCredit,
        restoreFocusKey = restoreFocusKey,
    )
    if (!person.hasCredits) {
        Text(
            "No credited titles.",
            style = InfusionTvScaleCalibration.Type.Body,
            modifier = Modifier.tvBrowseNonRailInset(),
        )
    }
}

@Composable
private fun CreditRow(
    title: String,

    credits: List<PersonCreditUi>,
    focusPrefix: String,

    regionKey: String,
    requesters: MutableMap<String, FocusRequester>,
    onOpenCredit: (PersonCreditUi) -> Unit,

    restoreFocusKey: String? = null,
) {
    if (credits.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = InfusionTvScaleCalibration.Type.Emphasis,
            modifier = Modifier.tvBrowseNonRailInset(),
        )
        CompositionLocalProvider(LocalTvRowKey provides regionKey) {

            val itemKeys = credits.mapNotNull { credit ->
                credit.card.key
                    .takeIf { credit.card.isNavigable }
                    ?.let { cardFocusKey(focusPrefix, it) }
            }
            val railLaw = rememberSharedRailLaw(itemKeys)

            val returnedCreditIndex = restoreFocusKey
                ?.takeIf { it.startsWith("$focusPrefix:") }
                ?.let { key -> itemKeys.indexOf(key) }
            LaunchedEffect(restoreFocusKey, returnedCreditIndex, railLaw.listState) {
                val index = returnedCreditIndex ?: return@LaunchedEffect
                if (index < 0) return@LaunchedEffect
                railLaw.listState.scrollToItem(index)
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides railLaw.spec) {
                LazyRow(
                    state = railLaw.listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(personCreditRailTag(focusPrefix))
                        .then(railLaw.modifier),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = tvRailContentPadding(vertical = 8.dp),
                ) {
                    items(credits, key = { credit -> "$focusPrefix:${credit.key}" }) { credit ->

                        DetailsSharedMediaCard(
                            card = credit.card,
                            focusPrefix = focusPrefix,
                            requesters = requesters,
                            onOpenCard = { onOpenCredit(credit) },
                            artworkLabel = credit.role,
                        )
                    }
                }
            }
        }
    }
}
