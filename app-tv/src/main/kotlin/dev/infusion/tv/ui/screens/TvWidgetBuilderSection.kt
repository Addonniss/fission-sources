package dev.infusion.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import kotlin.math.abs
import dev.infusion.core.domain.publist.PublicListRoute
import dev.infusion.feature.vertical.home.HomeCatalogOption
import dev.infusion.feature.vertical.home.SpotlightSourceRowInUseMessage
import dev.infusion.feature.vertical.home.HomeWidgetAvailability
import dev.infusion.feature.vertical.home.HomeWidgetEntry
import dev.infusion.feature.vertical.home.WIDGET_SOURCE_EXTERNAL_CATALOGS
import dev.infusion.feature.vertical.home.WidgetBuilderAddonUi
import dev.infusion.feature.vertical.home.WidgetBuilderPageUi
import dev.infusion.feature.vertical.collections.CollectionPackManagerUiState
import dev.infusion.feature.vertical.collections.CollectionRowEntryUi
import dev.infusion.feature.vertical.collections.CollectionRowSettingsUiState
import dev.infusion.feature.vertical.home.WidgetBuilderSourceKind
import dev.infusion.feature.vertical.home.WidgetBuilderStateHolder
import dev.infusion.feature.vertical.home.WidgetBuilderUiState
import dev.infusion.feature.vertical.home.WidgetBuilderWidgetUi
import dev.infusion.feature.vertical.home.WidgetDestination
import dev.infusion.tv.ui.focus.DirectionalPressOwnership
import dev.infusion.tv.ui.focus.FocusKeyRegistry
import dev.infusion.tv.ui.focus.LocalDirectionalPressOwnership
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.InfusionTvStateTextField
import dev.infusion.tv.ui.theme.infusionTvButtonColors

const val SettingsWidgetsPane = "settings:widgets:pane"

const val SettingsWidgetsSourceRow = "settings:widgets:source"
const val SettingsWidgetsAddonRow = "settings:widgets:addon"
const val SettingsWidgetsCatalogRow = "settings:widgets:catalog"
const val SettingsWidgetsProviderRow = "settings:widgets:provider"
const val SettingsWidgetsRouteRow = "settings:widgets:route"
const val SettingsWidgetsQueryRow = "settings:widgets:query"
const val SettingsWidgetsUserRow = "settings:widgets:user"
const val SettingsWidgetsListRow = "settings:widgets:list"
const val SettingsWidgetsDestinationRow = "settings:widgets:destination"
const val SettingsWidgetsAddAction = "settings:widgets:add"

const val SettingsWidgetsPackUrlRow = "settings:widgets:packUrl"
const val SettingsWidgetsPackRow = "settings:widgets:pack"
const val SettingsWidgetsGroupRow = "settings:widgets:group"

const val SettingsWidgetsReimportPack = "settings:widgets:pack:reimport"
const val SettingsWidgetsDeletePack = "settings:widgets:pack:delete"

const val SettingsWidgetsManageRows = "settings:widgets:manageRows"
const val SettingsCollectionRowsPane = "settings:widgets:rows:pane"
const val SettingsCollectionRowsPackRow = "settings:widgets:rows:pack"
const val SettingsCollectionRowsGroupRow = "settings:widgets:rows:group"
const val SettingsCollectionRowsPageRow = "settings:widgets:rows:page"

fun collectionRowEntryTag(rowKey: String): String = "settings:widgets:rows:row:$rowKey"
fun collectionRowUpTag(rowKey: String): String = "settings:widgets:rows:row:$rowKey:up"
fun collectionRowDownTag(rowKey: String): String = "settings:widgets:rows:row:$rowKey:down"

internal fun collectionRowInRowKeys(rows: List<dev.infusion.feature.vertical.collections.CollectionRowEntryUi>): Set<String> =
    rows.flatMap { row ->
        buildList {
            if (row.canMoveUp) add(collectionRowUpTag(row.rowKey))
            if (row.canMoveDown) add(collectionRowDownTag(row.rowKey))
        }
    }.toSet()

internal fun collectionRowBackKeys(
    state: dev.infusion.feature.vertical.collections.CollectionRowSettingsUiState,
): Set<String> = buildSet {
    add(SettingsCollectionRowsPackRow)
    if (state.selectedPackId != null) add(SettingsCollectionRowsGroupRow)
    if (state.selectedGroupKey != null) add(SettingsCollectionRowsPageRow)
    state.rows.forEach { row ->
        add(collectionRowEntryTag(row.rowKey))
        add(collectionRowUpTag(row.rowKey))
        add(collectionRowDownTag(row.rowKey))
    }
}

const val WidgetBuilderFirstKey = SettingsWidgetsSourceRow

private val WidgetsTextFieldKeys: Set<String> = setOf(
    SettingsWidgetsQueryRow,
    SettingsWidgetsPackUrlRow,
)

fun widgetRowTag(widgetId: String): String = "settings:widgets:widget:$widgetId"
fun widgetRowUpTag(widgetId: String): String = "${widgetRowTag(widgetId)}:up"
fun widgetRowDownTag(widgetId: String): String = "${widgetRowTag(widgetId)}:down"
fun widgetRowDeleteTag(widgetId: String): String = "${widgetRowTag(widgetId)}:delete"

fun widgetPageHeaderTag(destinationId: String): String = "settings:widgets:page:$destinationId"

fun widgetsRowValueTag(rowTag: String): String = "$rowTag:value"

internal fun widgetRowInRowKeys(pages: List<WidgetBuilderPageUi>): Set<String> =
    pages.flatMap { page -> page.widgets }
        .flatMap { widget -> widgetRowInRowKeys(widget) }
        .toSet()

private fun widgetRowInRowKeys(widget: WidgetBuilderWidgetUi): List<String> = buildList {
    if (widget.canMoveUp) add(widgetRowUpTag(widget.entry.widgetId))
    if (widget.canMoveDown) add(widgetRowDownTag(widget.entry.widgetId))
    add(widgetRowDeleteTag(widget.entry.widgetId))
}

private data class WidgetFocusCell(val column: Int, val key: String)

private fun widgetBuilderPaneFocusOrder(
    sourceKind: WidgetBuilderSourceKind,
    publicSearchActive: Boolean,
    hasPublicUsers: Boolean,
    pages: List<WidgetBuilderPageUi>,
    expandedPageIds: Set<String>,
): List<List<WidgetFocusCell>> = buildList {
    add(listOf(WidgetFocusCell(column = 0, key = SettingsWidgetsSourceRow)))
    when (sourceKind) {
        WidgetBuilderSourceKind.EXTERNAL_CATALOGS -> {
            add(listOf(WidgetFocusCell(column = 0, key = SettingsWidgetsAddonRow)))
            add(listOf(WidgetFocusCell(column = 0, key = SettingsWidgetsCatalogRow)))
        }

        WidgetBuilderSourceKind.PUBLIC_LISTS -> {
            add(listOf(WidgetFocusCell(column = 0, key = SettingsWidgetsProviderRow)))
            add(listOf(WidgetFocusCell(column = 0, key = SettingsWidgetsRouteRow)))

            if (publicSearchActive) {
                add(listOf(WidgetFocusCell(column = 0, key = SettingsWidgetsQueryRow)))
            }
            if (hasPublicUsers) {
                add(listOf(WidgetFocusCell(column = 0, key = SettingsWidgetsUserRow)))
            }
            add(listOf(WidgetFocusCell(column = 0, key = SettingsWidgetsListRow)))
        }

        WidgetBuilderSourceKind.COLLECTIONS -> {
            add(listOf(WidgetFocusCell(column = 0, key = SettingsWidgetsPackUrlRow)))
            add(listOf(WidgetFocusCell(column = 0, key = SettingsWidgetsPackRow)))

            add(listOf(WidgetFocusCell(column = 0, key = SettingsWidgetsReimportPack)))
            add(listOf(WidgetFocusCell(column = 0, key = SettingsWidgetsDeletePack)))
            add(listOf(WidgetFocusCell(column = 0, key = SettingsWidgetsGroupRow)))
            add(listOf(WidgetFocusCell(column = 0, key = SettingsWidgetsManageRows)))
        }
    }
    add(listOf(WidgetFocusCell(column = 0, key = SettingsWidgetsDestinationRow)))
    add(listOf(WidgetFocusCell(column = 0, key = SettingsWidgetsAddAction)))
    pages.filter { it.widgets.isNotEmpty() || it.available }.forEach { page ->
        add(listOf(WidgetFocusCell(column = 0, key = widgetPageHeaderTag(page.destinationId))))
        if (page.destinationId !in expandedPageIds || page.widgets.isEmpty()) return@forEach
        page.widgets.forEach { widget -> add(widgetRowCells(widget)) }
    }
}

private fun widgetRowCells(widget: WidgetBuilderWidgetUi): List<WidgetFocusCell> = buildList {
    add(WidgetFocusCell(column = 0, key = widgetRowTag(widget.entry.widgetId)))
    if (widget.canMoveUp) {
        add(WidgetFocusCell(column = 1, key = widgetRowUpTag(widget.entry.widgetId)))
    }
    if (widget.canMoveDown) {
        add(WidgetFocusCell(column = 2, key = widgetRowDownTag(widget.entry.widgetId)))
    }
    add(WidgetFocusCell(column = 3, key = widgetRowDeleteTag(widget.entry.widgetId)))
}

private class WidgetRowNav(private val rows: List<List<WidgetFocusCell>>) {

    fun horizontalTargetKey(currentKey: String, direction: Key): String? {
        val delta = when (direction) {
            Key.DirectionLeft -> -1
            Key.DirectionRight -> 1
            else -> return null
        }
        val row = rows.firstOrNull { row -> row.any { cell -> cell.key == currentKey } } ?: return null
        val index = row.indexOfFirst { cell -> cell.key == currentKey }
        return row.getOrNull(index + delta)?.key
    }

    fun verticalTargetKey(currentKey: String, direction: Key): String? {
        val delta = when (direction) {
            Key.DirectionUp -> -1
            Key.DirectionDown -> 1
            else -> return null
        }
        val rowIndex = rows.indexOfFirst { row -> row.any { cell -> cell.key == currentKey } }
        if (rowIndex < 0) return null
        val column = rows[rowIndex].first { cell -> cell.key == currentKey }.column
        val targetRow = rows.getOrNull(rowIndex + delta) ?: return null
        return targetRow.minByOrNull { cell -> abs(cell.column - column) }?.key
    }

    fun holds(key: String?): Boolean =
        key != null && rows.any { row -> row.any { cell -> cell.key == key } }
}

private val WidgetsRowHeight = 48.dp
private val WidgetsRowSpacing = 8.dp
private val WidgetsMaxWidth = 900.dp

private val WidgetsActionWidth = 64.dp

internal val WidgetsSelectorWidth = 500.dp

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun WidgetBuilderSectionContent(
    holder: WidgetBuilderStateHolder?,
    requesters: MutableMap<String, FocusRequester>,
    entryToken: Int,
    rememberedKey: String?,
    onFocusRequested: (String) -> Unit,
    onReturnToRail: () -> Unit,

    packManagerState: CollectionPackManagerUiState = CollectionPackManagerUiState(),
    onPackUrlChange: (String) -> Unit = {},
    onSubmitPackUrl: () -> Unit = {},
    onReimportPack: (String) -> Unit = {},
    onDeletePack: (String) -> Unit = {},

    rowSettingsState: dev.infusion.feature.vertical.collections.CollectionRowSettingsUiState =
        dev.infusion.feature.vertical.collections.CollectionRowSettingsUiState(),
    onSelectRowPack: (String) -> Unit = {},
    onSelectRowGroup: (String) -> Unit = {},
    onSelectRowPage: (String) -> Unit = {},
    onToggleRow: (String) -> Unit = {},
    onMoveRow: (String, Int) -> Unit = { _, _ -> },

    onClearRowPage: () -> Unit = {},

    onClearRowGroup: () -> Unit = {},

    onClearRowPack: () -> Unit = {},
) {
    if (holder == null) {
        Text(
            "Widget Builder unavailable.",
            style = InfusionTvScaleCalibration.Type.SettingsContent,
            modifier = Modifier.padding(24.dp),
        )
        return
    }
    val state by holder.state.collectAsState()
    var focusedKey by remember { mutableStateOf<String?>(null) }
    fun reportFocus(key: String) {
        focusedKey = key
        onFocusRequested(key)
    }

    var openSelector by remember { mutableStateOf<WidgetSelector?>(null) }

    var selectorOriginKey by remember { mutableStateOf<String?>(null) }

    fun openSelectorFrom(selector: WidgetSelector, originKey: String) {
        selectorOriginKey = originKey
        openSelector = selector
    }

    var selectedPackId by remember { mutableStateOf<String?>(null) }

    var pendingFocusKey by remember { mutableStateOf<String?>(null) }
    var pendingFocusFallback by remember { mutableStateOf<String?>(null) }

    var collapsedPageIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    var managingRows by remember { mutableStateOf(false) }

    val expandedPageIds = remember(state.pages, collapsedPageIds) {
        state.pages.map { it.destinationId }.filterNot { it in collapsedPageIds }.toSet()
    }
    val paneNav = widgetBuilderPaneFocusOrder(
        sourceKind = state.sourceKind,
        publicSearchActive = state.publicSearchActive,
        hasPublicUsers = state.publicUsers.isNotEmpty(),
        pages = state.pages,
        expandedPageIds = expandedPageIds,
    ).let { WidgetRowNav(it) }

    val pressOwner = LocalDirectionalPressOwnership.current ?: remember { DirectionalPressOwnership() }

    val imeVisible = WindowInsets.isImeVisible

    var pendingSubmitFocus by remember { mutableStateOf<WidgetSubmit?>(null) }

    RestoreSectionFocus(entryToken, rememberedKey, WidgetBuilderFirstKey, requesters)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = WidgetsMaxWidth)
                .fillMaxWidth()
                .testTag(SettingsWidgetsPane)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                    val directional = event.key == Key.DirectionUp || event.key == Key.DirectionDown ||
                        event.key == Key.DirectionLeft || event.key == Key.DirectionRight
                    if (directional && !pressOwner.claim(
                            event.nativeKeyEvent.downTime,
                            event.nativeKeyEvent.repeatCount,
                        )
                    ) {
                        return@onPreviewKeyEvent true
                    }

                    if (event.key == Key.DirectionUp && focusedKey == WidgetBuilderFirstKey) {
                        return@onPreviewKeyEvent true
                    }
                    val current = focusedKey ?: return@onPreviewKeyEvent false

                    if (imeVisible && current in WidgetsTextFieldKeys) return@onPreviewKeyEvent false

                    if (!paneNav.holds(current)) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> {

                            val target = paneNav.horizontalTargetKey(current, event.key)
                                ?: return@onPreviewKeyEvent false
                            requesters[target]?.requestFocus()
                            true
                        }
                        Key.DirectionRight -> {

                            paneNav.horizontalTargetKey(current, event.key)
                                ?.let { target -> requesters[target]?.requestFocus() }
                            true
                        }
                        Key.DirectionUp, Key.DirectionDown -> {
                            paneNav.verticalTargetKey(current, event.key)
                                ?.let { target -> requesters[target]?.requestFocus() }

                            true
                        }
                        else -> false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(WidgetsRowSpacing),
        ) {
            if (managingRows) {

                CollectionRowsManager(
                    state = rowSettingsState,
                    requesters = requesters,
                    onFocusRequested = ::reportFocus,
                    onExit = {
                        pendingFocusKey = SettingsWidgetsManageRows
                        pendingFocusFallback = WidgetBuilderFirstKey
                        managingRows = false
                    },
                    onClearPage = onClearRowPage,
                    onClearGroup = onClearRowGroup,
                    onClearPack = onClearRowPack,
                    onSelectPack = onSelectRowPack,
                    onSelectGroup = onSelectRowGroup,
                    onSelectPage = onSelectRowPage,
                    onToggleRow = onToggleRow,
                    onMoveRow = onMoveRow,
                )
            } else {
            Text("Widget Builder", style = InfusionTvScaleCalibration.Type.Headline)
            Text(
                "Create a Home row from an installed add-on's catalog, or from one list of " +
                    "Trakt / MDBList / Simkl. Public routes need no account; a provider's " +
                    "account routes (for example My Lists) appear once that account is " +
                    "connected. Each widget is an independent row you can move or delete " +
                    "without affecting the source.",
                style = InfusionTvScaleCalibration.Type.SettingsContent,
            )

            WidgetsSelectorRow(
                label = "Content source",
                value = state.sourceKind.label,
                tag = SettingsWidgetsSourceRow,
                help = SettingsRowHelp.Widgets.ContentSource,
                requesters = requesters,
                onFocused = ::reportFocus,
                onOpen = { openSelectorFrom(WidgetSelector.SOURCE, SettingsWidgetsSourceRow) },
            )

            if (state.sourceKind == WidgetBuilderSourceKind.EXTERNAL_CATALOGS) {

                WidgetsSelectorRow(
                    label = "Add-on",
                    value = state.addons.firstOrNull { it.addonId == state.selectedAddonId }?.displayName
                        ?: "Choose an add-on",
                    tag = SettingsWidgetsAddonRow,
                    help = SettingsRowHelp.Widgets.Addon,
                    requesters = requesters,
                    onFocused = ::reportFocus,
                    onOpen = {
                        if (state.addons.isNotEmpty()) {
                            openSelectorFrom(WidgetSelector.ADDON, SettingsWidgetsAddonRow)
                        }
                    },
                )

                WidgetsSelectorRow(
                    label = "Catalog",
                    value = state.selectedCatalogLabel ?: "Choose a catalog",
                    tag = SettingsWidgetsCatalogRow,
                    help = SettingsRowHelp.Widgets.Catalog,
                    requesters = requesters,
                    onFocused = ::reportFocus,
                    onOpen = {
                        if (state.selectedAddonCatalogs.isNotEmpty()) {
                            openSelectorFrom(WidgetSelector.CATALOG, SettingsWidgetsCatalogRow)
                        }
                    },
                )
            } else if (state.sourceKind == WidgetBuilderSourceKind.PUBLIC_LISTS) {

                WidgetsSelectorRow(
                    label = "Provider",
                    value = state.selectedPublicProvider?.displayLabel ?: "Choose a provider",
                    tag = SettingsWidgetsProviderRow,
                    help = SettingsRowHelp.Widgets.Provider,
                    requesters = requesters,
                    onFocused = ::reportFocus,
                    onOpen = {
                        if (state.publicProviders.isNotEmpty()) {
                            openSelectorFrom(WidgetSelector.PROVIDER, SettingsWidgetsProviderRow)
                        }
                    },
                )

                WidgetsSelectorRow(
                    label = "Route",
                    value = state.selectedPublicRouteLabel ?: "Choose a route",
                    tag = SettingsWidgetsRouteRow,
                    help = SettingsRowHelp.Widgets.Route,
                    requesters = requesters,
                    onFocused = ::reportFocus,
                    onOpen = {
                        if (state.selectedPublicRoutes.isNotEmpty()) {
                            openSelectorFrom(WidgetSelector.ROUTE, SettingsWidgetsRouteRow)
                        }
                    },
                )

                if (state.publicSearchActive) {
                    WidgetsQueryRow(
                        label = state.publicQueryLabel,
                        value = state.publicQuery,
                        onValueChange = holder::setPublicQuery,
                        onSubmit = {

                            pendingSubmitFocus = WidgetSubmit.PUBLIC
                            holder.submitPublicSearch()
                        },
                        tag = SettingsWidgetsQueryRow,
                        requesters = requesters,
                        onFocused = ::reportFocus,
                    )
                }

                state.publicStatus?.let { status ->
                    Text(
                        status,
                        style = InfusionTvScaleCalibration.Type.SettingsContent,
                        color = LocalContentColor.current.copy(alpha = 0.75f),
                    )
                }

                if (state.publicUsers.isNotEmpty()) {
                    WidgetsSelectorRow(
                        label = "User",
                        value = state.selectedPublicUser?.let { "@$it" } ?: "Choose a user",
                        tag = SettingsWidgetsUserRow,
                        help = SettingsRowHelp.Widgets.User,
                        requesters = requesters,
                        onFocused = ::reportFocus,
                        onOpen = { openSelectorFrom(WidgetSelector.USER, SettingsWidgetsUserRow) },
                    )
                }

                WidgetsSelectorRow(
                    label = "List",
                    value = state.selectedPublicList?.title ?: "Choose a list",
                    tag = SettingsWidgetsListRow,
                    help = SettingsRowHelp.Widgets.List,
                    requesters = requesters,
                    onFocused = ::reportFocus,
                    onOpen = {
                        if (state.publicResults.isNotEmpty()) {
                            openSelectorFrom(WidgetSelector.LIST, SettingsWidgetsListRow)
                        }
                    },
                )
            } else {

                if (packManagerState.packs.isEmpty()) {
                    Text(
                        "No collection pack is imported yet. Paste a collection-pack URL above " +
                            "and submit it to import one.",
                        style = InfusionTvScaleCalibration.Type.SettingsContent,
                        color = LocalContentColor.current.copy(alpha = 0.75f),
                    )
                } else {
                    val count = packManagerState.packs.size
                    Text(
                        "Collections available — $count imported collection pack" +
                            (if (count == 1) "" else "s") +
                            " ready to use. You do not need to import again; choose a pack " +
                            "below, or manage its page rows.",
                        style = InfusionTvScaleCalibration.Type.SettingsContent,
                        color = LocalContentColor.current.copy(alpha = 0.75f),
                    )
                }
                WidgetsQueryRow(
                    label = "Collection pack URL",
                    value = packManagerState.urlText,
                    onValueChange = onPackUrlChange,
                    onSubmit = {

                        pendingSubmitFocus = WidgetSubmit.COLLECTIONS
                        onSubmitPackUrl()
                    },
                    tag = SettingsWidgetsPackUrlRow,
                    requesters = requesters,
                    onFocused = ::reportFocus,
                )

                packManagerState.status?.let { status ->
                    Text(
                        status,
                        style = InfusionTvScaleCalibration.Type.SettingsContent,
                        color = LocalContentColor.current.copy(alpha = 0.75f),
                    )
                }

                WidgetsSelectorRow(
                    label = "Imported pack",
                    value = packManagerState.packs.firstOrNull { it.packId == selectedPackId }?.name
                        ?: "Choose a pack",
                    tag = SettingsWidgetsPackRow,
                    help = SettingsRowHelp.Widgets.ImportedPack,
                    requesters = requesters,
                    onFocused = ::reportFocus,
                    onOpen = {
                        if (packManagerState.packs.isNotEmpty()) {
                            openSelectorFrom(WidgetSelector.PACK, SettingsWidgetsPackRow)
                        }
                    },
                )

                var reimportFocused by remember { mutableStateOf(false) }
                InfusionTvButton(
                    onClick = { selectedPackId?.let(onReimportPack) },
                    colors = infusionTvButtonColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = WidgetsRowHeight)
                        .then(stableFocusModifier(SettingsWidgetsReimportPack, requesters, ::reportFocus))
                        .then(settingsRowBringIntoView(reimportFocused))
                        .onFocusChanged { reimportFocused = it.isFocused },
                ) {
                    SettingsRowLabelBlock(
                        label = "Re-import pack",
                        help = SettingsRowHelp.Widgets.ReimportPack,
                        focused = reimportFocused,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                var deletePackFocused by remember { mutableStateOf(false) }
                InfusionTvButton(
                    onClick = { selectedPackId?.let(onDeletePack) },
                    colors = infusionTvButtonColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = WidgetsRowHeight)
                        .then(stableFocusModifier(SettingsWidgetsDeletePack, requesters, ::reportFocus))
                        .then(settingsRowBringIntoView(deletePackFocused))
                        .onFocusChanged { deletePackFocused = it.isFocused },
                ) {
                    SettingsRowLabelBlock(
                        label = "Delete pack",
                        help = SettingsRowHelp.Widgets.DeletePack,
                        focused = deletePackFocused,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                WidgetsSelectorRow(
                    label = "Collection group",
                    value = state.selectedCollectionGroupLabel ?: "Choose a group",
                    tag = SettingsWidgetsGroupRow,
                    help = SettingsRowHelp.Widgets.CollectionGroup,
                    requesters = requesters,
                    onFocused = ::reportFocus,
                    onOpen = {
                        if (state.collectionGroups.isNotEmpty()) {
                            openSelectorFrom(WidgetSelector.GROUP, SettingsWidgetsGroupRow)
                        }
                    },
                )

                var manageRowFocused by remember { mutableStateOf(false) }
                InfusionTvButton(
                    onClick = { managingRows = true },
                    colors = infusionTvButtonColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = WidgetsRowHeight)
                        .then(stableFocusModifier(SettingsWidgetsManageRows, requesters, ::reportFocus))
                        .then(settingsRowBringIntoView(manageRowFocused))
                        .onFocusChanged { manageRowFocused = it.isFocused },
                ) {
                    SettingsRowLabelBlock(
                        label = "Manage collection rows",
                        help = SettingsRowHelp.Widgets.ManageRows,
                        focused = manageRowFocused,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            WidgetsSelectorRow(
                label = "Destination",
                value = state.destinationLabel,
                tag = SettingsWidgetsDestinationRow,
                help = SettingsRowHelp.Widgets.Destination,
                requesters = requesters,
                onFocused = ::reportFocus,
                onOpen = {
                    if (state.destinations.isNotEmpty()) {
                        openSelectorFrom(WidgetSelector.DESTINATION, SettingsWidgetsDestinationRow)
                    }
                },
            )

            val canAdd = when (state.sourceKind) {
                WidgetBuilderSourceKind.PUBLIC_LISTS -> state.canAddPublicList
                WidgetBuilderSourceKind.COLLECTIONS -> state.canAddCollectionGroup
                else -> state.canAdd
            }
            var addWidgetFocused by remember { mutableStateOf(false) }
            InfusionTvButton(
                onClick = { holder.addWidget() },
                colors = infusionTvButtonColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = WidgetsRowHeight)
                    .then(stableFocusModifier(SettingsWidgetsAddAction, requesters, ::reportFocus))
                    .then(settingsRowBringIntoView(addWidgetFocused))
                    .onFocusChanged { addWidgetFocused = it.isFocused },
            ) {
                SettingsRowLabelBlock(
                    label = when {
                        canAdd -> "Add widget"
                        state.sourceKind == WidgetBuilderSourceKind.PUBLIC_LISTS ->
                            "Add widget (choose provider, route and one list)"
                        state.sourceKind == WidgetBuilderSourceKind.COLLECTIONS ->
                            "Add widget (import a pack and choose one group)"
                        else -> "Add widget (choose add-on, catalog and destination)"
                    },
                    help = SettingsRowHelp.Widgets.AddWidget,
                    focused = addWidgetFocused,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            state.actionMessage?.let { message ->
                Text(
                    message,
                    style = InfusionTvScaleCalibration.Type.SettingsContent,
                    color = LocalContentColor.current.copy(alpha = 0.75f),
                )
            }

            if (state.loading) {
                Text("Loading widgets…", style = InfusionTvScaleCalibration.Type.SettingsContent)
            }
            val pages = state.pages.filter { it.widgets.isNotEmpty() || it.available }
            if (!state.loading && pages.all { it.widgets.isEmpty() }) {
                Text(
                    "No widgets yet. Choose an add-on and one of its catalogs, then Add widget.",
                    style = InfusionTvScaleCalibration.Type.SettingsContent,
                )
            }
            pages.forEach { page ->
                WidgetsPageGroup(
                    page = page,
                    expanded = page.destinationId in expandedPageIds,
                    blockedDeleteWidgetId = state.blockedDeleteWidgetId,
                    onToggleExpanded = {

                        val collapsing = page.destinationId in expandedPageIds

                        pendingFocusKey = widgetPageHeaderTag(page.destinationId)
                        pendingFocusFallback = widgetPageHeaderTag(page.destinationId)
                        collapsedPageIds = if (collapsing) {
                            collapsedPageIds + page.destinationId
                        } else {
                            collapsedPageIds - page.destinationId
                        }
                    },
                    requesters = requesters,
                    onFocused = ::reportFocus,
                    onMoveUp = { widget ->
                        pendingFocusKey = widgetRowUpTag(widget.entry.widgetId)
                        pendingFocusFallback = widgetRowTag(widget.entry.widgetId)
                        holder.moveWidget(widget.entry.widgetId, -1)
                    },
                    onMoveDown = { widget ->
                        pendingFocusKey = widgetRowDownTag(widget.entry.widgetId)
                        pendingFocusFallback = widgetRowTag(widget.entry.widgetId)
                        holder.moveWidget(widget.entry.widgetId, 1)
                    },
                    onDelete = { widget ->

                        val index = page.widgets.indexOfFirst {
                            it.entry.widgetId == widget.entry.widgetId
                        }
                        val survivor = page.widgets.getOrNull(index + 1)
                            ?: page.widgets.getOrNull(index - 1)
                        pendingFocusKey = widgetRowTag(widget.entry.widgetId)
                        pendingFocusFallback = survivor?.let { widgetRowTag(it.entry.widgetId) }
                            ?: SettingsWidgetsAddAction
                        holder.deleteWidget(widget.entry.widgetId)
                    },
                )
            }
            }
        }
    }

    LaunchedEffect(state.pages, collapsedPageIds, managingRows) {
        val primary = pendingFocusKey ?: return@LaunchedEffect
        val fallback = pendingFocusFallback
        pendingFocusKey = null
        pendingFocusFallback = null
        if (requesters[primary]?.requestFocus() == true) return@LaunchedEffect
        if (fallback != null) requesters[fallback]?.requestFocus()
    }

    LaunchedEffect(pendingSubmitFocus, state.publicResults, state.publicUsers, state.publicStatus) {
        if (pendingSubmitFocus != WidgetSubmit.PUBLIC) return@LaunchedEffect
        val target = when {
            state.publicResults.isNotEmpty() -> SettingsWidgetsListRow
            state.publicUsers.isNotEmpty() -> SettingsWidgetsUserRow
            else -> SettingsWidgetsQueryRow
        }
        if (requesters[target]?.requestFocus() == true) pendingSubmitFocus = null
    }

    LaunchedEffect(
        pendingSubmitFocus,
        packManagerState.busy,
        packManagerState.status,
        packManagerState.packs,
    ) {
        if (pendingSubmitFocus != WidgetSubmit.COLLECTIONS) return@LaunchedEffect
        if (packManagerState.busy) {
            requesters[SettingsWidgetsPackUrlRow]?.requestFocus()
            return@LaunchedEffect
        }
        val target = if (packManagerState.packs.isNotEmpty()) {
            SettingsWidgetsPackRow
        } else {
            SettingsWidgetsPackUrlRow
        }
        if (requesters[target]?.requestFocus() == true) pendingSubmitFocus = null
    }

    val publicResultsEndReached: (() -> Unit)? = if (state.publicResultsHasMore) {
        { holder.loadMorePublicResults() }
    } else {
        null
    }

    WidgetPickerReturnFocus(
        open = openSelector != null,
        originKey = selectorOriginKey,
        requesters = requesters,
    )

    when (openSelector) {
        WidgetSelector.SOURCE -> GeneralSelectorDialog(
            rowTag = SettingsWidgetsSourceRow,
            choices = WidgetBuilderSourceKind.entries,
            current = state.sourceKind,
            choiceKey = { kind -> kind.name },
            choiceLabel = { kind -> kind.label },
            onSelect = { kind ->
                holder.selectSourceKind(kind)
                openSelector = null
            },
            onDismiss = { openSelector = null },
            panelWidth = WidgetsSelectorWidth,
            scrollIndicators = true,
        )

        WidgetSelector.PROVIDER -> GeneralSelectorDialog(
            rowTag = SettingsWidgetsProviderRow,
            choices = state.publicProviders,
            current = state.selectedPublicProvider ?: state.publicProviders.firstOrNull() ?: return,
            choiceKey = { provider -> provider.providerId },
            choiceLabel = { provider -> provider.displayLabel },

            choiceSupportingText = { provider ->
                if (provider.needsAccount) "Uses the connected account" else ""
            },
            onSelect = { provider ->
                holder.selectPublicProvider(provider.providerId)
                openSelector = null
            },
            onDismiss = { openSelector = null },
            panelWidth = WidgetsSelectorWidth,
            scrollIndicators = true,
        )

        WidgetSelector.ROUTE -> GeneralSelectorDialog(
            rowTag = SettingsWidgetsRouteRow,
            choices = state.selectedPublicRoutes,
            current = state.selectedPublicRoutes.firstOrNull { it.route == state.selectedPublicRoute }
                ?: state.selectedPublicRoutes.firstOrNull() ?: return,
            choiceKey = { route -> route.route.name },
            choiceLabel = { route -> route.label },
            onSelect = { route ->
                holder.selectPublicRoute(route.route)
                openSelector = null
            },
            onDismiss = { openSelector = null },
            panelWidth = WidgetsSelectorWidth,
            scrollIndicators = true,
        )

        WidgetSelector.USER -> GeneralSelectorDialog(
            rowTag = SettingsWidgetsUserRow,
            choices = state.publicUsers,
            current = state.publicUsers.firstOrNull { it.username == state.selectedPublicUser }
                ?: state.publicUsers.firstOrNull() ?: return,
            choiceKey = { user -> user.username },
            choiceLabel = { user -> "@${user.username}" },
            choiceSupportingText = { user -> user.displayName.orEmpty() },
            onSelect = { user ->
                holder.selectPublicUser(user.username)
                openSelector = null
            },
            onDismiss = { openSelector = null },
            panelWidth = WidgetsSelectorWidth,
            scrollIndicators = true,
        )

        WidgetSelector.LIST -> GeneralSelectorDialog(
            rowTag = SettingsWidgetsListRow,
            choices = state.publicResults,
            current = state.publicResults.firstOrNull { it.ref == state.selectedPublicList?.ref }
                ?: state.publicResults.firstOrNull() ?: return,
            choiceKey = { list -> "${list.ref.provider.id}:${list.ref.owner.orEmpty()}:${list.ref.listId}" },
            choiceLabel = { list -> list.title },
            choiceSupportingText = { list ->
                listOfNotNull(
                    list.ownerLabel,
                    list.itemCount?.let { "$it items" },
                    list.ref.provider.displayName,
                ).joinToString(" · ")
            },
            onSelect = { list ->
                holder.selectPublicList(list)
                openSelector = null
            },
            onDismiss = { openSelector = null },
            panelWidth = WidgetsSelectorWidth,
            scrollIndicators = true,
            onEndReached = publicResultsEndReached,
        )

        WidgetSelector.ADDON -> GeneralSelectorDialog(
            rowTag = SettingsWidgetsAddonRow,
            choices = state.addons,
            current = state.addons.firstOrNull { it.addonId == state.selectedAddonId }
                ?: state.addons.firstOrNull() ?: return,
            choiceKey = { addon -> addon.addonId },
            choiceLabel = { addon -> addon.displayName },
            onSelect = { addon ->
                holder.selectAddon(addon.addonId)
                openSelector = null
            },
            onDismiss = { openSelector = null },
            panelWidth = WidgetsSelectorWidth,
            scrollIndicators = true,
        )

        WidgetSelector.CATALOG -> GeneralSelectorDialog(
            rowTag = SettingsWidgetsCatalogRow,
            choices = state.selectedAddonCatalogs,
            current = state.selectedAddonCatalogs.firstOrNull { it.key == state.selectedCatalogKey }
                ?: state.selectedAddonCatalogs.firstOrNull() ?: return,
            choiceKey = { option -> option.key.shelfKey },
            choiceLabel = { option -> option.displayName },
            choiceSupportingText = { option ->
                "${option.key.catalogType} · ${option.addonDisplayName}"
            },
            onSelect = { option ->
                holder.selectCatalog(option.key)
                openSelector = null
            },
            onDismiss = { openSelector = null },
            panelWidth = WidgetsSelectorWidth,
            scrollIndicators = true,
        )

        WidgetSelector.DESTINATION -> GeneralSelectorDialog(
            rowTag = SettingsWidgetsDestinationRow,
            choices = state.destinations,
            current = state.destinations.firstOrNull { it.id == state.destinationId }
                ?: state.destinations.firstOrNull() ?: return,
            choiceKey = { destination -> destination.id },
            choiceLabel = { destination -> destination.displayName },
            onSelect = { destination ->
                holder.selectDestination(destination.id)
                openSelector = null
            },
            onDismiss = { openSelector = null },
            panelWidth = WidgetsSelectorWidth,
            scrollIndicators = true,
        )

        null -> Unit

        WidgetSelector.PACK -> {
            val current = packManagerState.packs.firstOrNull { it.packId == selectedPackId }
                ?: packManagerState.packs.firstOrNull()
            if (current != null) {
                GeneralSelectorDialog(
                    rowTag = SettingsWidgetsPackRow,
                    choices = packManagerState.packs,
                    current = current,
                    choiceKey = { pack -> pack.packId },
                    choiceLabel = { pack -> pack.name },
                    choiceSupportingText = { pack ->

                        val url = pack.sourceUrl?.substringBefore('?').orEmpty()
                        "${pack.groupCount} group(s) · ${pack.tileCount} tile(s)" +
                            if (url.isNotEmpty()) " · $url" else ""
                    },
                    onSelect = { pack ->
                        selectedPackId = pack.packId
                        openSelector = null
                    },
                    onDismiss = { openSelector = null },
                    panelWidth = WidgetsSelectorWidth,
                    scrollIndicators = true,
                )
            }
        }

        WidgetSelector.GROUP -> {
            val choices = state.collectionGroups
            val current = choices.firstOrNull { it.ref == state.selectedCollectionGroup }
                ?: choices.firstOrNull()
            if (current != null) {
                GeneralSelectorDialog(
                    rowTag = SettingsWidgetsGroupRow,
                    choices = choices,
                    current = current,
                    choiceKey = { group -> "${group.packId}:${group.groupKey}" },
                    choiceLabel = { group -> group.displayLabel },
                    choiceSupportingText = { group -> "${group.packName} · ${group.tileCount} tile(s)" },
                    onSelect = { group ->
                        holder.selectCollectionGroup(group.ref)
                        openSelector = null
                    },
                    onDismiss = { openSelector = null },
                    panelWidth = WidgetsSelectorWidth,
                    scrollIndicators = true,
                )
            }
        }
    }
}

private enum class WidgetSelector { SOURCE, ADDON, CATALOG, PROVIDER, ROUTE, USER, LIST, DESTINATION, PACK, GROUP }

private enum class WidgetSubmit { PUBLIC, COLLECTIONS }

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun WidgetsQueryRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    tag: String,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val textState = rememberTextFieldState()
    LaunchedEffect(value) {
        if (textState.text.toString() != value) textState.setTextAndPlaceCursorAtEnd(value)
    }
    LaunchedEffect(textState) {
        snapshotFlow { textState.text.toString() }.collect(onValueChange)
    }

    val focusModifier = stableFocusModifier(tag, requesters, onFocused)
    Row(
        modifier = Modifier.fillMaxWidth().height(WidgetsRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = InfusionTvScaleCalibration.Type.SettingsContent)
        Spacer(Modifier.width(16.dp))
        InfusionTvStateTextField(
            state = textState,
            textStyle = InfusionTvScaleCalibration.Type.SettingsContent,

            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),

            onKeyboardAction = { _ ->
                onSubmit()

                keyboardController?.hide()
                focusManager.clearFocus()
            },
            modifier = Modifier
                .weight(1f)
                .then(focusModifier),
        )
    }
}

@Composable
private fun WidgetsInfoRow(label: String, value: String, tag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().height(WidgetsRowHeight).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = InfusionTvScaleCalibration.Type.SettingsContent)
        Spacer(Modifier.width(16.dp))
        Text(value, style = InfusionTvScaleCalibration.Type.SettingsContent)
    }
}

@Composable
private fun WidgetPickerReturnFocus(
    open: Boolean,
    originKey: String?,
    requesters: MutableMap<String, FocusRequester>,
) {
    val registry = requesters as? FocusKeyRegistry
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        val closedNow = wasOpen && !open
        wasOpen = open
        if (!closedNow) return@LaunchedEffect
        val origin = originKey ?: return@LaunchedEffect
        if (registry != null) {
            if (!registry.request(origin)) registry.arm(origin)
        } else {
            requesters[origin]?.requestFocus()
        }
    }
}

@Composable
private fun WidgetsSelectorRow(
    label: String,
    value: String,
    tag: String,
    help: String,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onOpen: () -> Unit,
) {
    var focused by remember(tag) { mutableStateOf(false) }
    InfusionTvButton(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WidgetsRowHeight)
            .then(stableFocusModifier(tag, requesters, onFocused))
            .then(settingsRowBringIntoView(focused))
            .onFocusChanged { focused = it.isFocused },
    ) {
        SettingsRowBody(
            presentation = SettingsRowPresentation.EXPANDED,
            label = label,
            help = help,
            value = value,
            focused = focused,
            valueModifier = Modifier.testTag(widgetsRowValueTag(tag)),
            trailing = {
                Spacer(Modifier.width(SettingsRowPresentationMetrics.TrailingGap))
                Text("›", style = InfusionTvScaleCalibration.Type.SettingsContent)
            },
        )
    }
}

@Composable
private fun WidgetsPageGroup(
    page: WidgetBuilderPageUi,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onMoveUp: (WidgetBuilderWidgetUi) -> Unit,
    onMoveDown: (WidgetBuilderWidgetUi) -> Unit,
    onDelete: (WidgetBuilderWidgetUi) -> Unit,

    blockedDeleteWidgetId: String? = null,
) {
    WidgetsPageHeader(
        page = page,
        expanded = expanded,
        requesters = requesters,
        onFocused = onFocused,
        onToggleExpanded = onToggleExpanded,
    )
    if (!page.available) {
        Text(
            "This destination is not available in this build. These widgets are kept and can be deleted.",
            style = InfusionTvScaleCalibration.Type.SettingsContent,
            color = LocalContentColor.current.copy(alpha = 0.75f),
        )
    }
    if (!expanded) return
    if (page.widgets.isEmpty()) {
        Text("No widgets on this page.", style = InfusionTvScaleCalibration.Type.SettingsContent)
        return
    }
    page.widgets.forEach { widget ->
        WidgetsCreatedRow(
            widget = widget,
            requesters = requesters,
            onFocused = onFocused,
            onMoveUp = { onMoveUp(widget) },
            onMoveDown = { onMoveDown(widget) },
            onDelete = { onDelete(widget) },

            deleteRefusal = if (widget.entry.widgetId == blockedDeleteWidgetId) {
                SpotlightSourceRowInUseMessage
            } else {
                null
            },
        )
    }
}

@Composable
private fun WidgetsPageHeader(
    page: WidgetBuilderPageUi,
    expanded: Boolean,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onToggleExpanded: () -> Unit,
) {
    val tag = widgetPageHeaderTag(page.destinationId)
    InfusionTvButton(
        onClick = onToggleExpanded,
        colors = infusionTvButtonColors(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        modifier = Modifier
            .height(WidgetsRowHeight)
            .then(stableFocusModifier(tag, requesters, onFocused)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(page.label, style = InfusionTvScaleCalibration.Type.Headline)
            Spacer(Modifier.width(12.dp))

            Text(
                if (expanded) "▼" else "▶",
                style = InfusionTvScaleCalibration.Type.SettingsContent,
                color = LocalContentColor.current.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun WidgetsCreatedRow(
    widget: WidgetBuilderWidgetUi,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,

    deleteRefusal: String? = null,
) {
    val entry = widget.entry
    val tag = widgetRowTag(entry.widgetId)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = WidgetsRowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WidgetsRowSpacing),
    ) {
        InfusionTvButton(
            onClick = {},
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(stableFocusModifier(tag, requesters, onFocused)),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.displayTitle, style = InfusionTvScaleCalibration.Type.SettingsContent)
                    Text(
                        widgetSubtitle(entry),
                        style = InfusionTvScaleCalibration.Type.SettingsContent,
                        color = LocalContentColor.current.copy(alpha = 0.75f),
                    )
                }
            }
        }
        WidgetsActionButton(
            accessibleLabel = "Move up",
            tag = widgetRowUpTag(entry.widgetId),
            enabled = widget.canMoveUp,
            requesters = requesters,
            onFocused = onFocused,
            onClick = onMoveUp,
        ) {
            Text("▲", style = InfusionTvScaleCalibration.Type.SettingsContent)
        }
        WidgetsActionButton(
            accessibleLabel = "Move down",
            tag = widgetRowDownTag(entry.widgetId),
            enabled = widget.canMoveDown,
            requesters = requesters,
            onFocused = onFocused,
            onClick = onMoveDown,
        ) {
            Text("▼", style = InfusionTvScaleCalibration.Type.SettingsContent)
        }
        WidgetsActionButton(
            accessibleLabel = "Delete",
            tag = widgetRowDeleteTag(entry.widgetId),
            enabled = true,
            requesters = requesters,
            onFocused = onFocused,
            onClick = onDelete,
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                tint = LocalContentColor.current,
            )
        }
    }

    deleteRefusal?.let { message ->
        Text(
            message,
            style = InfusionTvScaleCalibration.Type.SettingsContent,
            color = LocalContentColor.current.copy(alpha = 0.75f),
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun WidgetsActionButton(
    accessibleLabel: String,
    tag: String,
    enabled: Boolean,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        Box(
            modifier = Modifier
                .width(WidgetsActionWidth)
                .height(WidgetsRowHeight)
                .testTag(tag)
                .semantics {
                    this.contentDescription = accessibleLabel
                    disabled()
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.clearAndSetSemantics { }) {
                CompositionLocalProvider(
                    LocalContentColor provides LocalContentColor.current.copy(alpha = 0.38f),
                ) {
                    content()
                }
            }
        }
        return
    }
    InfusionTvButton(
        onClick = onClick,
        colors = infusionTvButtonColors(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
        modifier = Modifier
            .width(WidgetsActionWidth)
            .height(WidgetsRowHeight)
            .semantics { this.contentDescription = accessibleLabel }
            .then(stableFocusModifier(tag, requesters, onFocused)),
    ) {
        content()
    }
}

internal fun widgetSubtitle(entry: HomeWidgetEntry): String = when (entry.availability) {
    HomeWidgetAvailability.AVAILABLE -> buildString {
        append(entry.destinationDisplayName)

        val provider = entry.sourceProviderLabel
        if (provider != null) {
            append(" · ").append(provider)
            entry.sourceOwnerLabel?.takeIf { it.isNotBlank() }?.let { owner ->
                append(" · ").append(owner)
            }
        } else if (entry.catalog != null) {
            entry.addonDisplayName?.takeIf { it.isNotBlank() }?.let { addon ->
                append(" · ").append(addon)
            }
        } else {
            append(" · ").append(entry.displayTitle)
        }
    }
    HomeWidgetAvailability.SOURCE_PENDING ->

        "${entry.destinationDisplayName} · Still reading its source"
    HomeWidgetAvailability.SOURCE_UNAVAILABLE ->
        "${entry.destinationDisplayName} · Source unavailable — kept until you delete it"
    HomeWidgetAvailability.DESTINATION_UNAVAILABLE ->
        "Destination unavailable — kept until you delete it"
}

internal fun widgetBuilderCanAdd(state: WidgetBuilderUiState): Boolean = when (state.sourceKind) {
    WidgetBuilderSourceKind.PUBLIC_LISTS -> state.canAddPublicList

    WidgetBuilderSourceKind.COLLECTIONS -> state.canAddCollectionGroup
    else -> state.canAdd
}

private enum class RowManagerSelector { PACK, GROUP, PAGE }

private enum class RowManagerFocusAction { NAME, UP, DOWN }

private fun rowDescription(row: CollectionRowEntryUi): String {
    val provider = if (row.sourceLabel.isNotBlank()) " (${row.sourceLabel})" else ""
    val action = if (row.enabled) "hide" else "show"
    return "${row.title}$provider. ${if (row.enabled) "Shown" else "Hidden"}. Activate to $action this row."
}

@Composable
private fun CollectionRowsManager(
    state: CollectionRowSettingsUiState,
    requesters: MutableMap<String, FocusRequester>,
    onFocusRequested: (String) -> Unit,
    onExit: () -> Unit,
    onClearPage: () -> Unit,
    onClearGroup: () -> Unit,
    onClearPack: () -> Unit,
    onSelectPack: (String) -> Unit,
    onSelectGroup: (String) -> Unit,
    onSelectPage: (String) -> Unit,
    onToggleRow: (String) -> Unit,
    onMoveRow: (String, Int) -> Unit,
) {
    var focusedKey by remember { mutableStateOf<String?>(null) }
    fun reportFocus(key: String) {
        focusedKey = key
        onFocusRequested(key)
    }
    var openSelector by remember { mutableStateOf<RowManagerSelector?>(null) }

    var selectorOriginKey by remember { mutableStateOf<String?>(null) }

    fun openSelectorFrom(selector: RowManagerSelector, originKey: String) {
        selectorOriginKey = originKey
        openSelector = selector
    }

    var pendingFocusRowKey by remember { mutableStateOf<String?>(null) }
    var pendingFocusAction by remember { mutableStateOf(RowManagerFocusAction.NAME) }

    var pendingBackFocusKey by remember { mutableStateOf<String?>(null) }

    fun stepBack() {
        when {
            state.selectedTileKey != null -> {
                onClearPage()
                pendingBackFocusKey = SettingsCollectionRowsPageRow
            }
            state.selectedGroupKey != null -> {
                onClearGroup()
                pendingBackFocusKey = SettingsCollectionRowsGroupRow
            }
            state.selectedPackId != null -> {
                onClearPack()
                pendingBackFocusKey = SettingsCollectionRowsPackRow
            }
            else -> onExit()
        }
    }

    val lines = buildList {
        add(listOf(WidgetFocusCell(0, SettingsCollectionRowsPackRow)))
        if (state.selectedPackId != null) add(listOf(WidgetFocusCell(0, SettingsCollectionRowsGroupRow)))
        if (state.selectedGroupKey != null) add(listOf(WidgetFocusCell(0, SettingsCollectionRowsPageRow)))
        state.rows.forEach { row ->
            add(
                buildList {
                    add(WidgetFocusCell(0, collectionRowEntryTag(row.rowKey)))
                    if (row.canMoveUp) add(WidgetFocusCell(1, collectionRowUpTag(row.rowKey)))
                    if (row.canMoveDown) add(WidgetFocusCell(2, collectionRowDownTag(row.rowKey)))
                },
            )
        }
    }
    val nav = WidgetRowNav(lines)

    BackHandler(enabled = openSelector == null) { stepBack() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SettingsCollectionRowsPane)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                if (event.key == Key.Back) {
                    stepBack()
                    return@onPreviewKeyEvent true
                }
                val current = focusedKey ?: return@onPreviewKeyEvent false
                if (!nav.holds(current)) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        val target = nav.horizontalTargetKey(current, event.key)
                            ?: return@onPreviewKeyEvent false
                        requesters[target]?.requestFocus()
                        true
                    }
                    Key.DirectionRight -> {
                        nav.horizontalTargetKey(current, event.key)?.let { requesters[it]?.requestFocus() }
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        nav.verticalTargetKey(current, event.key)?.let { requesters[it]?.requestFocus() }
                        true
                    }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(WidgetsRowSpacing),
    ) {

        val entryKey = when {
            state.selectedTileKey != null -> SettingsCollectionRowsPageRow
            state.selectedGroupKey != null -> SettingsCollectionRowsGroupRow
            else -> SettingsCollectionRowsPackRow
        }
        LaunchedEffect(Unit) {
            val registry = requesters as? FocusKeyRegistry
            if (registry != null) {
                if (!registry.request(entryKey)) registry.arm(entryKey)
            } else if (requesters[entryKey]?.requestFocus() != true) {

                repeat(10) {
                    withFrameNanos { }
                    if (requesters[entryKey]?.requestFocus() == true) return@LaunchedEffect
                }
            }
        }

        LaunchedEffect(state.selectedTileKey, state.selectedGroupKey, state.selectedPackId) {
            val target = pendingBackFocusKey ?: return@LaunchedEffect
            pendingBackFocusKey = null
            requesters[target]?.requestFocus()
        }

        Text("Manage collection rows", style = InfusionTvScaleCalibration.Type.Headline)
        Text(
            "Pick an imported pack, one of its collection groups and a collection page, then choose " +
                "which named rows that page shows and the order they appear in. Both choices apply " +
                "wherever that page is opened, and hiding a row stops it loading.",
            style = InfusionTvScaleCalibration.Type.SettingsContent,
        )
        if (state.packs.isEmpty()) {
            Text(
                "Import a collection pack in the Widget Builder first.",
                style = InfusionTvScaleCalibration.Type.SettingsContent,
                color = LocalContentColor.current.copy(alpha = 0.75f),
            )
        }

        WidgetsSelectorRow(
            label = "Imported pack",
            value = state.packs.firstOrNull { it.packId == state.selectedPackId }?.name ?: "Choose a pack",
            tag = SettingsCollectionRowsPackRow,
            help = SettingsRowHelp.Widgets.ImportedPack,
            requesters = requesters,
            onFocused = ::reportFocus,
            onOpen = {
                if (state.packs.isNotEmpty()) {
                    openSelectorFrom(RowManagerSelector.PACK, SettingsCollectionRowsPackRow)
                }
            },
        )

        if (state.selectedPackId != null) {
            WidgetsSelectorRow(
                label = "Collection group",
                value = state.groups.firstOrNull { it.groupKey == state.selectedGroupKey }?.title
                    ?: "Choose a group",
                tag = SettingsCollectionRowsGroupRow,
                help = SettingsRowHelp.Widgets.CollectionGroup,
                requesters = requesters,
                onFocused = ::reportFocus,
                onOpen = {
                    if (state.groups.isNotEmpty()) {
                        openSelectorFrom(RowManagerSelector.GROUP, SettingsCollectionRowsGroupRow)
                    }
                },
            )
        }

        if (state.selectedGroupKey != null) {
            WidgetsSelectorRow(
                label = "Collection page",
                value = state.tiles.firstOrNull { it.tileKey == state.selectedTileKey }?.title
                    ?: "Choose a page",
                tag = SettingsCollectionRowsPageRow,
                help = SettingsRowHelp.Widgets.CollectionPage,
                requesters = requesters,
                onFocused = ::reportFocus,
                onOpen = {
                    if (state.tiles.isNotEmpty()) {
                        openSelectorFrom(RowManagerSelector.PAGE, SettingsCollectionRowsPageRow)
                    }
                },
            )
        }

        if (state.selectedGroupKey != null && state.selectedTileKey == null) {
            Text(
                "Choose a collection page to manage its rows.",
                style = InfusionTvScaleCalibration.Type.SettingsContent,
                color = LocalContentColor.current.copy(alpha = 0.75f),
            )
        } else if (state.selectedTileKey != null && state.rows.isEmpty()) {
            Text(
                "This page defines no rows.",
                style = InfusionTvScaleCalibration.Type.SettingsContent,
                color = LocalContentColor.current.copy(alpha = 0.75f),
            )
        }

        state.rows.forEach { row ->
            CollectionRowManageRow(
                row = row,
                requesters = requesters,
                onFocused = ::reportFocus,
                onToggle = {
                    pendingFocusRowKey = row.rowKey
                    pendingFocusAction = RowManagerFocusAction.NAME
                    onToggleRow(row.rowKey)
                },
                onMoveUp = {
                    pendingFocusRowKey = row.rowKey
                    pendingFocusAction = RowManagerFocusAction.UP
                    onMoveRow(row.rowKey, -1)
                },
                onMoveDown = {
                    pendingFocusRowKey = row.rowKey
                    pendingFocusAction = RowManagerFocusAction.DOWN
                    onMoveRow(row.rowKey, 1)
                },
            )
        }

        state.status?.let { status ->
            Text(
                status,
                style = InfusionTvScaleCalibration.Type.SettingsContent,
                color = LocalContentColor.current.copy(alpha = 0.75f),
            )
        }

    }

    LaunchedEffect(state.rows, state.selectedTileKey) {
        val rowKey = pendingFocusRowKey ?: return@LaunchedEffect
        val action = pendingFocusAction
        pendingFocusRowKey = null
        val row = state.rows.firstOrNull { it.rowKey == rowKey }
        val nameTag = collectionRowEntryTag(rowKey)
        val target = when (action) {
            RowManagerFocusAction.NAME -> nameTag
            RowManagerFocusAction.UP ->
                if (row?.canMoveUp == true) collectionRowUpTag(rowKey) else nameTag
            RowManagerFocusAction.DOWN ->
                if (row?.canMoveDown == true) collectionRowDownTag(rowKey) else nameTag
        }
        if (requesters[target]?.requestFocus() == true) return@LaunchedEffect
        requesters[nameTag]?.requestFocus()
    }

    WidgetPickerReturnFocus(
        open = openSelector != null,
        originKey = selectorOriginKey,
        requesters = requesters,
    )

    when (openSelector) {
        RowManagerSelector.PACK -> {
            val current = state.packs.firstOrNull { it.packId == state.selectedPackId }
                ?: state.packs.firstOrNull() ?: return
            GeneralSelectorDialog(
                rowTag = SettingsCollectionRowsPackRow,
                choices = state.packs,
                current = current,
                choiceKey = { pack -> pack.packId },
                choiceLabel = { pack -> pack.name },
                onSelect = { pack ->
                    onSelectPack(pack.packId)
                    openSelector = null
                },
                onDismiss = { openSelector = null },
                panelWidth = WidgetsSelectorWidth,
                scrollIndicators = true,
            )
        }

        RowManagerSelector.GROUP -> {
            val current = state.groups.firstOrNull { it.groupKey == state.selectedGroupKey }
                ?: state.groups.firstOrNull() ?: return
            GeneralSelectorDialog(
                rowTag = SettingsCollectionRowsGroupRow,
                choices = state.groups,
                current = current,
                choiceKey = { group -> group.groupKey },
                choiceLabel = { group -> group.title },
                onSelect = { group ->
                    onSelectGroup(group.groupKey)
                    openSelector = null
                },
                onDismiss = { openSelector = null },
                panelWidth = WidgetsSelectorWidth,
                scrollIndicators = true,
            )
        }

        RowManagerSelector.PAGE -> {
            val current = state.tiles.firstOrNull { it.tileKey == state.selectedTileKey }
                ?: state.tiles.firstOrNull() ?: return
            GeneralSelectorDialog(
                rowTag = SettingsCollectionRowsPageRow,
                choices = state.tiles,
                current = current,
                choiceKey = { tile -> tile.tileKey },
                choiceLabel = { tile -> tile.title },
                onSelect = { tile ->
                    onSelectPage(tile.tileKey)
                    openSelector = null
                },
                onDismiss = { openSelector = null },
                panelWidth = WidgetsSelectorWidth,
                scrollIndicators = true,
            )
        }

        null -> Unit
    }
}

@Composable
private fun CollectionRowManageRow(
    row: CollectionRowEntryUi,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val nameTag = collectionRowEntryTag(row.rowKey)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = WidgetsRowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WidgetsRowSpacing),
    ) {

        InfusionTvButton(
            onClick = onToggle,
            colors = infusionTvButtonColors(),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .semantics {
                    this.contentDescription = rowDescription(row)
                }
                .then(stableFocusModifier(nameTag, requesters, onFocused)),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(row.title, style = InfusionTvScaleCalibration.Type.SettingsContent)
                    if (row.sourceLabel.isNotBlank()) {
                        Text(
                            row.sourceLabel,
                            style = InfusionTvScaleCalibration.Type.SettingsContent,
                            color = LocalContentColor.current.copy(alpha = 0.75f),
                        )
                    }
                }
                Text(
                    if (row.enabled) "Shown" else "Hidden",
                    style = InfusionTvScaleCalibration.Type.SettingsContent,
                    color = LocalContentColor.current.copy(alpha = if (row.enabled) 0.75f else 0.5f),
                )
            }
        }
        WidgetsActionButton(
            accessibleLabel = "Move up",
            tag = collectionRowUpTag(row.rowKey),
            enabled = row.canMoveUp,
            requesters = requesters,
            onFocused = onFocused,
            onClick = onMoveUp,
        ) {
            Text("▲", style = InfusionTvScaleCalibration.Type.SettingsContent)
        }
        WidgetsActionButton(
            accessibleLabel = "Move down",
            tag = collectionRowDownTag(row.rowKey),
            enabled = row.canMoveDown,
            requesters = requesters,
            onFocused = onFocused,
            onClick = onMoveDown,
        ) {
            Text("▼", style = InfusionTvScaleCalibration.Type.SettingsContent)
        }
    }
}
