package dev.infusion.tv.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import dev.infusion.tv.ui.screens.HomeHeroSpotlightFocusKey
import dev.infusion.tv.ui.screens.TopLevelDestination
import dev.infusion.tv.ui.screens.TopLevelPageChromeState
import dev.infusion.tv.ui.screens.TvExitConfirmationDialog
import dev.infusion.tv.ui.screens.TvTopLevelNav
import dev.infusion.tv.ui.screens.TvTopNavModel
import dev.infusion.tv.ui.screens.tabKey
import dev.infusion.tv.ui.focus.FocusKeyRegistry
import dev.infusion.tv.ui.focus.LocalTopLevelContentUpRequester
import dev.infusion.tv.ui.focus.LocalTvZoneEntry
import dev.infusion.tv.ui.focus.RequestZoneEntry
import dev.infusion.tv.ui.focus.TvEntryEvent
import dev.infusion.tv.ui.focus.TvFreshEntry
import dev.infusion.tv.ui.focus.rememberTvZoneEntry
import dev.infusion.tv.ui.theme.LocalTvTopShelfAppearance

enum class TvZone {
    TOP_SHELF,
    PAGE_CONTENT,
    SETTINGS_RAIL,
    SETTINGS_CONTENT,
}

fun tvZoneOf(key: String, railKeys: Set<String>): TvZone = when {
    dev.infusion.tv.ui.screens.isTopBarKey(key) -> TvZone.TOP_SHELF
    key in railKeys -> TvZone.SETTINGS_RAIL
    else -> TvZone.PAGE_CONTENT
}

@Composable
fun PersistentTopShelf(
    page: TopLevelDestination,
    jellyfinConnected: Boolean,
    requesters: MutableMap<String, FocusRequester>,
    onNavigate: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,

    hiddenWhileSpotlight: () -> Boolean = { false },
    onBarKeyFocused: (String) -> Unit = {},
    onTabDown: (tab: TopLevelDestination, key: Key) -> Boolean = { _, _ -> false },
) {

    val appearance = LocalTvTopShelfAppearance.current
    Row(
        modifier = modifier
            .fillMaxWidth()

            .height(TvTopShelfBand.height())

            .graphicsLayer {
                alpha = TvTopShelfBand.alphaFor(hiddenWhileSpotlight())
            }
            .padding(
                horizontal = TvTopShelfBand.HorizontalPadding,
                vertical = TvTopShelfBand.VerticalPadding,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvTopLevelNav(
            current = page,
            jellyfinConnected = jellyfinConnected,
            requesters = requesters,
            onNavigate = onNavigate,
            onBarKeyFocused = onBarKeyFocused,
            onFocusedTabDown = onTabDown,
            appearance = appearance,

            hiddenFromUser = hiddenWhileSpotlight(),
        )
    }
}

internal fun tvTopShelfHidden(
    page: TopLevelDestination,
    focusedKey: String?,

    shelfFocused: Boolean,

    spotlightAtPageTop: Boolean,
): Boolean {
    if (page != TopLevelDestination.HOME) return false

    if (focusedKey == HomeHeroSpotlightFocusKey) return true

    return spotlightAtPageTop && !shelfFocused
}

internal fun shelfBandRecessionPx(contentScrollPx: Float, bandHeightPx: Float): Float =
    if (contentScrollPx * 2f < bandHeightPx) 0f else bandHeightPx

@Composable
fun InfusionTvShell(
    page: TopLevelDestination,
    jellyfinConnected: Boolean,
    model: TvTopNavModel,
    requesters: FocusKeyRegistry,
    chrome: TopLevelPageChromeState,
    entryDefaultKey: String?,
    onNavigateTo: (TopLevelDestination) -> Unit,
    onExit: () -> Unit,
    contentBackHandledInternally: Boolean = false,
    entryFocusHandledInternally: Boolean = false,

    entryIgnoresRemembered: Boolean = false,

    entryFreshKey: String? = null,

    entryFreshReady: Boolean = true,

    spotlightAtPageTop: Boolean = false,
    onTopShelfTabDown: (tab: TopLevelDestination, key: Key) -> Boolean = { _, _ -> false },
    content: @Composable () -> Unit,
) {
    var showExit by remember { mutableStateOf(false) }

    val zone = rememberTvZoneEntry(requesters)

    val overlayAccess = LocalTvGlobalOverlayAccess.current
    DisposableEffect(overlayAccess, requesters) {
        overlayAccess?.attachOriginRestore { key ->
            key != null && requesters[key]?.requestFocus() == true
        }
        onDispose { overlayAccess?.attachOriginRestore(null) }
    }

    val shelfSeam = remember { TvTopShelfScrollSeam() }

    val shelfHeight = TvTopShelfBand.height()
    val shelfHeightPx = with(LocalDensity.current) { shelfHeight.toPx() }

    if (!contentBackHandledInternally) {

        BackHandler(enabled = !chrome.barFocused && !showExit) {
            requesters[page.tabKey()]?.requestFocus()

            chrome.onShelfRevealedByBack()
        }
    }

    BackHandler(enabled = chrome.barFocused && !showExit) {
        if (page == TopLevelDestination.HOME &&
            chrome.focusedKey == TopLevelDestination.HOME.tabKey()
        ) {
            showExit = true
        } else if (page == TopLevelDestination.HOME) {
            requesters[TopLevelDestination.HOME.tabKey()]?.requestFocus()
        } else {
            model.requestHomeTabFocus()
            onNavigateTo(TopLevelDestination.HOME)
        }
    }

    val journey = TvPageJourneys.of(page)

    val entryEvent = remember(page, chrome.entryToken) {
        when {

            chrome.entryToken > 0 -> chrome.lastEntryEvent

            chrome.entryIsChildReturn -> TvEntryEvent.CHILD_RETURN

            else -> TvEntryEvent.LANDING
        }
    }

    val homeTabIntent = if (page == TopLevelDestination.HOME) {
        model.consumeHomeTabFocusRequest()
    } else {
        false
    }

    LaunchedEffect(requesters.lastContentSlot) {
        requesters.lastContentSlot?.let { model.rememberContentSlot(page, it) }
    }

    val exactReturnTabKey = remember(page, chrome.entryToken) {

        if (chrome.childReturnRestorationOwed) model.consumeReturnFocus(page) else null
    }
    if (exactReturnTabKey != null) {

        LaunchedEffect(exactReturnTabKey, page) {
            repeat(3) {
                if (requesters[exactReturnTabKey]?.requestFocus() == true) {
                    chrome.onShelfRevealedByBack()
                    return@LaunchedEffect
                }
                withFrameNanos { }
            }

            chrome.onShelfRevealedByBack()
            chrome.requestContentEntry()
        }
    }

    if (!entryFocusHandledInternally && exactReturnTabKey == null) {
        RequestZoneEntry(
            entry = zone,
            entryToken = chrome.entryToken,

            force = chrome.entryToken > 0 || chrome.entryIsChildReturn,
            rememberedKey = when {
                homeTabIntent || entryIgnoresRemembered -> null

                entryEvent == TvEntryEvent.ACTIVE_TAB_RESELECT -> null
                else -> model.lastContentFocusFor(page)
            },
            rememberedSlot = model.lastContentSlotFor(page),
            defaultKey = if (homeTabIntent) null else entryDefaultKey,
            anchorKey = page.tabKey(),
            alreadyEntered = {
                val key = chrome.focusedKey
                key != null && key != page.tabKey()
            },
            settleOnAnchor = homeTabIntent,
            resetKey = page,

            event = entryEvent,

            freshEntryKey = entryFreshKey,
            freshEntry = journey.freshEntry,
            freshEntryOnLanding = journey.landingFreshEntry,
            freshEntryReady = entryFreshReady,
        )
    }

    LaunchedEffect(chrome.entryToken) {
        if (chrome.entryToken > 0 && chrome.lastEntryEvent == TvEntryEvent.ACTIVE_TAB_RESELECT) {
            shelfSeam.requestTopReset()
        }
    }

    CompositionLocalProvider(
        LocalTvZoneEntry provides zone,

        LocalTvTopShelfInset provides shelfHeight,
        LocalTvTopShelfScrollSeam provides shelfSeam,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            Box(modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(
                    LocalTopLevelContentUpRequester provides
                        if (contentBackHandledInternally) null else requesters[page.tabKey()],
                ) {
                    content()
                }
            }

            PersistentTopShelf(
                page = page,
                jellyfinConnected = jellyfinConnected,
                requesters = requesters,

                hiddenWhileSpotlight = {
                    tvTopShelfHidden(
                        page = page,
                        focusedKey = chrome.focusedKey,
                        shelfFocused = chrome.barFocused,
                        spotlightAtPageTop = spotlightAtPageTop,
                    )
                },
                onNavigate = { destination ->
                    if (destination == page) {

                        chrome.requestReturnToTop()
                    } else {
                        onNavigateTo(destination)
                    }
                },
                onBarKeyFocused = {
                    zone.onFocusReport(it)
                    chrome.onBarKeyFocused(it)
                },
                onTabDown = { tab, key ->
                    when {

                        key == Key.DirectionUp && overlayAccess?.enterFromShelf(tab.tabKey()) == true ->
                            true
                        !entryFocusHandledInternally && key == Key.DirectionDown -> {

                            chrome.requestContentEntry()
                            true
                        }
                        else -> onTopShelfTabDown(tab, key)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .offset {
                        val scrollPx = shelfSeam.contentScrollPx
                        val recessionPx = if (chrome.barFocused) {
                            0
                        } else {

                            shelfBandRecessionPx(scrollPx, shelfHeightPx).roundToInt()
                        }
                        IntOffset(x = 0, y = -recessionPx)
                    },
            )
        }
    }

    if (showExit) {
        TvExitConfirmationDialog(
            onExit = onExit,
            onCancel = {
                showExit = false

                requesters[TopLevelDestination.HOME.tabKey()]?.requestFocus()
            },
        )
    }
}
