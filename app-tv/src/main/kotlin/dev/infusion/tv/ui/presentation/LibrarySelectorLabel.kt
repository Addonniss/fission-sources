package dev.infusion.tv.ui.presentation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration

const val LibrarySelectorChevronTag: String = "library-selector-chevron"

const val LibrarySelectorLabelTag: String = "library-selector-label"

const val LibrarySelectorChevronRotation: Float = 90f

private val SelectorChevronGap = 10.dp

@Composable
fun LibrarySelectorLabel(
    label: String,
    withChevron: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!withChevron) {

        Text(
            text = label,
            style = InfusionTvScaleCalibration.Type.Emphasis,
            maxLines = 1,
            modifier = modifier,
        )
        return
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {

        Text(
            text = label,
            style = InfusionTvScaleCalibration.Type.Emphasis,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .testTag(LibrarySelectorLabelTag),
        )
        Text(
            text = LibraryOverlayChevron,
            style = InfusionTvScaleCalibration.Type.Emphasis,
            maxLines = 1,

            modifier = Modifier
                .padding(start = SelectorChevronGap)
                .rotate(LibrarySelectorChevronRotation)
                .testTag(LibrarySelectorChevronTag),
        )
    }
}
