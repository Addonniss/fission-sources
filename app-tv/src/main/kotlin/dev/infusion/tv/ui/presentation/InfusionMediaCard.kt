package dev.infusion.tv.ui.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.tv.material3.Card
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleKind
import dev.infusion.core.domain.watchlist.CardActionSurface
import dev.infusion.feature.vertical.artwork.SeriesLandscapeState
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.theme.infusionTvCardBorder
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.infusionTvCardColors
import dev.infusion.tv.ui.theme.infusionTvCardScale
import dev.infusion.tv.ui.theme.infusionTvCardShape

@Composable
fun InfusionMediaCard(
    onClick: () -> Unit,
    focusKey: String,
    kind: TitleKind?,
    preview: MediaPreview,
    requesters: MutableMap<String, FocusRequester>,
    onFocusedKeyChanged: (String) -> Unit,
    topLevelContentBoundary: Boolean = false,
    artworkTag: String? = null,
    focusable: Boolean = true,
    artworkLabel: String? = null,

    categoryTitle: String? = null,
    shapeOverride: ContentCardShape? = null,
    watchedMarkEligible: Boolean = true,

    contextSurface: CardActionSurface = CardActionSurface.BROWSE,

    contextMenuEnabled: Boolean = true,

    onCardFocusChanged: ((Boolean) -> Unit)? = null,
    artwork: @Composable BoxScope.() -> Unit,
) {

    val title = ContentCardTitle.visibleOrNull(preview.displayTitle)

    val isEpisodeCard = kind == TitleKind.EPISODE
    val policy = LocalContentCardPresentation.current

    val hideTitles = LocalContentCardPresentation.current.hideTitles

    val artworkSettlement = remember(preview) { CardArtworkSettlement() }
    val identityArtwork = if (hideTitles) policy.identityArtworkState(preview) else SeriesLandscapeState.PENDING
    val hidesBelowArtworkTitle = policy.hidesBelowArtworkTitle(
        kind = kind,
        identityArtwork = identityArtwork,
    )

    val ownArtwork = if (hideTitles) {
        policy.cardArtworkState(preview, artworkSettlement)
    } else {
        ContentCardArtworkState.PENDING
    }
    val titleOnArtwork: String? = title?.takeIf {
        policy.statesTitleOnArtwork(
            kind = kind,
            identityArtwork = identityArtwork,
            ownArtwork = ownArtwork,
        )
    }
    val hideEpisodeTitles = LocalContentCardPresentation.current.hideEpisodeTitles

    val identification = preview.episodeIdentification

    val hiddenBelowArtworkLabel: String? = if (isEpisodeCard) {
        if (hideEpisodeTitles) {
            identification?.let { episodeIdentificationLabel(it) } ?: title
        } else {
            null
        }
    } else {

        if (hidesBelowArtworkTitle && titleOnArtwork == null) title else null
    }

    val drawnCategoryTitle: String? = ContentCardTitle.visibleOrNull(categoryTitle)
    val artworkAccessibilityLabel: String? = listOfNotNull(
        hiddenBelowArtworkLabel,
        ContentCardTitle.visibleOrNull(artworkLabel),
        drawnCategoryTitle,
    ).takeIf { it.isNotEmpty() }?.joinToString(ArtworkAccessibilitySeparator)

    val shape = shapeOverride ?: policy.shapeFor(kind)

    val watchedUnit = if (watchedMarkEligible) preview.cardWatchedUnitOrNull() else null
    val markUnit = policy.watchedMarkUnit(
        preview = preview,
        watchedState = LocalCardWatchedState.current,
        markEligible = watchedMarkEligible,
    )

    val demand = LocalCardWatchedDemand.current
    val asksAboutUnit = watchedUnit != null && policy.cardBadges && policy.watchedCheckmark
    LaunchedEffect(demand, watchedUnit, asksAboutUnit) {
        if (asksAboutUnit && watchedUnit != null) demand.publish(watchedUnit)
    }

    val resumeDemand = LocalCardResumeDemand.current
    val progressUnit = preview.cardWatchedUnitOrNull().takeIf { preview.kind == TitleKind.MOVIE }
    val progressTitleId = progressUnit?.titleId
    val asksAboutProgress = progressUnit != null && policy.cardBadges && policy.progressBar
    val windowSize = LocalWindowInfo.current.containerSize
    var progressDemandOnScreen by remember { mutableStateOf(false) }

    val progressVisibilityModifier = Modifier.onGloballyPositioned { coordinates ->
        val onScreen = CardOnScreen.isVisible(coordinates.boundsInWindow(), windowSize)
        if (onScreen != progressDemandOnScreen) progressDemandOnScreen = onScreen
    }
    LaunchedEffect(resumeDemand, progressTitleId, asksAboutProgress, progressDemandOnScreen) {
        val titleId = progressTitleId ?: return@LaunchedEffect
        if (!asksAboutProgress) return@LaunchedEffect
        if (progressDemandOnScreen) resumeDemand.publish(titleId) else resumeDemand.withdraw(titleId)
    }

    DisposableEffect(resumeDemand, progressTitleId, asksAboutProgress) {
        val titleId = progressTitleId
        onDispose {
            if (asksAboutProgress && titleId != null) resumeDemand.withdraw(titleId)
        }
    }

    val membership = LocalCardWatchlistMembership.current
    val watchlistVariant = policy.watchlistBadge(
        preview = preview,
        kind = kind,
        membership = membership,
    )
    val jellyfinAvailable = policy.jellyfinAvailable(
        preview = preview,
        kind = kind,
        availability = LocalCardAvailability.current,
    )

    val genuineProgressFraction = preview.progressFraction
        ?: LocalCardProgress.current.fractionFor(preview.cardWatchedUnitOrNull())
    val progressFraction = genuineProgressFraction
        ?.toFloat()
        ?.takeIf { policy.cardBadges && policy.progressBar && it > 0f }

    var artworkSize by remember { mutableStateOf(IntSize.Zero) }

    val belowArtworkTextInset = Modifier.padding(
        start = InfusionTvContour.CardFocusWidth,
        end = InfusionTvContour.CardFocusWidth,
        bottom = ContentCardPresentation.ContainerPadding,
    )

    val contextRequest = rememberCardActionRequest(
        preview = preview,
        kind = kind,
        surface = contextSurface,
        enabled = contextMenuEnabled,
    )

    val contextModifier = cardContextActionModifier(focusKey, contextRequest)

    var cardFocused by remember { mutableStateOf(false) }

    val reportedCardFocus = rememberUpdatedState(onCardFocusChanged)

    val showContextMenuHint = contextMenuHintVisible(
        focused = cardFocused,
        menuAvailable = contextRequest != null,
        cardBadges = policy.cardBadges,
        contextMenuHint = policy.contextMenuBadge,
    )

    Card(
        onClick = onClick,
        modifier = Modifier
            .width(contentCardWidth(kind, shapeOverride))
            .then(
                stableFocusModifier(
                    key = focusKey,
                    requesters = requesters,
                    onFocusedKeyChanged = onFocusedKeyChanged,
                    topLevelContentBoundary = topLevelContentBoundary,
                ),
            )
            .then(
                if (focusable) {
                    Modifier
                } else {

                    Modifier.focusProperties { canFocus = false }
                },
            )
            .then(contextModifier)

            .onFocusChanged { state ->
                cardFocused = state.isFocused
                reportedCardFocus.value?.invoke(state.isFocused)
            }

            .then(progressVisibilityModifier),

        colors = infusionTvCardColors(),
        shape = infusionTvCardShape(),
        scale = infusionTvCardScale(),
        border = infusionTvCardBorder(),
    ) {

        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (artworkTag != null) Modifier.testTag(artworkTag) else Modifier)
                    .contentCardArtwork(kind, shapeOverride)

                    .onSizeChanged { artworkSize = it }

                    .contentCardArtworkClip()

                    .then(
                        if (artworkAccessibilityLabel != null) {
                            Modifier.semantics { contentDescription = artworkAccessibilityLabel }
                        } else {
                            Modifier
                        },
                    ),
            ) {

                CompositionLocalProvider(
                    LocalContentCardArtworkScale provides policy.artworkContentScaleForShape(shape),
                    LocalContentCardArtworkSource provides policy.artworkSourceFor(kind),

                    LocalContentCardArtworkSettlement provides artworkSettlement,
                ) {
                    artwork()
                }

                if (progressFraction != null) {
                    InfusionCardProgressBar(
                        artworkSize = artworkSize,
                        fraction = progressFraction,

                        tag = contentCardProgressBarKey(focusKey),

                        fillTag = contentCardProgressFillKey(focusKey),
                    )
                }
                if (markUnit != null) {
                    InfusionCardWatchedMark(
                        artworkSize = artworkSize,
                        tag = contentCardWatchedMarkKey(focusKey),
                    )
                }
                if (watchlistVariant != null) {
                    InfusionCardWatchlistBadge(
                        artworkSize = artworkSize,
                        variant = watchlistVariant,
                        providerId = membership.activeTracker,
                        tag = contentCardWatchlistBadgeKey(focusKey),
                    )
                }
                if (jellyfinAvailable) {
                    InfusionCardAvailabilityBadge(
                        artworkSize = artworkSize,
                        tag = contentCardAvailabilityBadgeKey(focusKey),
                    )
                }

                if (showContextMenuHint) {
                    InfusionCardContextMenuHint(
                        artworkSize = artworkSize,
                        tag = contentCardContextMenuHintKey(focusKey),
                    )
                }

                titleOnArtwork?.let { artworkTitle ->
                    InfusionCardArtworkTitle(
                        title = artworkTitle,
                        artworkSize = artworkSize,
                        tag = contentCardArtworkTitleKey(focusKey),
                    )
                }

                drawnCategoryTitle?.let { name ->
                    InfusionCardCategoryTitle(
                        title = name,
                        artworkSize = artworkSize,
                        tag = contentCardArtworkTitleKey(focusKey),
                        scrimTag = contentCardCategoryScrimKey(focusKey),
                    )
                }
            }

            if (isEpisodeCard) {
                if (!hideEpisodeTitles) {
                    Column(modifier = belowArtworkTextInset) {
                        Spacer(Modifier.height(ContentCardPresentation.ArtworkTitleSpacing))
                        if (identification != null) {
                            InfusionEpisodeCardIdentification(identification)
                        } else {
                            InfusionCardBelowArtwork(
                                title = title,
                                year = preview.year,
                                airDate = preview.airDate,
                            )
                        }
                    }
                }
            } else if (!hidesBelowArtworkTitle && titleOnArtwork == null && drawnCategoryTitle == null &&
                (title != null || preview.year != null || preview.airDate != null)
            ) {
                Column(modifier = belowArtworkTextInset) {
                    Spacer(Modifier.height(ContentCardPresentation.ArtworkTitleSpacing))
                    InfusionCardBelowArtwork(
                        title = title,
                        year = preview.year,
                        airDate = preview.airDate,
                    )
                }
            }
        }
    }
}

private const val ArtworkAccessibilitySeparator: String = " · "
