package dev.infusion.tv.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.infusion.core.domain.format.DurationFormat
import dev.infusion.core.domain.watchlist.CardAction
import dev.infusion.core.domain.watchlist.CardActionId
import dev.infusion.core.domain.watchlist.CardActionRequest
import dev.infusion.core.domain.watchlist.CardActionSurface
import dev.infusion.core.domain.watchlist.WatchlistKind
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.metadata.RatingAppearance
import dev.infusion.core.model.metadata.RatingMetric
import dev.infusion.feature.vertical.details.detailsRestartActionable
import dev.infusion.feature.vertical.media.RatingUi
import dev.infusion.feature.vertical.model.DetailsUiState
import dev.infusion.feature.vertical.model.EpisodeDetailsUiState
import dev.infusion.feature.vertical.model.PrimaryAction
import dev.infusion.feature.vertical.model.WatchedDisplay
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.presentation.CardWatchlistMembership
import dev.infusion.tv.ui.presentation.LocalCardActionOwner
import dev.infusion.tv.ui.presentation.LocalCardWatchlistMembership
import dev.infusion.tv.ui.presentation.LocalRatingAppearance
import dev.infusion.tv.ui.presentation.ratingMark
import dev.infusion.tv.ui.presentation.watchlistEntryKey
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvDetailsMetrics
import dev.infusion.tv.ui.theme.InfusionTvHeroContextualAction
import dev.infusion.tv.ui.theme.InfusionTvHeroIconAction
import dev.infusion.tv.ui.theme.InfusionTvHeroPrimaryAction
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import kotlinx.coroutines.launch

object TvDetailsActionLabels {

    const val Play = "Play"

    const val Next = "Next"

    const val Resume = "Resume"

    const val Restart = "Restart"

    const val Seen = "Seen"

    const val NotSeen = "Not Seen"

    const val Sources = "Sources"

    const val Trailer = "Trailer"

    const val Watchlist = "Watchlist"

    const val Back = "Back"

    val all: List<String> =
        listOf(Play, Next, Resume, Restart, Seen, NotSeen, Sources, Trailer, Watchlist, Back)
}

internal const val DetailsPrimaryActionKey = "primary"
internal const val DetailsRestartKey = "play-beginning"
internal const val DetailsSeenKey = "mark-watched"
internal const val DetailsNotSeenKey = "mark-unwatched"
internal const val DetailsSourcesKey = "choose-source"

internal const val DetailsWatchlistKey = "toggle-watchlist"

internal const val DetailsBackKey = "back"

internal const val EpisodeActionLineTag = "episode-details:actions"

internal const val SeriesNextActionKey = "next-unwatched"

internal const val SeriesActionLineTag = "series-details:stage1:actions"

internal const val DetailsActionLineTag = "details:stage1:actions"

internal const val DetailsRatingsRowTag = "details:stage1:ratings"

internal const val DetailsMetaRatingsLineTag = "details:stage1:meta-ratings"
internal const val DetailsMetaRatingsLineTextTag = "$DetailsMetaRatingsLineTag:text"

internal fun detailsRatingItemTag(index: Int): String = "$DetailsRatingsRowTag:$index"

enum class DetailsStageAction {

    PRIMARY,

    RESTART,

    SEEN,

    NOT_SEEN,

    SOURCES,

    TRAILER,

    WATCHLIST,

    BACK,
}

data class DetailsStageActionItem(
    val action: DetailsStageAction,
    val label: String,
    val key: String,
    val enabled: Boolean,
    val primary: Boolean,
    val secondaryLabel: String? = null,

    val watchlisted: Boolean = false,
)

enum class DetailsActionTier {

    PRIMARY,

    SECONDARY,

    CONTEXTUAL,
}

fun detailsActionTier(action: DetailsStageAction): DetailsActionTier = when (action) {
    DetailsStageAction.PRIMARY -> DetailsActionTier.PRIMARY

    DetailsStageAction.SEEN,
    DetailsStageAction.NOT_SEEN,
    DetailsStageAction.WATCHLIST,
    DetailsStageAction.TRAILER,
    -> DetailsActionTier.SECONDARY

    DetailsStageAction.RESTART,
    DetailsStageAction.SOURCES,
    DetailsStageAction.BACK,
    -> DetailsActionTier.CONTEXTUAL
}

internal fun detailsActionIconSize(tier: DetailsActionTier): Dp = when (tier) {
    DetailsActionTier.PRIMARY -> InfusionTvDetailsMetrics.PrimaryActionIconSize
    DetailsActionTier.SECONDARY -> InfusionTvDetailsMetrics.SecondaryActionIconSize
    DetailsActionTier.CONTEXTUAL -> InfusionTvDetailsMetrics.ContextualActionIconSize
}

internal fun detailsWatchedActionKey(state: DetailsUiState): String =
    detailsWatchedActionKey(state.watched)

internal fun detailsWatchedActionKey(watched: WatchedDisplay): String =
    if (watched is WatchedDisplay.Completed) DetailsNotSeenKey else DetailsSeenKey

fun detailsStageActions(
    state: DetailsUiState,
    hasTrailer: Boolean,

    watchlisted: Boolean = false,
): List<DetailsStageActionItem> {
    val resumable = state.primaryAction == PrimaryAction.RESUME
    val seen = state.watched is WatchedDisplay.Completed

    val resumeClock = state.resumePositionMs
        ?.takeIf { resumable && it > 0L }
        ?.let { DurationFormat.formatPositionClock(it) }
    return buildList {
        add(
            when (state.primaryAction) {
                PrimaryAction.RESUME -> DetailsStageActionItem(
                    action = DetailsStageAction.PRIMARY,
                    label = TvDetailsActionLabels.Resume,
                    key = DetailsPrimaryActionKey,
                    enabled = true,
                    primary = true,
                    secondaryLabel = resumeClock,
                )

                PrimaryAction.PLAY -> DetailsStageActionItem(
                    action = DetailsStageAction.PRIMARY,
                    label = TvDetailsActionLabels.Play,
                    key = DetailsPrimaryActionKey,
                    enabled = true,
                    primary = true,
                )

                PrimaryAction.UNPLAYABLE -> DetailsStageActionItem(
                    action = DetailsStageAction.PRIMARY,
                    label = TvDetailsActionLabels.Play,
                    key = DetailsPrimaryActionKey,
                    enabled = false,
                    primary = true,
                )
            },
        )
        if (resumable) {
            add(
                DetailsStageActionItem(
                    action = DetailsStageAction.RESTART,
                    label = TvDetailsActionLabels.Restart,
                    key = DetailsRestartKey,

                    enabled = detailsRestartActionable(state.primaryAction),
                    primary = false,
                ),
            )
        }
        add(
            if (seen) {
                DetailsStageActionItem(
                    action = DetailsStageAction.NOT_SEEN,
                    label = TvDetailsActionLabels.NotSeen,
                    key = DetailsNotSeenKey,
                    enabled = true,
                    primary = false,
                )
            } else {
                DetailsStageActionItem(
                    action = DetailsStageAction.SEEN,
                    label = TvDetailsActionLabels.Seen,
                    key = DetailsSeenKey,
                    enabled = true,
                    primary = false,
                )
            },
        )

        add(detailsWatchlistActionItem(watchlisted))
        add(
            DetailsStageActionItem(
                action = DetailsStageAction.SOURCES,
                label = TvDetailsActionLabels.Sources,
                key = DetailsSourcesKey,
                enabled = true,
                primary = false,
            ),
        )
        if (hasTrailer) {
            add(
                DetailsStageActionItem(
                    action = DetailsStageAction.TRAILER,
                    label = TvDetailsActionLabels.Trailer,
                    key = TrailerActionKey,
                    enabled = true,
                    primary = false,
                ),
            )
        }
    }
}

internal fun detailsWatchlistActionItem(watchlisted: Boolean): DetailsStageActionItem =
    DetailsStageActionItem(
        action = DetailsStageAction.WATCHLIST,
        label = TvDetailsActionLabels.Watchlist,
        key = DetailsWatchlistKey,
        enabled = true,
        primary = false,
        watchlisted = watchlisted,
    )

internal fun detailsWatchlistMember(
    membership: CardWatchlistMembership,
    kind: TitleKind?,
    titleId: TitleId?,
): Boolean {
    if (titleId == null) return false
    val entryKind = when (kind) {
        TitleKind.MOVIE -> WatchlistKind.MOVIE
        TitleKind.SERIES -> WatchlistKind.SHOW
        TitleKind.EPISODE, TitleKind.SEASON, TitleKind.CLIP, null -> return false
    }
    return membership.variantFor(watchlistEntryKey(entryKind, titleId.value.toString())) != null
}

fun seriesStageActions(
    hasContinuationEpisode: Boolean,
    hasTrailer: Boolean,

    nextEpisodeCode: String? = null,

    watchlisted: Boolean = false,
): List<DetailsStageActionItem> =
    buildList {
        if (hasContinuationEpisode) {
            add(
                DetailsStageActionItem(
                    action = DetailsStageAction.PRIMARY,
                    label = TvDetailsActionLabels.Next,
                    key = SeriesNextActionKey,
                    enabled = true,
                    primary = true,
                    secondaryLabel = nextEpisodeCode,
                ),
            )
        }

        add(detailsWatchlistActionItem(watchlisted))
        if (hasTrailer) {
            add(
                DetailsStageActionItem(
                    action = DetailsStageAction.TRAILER,
                    label = TvDetailsActionLabels.Trailer,
                    key = TrailerActionKey,
                    enabled = true,
                    primary = false,
                ),
            )
        }
    }

internal val SeriesActionLineKeys: Set<String> =
    seriesStageActions(hasContinuationEpisode = true, hasTrailer = true).map { it.key }.toSet()

internal fun episodeCoordinateCode(seasonNumber: Int, episodeNumber: Int): String =
    "S${seasonNumber}E${episodeNumber}"

fun episodeStageActions(state: EpisodeDetailsUiState): List<DetailsStageActionItem> = buildList {

    val resumeClock = state.resumePositionMs
        ?.takeIf { state.primaryAction == PrimaryAction.RESUME && it > 0L }
        ?.let { DurationFormat.formatPositionClock(it) }
    add(
        when (state.primaryAction) {
            PrimaryAction.RESUME -> DetailsStageActionItem(
                action = DetailsStageAction.PRIMARY,
                label = TvDetailsActionLabels.Resume,
                key = DetailsPrimaryActionKey,
                enabled = true,
                primary = true,
                secondaryLabel = resumeClock,
            )

            PrimaryAction.PLAY -> DetailsStageActionItem(
                action = DetailsStageAction.PRIMARY,
                label = TvDetailsActionLabels.Play,
                key = DetailsPrimaryActionKey,
                enabled = true,
                primary = true,
            )

            PrimaryAction.UNPLAYABLE -> DetailsStageActionItem(
                action = DetailsStageAction.PRIMARY,
                label = TvDetailsActionLabels.Play,
                key = DetailsPrimaryActionKey,
                enabled = false,
                primary = true,
            )
        },
    )

    add(
        if (detailsWatchedActionKey(state.watched) == DetailsNotSeenKey) {
            DetailsStageActionItem(
                action = DetailsStageAction.NOT_SEEN,
                label = TvDetailsActionLabels.NotSeen,
                key = DetailsNotSeenKey,
                enabled = true,
                primary = false,
            )
        } else {
            DetailsStageActionItem(
                action = DetailsStageAction.SEEN,
                label = TvDetailsActionLabels.Seen,
                key = DetailsSeenKey,
                enabled = true,
                primary = false,
            )
        },
    )
    add(
        DetailsStageActionItem(
            action = DetailsStageAction.RESTART,
            label = TvDetailsActionLabels.Restart,
            key = DetailsRestartKey,

            enabled = detailsRestartActionable(state.primaryAction),
            primary = false,
        ),
    )
    add(
        DetailsStageActionItem(
            action = DetailsStageAction.SOURCES,
            label = TvDetailsActionLabels.Sources,
            key = DetailsSourcesKey,
            enabled = true,
            primary = false,
        ),
    )
}

@Composable
fun DetailsStageOneActionLine(
    items: List<DetailsStageActionItem>,
    requesters: MutableMap<String, FocusRequester>,
    onPrimaryAction: () -> Unit,
    onRestart: () -> Unit,
    onSeen: () -> Unit,
    onNotSeen: () -> Unit,
    onSources: () -> Unit,
    onTrailer: () -> Unit,

    onWatchlist: (() -> Unit)? = null,

    onBack: (() -> Unit)? = null,

    onStageDown: (() -> Unit)? = null,

    lineTag: String = DetailsActionLineTag,
) {

    Row(
        modifier = Modifier
            .testTag(lineTag)
            .onPreviewKeyEvent { event ->
                if (
                    onStageDown != null &&
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionDown
                ) {
                    onStageDown.invoke()
                    true
                } else {
                    false
                }
            },
        horizontalArrangement = Arrangement.spacedBy(InfusionTvDetailsMetrics.ActionRowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val onClick = when (item.action) {
                DetailsStageAction.PRIMARY -> onPrimaryAction
                DetailsStageAction.RESTART -> onRestart
                DetailsStageAction.SEEN -> onSeen
                DetailsStageAction.NOT_SEEN -> onNotSeen
                DetailsStageAction.SOURCES -> onSources
                DetailsStageAction.TRAILER -> onTrailer

                DetailsStageAction.WATCHLIST -> onWatchlist ?: {}

                DetailsStageAction.BACK -> onBack ?: {}
            }

            val icon = when (item.action) {
                DetailsStageAction.PRIMARY, DetailsStageAction.TRAILER -> Icons.Filled.PlayArrow
                DetailsStageAction.RESTART -> Icons.Filled.Refresh
                DetailsStageAction.SEEN -> Icons.Filled.Check
                DetailsStageAction.NOT_SEEN -> Icons.Filled.Clear
                DetailsStageAction.SOURCES -> Icons.AutoMirrored.Filled.List
                DetailsStageAction.WATCHLIST ->
                    if (item.watchlisted) Icons.Filled.Clear else Icons.Filled.Add
                DetailsStageAction.BACK -> Icons.AutoMirrored.Filled.ArrowBack
            }
            val modifier = stableFocusModifier(
                key = item.key,
                requesters = requesters,
                onFocusedKeyChanged = {},
            ).heightIn(min = InfusionTvDetailsMetrics.ActionControlHeight)
            when (detailsActionTier(item.action)) {
                DetailsActionTier.PRIMARY -> InfusionTvHeroPrimaryAction(
                    onClick = onClick,
                    enabled = item.enabled,
                    icon = icon,
                    modifier = modifier,
                ) {
                    ActionControlLabel(item)
                }

                DetailsActionTier.SECONDARY -> InfusionTvHeroIconAction(
                    onClick = onClick,
                    enabled = item.enabled,
                    icon = icon,
                    modifier = modifier,
                ) {
                    ActionControlLabel(item)
                }

                DetailsActionTier.CONTEXTUAL -> InfusionTvHeroContextualAction(
                    onClick = onClick,
                    enabled = item.enabled,
                    icon = icon,
                    modifier = modifier,
                ) {
                    ActionControlLabel(item)
                }
            }
        }
    }
}

@Composable
fun rememberDetailsWatchlistAction(
    kind: TitleKind?,
    titleId: TitleId?,
    displayTitle: String,
): DetailsWatchlistAction {
    val membership = LocalCardWatchlistMembership.current
    val owner = LocalCardActionOwner.current
    val scope = rememberCoroutineScope()
    val member = detailsWatchlistMember(membership, kind, titleId)
    return DetailsWatchlistAction(
        watchlisted = member,
        onToggle = {
            if (owner != null) {

                val actionId = if (member) {
                    CardActionId.REMOVE_FROM_WATCHLIST
                } else {
                    CardActionId.ADD_TO_WATCHLIST
                }
                val request = CardActionRequest(
                    titleId = titleId,
                    kind = kind,
                    watchKey = null,
                    surface = CardActionSurface.DETAILS,
                    displayTitle = displayTitle,
                )
                scope.launch {
                    owner.perform(CardAction(id = actionId, label = TvDetailsActionLabels.Watchlist), request)
                }
            }
        },
    )
}

class DetailsWatchlistAction(
    val watchlisted: Boolean,
    val onToggle: () -> Unit,
)

@Composable
private fun ActionControlLabel(item: DetailsStageActionItem) {
    val second = item.secondaryLabel
    if (second == null) {
        Text(item.label)
        return
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ActionLabelLineGap),
    ) {
        Text(item.label, maxLines = 1)
        Text(
            second,
            style = InfusionTvScaleCalibration.Type.Body,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.testTag(ActionSecondaryLabelTag),
        )
    }
}

private val ActionLabelLineGap = 0.dp

internal const val ActionSecondaryLabelTag = "details:stage1:actions:secondary"

data class DetailsRatingItem(
    val providerLabel: String?,
    val value: String,

    val metricId: String = "",

    val fraction: Double = 0.0,
)

fun detailsRatingItems(ratings: List<RatingUi>): List<DetailsRatingItem> =
    ratings.map(::ratingItem)

fun ratingItem(rating: RatingUi): DetailsRatingItem = DetailsRatingItem(
    providerLabel = rating.label.ifBlank { null },
    value = rating.text,
    metricId = rating.metricId,
    fraction = rating.fraction,
)

fun legacyRatingItems(rating: String?): List<DetailsRatingItem> =
    rating?.takeIf { it.isNotBlank() }?.let { listOf(DetailsRatingItem(providerLabel = null, value = it)) }
        ?: emptyList()

internal const val RatingFactSeparator = "·"

internal val RatingFactGap = 6.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RatingFactsLine(
    items: List<DetailsRatingItem>,
    modifier: Modifier = Modifier,
    itemTag: (Int) -> String? = { null },
) {
    if (items.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(RatingFactGap),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier,
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                Text(
                    RatingFactSeparator,
                    style = InfusionTvScaleCalibration.Type.Body,
                    color = InfusionTvContour.ControlContentColor.copy(alpha = 0.55f),

                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            val tag = itemTag(index)
            RatingFactChip(
                item = item,
                modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
            )
        }
    }
}

@Composable
fun DetailsStageOneRatingsRow(items: List<DetailsRatingItem>) {
    RatingFactsLine(
        items = items,
        modifier = Modifier.testTag(DetailsRatingsRowTag),
        itemTag = ::detailsRatingItemTag,
    )
}

@Composable
fun RatingFactChip(
    item: DetailsRatingItem,
    modifier: Modifier = Modifier,
    appearanceOverride: RatingAppearance? = null,
) {
    val appearance = appearanceOverride ?: LocalRatingAppearance.current
    val mark = ratingMark(RatingMetric.fromId(item.metricId), item.fraction, appearance)

    val spokenName = item.providerLabel ?: RatingMetric.fromId(item.metricId)?.chipLabel
    val description = listOfNotNull(spokenName, item.value).joinToString(" ")
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RatingFactGap),
    ) {
        if (mark != null) {
            Image(
                painter = painterResource(id = mark.res),
                contentDescription = null,

                colorFilter = mark.tint?.let(ColorFilter::tint),
                modifier = Modifier.size(mark.size),
            )
            Text(item.value, style = InfusionTvScaleCalibration.Type.Body, color = InfusionTvContour.ControlContentColor)
        } else {

            Text(
                ratingFactText(item, appearance),
                style = InfusionTvScaleCalibration.Type.Body,
                color = InfusionTvContour.ControlContentColor,
            )
        }
    }
}

internal fun ratingFactText(item: DetailsRatingItem, appearance: RatingAppearance): String {
    val metric = RatingMetric.fromId(item.metricId)
    val name = when (appearance) {
        RatingAppearance.ABBREVIATED_TEXT -> metric?.abbreviatedLabel ?: item.providerLabel
        else -> metric?.chipLabel ?: item.providerLabel
    }
    return name?.let { "$it ${item.value}" } ?: item.value
}
