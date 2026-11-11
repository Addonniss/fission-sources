package dev.infusion.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.LocalInfusionTheme
import dev.infusion.tv.ui.theme.settingsValueChipFill
import dev.infusion.tv.ui.theme.themeValueChip

internal enum class SettingsRowPresentation {

    COMPACT,

    EXPANDED,
}

internal object SettingsRowPresentationMetrics {

    val ValueGap: Dp = 24.dp

    val HelpGap: Dp = 2.dp

    val TrailingGap: Dp = 12.dp

    val ValueChipHorizontalPadding: Dp = 10.dp

    val ValueChipVerticalPadding: Dp = 3.dp
}

@Composable
internal fun SettingsRowLabelBlock(
    label: String,
    help: String?,
    focused: Boolean,
    maxLines: Int,
    modifier: Modifier = Modifier,
    labelIndent: Dp = 0.dp,
    labelColor: Color = Color.Unspecified,
) {
    Column(
        modifier = modifier.padding(start = labelIndent),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            label,
            style = InfusionTvScaleCalibration.Type.SettingsContent,
            color = labelColor,
            maxLines = maxLines,
            overflow = if (maxLines == 1) TextOverflow.Ellipsis else TextOverflow.Clip,
        )
        if (focused && help != null) {
            Text(
                help,
                style = InfusionTvScaleCalibration.Type.Body,
                color = LocalContentColor.current.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = SettingsRowPresentationMetrics.HelpGap),
            )
        }
    }
}

@Composable
internal fun SettingsRowBody(
    presentation: SettingsRowPresentation,
    label: String,
    help: String?,
    value: String,
    focused: Boolean,
    modifier: Modifier = Modifier,
    labelIndent: Dp = 0.dp,
    labelColor: Color = Color.Unspecified,
    valueColor: Color = Color.Unspecified,

    valueModifier: Modifier = Modifier,

    valueContent: (@Composable () -> Unit)? = null,

    valueChip: Boolean = true,

    valueChipTag: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val expanded = presentation == SettingsRowPresentation.EXPANDED

    val chipFill = if (valueChip && value.isNotBlank() && valueContent == null) {
        settingsValueChipFill(LocalInfusionTheme.current, focused)
    } else {
        null
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRowLabelBlock(
            label = label,
            help = help,
            focused = expanded && focused,
            maxLines = if (expanded) 1 else Int.MAX_VALUE,
            modifier = Modifier.weight(1f),
            labelIndent = labelIndent,
            labelColor = labelColor,
        )
        if (expanded) Spacer(Modifier.width(SettingsRowPresentationMetrics.ValueGap))
        if (valueContent != null) {

            valueContent()
        } else if (chipFill != null) {

            Row(
                modifier = Modifier
                    .then(if (valueChipTag != null) Modifier.testTag(valueChipTag) else Modifier)
                    .themeValueChip(focused)
                    .padding(
                        horizontal = SettingsRowPresentationMetrics.ValueChipHorizontalPadding,
                        vertical = SettingsRowPresentationMetrics.ValueChipVerticalPadding,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    value,
                    style = InfusionTvScaleCalibration.Type.SettingsContent,
                    color = Color.Unspecified,
                    maxLines = if (expanded) 1 else Int.MAX_VALUE,
                    overflow = if (expanded) TextOverflow.Ellipsis else TextOverflow.Clip,
                    modifier = valueModifier,
                )
                trailing?.invoke()
            }
        } else {
            Text(
                value,
                style = InfusionTvScaleCalibration.Type.SettingsContent,
                color = valueColor,
                maxLines = if (expanded) 1 else Int.MAX_VALUE,
                overflow = if (expanded) TextOverflow.Ellipsis else TextOverflow.Clip,
                modifier = valueModifier,
            )
        }
        if (chipFill == null) trailing?.invoke()
    }
}

@Composable
internal fun settingsRowBringIntoView(focused: Boolean): Modifier {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester.bringIntoView() }
    }
    return Modifier.bringIntoViewRequester(requester)
}
