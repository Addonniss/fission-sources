package dev.infusion.tv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.LocalContentColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text

@Composable
fun InfusionTvHeroFactChip(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = InfusionTvScaleCalibration.Type.Body,
) {
    val treatment = infusionTvHeroFactChipTreatment()
    val contourWidth = treatment.contourWidth
    Box(
        modifier = modifier
            .clip(InfusionTvContour.Shape)
            .background(treatment.containerColor)
            .then(
                if (contourWidth == null) {
                    Modifier
                } else {
                    Modifier.border(contourWidth, treatment.contentColor, InfusionTvContour.Shape)
                },
            )
            .padding(HeroFactChipPadding)

            .focusProperties { canFocus = false },
    ) {
        Text(text, style = style, color = treatment.contentColor)
    }
}

@Composable
fun InfusionTvHeroFactChip(
    modifier: Modifier = Modifier,
    style: TextStyle = InfusionTvScaleCalibration.Type.Body,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val treatment = infusionTvHeroFactChipTreatment()
    val contourWidth = treatment.contourWidth
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .clip(InfusionTvContour.Shape)
            .background(treatment.containerColor)
            .then(
                if (contourWidth == null) {
                    Modifier
                } else {
                    Modifier.border(contourWidth, treatment.contentColor, InfusionTvContour.Shape)
                },
            )
            .padding(HeroFactChipPadding)
            .focusProperties { canFocus = false },
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(RatingMarkGap),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalContentColor provides treatment.contentColor,
        ) {
            content()
        }
    }
}

@Composable
fun infusionTvHeroFactChipTreatment(): InfusionControlTreatment = InfusionControlTreatment(
    containerColor = InfusionTvContour.OnArtworkScrim,
    contentColor = InfusionTvContour.ControlContentColor,
    contourWidth = null,
)

private val HeroFactChipPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)

private val RatingMarkGap = 6.dp
