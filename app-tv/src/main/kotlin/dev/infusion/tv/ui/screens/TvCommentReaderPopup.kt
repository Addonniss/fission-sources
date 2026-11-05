package dev.infusion.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import dev.infusion.feature.vertical.media.ReviewUi
import dev.infusion.tv.ui.focus.RequestFocusOnKey
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvDetailsMetrics
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.LocalInfusionAccent
import kotlinx.coroutines.launch

internal const val CommentReaderPopupTag = "canonical:comments:reader:popup"

internal const val CommentReaderScrimTag = "canonical:comments:reader:scrim"

internal const val CommentReaderFocusKey = "canonical:comments:reader"

internal const val CommentReaderBodyTag = CommentReaderFocusKey

internal const val CommentReaderChevronUpTag = "canonical:comments:reader:chevron:up"
internal const val CommentReaderChevronDownTag = "canonical:comments:reader:chevron:down"
internal const val CommentReaderChevronLeftTag = "canonical:comments:reader:chevron:left"
internal const val CommentReaderChevronRightTag = "canonical:comments:reader:chevron:right"

internal fun commentReaderPreviousIndex(index: Int, count: Int): Int =
    (index - 1).coerceAtLeast(0).coerceAtMost((count - 1).coerceAtLeast(0))

internal fun commentReaderNextIndex(index: Int, count: Int): Int =
    (index + 1).coerceAtMost((count - 1).coerceAtLeast(0)).coerceAtLeast(0)

internal fun commentReaderReconciledIndex(lastIndex: Int, count: Int): Int =
    if (count <= 0) 0 else lastIndex.coerceIn(0, count - 1)

private val CommentReaderPadding = 24.dp

private val CommentReaderBodyPadding = 12.dp

private val CommentReaderHeaderGap = 8.dp

private val CommentReaderChevronGutter =
    InfusionTvDetailsMetrics.SecondaryActionIconSize + CommentReaderBodyPadding

private val CommentReaderChevronSize = InfusionTvDetailsMetrics.SecondaryActionIconSize

private const val CommentReaderPanelGrowth = 1.20f

private const val CommentReaderPanelWidthFraction =
    InfusionTvDetailsMetrics.HeroTextWidthFraction * CommentReaderPanelGrowth

private const val CommentReaderBodyMaxHeightFraction = 0.5f * CommentReaderPanelGrowth

private const val CommentReaderScrollLines = 3

@Composable
internal fun TvCommentReaderPopup(
    reviews: List<ReviewUi>,
    initialKey: String,
    requesters: MutableMap<String, FocusRequester>,
    onDismiss: (displayedKey: String) -> Unit,
) {
    if (reviews.isEmpty()) return
    val lastIndex = reviews.lastIndex

    var selectedKey by remember { mutableStateOf(initialKey) }

    var reconciledIndex by remember {
        mutableIntStateOf(reviews.indexOfFirst { it.key == initialKey }.coerceIn(0, lastIndex))
    }
    val selectedIndex = reviews.indexOfFirst { it.key == selectedKey }
    val index = if (selectedIndex >= 0) selectedIndex else commentReaderReconciledIndex(reconciledIndex, reviews.size)
    val displayed = reviews[index.coerceIn(0, lastIndex)]
    val accent = LocalInfusionAccent.current
    val density = LocalDensity.current
    val bodyScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val lineStepPx = with(density) {
        InfusionTvScaleCalibration.Type.Body.lineHeight.toPx()
    } * CommentReaderScrollLines

    LaunchedEffect(displayed.key) { bodyScroll.scrollTo(0) }

    var dismissed by remember { mutableStateOf(false) }
    val dismiss: () -> Unit = {
        if (!dismissed) {
            dismissed = true
            onDismiss(displayed.key)
        }
    }

    RequestFocusOnKey(key = CommentReaderFocusKey, requesters = requesters)
    val hasPrevious = index > 0
    val hasNext = index < lastIndex

    val canScrollUp by remember { derivedStateOf { bodyScroll.value > 0 } }
    val canScrollDown by remember { derivedStateOf { bodyScroll.value < bodyScroll.maxValue } }
    val showVertical = canScrollUp || canScrollDown

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {

                        Key.DirectionUp -> {
                            scope.launch { bodyScroll.scrollBy(-lineStepPx) }
                            true
                        }
                        Key.DirectionDown -> {
                            scope.launch { bodyScroll.scrollBy(lineStepPx) }
                            true
                        }

                        Key.DirectionLeft -> {
                            val next = commentReaderPreviousIndex(index, reviews.size)
                            selectedKey = reviews[next].key
                            reconciledIndex = next
                            true
                        }
                        Key.DirectionRight -> {
                            val next = commentReaderNextIndex(index, reviews.size)
                            selectedKey = reviews[next].key
                            reconciledIndex = next
                            true
                        }

                        Key.Back, Key.Escape -> {
                            dismiss()
                            true
                        }
                        else -> false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val readerWidth = maxWidth * CommentReaderPanelWidthFraction
            val bodyMaxHeight = maxHeight * CommentReaderBodyMaxHeightFraction

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(InfusionTvContour.CommentaryOverlayScrim)
                    .testTag(CommentReaderScrimTag)
                    .focusProperties { canFocus = false },
            )
            Surface(
                modifier = Modifier
                    .width(readerWidth)
                    .testTag(CommentReaderPopupTag),
                shape = InfusionTvContour.Shape,
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    modifier = Modifier.padding(CommentReaderPadding),
                    verticalArrangement = Arrangement.spacedBy(CommentReaderHeaderGap),
                ) {

                    ReviewAttribution(
                        review = displayed,
                        authorStyle = InfusionTvScaleCalibration.Type.HeadlineNormal,
                        supportingStyle = InfusionTvScaleCalibration.Type.Body,
                        modifier = Modifier.fillMaxWidth(),
                        includeDate = true,
                    )
                    Column(modifier = Modifier.fillMaxWidth()) {

                        if (showVertical) {
                            ReaderChevron(
                                icon = Icons.Filled.KeyboardArrowUp,
                                tag = CommentReaderChevronUpTag,
                                visible = canScrollUp,
                                accent = accent,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {

                            Box(
                                modifier = Modifier.width(CommentReaderChevronGutter),
                                contentAlignment = Alignment.Center,
                            ) {
                                ReaderChevron(
                                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    tag = CommentReaderChevronLeftTag,
                                    visible = hasPrevious,
                                    accent = accent,
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(max = bodyMaxHeight)
                                    .verticalScroll(bodyScroll)

                                    .padding(CommentReaderBodyPadding)
                                    .then(
                                        stableFocusModifier(
                                            key = CommentReaderFocusKey,
                                            requesters = requesters,
                                            onFocusedKeyChanged = {},
                                        ),
                                    )
                                    .focusable()
                                    .testTag(CommentReaderBodyTag),
                            ) {

                                Text(
                                    displayed.content,
                                    style = InfusionTvScaleCalibration.Type.Title,
                                )
                            }

                            Box(
                                modifier = Modifier.width(CommentReaderChevronGutter),
                                contentAlignment = Alignment.Center,
                            ) {
                                ReaderChevron(
                                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    tag = CommentReaderChevronRightTag,
                                    visible = hasNext,
                                    accent = accent,
                                )
                            }
                        }

                        if (showVertical) {
                            ReaderChevron(
                                icon = Icons.Filled.KeyboardArrowDown,
                                tag = CommentReaderChevronDownTag,
                                visible = canScrollDown,
                                accent = accent,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderChevron(
    icon: ImageVector,
    tag: String,
    visible: Boolean,
    accent: androidx.compose.ui.graphics.Color,
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (visible) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .size(CommentReaderChevronSize)
                    .testTag(tag),
            )
        } else {
            Spacer(modifier = Modifier.size(CommentReaderChevronSize))
        }
    }
}

internal const val CommentHeaderSeparator = " · "
