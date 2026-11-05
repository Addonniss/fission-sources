package dev.infusion.tv.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.infusion.tv.ui.presentation.TvBrowseContentAlignmentInset
import dev.infusion.tv.ui.theme.InfusionTvButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import dev.infusion.tv.ui.theme.InfusionTvAppearance
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.infusionTvButtonColors
import dev.infusion.tv.ui.theme.infusionTvNavigationItemBorder
import dev.infusion.tv.ui.theme.infusionTvNavigationItemColors

class RailSection(
    val railKey: String,
    val label: String,
    val content: @Composable (
        entryToken: Int,
        rememberedFocusKey: String?,
        onFocusRequested: (String) -> Unit,
        onReturnToRail: () -> Unit,
    ) -> Unit,
)

internal object RailContentBand {

    val PagePadding: Dp = 24.dp

    const val PanelTestTag: String = "settings:rail:panel"

    val LabelAnchor: Dp = TvBrowseContentAlignmentInset

    val ItemContentPadding: PaddingValues = TvTopShelfBand.TabContentPadding

    val ItemHorizontalPadding: Dp = TvTopShelfBand.TabHorizontalPadding

    const val LabelCapacityCharacters: Int = 12

    const val LabelAdvanceEm: Float = 0.62f

    val LabelCapacity: Dp =
        (
            LabelCapacityCharacters * LabelAdvanceEm *
                InfusionTvScaleCalibration.Type.Headline.fontSize.value
            ).dp

    val StartInset: Dp = LabelAnchor - ItemHorizontalPadding

    val Width: Dp =
        StartInset + ItemHorizontalPadding + LabelCapacity + ItemHorizontalPadding + PagePadding

    val BackgroundShape: Shape = InfusionTvContour.Shape

    const val ScrollHintUp: String = "▲"
    const val ScrollHintDown: String = "▼"
}

@Composable
fun RailContentPage(
    sections: List<RailSection>,
    selectedKey: String,
    onSelectedKeyChange: (String) -> Unit,
    onBackToTopShelf: () -> Unit,
    topShelfFocused: Boolean = false,
    focusRailOnEntry: Boolean = true,
    railEntryToken: Int = 0,
    onZoneFocusChanged: (String) -> Unit = {},
    editorKeys: Set<String> = emptySet(),

    inRowKeys: Set<String> = emptySet(),

    contentBackKeys: Set<String> = emptySet(),
) {
    val railRequesters = remember { mutableMapOf<String, FocusRequester>() }

    val sectionFocusKeys = remember { mutableMapOf<String, String?>() }

    var focusedContentKey by remember { mutableStateOf<String?>(null) }

    var activeSectionKey by remember { mutableStateOf(selectedKey) }

    var railFocusKey by remember { mutableStateOf<String?>(null) }

    val sectionEntryTokens = remember { mutableStateMapOf<String, Int>() }

    var focusInRail by remember { mutableStateOf(true) }

    var initialFocusRequested by remember { mutableStateOf(false) }

    val activeSection = sections.firstOrNull { it.railKey == activeSectionKey } ?: sections.first()

    fun sectionEntryToken(railKey: String): Int = sectionEntryTokens[railKey] ?: 0

    fun selectRailItem(railKey: String) {
        onSelectedKeyChange(railKey)
        if (activeSectionKey != railKey) activeSectionKey = railKey
        sectionEntryTokens.keys
            .filter { it != railKey }
            .forEach { sectionEntryTokens[it] = 0 }
        railRequesters[railKey]?.requestFocus()
    }

    fun enterSection(section: RailSection) {

        if (activeSectionKey != section.railKey) activeSectionKey = section.railKey
        onSelectedKeyChange(section.railKey)
        sectionEntryTokens[section.railKey] = sectionEntryToken(section.railKey) + 1
    }

    fun railCursorKey(): String = railFocusKey ?: activeSectionKey

    LaunchedEffect(sections.map { it.railKey }) {
        if (sections.none { it.railKey == activeSectionKey }) {
            sections.firstOrNull()?.let { selectRailItem(it.railKey) }
        }
    }

    LaunchedEffect(selectedKey) {
        if (selectedKey == activeSectionKey) return@LaunchedEffect
        if (sections.none { it.railKey == selectedKey }) return@LaunchedEffect
        selectRailItem(selectedKey)
    }

    fun returnToRail() {
        railRequesters[activeSection.railKey]?.requestFocus()
    }

    LaunchedEffect(railEntryToken) {
        if (railEntryToken > 0) {
            railRequesters[activeSection.railKey]?.requestFocus()
        }
    }

    BackHandler(enabled = !topShelfFocused) {
        if (focusInRail) {
            onBackToTopShelf()
        } else {
            returnToRail()
        }
    }

    val railColumnModifier = Modifier
        .width(RailContentBand.Width)
        .fillMaxHeight()
        .testTag(RailContentBand.PanelTestTag)
        .background(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RailContentBand.BackgroundShape,
        )
        .padding(
            start = RailContentBand.StartInset,
            end = RailContentBand.PagePadding,
            top = RailContentBand.PagePadding,
            bottom = RailContentBand.PagePadding,
        )
        .onGloballyPositioned {

            if (!initialFocusRequested && focusRailOnEntry) {
                initialFocusRequested = true
                railRequesters[activeSection.railKey]?.requestFocus()
            }
        }

        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val cursorKey = railCursorKey()
            val index = sections.indexOfFirst { it.railKey == cursorKey }
            when (event.key) {
                Key.DirectionUp -> {
                    if (index > 0) {

                        selectRailItem(sections[index - 1].railKey)
                    } else {

                        onBackToTopShelf()
                    }
                    true
                }
                Key.DirectionDown -> {

                    if (index in 0 until sections.lastIndex) {
                        selectRailItem(sections[index + 1].railKey)
                    }
                    true
                }
                Key.DirectionRight, Key.DirectionCenter -> {

                    val section = sections.firstOrNull { it.railKey == cursorKey } ?: activeSection
                    enterSection(section)
                    true
                }
                Key.DirectionLeft -> {

                    true
                }
                Key.Back -> {
                    onBackToTopShelf()
                    true
                }
                else -> false
            }
        }

    val shelfInset = LocalTvTopShelfInset.current

    val railScrollState = rememberScrollState()
    val railScope = rememberCoroutineScope()

    Row(modifier = Modifier.fillMaxSize().padding(top = shelfInset)) {

        Box(modifier = railColumnModifier) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(railScrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sections.forEach { section ->
                    val selected = section.railKey == activeSectionKey
                    val requester = remember(section.railKey) { FocusRequester() }
                    val bringIntoView = remember(section.railKey) { BringIntoViewRequester() }
                    railRequesters[section.railKey] = requester
                    InfusionTvButton(
                        onClick = { enterSection(section) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(section.railKey)
                            .focusRequester(requester)
                            .bringIntoViewRequester(bringIntoView)

                            .semantics { this.selected = selected }
                            .onFocusChanged { state ->

                                if (state.isFocused) {
                                    focusInRail = true
                                    railFocusKey = section.railKey
                                    onZoneFocusChanged(section.railKey)

                                    railScope.launch { bringIntoView.bringIntoView() }
                                }
                            },

                        colors = infusionTvNavigationItemColors(),
                        border = infusionTvNavigationItemBorder(selected = selected),

                        contentPadding = RailContentBand.ItemContentPadding,
                    ) {
                        Text(
                            text = section.label,
                            style = InfusionTvScaleCalibration.Type.Headline,

                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

            }

            if (railScrollState.canScrollBackward) {
                RailScrollHint(
                    glyph = RailContentBand.ScrollHintUp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = -RailContentBand.PagePadding),
                )
            }
            if (railScrollState.canScrollForward) {
                RailScrollHint(
                    glyph = RailContentBand.ScrollHintDown,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = RailContentBand.PagePadding),
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .onFocusChanged { state ->
                    if (state.hasFocus) focusInRail = false
                }

                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.DirectionLeft &&
                            focusedContentKey !in editorKeys &&
                            focusedContentKey !in inRowKeys -> {
                            returnToRail()
                            true
                        }
                        event.key == Key.Back -> {

                            if (focusedContentKey in contentBackKeys) {
                                false
                            } else {
                                returnToRail()
                                true
                            }
                        }
                        else -> false
                    }
                },
        ) {
            activeSection.content.invoke(

                sectionEntryTokens[activeSection.railKey] ?: 0,
                sectionFocusKeys[activeSection.railKey],
                { controlKey ->
                    focusedContentKey = controlKey
                    sectionFocusKeys[activeSection.railKey] = controlKey
                    onZoneFocusChanged(controlKey)
                },
                { returnToRail() },
            )
        }
    }
}

@Composable
private fun RailScrollHint(glyph: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(RailContentBand.PagePadding)
            .semantics { disabled() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = InfusionTvScaleCalibration.Type.SettingsContent,
            color = LocalContentColor.current.copy(alpha = 0.75f),
        )
    }
}
