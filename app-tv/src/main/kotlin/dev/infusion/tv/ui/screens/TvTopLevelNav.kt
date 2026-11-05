package dev.infusion.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.infusion.core.domain.appearance.TopShelfAppearance
import dev.infusion.tv.R
import dev.infusion.tv.ui.shell.TvTopShelfBand
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import dev.infusion.tv.ui.focus.ContentSlot
import dev.infusion.tv.ui.focus.TvEntryEvent
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.presentation.RowMemoryStore
import dev.infusion.tv.ui.presentation.RowViewport
import dev.infusion.tv.ui.presentation.RowViewportMemory
import dev.infusion.tv.ui.theme.infusionTvButtonColors

enum class TopLevelDestination(val label: String) {
    HOME("Home"),
    JELLYFIN("Jellyfin"),
    WATCHLIST("Watchlist"),
    DISCOVER("Discover"),
    SEARCH("Search"),
    SETTINGS("Settings"),
}

fun TopLevelDestination.tabKey(): String = "tab:${name.lowercase()}"

fun isTopBarKey(key: String?): Boolean = key?.startsWith("tab:") == true

fun TopLevelDestination.tabIconRes(selected: Boolean): Int = when (this) {
    TopLevelDestination.HOME ->
        if (selected) R.drawable.ic_tab_home_selected else R.drawable.ic_tab_home
    TopLevelDestination.JELLYFIN -> R.drawable.ic_rating_jellyfin
    TopLevelDestination.WATCHLIST ->
        if (selected) R.drawable.ic_tab_watchlist_selected else R.drawable.ic_tab_watchlist
    TopLevelDestination.DISCOVER ->
        if (selected) R.drawable.ic_tab_discover_selected else R.drawable.ic_tab_discover
    TopLevelDestination.SEARCH -> R.drawable.ic_tab_search
    TopLevelDestination.SETTINGS ->
        if (selected) R.drawable.ic_tab_settings_selected else R.drawable.ic_tab_settings
}

class TvTopNavModel {
    private val lastContentFocus = mutableMapOf<TopLevelDestination, String?>()
    private var homeTabFocusRequested = false

    private val childDepartures = mutableSetOf<TopLevelDestination>()

    fun rememberContentFocus(tab: TopLevelDestination, key: String) {
        if (isTopBarKey(key)) return

        if (tab in childDepartures) return
        lastContentFocus[tab] = key
    }

    fun lastContentFocusFor(tab: TopLevelDestination): String? = lastContentFocus[tab]

    fun markChildDeparture(tab: TopLevelDestination) {
        childDepartures += tab
    }

    fun consumeChildReturn(tab: TopLevelDestination): Boolean = childDepartures.remove(tab)

    private val lastContentSlot = mutableMapOf<TopLevelDestination, ContentSlot>()

    fun rememberContentSlot(tab: TopLevelDestination, slot: ContentSlot) {

        if (tab in childDepartures) return
        lastContentSlot[tab] = slot
    }

    fun lastContentSlotFor(tab: TopLevelDestination): ContentSlot? = lastContentSlot[tab]

    private val lastRowContentFocus = mutableMapOf<TopLevelDestination, MutableMap<String, String>>()

    fun rememberRowContentFocus(tab: TopLevelDestination, rowKey: String, itemKey: String) {
        if (tab in childDepartures) return
        lastRowContentFocus.getOrPut(tab) { mutableMapOf() }[rowKey] = itemKey
    }

    fun lastRowContentFocusFor(tab: TopLevelDestination, rowKey: String): String? =
        lastRowContentFocus[tab]?.get(rowKey)

    fun rowMemoryStore(tab: TopLevelDestination): RowMemoryStore = object :
        RowMemoryStore,
        RowViewportMemory {

        override fun rememberedItem(rowKey: Any): String? =
            lastRowContentFocusFor(tab, rowKey.toString())

        override fun rememberItem(rowKey: Any, itemKey: String) =
            rememberRowContentFocus(tab, rowKey.toString(), itemKey)

        override fun rememberedViewport(rowKey: Any): RowViewport? =
            lastRowViewportFor(tab, rowKey.toString())

        override fun rememberViewport(rowKey: Any, viewport: RowViewport) =
            rememberRowViewport(tab, rowKey.toString(), viewport)
    }

    private val lastRowViewport =
        mutableMapOf<TopLevelDestination, MutableMap<String, RowViewport>>()

    fun rememberRowViewport(
        tab: TopLevelDestination,
        rowKey: String,
        viewport: RowViewport,
    ) {

        if (tab in childDepartures) return
        lastRowViewport.getOrPut(tab) { mutableMapOf() }[rowKey] = viewport
    }

    fun lastRowViewportFor(tab: TopLevelDestination, rowKey: String): RowViewport? =
        lastRowViewport[tab]?.get(rowKey)

    fun forget(tab: TopLevelDestination) {
        lastContentFocus.remove(tab)
        lastContentSlot.remove(tab)
        lastRowContentFocus.remove(tab)
        lastRowViewport.remove(tab)
        childDepartures -= tab
    }

    fun requestHomeTabFocus() {
        homeTabFocusRequested = true
    }

    fun consumeHomeTabFocusRequest(): Boolean {
        val requested = homeTabFocusRequested
        homeTabFocusRequested = false
        return requested
    }

    private var pendingReturnFocusKey: String? = null

    fun requestReturnFocus(key: String?) {
        pendingReturnFocusKey = key?.takeIf { it.isNotBlank() }
    }

    fun consumeReturnFocus(tab: TopLevelDestination): String? {
        val key = pendingReturnFocusKey ?: return null
        pendingReturnFocusKey = null
        return key.takeIf { it == tab.tabKey() }
    }
}

class TopLevelPageChromeState(
    val tab: TopLevelDestination,
    private val model: TvTopNavModel,
) {
    var focusedKey: String? by mutableStateOf(null)
        private set

    var lastContentKey: String? by mutableStateOf(null)
        private set

    val entryIsChildReturn: Boolean = model.consumeChildReturn(tab)

    var shelfRevealedByBack: Boolean by mutableStateOf(false)
        private set

    var lastEntryEvent: TvEntryEvent by mutableStateOf(TvEntryEvent.LANDING)
        private set

    val barFocused: Boolean get() = isTopBarKey(focusedKey)

    var entryToken: Int by mutableIntStateOf(0)
        private set

    val entryActIsActiveTabReselect: Boolean
        get() = entryToken > 0 && lastEntryEvent == TvEntryEvent.ACTIVE_TAB_RESELECT

    val childReturnRestorationOwed: Boolean
        get() = entryIsChildReturn && !entryActIsActiveTabReselect

    var entryActFramePending: Boolean by mutableStateOf(false)
        private set

    fun requestContentEntry() = requestEntry(activeTabReselect = false)

    fun requestReturnToTop() = requestEntry(activeTabReselect = true)

    private fun requestEntry(activeTabReselect: Boolean) {
        lastEntryEvent = when {
            activeTabReselect -> TvEntryEvent.ACTIVE_TAB_RESELECT
            shelfRevealedByBack -> TvEntryEvent.SHELF_REENTRY
            else -> TvEntryEvent.FRESH_TAB
        }
        shelfRevealedByBack = false

        entryActFramePending = activeTabReselect
        entryToken += 1
    }

    fun onShelfRevealedByBack() {
        shelfRevealedByBack = true
    }

    fun onContentKeyFocused(key: String) {
        focusedKey = key
        if (isTopBarKey(key)) return
        lastContentKey = key
        model.rememberContentFocus(tab, key)

        entryActFramePending = false
    }

    fun onBarKeyFocused(key: String) {

        val previous = focusedKey
        if (previous != null && !isTopBarKey(previous)) {
            shelfRevealedByBack = true
        }
        focusedKey = key
    }
}

@Composable
fun rememberTopLevelPageChrome(
    tab: TopLevelDestination,
    model: TvTopNavModel,
): TopLevelPageChromeState = remember(tab) { TopLevelPageChromeState(tab, model) }

@Composable
fun TvTopLevelNav(
    current: TopLevelDestination,
    jellyfinConnected: Boolean,
    requesters: MutableMap<String, FocusRequester>,
    onNavigate: (TopLevelDestination) -> Unit,
    onBarKeyFocused: (String) -> Unit = {},
    onFocusedTabDown: (tab: TopLevelDestination, key: Key) -> Boolean = { _, _ -> false },

    appearance: TopShelfAppearance = TopShelfAppearance.DEFAULT,

    hiddenFromUser: Boolean = false,
) {
    var lastFocusedTabKey by remember { mutableStateOf<String?>(null) }

    val labelMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val iconSize = TvTopShelfBand.iconSize()
    fun labelWidthOf(destination: TopLevelDestination): Dp =
        with(density) {
            labelMeasurer.measure(
                text = destination.label,
                style = InfusionTvScaleCalibration.Type.Emphasis,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
                maxLines = 1,
            ).size.width.toDp()
        }

    fun itemWidthOf(destination: TopLevelDestination): Dp =
        TvTopShelfBand.tabWidthFor(
            labelWidth = labelWidthOf(destination),
            appearance = appearance,
            iconSize = iconSize,
        )

    val visibleDestinations = remember(jellyfinConnected) {
        TopLevelDestination.entries.filter { destination ->
            destination != TopLevelDestination.JELLYFIN || jellyfinConnected
        }
    }

    fun wrapFocusTo(target: TopLevelDestination): Boolean =
        requesters[target.tabKey()]?.requestFocus() ?: false

    LaunchedEffect(jellyfinConnected) {
        if (!jellyfinConnected && lastFocusedTabKey == TopLevelDestination.JELLYFIN.tabKey()) {
            requesters[TopLevelDestination.HOME.tabKey()]?.requestFocus()
        }
    }
    Row(

        modifier = Modifier.wrapContentWidth(),
        horizontalArrangement = Arrangement.spacedBy(TvTopShelfBand.TabGap),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        for (destination in visibleDestinations) {
            val isCurrent = destination == current
            InfusionTvButton(
                onClick = { onNavigate(destination) },

                colors = infusionTvButtonColors(containerColor = TvTopShelfBand.TabBackgroundColor),
                selected = isCurrent,
                contentPadding = TvTopShelfBand.TabContentPadding,
                modifier = Modifier

                    .width(itemWidthOf(destination))
                    .then(
                        stableFocusModifier(
                            key = destination.tabKey(),
                            requesters = requesters,
                            onFocusedKeyChanged = { key ->
                                lastFocusedTabKey = key
                                onBarKeyFocused(key)
                            },
                        ),
                    )

                    .then(
                        if (hiddenFromUser) {
                            Modifier.semantics { hideFromAccessibility() }
                        } else {
                            Modifier
                        },
                    )
                    .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                    when (event.key) {
                        Key.DirectionLeft -> {
                            if (destination == visibleDestinations.first() &&
                                wrapFocusTo(visibleDestinations.last())
                            ) {
                                true
                            } else {
                                false
                            }
                        }
                        Key.DirectionRight -> {
                            if (destination == visibleDestinations.last() &&
                                wrapFocusTo(visibleDestinations.first())
                            ) {
                                true
                            } else {
                                false
                            }
                        }

                        else -> onFocusedTabDown(destination, event.key)
                    }
                },
            ) {
                TabContent(
                    destination = destination,
                    selected = isCurrent,
                    appearance = appearance,
                    iconSize = iconSize,
                )
            }
        }
    }
}

@Composable
private fun TabContent(
    destination: TopLevelDestination,
    selected: Boolean,
    appearance: TopShelfAppearance,
    iconSize: Dp,
) {
    val glyph: @Composable () -> Unit = {
        Icon(
            painter = painterResource(destination.tabIconRes(selected = selected)),

            contentDescription = if (appearance.showsText) null else destination.label,
            tint = LocalContentColor.current,
            modifier = Modifier.size(iconSize),
        )
    }
    when {
        appearance.showsIcons && appearance.showsText -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                glyph()
                Spacer(Modifier.width(TvTopShelfBand.IconLabelGap))
                Text(
                    text = destination.label,
                    style = InfusionTvScaleCalibration.Type.Emphasis,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        appearance.showsIcons -> {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                glyph()
            }
        }
        else -> {

            Text(
                text = destination.label,
                style = InfusionTvScaleCalibration.Type.Emphasis,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal val ExitDialogMinWidth: Dp = 240.dp
internal val ExitDialogMaxWidth: Dp = 420.dp
internal val ExitDialogHorizontalPadding: Dp = 28.dp
internal val ExitDialogVerticalPadding: Dp = 24.dp
internal val ExitDialogActionSpacing: Dp = 12.dp

internal const val ExitDialogPanelTag = "exit-dialog-panel"

internal const val ExitDialogRootTag = "exit-dialog-root"

internal const val ExitDialogTitle = "Exit infusion?"

@Composable
fun TvExitConfirmationDialog(
    onExit: () -> Unit,
    onCancel: () -> Unit,
) {
    val requesters = remember { mutableMapOf<String, FocusRequester>() }

    LaunchedEffect(Unit) {
        repeat(30) {
            androidx.compose.runtime.withFrameNanos { }
            if (requesters["exit-cancel"]?.requestFocus() == true) return@LaunchedEffect
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
                .testTag(ExitDialogRootTag),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .testTag(ExitDialogPanelTag)

                    .wrapContentWidth()
                    .widthIn(min = ExitDialogMinWidth, max = ExitDialogMaxWidth)
                    .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium),
                shape = MaterialTheme.shapes.medium,
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
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
                    Text(ExitDialogTitle, style = InfusionTvScaleCalibration.Type.Headline)
                    Row(horizontalArrangement = Arrangement.spacedBy(ExitDialogActionSpacing)) {
                        InfusionTvButton(
                            onClick = onCancel,
                            colors = infusionTvButtonColors(),
                            modifier = stableFocusModifier("exit-cancel", requesters, {}),
                        ) { Text("Cancel") }
                        InfusionTvButton(
                            onClick = onExit,
                            colors = infusionTvButtonColors(),
                            modifier = stableFocusModifier("exit-confirm", requesters, {}),
                        ) { Text("Exit") }
                    }
                }
            }
        }
    }
}
