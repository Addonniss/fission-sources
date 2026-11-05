package dev.infusion.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import dev.infusion.core.domain.search.SearchHistoryLimits
import dev.infusion.core.domain.search.SearchHistoryQueries
import dev.infusion.core.model.metadata.PersonId
import dev.infusion.feature.vertical.media.PersonCardUi
import dev.infusion.feature.vertical.search.AddonSearchStateHolder
import dev.infusion.feature.vertical.search.AddonSearchUiState
import dev.infusion.source.aggregation.search.SearchDestination
import dev.infusion.source.aggregation.search.SearchGroup
import dev.infusion.source.aggregation.search.SearchSection
import dev.infusion.source.aggregation.search.SearchSectionOrigin
import dev.infusion.tv.ui.focus.FocusKeyRegistry
import dev.infusion.tv.ui.focus.LocalTopLevelContentUpRequester
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.presentation.InfusionContentRow
import dev.infusion.tv.ui.presentation.LocalContentCardPresentation
import dev.infusion.tv.ui.presentation.TvBrowseColumn
import dev.infusion.tv.ui.presentation.presentableSearchSections
import dev.infusion.tv.ui.presentation.tvBrowseNonRailInset
import dev.infusion.tv.ui.shell.InfusionTvShell
import dev.infusion.tv.ui.theme.InfusionControlState
import dev.infusion.tv.ui.theme.InfusionControlTreatment
import dev.infusion.tv.ui.theme.InfusionTvAppearance
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.InfusionTvStateTextField
import dev.infusion.tv.ui.theme.LocalInfusionAccent
import dev.infusion.tv.ui.theme.infusionTvButtonColors

@Composable
fun TvAddonSearchScreen(
    stateHolder: AddonSearchStateHolder,
    jellyfinConnected: Boolean,
    model: TvTopNavModel,
    onNavigate: (TopLevelDestination) -> Unit,

    onOpenDestination: (SearchDestination) -> Unit,

    onOpenPerson: (PersonId) -> Unit,
    onExit: () -> Unit,
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    val requesters = rememberFocusRequesters()
    val chrome = rememberTopLevelPageChrome(TopLevelDestination.SEARCH, model)
    val onContentKeyFocused: (String) -> Unit = { chrome.onContentKeyFocused(it) }

    var overlay by remember { mutableStateOf(SearchOverlay.None) }

    LaunchedEffect(Unit) { stateHolder.loadHistory() }

    val presentation = LocalContentCardPresentation.current
    val sections = presentableSearchSections(state.sections, presentation)
    val people = state.people

    val browseRows = buildList {
        if (state.searching) {
            add(SearchBrowseRow(key = "search:status:searching", itemKeys = { emptyList() }) {
                Text(
                    "Searching…",
                    style = InfusionTvScaleCalibration.Type.Body,
                    modifier = Modifier.tvBrowseNonRailInset(),
                )
            })
        }

        if (state.degraded) {
            add(SearchBrowseRow(key = "search:status:degraded", itemKeys = { emptyList() }) {
                Text(
                    degradationNote(state),
                    style = InfusionTvScaleCalibration.Type.Body,
                    modifier = Modifier.tvBrowseNonRailInset(),
                )
            })
        }
        if (state.peopleFailed) {
            add(SearchBrowseRow(key = "search:status:people-degraded", itemKeys = { emptyList() }) {
                Text(
                    "People results are unavailable right now.",
                    style = InfusionTvScaleCalibration.Type.Body,
                    modifier = Modifier.tvBrowseNonRailInset(),
                )
            })
        }

        sections.filter { it.origin is SearchSectionOrigin.Canonical }.forEach { section ->
            add(sectionRow(section, requesters, onOpenDestination, onContentKeyFocused))
        }
        if (people.isNotEmpty()) {
            add(
                SearchBrowseRow(
                    key = SEARCH_PEOPLE_ROW_KEY,
                    itemKeys = { people.mapNotNull { it.activationKey } },
                ) {
                    SearchPeopleRow(
                        people = people,
                        requesters = requesters,
                        onOpenPerson = onOpenPerson,
                        onContentKeyFocused = onContentKeyFocused,
                    )
                },
            )
        }
        sections.filterNot { it.origin is SearchSectionOrigin.Canonical }.forEach { section ->
            add(sectionRow(section, requesters, onOpenDestination, onContentKeyFocused))
        }

        if (state.unidentified.isNotEmpty()) {
            add(
                SearchBrowseRow(
                    key = "search:unidentified",
                    itemKeys = { state.unidentified.map { unidentifiedSearchResultTag(it.key) } },
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().tvBrowseNonRailInset(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        state.unidentified.forEach { group ->
                            Text(
                                unidentifiedSearchResultLabel(group),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(unidentifiedSearchResultTag(group.key)),
                                style = InfusionTvScaleCalibration.Type.Body,
                            )
                        }
                    }
                },
            )
        }

        if (state.searched && !state.searching && !state.degraded && !state.peopleFailed &&
            sections.isEmpty() && people.isEmpty() && state.unidentified.isEmpty()
        ) {
            add(SearchBrowseRow(key = "search:empty", itemKeys = { emptyList() }) {
                Text(
                    "No results.",
                    style = InfusionTvScaleCalibration.Type.Body,
                    modifier = Modifier.tvBrowseNonRailInset(),
                )
            })
        }
    }

    val firstActionableRow = browseRows.firstOrNull { it.itemKeys().isNotEmpty() }
    val firstResultKey = firstActionableRow?.itemKeys()?.firstOrNull()

    val firstRowKeys = firstActionableRow?.itemKeys().orEmpty()

    var confirmedTerm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(confirmedTerm, firstResultKey, state.searching, state.term) {
        val term = confirmedTerm ?: return@LaunchedEffect
        if (state.term.trim() != term.trim() || chrome.focusedKey != SEARCH_FIELD_KEY) {
            confirmedTerm = null
            return@LaunchedEffect
        }
        if (state.searching) return@LaunchedEffect
        val target = firstResultKey ?: return@LaunchedEffect
        confirmedTerm = null
        requesters.request(target)
    }

    InfusionTvShell(
        page = TopLevelDestination.SEARCH,
        jellyfinConnected = jellyfinConnected,
        model = model,
        requesters = requesters,
        chrome = chrome,

        entryDefaultKey = SEARCH_HISTORY_KEY,
        entryFreshKey = firstResultKey,
        onNavigateTo = onNavigate,
        onExit = onExit,
    ) {
        TvBrowseColumn(
            rows = browseRows,
            rowKey = { it.key },
            rowItemKeys = { it.itemKeys() },
            modifier = Modifier
                .fillMaxSize()

                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (event.key != Key.DirectionUp) return@onPreviewKeyEvent false
                    val focused = chrome.focusedKey ?: return@onPreviewKeyEvent false
                    if (focused !in firstRowKeys) return@onPreviewKeyEvent false
                    requesters.request(SEARCH_HISTORY_KEY)
                    true
                },
            listTag = SEARCH_RESULTS_LIST_TAG,

            sharedRowRules = true,
            centredVerticalFraming = true,

            header = {
                SearchInputRow(
                    state = state,
                    stateHolder = stateHolder,
                    requesters = requesters,
                    onContentKeyFocused = onContentKeyFocused,
                    firstResultKey = firstResultKey,
                    onConfirmed = { confirmedTerm = it },
                    onOpenHistory = { overlay = SearchOverlay.History },
                    onRequestClearHistory = { overlay = SearchOverlay.ClearHistory },
                )
            },
        ) { row -> row.content() }
    }

    if (overlay == SearchOverlay.History) {
        SearchHistoryOverlay(
            history = { state.history },
            onDismiss = {
                overlay = SearchOverlay.None
                requesters.request(SEARCH_HISTORY_KEY)
            },
            onSelect = { query ->
                overlay = SearchOverlay.None
                stateHolder.submitQuery(query)
                requesters.request(SEARCH_HISTORY_KEY)
            },
        )
    }

    if (overlay == SearchOverlay.ClearHistory) {
        ClearSearchHistoryConfirmation(
            onCancel = {
                overlay = SearchOverlay.None
                requesters.request(SEARCH_CLEAR_HISTORY_KEY)
            },
            onConfirm = {
                overlay = SearchOverlay.None
                stateHolder.clearHistory()

                requesters.request(SEARCH_HISTORY_KEY)
            },
        )
    }
}

private enum class SearchOverlay { None, History, ClearHistory }

internal const val SEARCH_FIELD_KEY = "search-field"

internal const val SEARCH_HISTORY_KEY = "search-history"

internal const val SEARCH_CLEAR_HISTORY_KEY = "search-clear-history"

internal const val SEARCH_RESULTS_LIST_TAG = "search-results"

internal const val SEARCH_PEOPLE_ROW_KEY = "search:people"

internal const val SEARCH_HISTORY_OVERLAY_TAG = "search-history-overlay"

internal const val SEARCH_CLEAR_CONFIRM_TAG = "search-clear-confirm"

internal const val SEARCH_CLEAR_CONFIRM_ROOT_TAG = "search-clear-confirm-root"

internal const val SEARCH_HISTORY_EMPTY_KEY = "search-history-empty"

internal const val SEARCH_HISTORY_EMPTY_TAG = "search-history-empty"

internal fun searchHistoryEntryKey(index: Int): String = "search-history-entry:$index"

internal const val SEARCH_HISTORY_LIST_TAG = "search-history-list"

internal const val SEARCH_HISTORY_UP_CHEVRON_TAG = "search-history-chevron-up"

internal const val SEARCH_HISTORY_DOWN_CHEVRON_TAG = "search-history-chevron-down"

private class SearchBrowseRow(
    val key: String,
    val itemKeys: () -> List<String>,
    val content: @Composable () -> Unit,
)

private fun sectionRow(
    section: SearchSection,
    requesters: FocusKeyRegistry,
    onOpenDestination: (SearchDestination) -> Unit,
    onContentKeyFocused: (String) -> Unit,
): SearchBrowseRow = SearchBrowseRow(
    key = section.key,
    itemKeys = { section.items.map { it.key } },
) {
    SearchResultSectionRow(
        section = section,
        requesters = requesters,
        onOpenDestination = onOpenDestination,
        onContentKeyFocused = onContentKeyFocused,
    )
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun SearchInputRow(
    state: AddonSearchUiState,
    stateHolder: AddonSearchStateHolder,
    requesters: FocusKeyRegistry,
    onContentKeyFocused: (String) -> Unit,
    firstResultKey: String?,
    onConfirmed: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onRequestClearHistory: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val topShelfRequester = LocalTopLevelContentUpRequester.current
    val fieldRequester = remember { FocusRequester() }
    val historyRequester = remember { FocusRequester() }
    val clearRequester = remember { FocusRequester() }
    val textState = rememberTextFieldState(state.term)
    LaunchedEffect(state.term) {
        if (textState.text.toString() != state.term) {
            textState.setTextAndPlaceCursorAtEnd(state.term)
        }
    }
    LaunchedEffect(textState) {
        snapshotFlow { textState.text.toString() }.collect { stateHolder.onTermChanged(it) }
    }

    BackHandler(enabled = imeVisible) {
        keyboardController?.hide()
        requesters.request(SEARCH_FIELD_KEY)
    }
    val clearEnabled = state.history.isNotEmpty()
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 48.dp,
                end = 48.dp,

                top = 0.dp,
                bottom = 8.dp,
            ),
    ) {
        InfusionTvStateTextField(
            state = textState,

            textStyle = InfusionTvScaleCalibration.Type.Emphasis,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            onKeyboardAction = { _ ->

                val confirmed = textState.text.toString()
                stateHolder.submit()
                onConfirmed(confirmed)
                keyboardController?.hide()
            },
            modifier = Modifier

                .weight(1f)
                .focusRequester(fieldRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {

                        imeVisible -> false

                        event.key == Key.DirectionDown && firstResultKey != null -> {
                            requesters.request(firstResultKey)
                            true
                        }

                        event.key == Key.DirectionRight -> {
                            requesters.request(SEARCH_HISTORY_KEY)
                            true
                        }

                        event.key == Key.DirectionUp && topShelfRequester != null -> {
                            topShelfRequester.requestFocus()
                            true
                        }
                        else -> false
                    }
                }
                .then(
                    stableFocusModifier(
                        SEARCH_FIELD_KEY,
                        requesters,
                        onContentKeyFocused,
                        topLevelContentBoundary = true,
                    ),
                ),
        )
        InfusionTvButton(
            onClick = onOpenHistory,
            colors = infusionTvButtonColors(),
            modifier = Modifier
                .focusRequester(historyRequester)
                .focusProperties {
                    left = fieldRequester

                    right = if (clearEnabled) clearRequester else FocusRequester.Cancel
                }

                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                    val target = firstResultKey ?: return@onPreviewKeyEvent false
                    requesters.request(target)
                    true
                }
                .then(
                    stableFocusModifier(
                        SEARCH_HISTORY_KEY,
                        requesters,
                        onContentKeyFocused,
                        topLevelContentBoundary = true,
                    ),
                ),
        ) {
            Text("Search History")
        }
        InfusionTvButton(
            onClick = onRequestClearHistory,
            enabled = clearEnabled,
            colors = infusionTvButtonColors(),
            modifier = Modifier
                .focusRequester(clearRequester)
                .focusProperties { left = historyRequester }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                    val target = firstResultKey ?: return@onPreviewKeyEvent false
                    requesters.request(target)
                    true
                }
                .then(
                    stableFocusModifier(
                        SEARCH_CLEAR_HISTORY_KEY,
                        requesters,
                        onContentKeyFocused,
                        topLevelContentBoundary = true,
                    ),
                ),
        ) {
            Text("Clear History")
        }
    }

}

@Composable
private fun SearchPeopleRow(
    people: List<PersonCardUi>,
    requesters: FocusKeyRegistry,
    onOpenPerson: (PersonId) -> Unit,
    onContentKeyFocused: (String) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag(SEARCH_PEOPLE_ROW_KEY),
    ) {
        Text(
            "People",
            style = InfusionTvScaleCalibration.Type.Emphasis,
            modifier = Modifier.tvBrowseNonRailInset(),
        )
        InfusionContentRow(
            items = people,
            key = { person -> person.activationKey ?: person.key },
        ) { person ->
            PersonCard(
                person = person,
                requesters = requesters,
                onOpenPerson = { card ->
                    card.personId?.let(onOpenPerson)
                },
                onFocusedKeyChanged = onContentKeyFocused,
            )
        }
    }
}

@Composable
private fun SearchHistoryOverlay(
    history: () -> List<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val entries = history()
    val listState = rememberLazyListState()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }

    val labelStyle = InfusionTvScaleCalibration.Type.Emphasis
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val panelWidth = with(density) {
        measurer.measure(
            text = "W".repeat(SearchHistoryLimits.LABEL_CHARS),
            style = labelStyle,
        ).size.width.toDp() + SEARCH_HISTORY_ROW_PADDING * 2 + SEARCH_HISTORY_PANEL_PADDING * 2
    }
    BackHandler(enabled = true, onBack = onDismiss)

    LaunchedEffect(entries) {
        if (entries.isEmpty()) return@LaunchedEffect
        repeat(30) {
            withFrameNanos { }
            if (requesters[searchHistoryEntryKey(0)]?.requestFocus() == true) return@LaunchedEffect
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .testTag(SEARCH_HISTORY_OVERLAY_TAG)
                .width(panelWidth)
                .padding(SEARCH_HISTORY_PANEL_PADDING),
            shape = InfusionTvContour.Shape,

            colors = SurfaceDefaults.colors(containerColor = InfusionTvAppearance.OledBlack),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {

                if (entries.isEmpty()) {

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(SEARCH_HISTORY_EMPTY_HEIGHT)
                            .focusRequester(remember { FocusRequester() })
                            .focusable()
                            .then(
                                stableFocusModifier(
                                    SEARCH_HISTORY_EMPTY_KEY,
                                    requesters,
                                    {},
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No recent searches",
                            style = InfusionTvScaleCalibration.Type.Body,
                            modifier = Modifier.testTag(SEARCH_HISTORY_EMPTY_TAG),
                        )
                    }
                } else {

                    Text(
                        if (listState.firstVisibleItemIndex > 0) "▲" else "",
                        style = InfusionTvScaleCalibration.Type.Body,
                        modifier = Modifier.testTag(SEARCH_HISTORY_UP_CHEVRON_TAG),
                    )
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(SEARCH_HISTORY_ROW_HEIGHT * SEARCH_HISTORY_VISIBLE_ENTRIES)
                            .testTag(SEARCH_HISTORY_LIST_TAG),
                    ) {
                        itemsIndexed(entries, key = { index, _ -> index }) { index, query ->
                            val key = searchHistoryEntryKey(index)

                            var entryFocused by remember(key) { mutableStateOf(false) }
                            val treatment = searchHistoryEntryTreatment(entryFocused)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(SEARCH_HISTORY_ROW_HEIGHT)

                                    .clip(InfusionTvContour.Shape)
                                    .background(treatment.containerColor)
                                    .then(
                                        stableFocusModifier(
                                            key,
                                            requesters,
                                            {},
                                        ),
                                    )

                                    .onFocusChanged { entryFocused = it.isFocused }

                                    .focusable()
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) {
                                            return@onPreviewKeyEvent false
                                        }
                                        if (event.key == Key.DirectionCenter) {
                                            onSelect(query)
                                            true
                                        } else {
                                            false
                                        }
                                    },
                            ) {
                                Text(

                                    SearchHistoryQueries.displayLabel(query),
                                    style = InfusionTvScaleCalibration.Type.Emphasis,

                                    color = treatment.contentColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = SEARCH_HISTORY_ROW_PADDING),
                                )
                            }
                        }
                    }

                    Text(
                        if (listState.canScrollForward) "▼" else "",
                        style = InfusionTvScaleCalibration.Type.Body,
                        modifier = Modifier.testTag(SEARCH_HISTORY_DOWN_CHEVRON_TAG),
                    )
                }
            }
        }
    }
}

@Composable
private fun ClearSearchHistoryConfirmation(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    LaunchedEffect(Unit) {
        repeat(30) {
            withFrameNanos { }
            if (requesters[SEARCH_CLEAR_CANCEL_KEY]?.requestFocus() == true) return@LaunchedEffect
        }
    }
    BackHandler(enabled = true, onBack = onCancel)
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {

        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(SEARCH_CLEAR_CONFIRM_ROOT_TAG),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .testTag(SEARCH_CLEAR_CONFIRM_TAG)

                    .wrapContentWidth()
                    .widthIn(min = ExitDialogMinWidth, max = ExitDialogMaxWidth),
                shape = InfusionTvContour.Shape,

                colors = SurfaceDefaults.colors(containerColor = InfusionTvAppearance.OledBlack),
            ) {
                Column(
                    modifier = Modifier
                        .wrapContentWidth()
                        .padding(
                            horizontal = ExitDialogHorizontalPadding,
                            vertical = ExitDialogVerticalPadding,
                        ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        "Clear search history?",
                        style = InfusionTvScaleCalibration.Type.Headline,
                    )
                    Text(
                        "Only this profile's recent searches are removed.",
                        style = InfusionTvScaleCalibration.Type.Body,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        InfusionTvButton(
                            onClick = onCancel,
                            colors = infusionTvButtonColors(),
                            modifier = Modifier
                                .focusRequester(requesters.getOrPut(SEARCH_CLEAR_CANCEL_KEY) {
                                    FocusRequester()
                                })
                                .then(
                                    stableFocusModifier(
                                        SEARCH_CLEAR_CANCEL_KEY,
                                        requesters,
                                        {},
                                    ),
                                ),
                        ) {
                            Text("Cancel")
                        }
                        InfusionTvButton(
                            onClick = onConfirm,
                            colors = infusionTvButtonColors(),
                            modifier = Modifier
                                .focusRequester(requesters.getOrPut(SEARCH_CLEAR_CONFIRM_KEY) {
                                    FocusRequester()
                                })
                                .then(
                                    stableFocusModifier(
                                        SEARCH_CLEAR_CONFIRM_KEY,
                                        requesters,
                                        {},
                                    ),
                                ),
                        ) {
                            Text("Clear")
                        }
                    }
                }
            }
        }
    }
}

internal const val SEARCH_CLEAR_CANCEL_KEY = "search-clear-cancel"
internal const val SEARCH_CLEAR_CONFIRM_KEY = "search-clear-confirm-action"

@Composable
internal fun searchHistoryEntryTreatment(focused: Boolean): InfusionControlTreatment =
    if (focused) {
        InfusionTvContour.treatment(InfusionControlState.FOCUSED, LocalInfusionAccent.current)
    } else {
        InfusionControlTreatment(
            containerColor = Color.Transparent,
            contentColor = InfusionTvAppearance.TextPrimary,
            contourWidth = null,
        )
    }

internal const val SEARCH_HISTORY_VISIBLE_ENTRIES = 10
internal val SEARCH_HISTORY_ROW_HEIGHT = 30.dp
internal val SEARCH_HISTORY_PANEL_PADDING = 16.dp
internal val SEARCH_HISTORY_ROW_PADDING = 12.dp
internal val SEARCH_HISTORY_EMPTY_HEIGHT = 60.dp

internal val SEARCH_CONFIRM_MIN_WIDTH = 240.dp
internal val SEARCH_CONFIRM_MAX_WIDTH = 420.dp

@Composable
private fun SearchResultSectionRow(
    section: SearchSection,
    requesters: FocusKeyRegistry,
    onOpenDestination: (SearchDestination) -> Unit,
    onContentKeyFocused: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            section.title,
            style = InfusionTvScaleCalibration.Type.Emphasis,
            modifier = Modifier.tvBrowseNonRailInset(),
        )
        InfusionContentRow(
            items = section.items,
            key = { it.key },
        ) { item ->
            AddonPosterCard(
                itemKey = item.key,
                preview = item.preview,
                requesters = requesters,

                onClick = { onOpenDestination(item.destination) },
                onFocusedKeyChanged = onContentKeyFocused,
            )
        }
    }
}

internal fun unidentifiedSearchResultLabel(group: SearchGroup): String {
    val addon = group.items.firstOrNull()?.addonDisplayName
    val provenance = if (addon == null) "" else " ($addon)"
    return "${group.displayTitle}$provenance · no catalog identity, so it can't be opened"
}

internal fun unidentifiedSearchResultTag(groupKey: String): String = "unidentified-result:$groupKey"

private fun degradationNote(state: AddonSearchUiState): String = when {
    state.canonicalFailed && state.addonFailed -> "Search is temporarily unavailable."
    state.canonicalFailed -> "Canonical results are unavailable right now."
    else -> "Add-on results are unavailable right now."
}
