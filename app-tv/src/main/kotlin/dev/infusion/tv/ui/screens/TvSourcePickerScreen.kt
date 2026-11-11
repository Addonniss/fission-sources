package dev.infusion.tv.ui.screens

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.infusion.tv.ui.theme.InfusionControlState
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvDetailsMetrics
import dev.infusion.tv.ui.theme.InfusionTvMotion
import dev.infusion.tv.ui.theme.LocalInfusionAccent
import dev.infusion.tv.ui.theme.LocalInfusionTheme
import dev.infusion.tv.ui.theme.infusionTvCardScale
import dev.infusion.tv.ui.theme.infusionTvCardShape
import dev.infusion.tv.ui.theme.themeControlBackdrop
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardColors
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import dev.infusion.core.artworkimage.ArtworkImage
import dev.infusion.core.artworkimage.ArtworkRequest
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.feature.vertical.model.PickerResolution
import dev.infusion.feature.vertical.model.PickerRow
import dev.infusion.feature.vertical.model.PickerRowState
import dev.infusion.feature.vertical.model.PickerTab
import dev.infusion.feature.vertical.model.SelectionResult
import dev.infusion.feature.vertical.sourcepicker.SourceDiscoveryState
import dev.infusion.feature.vertical.sourcepicker.SourcePickerStateHolder
import dev.infusion.tv.ui.focus.LocalTvZoneEntry
import dev.infusion.tv.ui.focus.RequestFocusOnKey
import dev.infusion.tv.ui.focus.TvFilterStrip
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.focus.rememberTvZoneEntry
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.presentation.artworkTargetPx
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration

private val ScreenHorizontalPadding = 48.dp

private val ScreenVerticalPadding = 12.dp

private val SectionSpacing = 8.dp

private val RowSpacing = 6.dp

private val RowHorizontalPadding = 12.dp

private val RowVerticalPadding = 8.dp

private val PosterGap = 32.dp

private const val PosterAspectRatio = 2f / 3f

private const val RetryKey = "retry"

internal const val SourcePickerHeaderTag = "source-picker:header"

internal const val SourcePickerPosterTag = "source-picker:poster"

@Composable
fun TvSourcePickerScreen(
    stateHolder: SourcePickerStateHolder,
    onStarted: (PlayableCandidate) -> Unit,
    onCancel: () -> Unit,

    onExternalLink: (PlayableCandidate) -> Unit = {},

    externalLinkCandidate: PlayableCandidate? = null,
    onDismissExternalLink: () -> Unit = {},
    onOpenExternalLink: (PlayableCandidate) -> Unit = {},

    headerLogoLocator: String? = null,

    posterLocator: String? = null,
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    val requesters = rememberFocusRequesters()

    val zone = rememberTvZoneEntry(requesters)
    val resultsState = rememberLazyListState()

    CompositionLocalProvider(LocalTvZoneEntry provides zone) {

        val focusEntryKey =
            if (
                state.focusedKey == SourcePickerStateHolder.CANCEL_KEY &&
                state.resolution is PickerResolution.Resolved &&
                state.discovery == SourceDiscoveryState.EMPTY
            ) {
                RetryKey
            } else {
                state.focusedKey
            }
        RequestFocusOnKey(key = focusEntryKey, requesters = requesters)
        BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ScreenHorizontalPadding, vertical = ScreenVerticalPadding)

            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key != Key.Back && event.key != Key.Escape) return@onPreviewKeyEvent false
                when {

                    state.visibleRows.any { it.key == state.focusedKey } -> {
                        stateHolder.focusTabStrip()
                        true
                    }

                    state.tabs.any { it.key == state.focusedKey } -> {
                        onCancel()
                        true
                    }

                    else -> false
                }
            },
    ) {

        val pageWidth = maxWidth
        val pageHeight = maxHeight

        val posterWidth = pageHeight * PosterAspectRatio

        val leftRegionWidth =
            if (posterLocator == null) pageWidth else pageWidth - posterWidth - PosterGap
        Row(modifier = Modifier.fillMaxSize()) {
            Column(

                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(SectionSpacing),
            ) {

            TvCanonicalLogoHeader(
                logoLocator = headerLogoLocator,
                modifier = Modifier.testTag(SourcePickerHeaderTag),
                logoWidthFractionOfScreen = clearLogoFractionForLeftRegion(
                    leftRegionWidth = leftRegionWidth,
                    pageWidth = pageWidth,
                ),
            )
            state.autoplayCountdownMs?.let {
                Text("Starting in ${it / 1000 + 1}s…", style = InfusionTvScaleCalibration.Type.Body)
            }

            if (state.notices.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("picker-notices"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("No playable sources matched the provider filters", style = InfusionTvScaleCalibration.Type.Body)
                }
                state.notices.forEach { notice ->
                    Column(modifier = Modifier.padding(bottom = 4.dp)) {
                        Text(
                            "${notice.providerName}: ${notice.title}",
                            style = InfusionTvScaleCalibration.Type.Emphasis,
                            modifier = Modifier.testTag("picker-notice-${notice.providerName}"),
                        )
                        notice.body?.let {
                            Text(it, style = InfusionTvScaleCalibration.Type.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            when (val resolution = state.resolution) {
                is PickerResolution.Loading -> {

                    Text("Looking for sources…", style = InfusionTvScaleCalibration.Type.Body)

                    InfusionTvButton(
                        onClick = onCancel,
                        modifier = stableFocusModifier(
                            key = SourcePickerStateHolder.CANCEL_KEY,
                            requesters = requesters,
                            onFocusedKeyChanged = { stateHolder.onUserFocusMoved(it) },
                        ),
                    ) { Text("Cancel") }
                }
                is PickerResolution.Failed -> {
                    Text("Could not load sources: ${resolution.failure.message}", style = InfusionTvScaleCalibration.Type.Body)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        InfusionTvButton(
                            onClick = { stateHolder.retryResolution() },
                            modifier = stableFocusModifier(
                                key = RetryKey,
                                requesters = requesters,
                                onFocusedKeyChanged = { stateHolder.onUserFocusMoved(it) },
                            ),
                        ) { Text("Retry") }
                        InfusionTvButton(
                            onClick = onCancel,
                            modifier = stableFocusModifier(
                                key = SourcePickerStateHolder.CANCEL_KEY,
                                requesters = requesters,
                                onFocusedKeyChanged = { stateHolder.onUserFocusMoved(it) },
                            ),
                        ) { Text("Back") }
                    }
                }
                is PickerResolution.Resolved -> {

                    if (state.tabs.isNotEmpty()) {

                        TvFilterStrip(
                            items = state.tabs,
                            key = { it.key },
                            label = { tab ->

                                if (tab.enabled) "${tab.label} ${tab.count}" else tab.label
                            },
                            selectedKey = state.activeTabKey,
                            registry = requesters,
                            activateOnTraverse = true,
                            enabled = { it.enabled },
                            onActivate = { stateHolder.activateTab(it.key) },
                            onEnter = { stateHolder.enterResults() },
                            onFocusedChipChanged = { stateHolder.onUserFocusMoved(it) },

                            onExitUp = { true },
                            listTag = "picker-tabs",
                        )
                    }
                    val visibleRows = state.visibleRows

                    when (state.discovery) {
                        SourceDiscoveryState.SEARCHING -> {
                            Text("Looking for sources…", style = InfusionTvScaleCalibration.Type.Body)
                            InfusionTvButton(
                                onClick = onCancel,
                                modifier = stableFocusModifier(
                                    key = SourcePickerStateHolder.CANCEL_KEY,
                                    requesters = requesters,
                                    onFocusedKeyChanged = { stateHolder.onUserFocusMoved(it) },
                                ),
                            ) { Text("Cancel") }
                        }
                        SourceDiscoveryState.EMPTY -> {
                            Text("No sources available for this selection.", style = InfusionTvScaleCalibration.Type.Body)

                            InfusionTvButton(
                                onClick = { stateHolder.retryResolution() },
                                modifier = stableFocusModifier(
                                    key = RetryKey,
                                    requesters = requesters,
                                    onFocusedKeyChanged = { stateHolder.onUserFocusMoved(it) },
                                ),
                            ) { Text("Retry") }
                        }

                        else -> {

                            LaunchedEffect(zone.armedKey, visibleRows) {
                                val armed = zone.armedKey ?: return@LaunchedEffect
                                val index = visibleRows.indexOfFirst { it.key == armed }
                                if (index >= 0) resultsState.scrollToItem(index)
                            }
                            LazyColumn(
                                state = resultsState,
                                verticalArrangement = Arrangement.spacedBy(RowSpacing),
                                modifier = Modifier.testTag("picker-list"),
                            ) {
                                items(visibleRows, key = { it.key }) { row ->
                                    CandidateRow(
                                        row = row,
                                        isFirstRow = row.key == visibleRows.first().key,
                                        activeTabKey = state.activeTabKey,

                                        showProviderLine = state.activeTabKey == PickerTab.ALL_KEY,
                                        requesters = requesters,
                                        onFocus = { stateHolder.onUserFocusMoved(it) },
                                        onExitToTabs = { stateHolder.focusTabStrip() },
                                        onSelect = {
                                            when (val result = stateHolder.select(row.key)) {
                                                is SelectionResult.Started -> onStarted(result.candidate)
                                                is SelectionResult.ExternalLink -> onExternalLink(result.candidate)
                                                is SelectionResult.Blocked -> Unit
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            externalLinkCandidate?.let { external ->
                Card(onClick = {}) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("External link — open in browser?", style = InfusionTvScaleCalibration.Type.Emphasis)
                        Text(
                            "${external.providerName}: ${external.originalLabel}",
                            style = InfusionTvScaleCalibration.Type.Body,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            InfusionTvButton(
                                onClick = { onOpenExternalLink(external) },
                                modifier = stableFocusModifier(
                                    key = "open-external",
                                    requesters = requesters,
                                    onFocusedKeyChanged = { stateHolder.onUserFocusMoved(it) },
                                ),
                            ) { Text("Open in browser") }
                            InfusionTvButton(
                                onClick = onDismissExternalLink,
                                modifier = stableFocusModifier(
                                    key = "cancel-external",
                                    requesters = requesters,
                                    onFocusedKeyChanged = { stateHolder.onUserFocusMoved(it) },
                                ),
                            ) { Text("Cancel") }
                        }
                    }
                }
            }
            }

            if (posterLocator != null) {
                Spacer(modifier = Modifier.width(PosterGap))
                SourcePickerPoster(
                    locator = posterLocator,
                    modifier = Modifier
                        .width(posterWidth)
                        .fillMaxHeight(),
                )
            }
        }
    }
    }
}

@Composable
private fun SourcePickerPoster(locator: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier
            .testTag(SourcePickerPosterTag)
            .clip(InfusionTvContour.Shape),
    ) {
        val target = artworkTargetPx(DpSize(maxWidth, maxHeight))
        ArtworkImage(
            request = ArtworkRequest(
                locator = locator,
                policy = ArtworkPolicy.POSTER,
                targetWidthPx = target.width,
                targetHeightPx = target.height,
                retainsPreviousSuccess = false,
            ),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            arrivalFadeMillis = InfusionTvMotion.ARTWORK_FADE_MILLIS,
            contentScale = ContentScale.Fit,
        )
    }
}

private fun clearLogoFractionForLeftRegion(leftRegionWidth: Dp, pageWidth: Dp): Float {
    val accepted = InfusionTvScaleCalibration.ClearLogo.MaxScreenWidthFraction
    val regionMeasure = (leftRegionWidth + InfusionTvDetailsMetrics.PageHorizontalPadding * 2).value
    val pageMeasure = (pageWidth + InfusionTvDetailsMetrics.PageHorizontalPadding * 2).value
    if (regionMeasure <= 0f) return accepted
    return accepted * (pageMeasure / regionMeasure)
}

@Composable
private fun sourceRowCardColors(): CardColors {

    val tokens = LocalInfusionTheme.current
    if (tokens.premium) {
        return CardDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = tokens.textPrimary,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = tokens.onAccent,
            pressedContainerColor = Color.Transparent,
            pressedContentColor = tokens.onAccent,
        )
    }
    val focused = InfusionTvContour.treatment(InfusionControlState.FOCUSED, LocalInfusionAccent.current)
    return CardDefaults.colors(
        containerColor = Color.Transparent,
        contentColor = InfusionTvContour.CardContentColor,
        focusedContainerColor = focused.containerColor,
        focusedContentColor = focused.contentColor,
        pressedContainerColor = focused.containerColor,
        pressedContentColor = focused.contentColor,
    )
}

@Composable
private fun sourceRowCardBorder(): CardBorder = CardDefaults.border(
    border = Border.None,
    focusedBorder = Border.None,
    pressedBorder = Border.None,
)

@Composable
private fun sourceRowContentColor(focused: Boolean): Color {
    val tokens = LocalInfusionTheme.current
    if (tokens.premium) return if (focused) tokens.onAccent else tokens.textPrimary
    return if (focused) InfusionTvContour.FocusedContentColor else InfusionTvContour.CardContentColor
}

@Composable
private fun CandidateRow(
    row: PickerRow,
    isFirstRow: Boolean,
    activeTabKey: String,
    showProviderLine: Boolean,
    requesters: MutableMap<String, androidx.compose.ui.focus.FocusRequester>,
    onFocus: (String) -> Unit,
    onExitToTabs: () -> Unit,
    onSelect: () -> Unit,
) {

    val state = row.state
    val candidate = when (state) {
        is PickerRowState.Ready -> state.candidate
        is PickerRowState.Expired -> state.candidate
        is PickerRowState.Unsupported -> state.candidate
        is PickerRowState.Unauthorized -> state.candidate
        is PickerRowState.TimedOut -> state.candidate
        is PickerRowState.Failed -> state.candidate
        is PickerRowState.ExternalLink -> state.candidate
        PickerRowState.Loading -> null
    }

    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val contentColor = sourceRowContentColor(focused)
    Card(
        onClick = onSelect,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()

            .themeControlBackdrop(focused = focused)

            .semantics {
                if (candidate != null) {
                    val stateLabel = when (state) {
                        is PickerRowState.Expired -> ", expired"
                        is PickerRowState.Unsupported -> ", not compatible"
                        is PickerRowState.Unauthorized -> ", authorization required"
                        is PickerRowState.TimedOut -> ", timed out"
                        is PickerRowState.Failed -> ", failed"
                        is PickerRowState.ExternalLink -> ", opens in a browser"
                        else -> ""
                    }
                    contentDescription = "${row.providerInstanceLabel ?: candidate.providerName}, ${candidate.originalLabel}$stateLabel"
                }
            }
            .then(
                stableFocusModifier(
                    key = row.key,
                    requesters = requesters,
                    onFocusedKeyChanged = onFocus,
                ),
            )

            .focusProperties {
                if (isFirstRow) {
                    requesters[activeTabKey]?.let { requester -> up = requester }
                }
            }
            .onPreviewKeyEvent { event ->
                if (isFirstRow && event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                    onExitToTabs()
                    true
                } else {
                    false
                }
            },

        colors = sourceRowCardColors(),
        shape = infusionTvCardShape(),
        scale = infusionTvCardScale(),
        border = sourceRowCardBorder(),
    ) {
        Column(modifier = Modifier.padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding)) {
            if (candidate != null) {

                if (showProviderLine) {
                    Text(
                        row.providerInstanceLabel ?: candidate.providerName,
                        style = InfusionTvScaleCalibration.Type.SourceRow,
                        maxLines = 1,
                        color = contentColor,
                    )
                }

                Text(
                    candidate.originalLabel,
                    style = InfusionTvScaleCalibration.Type.SourceRow,
                    color = contentColor,
                )

                candidate.providerDescription?.let { description ->
                    Text(
                        description,
                        style = InfusionTvScaleCalibration.Type.SourceRow,
                        color = contentColor,
                    )
                }
            }
            when (state) {
                is PickerRowState.Expired -> Text("Expired — refresh to retry", style = InfusionTvScaleCalibration.Type.SourceRow, color = contentColor)
                is PickerRowState.Unsupported -> Text("Not compatible: ${state.reason}", style = InfusionTvScaleCalibration.Type.SourceRow, color = contentColor)
                is PickerRowState.Unauthorized -> Text("Authorization required", style = InfusionTvScaleCalibration.Type.SourceRow, color = contentColor)
                is PickerRowState.TimedOut -> Text("Timed out", style = InfusionTvScaleCalibration.Type.SourceRow, color = contentColor)
                is PickerRowState.Failed -> Text("Failed: ${state.failure.message}", style = InfusionTvScaleCalibration.Type.SourceRow, color = contentColor)
                is PickerRowState.ExternalLink -> Text("External link — opens in a browser", style = InfusionTvScaleCalibration.Type.SourceRow, color = contentColor)
                PickerRowState.Loading -> Text("Loading…", style = InfusionTvScaleCalibration.Type.SourceRow, color = contentColor)
                else -> Unit
            }
        }
    }
}
