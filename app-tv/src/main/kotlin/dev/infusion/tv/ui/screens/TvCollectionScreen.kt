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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.infusion.core.artworkimage.ArtworkImage
import dev.infusion.core.artworkimage.ArtworkRequest
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.feature.vertical.media.CollectionDetailsStateHolder
import dev.infusion.feature.vertical.media.CollectionDetailsUi
import dev.infusion.feature.vertical.media.MediaCardUi
import dev.infusion.feature.vertical.media.SectionState
import dev.infusion.feature.vertical.media.collectionFailureCopy
import dev.infusion.tv.ui.focus.RequestFocusOnKey
import dev.infusion.tv.ui.focus.artworkFocusAnchor
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.presentation.LocalCardWatchedState
import dev.infusion.tv.ui.presentation.LocalContentCardPresentation
import dev.infusion.tv.ui.presentation.TvRowRules
import dev.infusion.tv.ui.presentation.anchoredRailBringIntoViewSpec
import dev.infusion.tv.ui.presentation.artworkTargetPx
import dev.infusion.tv.ui.presentation.hideWatchedMediaCards
import dev.infusion.tv.ui.presentation.tvBrowseNonRailInset
import dev.infusion.tv.ui.presentation.tvRailContentPadding
import dev.infusion.tv.ui.presentation.tvRailLawPagePadding
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration

private val CollectionPosterWidth = 180.dp
private val CollectionPosterHeight = CollectionPosterWidth * 1.5f

internal const val CollectionArtworkFocusKey = "collection:artwork"

internal const val CollectionBackKey = "collection-back"
internal const val CollectionRetryKey = "collection-surface:retry"

internal const val CollectionMemberFocusPrefix = "collection-member"

internal fun collectionEntryFocusKey(state: SectionState<CollectionDetailsUi>): String? = when (state) {
    is SectionState.Ready -> CollectionArtworkFocusKey
    is SectionState.Failed -> CollectionRetryKey
    SectionState.Loading, SectionState.Absent -> null
}

internal const val CollectionMembersRailTag: String = "collection:rail:members"

private val CollectionPageVerticalPadding = 48.dp

@Composable
fun TvCollectionScreen(
    stateHolder: CollectionDetailsStateHolder,
    onOpenCard: (MediaCardUi) -> Unit,
    onBack: () -> Unit,
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    val requesters = rememberFocusRequesters()

    RequestFocusOnKey(key = collectionEntryFocusKey(state), requesters = requesters)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())

            .padding(tvRailLawPagePadding(CollectionPageVerticalPadding)),
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

                Text(collectionFailureCopy(current.reason), style = InfusionTvScaleCalibration.Type.Body)
                InfusionTvButton(
                    onClick = { stateHolder.retry() },
                    modifier = stableFocusModifier(
                        key = CollectionRetryKey,
                        requesters = requesters,
                        onFocusedKeyChanged = {},
                    ),
                ) { Text("Retry") }
            }

            SectionState.Absent -> Text(
                collectionFailureCopy(dev.infusion.core.domain.metadata.MetadataFailureReason.NOT_FOUND),
                style = InfusionTvScaleCalibration.Type.Body,
                modifier = Modifier.tvBrowseNonRailInset(),
            )

            is SectionState.Ready -> CollectionBody(
                collection = current.value,
                requesters = requesters,
                onOpenCard = onOpenCard,
            )
        }
    }
}

@Composable
private fun CollectionBody(
    collection: CollectionDetailsUi,
    requesters: MutableMap<String, androidx.compose.ui.focus.FocusRequester>,
    onOpenCard: (MediaCardUi) -> Unit,
) {

    Row(
        modifier = Modifier.tvBrowseNonRailInset(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        val poster = collection.posterLocator

        Box(
            modifier = Modifier.artworkFocusAnchor(
                key = CollectionArtworkFocusKey,
                requesters = requesters,
            ),
        ) {
            if (poster != null) {
                ArtworkImage(
                    request = ArtworkRequest.of(
                        poster,
                        ArtworkPolicy.POSTER,
                        artworkTargetPx(DpSize(CollectionPosterWidth, CollectionPosterHeight)),
                    ),
                    contentDescription = collection.name,
                    modifier = Modifier
                        .size(CollectionPosterWidth, CollectionPosterHeight)
                        .clip(InfusionTvContour.ArtworkShape),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

            Text(collection.name, style = InfusionTvScaleCalibration.Type.HeadlineNormal)
            collection.overview?.let { Text(it, style = InfusionTvScaleCalibration.Type.Title) }
        }
    }

    if (collection.members.isEmpty()) {
        Text(
            "No titles in this collection.",
            style = InfusionTvScaleCalibration.Type.Body,
            modifier = Modifier.tvBrowseNonRailInset(),
        )
        return
    }

    val members = hideWatchedMediaCards(
        cards = collection.members,
        hideWatchedTitles = LocalContentCardPresentation.current.hideWatchedTitles,
        watchedState = LocalCardWatchedState.current,
    )
    if (members.isEmpty()) {
        Text(
            "No titles in this collection.",
            style = InfusionTvScaleCalibration.Type.Body,
            modifier = Modifier.tvBrowseNonRailInset(),
        )
        return
    }

    val membersRailState = rememberLazyListState()
    val density = LocalDensity.current
    val membersRailAnchorPx = with(density) { TvRowRules.AnchorInsetDp.toPx() }
    val membersRailSpec = remember(membersRailAnchorPx, membersRailState) {
        anchoredRailBringIntoViewSpec(
            anchorPx = { membersRailAnchorPx },
            canScrollBackward = { membersRailState.canScrollBackward },
            canScrollForward = { membersRailState.canScrollForward },
        )
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides membersRailSpec) {
        LazyRow(
            state = membersRailState,
            modifier = Modifier.fillMaxWidth().testTag(CollectionMembersRailTag),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = tvRailContentPadding(vertical = 8.dp),
        ) {
            items(members, key = { "collection-member:${it.key}" }) { card ->

                DetailsSharedMediaCard(
                    card = card,
                    focusPrefix = CollectionMemberFocusPrefix,
                    requesters = requesters,
                    onOpenCard = onOpenCard,
                    artworkLabel = if (collection.isCurrentTitle(card)) InThisCollectionLabel else null,
                )
            }
        }
    }
}
