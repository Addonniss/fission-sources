package dev.infusion.tv.ui.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.infusion.tv.ui.focus.FocusKeyRegistry
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.theme.InfusionTvAppearance
import dev.infusion.tv.ui.theme.InfusionTvFilterChip
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import androidx.tv.material3.Text

val LibraryOverlayMinWidth: Dp = 180.dp

val LibraryOverlayMaxWidth: Dp = 380.dp

val LibraryOverlayGap: Dp = 8.dp

val LibraryOverlayBottomMargin: Dp = 16.dp

val LibraryOverlayMinHeight: Dp = 160.dp

val LibraryOverlayPadding: Dp = 8.dp

val LibraryOverlayOptionSpacing: Dp = 4.dp

const val LibraryOverlayChevron: String = "\u203A"

@Composable
fun TvOverlayWindow(
    tag: String,
    alignment: Alignment,
    offset: IntOffset,
    onDismiss: () -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,

    contentAlpha: Float = 1f,
    content: @Composable ColumnScope.() -> Unit,
) {
    Popup(
        alignment = alignment,
        offset = offset,
        onDismissRequest = onDismiss,

        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {

        Box(
            modifier = modifier
                .widthIn(min = LibraryOverlayMinWidth, max = LibraryOverlayMaxWidth),
        ) {
            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)

                    .background(InfusionTvAppearance.OledBlack)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.Back || event.key == Key.Escape)
                        ) {
                            onDismiss()
                            true
                        } else {
                            false
                        }
                    }
                    .testTag(tag),
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = maxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(LibraryOverlayPadding),
                ) {
                    Column(
                        modifier = Modifier.alpha(contentAlpha),
                        verticalArrangement = Arrangement.spacedBy(LibraryOverlayOptionSpacing),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

@Composable
fun TvOverlayOption(
    label: String,
    selected: Boolean,
    focusKey: String,
    requesters: FocusKeyRegistry,
    onClick: () -> Unit,
    enabled: Boolean = true,
    opensChild: Boolean = false,
) {
    InfusionTvFilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        modifier = stableFocusModifier(
            key = focusKey,
            requesters = requesters,
            onFocusedKeyChanged = { requesters.onFocusReport(it) },
        ).fillMaxWidth().testTag(focusKey),
    ) {
        if (opensChild) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = InfusionTvScaleCalibration.Type.Emphasis,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = LibraryOverlayChevron,
                    style = InfusionTvScaleCalibration.Type.Emphasis,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        } else {
            Text(
                text = label,
                style = InfusionTvScaleCalibration.Type.Emphasis,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

suspend fun FocusKeyRegistry.requestFocusEventually(key: String, frames: Int = 30): Boolean {
    arm(key)
    repeat(frames) {
        withFrameNanos { }
        if (request(key)) {
            disarm()
            return true
        }
    }
    disarm()
    return false
}
