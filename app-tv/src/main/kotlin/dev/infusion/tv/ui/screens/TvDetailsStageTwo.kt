package dev.infusion.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.infusion.feature.vertical.media.CanonicalHeroUi
import dev.infusion.tv.ui.focus.LocalTvZoneEntry
import dev.infusion.tv.ui.presentation.TvRowNavigation
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration

internal const val DetailsStageTwoTag = "details:stage2"

internal val TvDetailsStageItemSpacing = 16.dp

internal const val StageTwoHeadOnScreenTolerancePx = 1

internal fun detailsStageTwoEntryFocusKey(regions: List<DetailsStageTwoRegion>): String? =
    regions.firstOrNull()?.itemKeys?.firstOrNull()

@Composable
internal fun DetailsStageTwoBlock(
    hero: CanonicalHeroUi?,
    minHeight: Dp,
    stageHeadPresented: () -> Boolean,
    onReturnToStageOne: () -> Unit,
    headInFlow: Boolean = true,

    regionNavigation: TvRowNavigation? = null,

    stageTwoTopBoundary: (() -> Boolean)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val zone = LocalTvZoneEntry.current

    val moveSelection: (String) -> Unit = { target ->
        val entry = zone
        if (entry != null && entry.registry.request(target)) {
            entry.registry.disarm()
        } else {
            entry?.arm(target)
        }
    }
    Column(
        modifier = Modifier
            .testTag(DetailsStageTwoTag)
            .heightIn(min = minHeight)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                if (regionNavigation != null) {
                    val selected = zone?.registry?.lastFocusedKey
                    when (event.key) {
                        Key.DirectionDown -> {
                            val target = regionNavigation.downTarget(selected)
                            if (target != null) {
                                moveSelection(target)
                                return@onPreviewKeyEvent true
                            }
                        }

                        Key.DirectionUp -> {
                            val target = regionNavigation.upTarget(selected)
                            if (target != null) {
                                moveSelection(target)
                                return@onPreviewKeyEvent true
                            }

                            if (stageTwoTopBoundary?.invoke() == true) {
                                onReturnToStageOne()
                                return@onPreviewKeyEvent true
                            }
                            return@onPreviewKeyEvent false
                        }

                        else -> Unit
                    }
                    return@onPreviewKeyEvent false
                }

                if (event.key == Key.DirectionUp && stageHeadPresented()) {
                    onReturnToStageOne()
                    true
                } else {
                    false
                }
            },
        verticalArrangement = Arrangement.spacedBy(TvDetailsStageItemSpacing),
    ) {

        if (headInFlow && hero?.logoLocator != null) {

            TvCanonicalLogoHeader(
                logoLocator = hero?.logoLocator,
                modifier = Modifier.padding(horizontal = TvDetailsPageHorizontalPadding),
            )
        }
        content()
    }
}
