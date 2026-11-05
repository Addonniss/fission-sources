package dev.infusion.tv.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.infusion.core.playback.api.PlaybackSegment
import dev.infusion.core.playback.api.PlaybackSegmentTimeline
import dev.infusion.core.playback.api.SegmentDisplayMode
import dev.infusion.core.playback.api.SegmentProvenance
import dev.infusion.core.playback.api.SegmentType
import dev.infusion.feature.vertical.player.PlayerMetadataPresentation
import dev.infusion.tv.ui.theme.InfusionTvAppearance
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvDetailsMetrics
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.LocalInfusionAccent
import java.time.LocalTime

/*
 * Portions of this file are adapted from android/tv-samples - JetStreamCompose
 * (https://github.com/android/tv-samples/tree/main/JetStreamCompose),
 * Copyright 2023 Google LLC, licensed under the Apache License, Version 2.0.
 * A copy of that licence is in LICENSE; see also THIRD-PARTY-NOTICES.md.
 */

internal const val PlayerOverlayScrimTag = "player:overlay-scrim"

internal const val PlayerMediaHeaderTag = "player:media-header"
internal const val PlayerGenreLineTag = "player:genre-line"
internal const val PlayerSynopsisTag = "player:synopsis"
internal const val PlayerFactLineTag = "player:fact-line"

internal const val PlayerActionRowTag = "player:action-row"
internal const val PlayerCastAffordanceTag = "player:cast-affordance"

internal const val PlayerSeekerTag = "player:seeker"
internal const val PlayerTimelineTag = "player:timeline"
internal const val PlayerElapsedTag = "player:elapsed"
internal const val PlayerRemainingTag = "player:remaining"

internal const val PlayerSegmentStripTag = "player:segment-strip"
internal const val PlayerSegmentLegendTag = "player:segment-legend"

internal const val PlayerMoreTag = "player:more"

internal const val PlayerClockTag = "player:clock"
internal const val PlayerEndsAtTag = "player:ends-at"

internal const val PlayerSeekPulseTag = "player:seek-pulse"

internal const val PlayerStingerNoticeTag = "player:stinger-notice"

internal object TvPlayerOverlayAppearance {

    const val ScrimTopAlpha: Float = 0.1f
    const val ScrimBottomAlpha: Float = 0.8f

    val ScrimBrush: Brush = Brush.verticalGradient(
        listOf(
            InfusionTvAppearance.OledBlack.copy(alpha = ScrimTopAlpha),
            InfusionTvAppearance.OledBlack.copy(alpha = ScrimBottomAlpha),
        ),
    )

    val IconButtonSize = 20.dp
    val IconGlyphSize = 12.dp
    val IconButtonContentPadding = PaddingValues(4.dp)

    val ControlGap = 6.dp

    val TitleLineGap = 2.dp

    val HeaderToSeekerGap = 8.dp

    val TimelineLabelGap = 6.dp

    val TimelineHeight = 4.dp
    val TimelineHorizontalPadding = 4.dp
    const val TimelineTrackAlpha: Float = 0.24f

    val SegmentStripHeight = 5.dp
    val SegmentLegendGap = 4.dp
    val SegmentLegendSwatchSize = 8.dp

    const val SegmentBandThicknessFraction: Float = 0.7f
    const val SegmentBandDashFraction: Float = 1.1f
    const val SegmentBandGapFraction: Float = 0.8f
    const val SegmentMarkerThicknessFraction: Float = 0.35f

    val SegmentLegendStartInset = 26.dp

    const val TimelineBufferedAlpha: Float = 0.55f

    val OverlayTopPadding = 4.dp
    val OverlayBottomPadding = 16.dp

    val ClockTopPadding = 24.dp

    val ClockLineGap = 2.dp

    val PulseDiscSize = 88.dp
    val PulseGlyphSize = 40.dp
    const val PulseDiscAlpha: Float = 0.6f

    val PulseNumeralGap = 6.dp

    val ChapterPulseGlyphSize = 26.dp
    val ChapterPulseInsideGap = 2.dp
    val ChapterPulseBelowGap = 6.dp

    val ChapterPulseBelowMaxWidth = 240.dp

    val CastAffordanceSize = 16.dp

    val CastAffordanceGap = 10.dp

    val InfoLineBlockGap = ControlGap + CastAffordanceGap

    const val SeekFeedbackMillis: Long = 2_000L

    const val StingerNoticeAlpha: Float = 0.85f
    val StingerNoticeHorizontalPadding = 16.dp
    val StingerNoticeVerticalPadding = 10.dp
    val StingerNoticeGlyphSize = 16.dp

    val SkipIntroControlHeight = 24.dp
    val SkipIntroControlContentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)

    val InfoLineRowHeight = 35.dp
    val SeekerRowHeight = 35.dp
    val ContextualActionBottomInset =
        OverlayBottomPadding + InfoLineRowHeight + ControlGap + SeekerRowHeight + HeaderToSeekerGap

    val ContextualActionCastInset = CastAffordanceGap + CastAffordanceSize
}

@Composable
internal fun TvPlayerOverlay(
    isControlsVisible: Boolean,
    isInformationalVisible: Boolean,
    seekFeedback: TvPlayerSeekFeedback,
    clock: String,
    endsAt: String?,
    castAvailable: Boolean,
    metadata: PlayerMetadataPresentation,
    modifier: Modifier = Modifier,

    chapterFeedback: TvPlayerChapterFeedback? = null,

    seekFeedbackSeconds: Int? = null,

    contextualActions: (@Composable () -> Unit)? = null,
    controls: @Composable () -> Unit,
) {
    val isOverlayVisible = isControlsVisible || isInformationalVisible
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = isOverlayVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            TvPlayerCinematicScrim(Modifier.fillMaxSize())
        }

        AnimatedVisibility(
            visible = isInformationalVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            TvPlayerClockBlock(clock = clock, endsAt = endsAt)
        }

        Column(Modifier.fillMaxSize()) {

            Box(Modifier.weight(1f))

            AnimatedVisibility(
                visible = isInformationalVisible,
                enter = slideInVertically { height -> height },
                exit = slideOutVertically { height -> height },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = InfusionTvDetailsMetrics.PageHorizontalPadding)
                        .padding(
                            top = TvPlayerOverlayAppearance.OverlayTopPadding,
                            bottom = TvPlayerOverlayAppearance.OverlayBottomPadding,
                        ),
                ) {
                    controls()
                }
            }
        }

        if (contextualActions != null) {
            val castInset = if (castAvailable) {
                TvPlayerOverlayAppearance.ContextualActionCastInset
            } else {
                0.dp
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = InfusionTvDetailsMetrics.PageHorizontalPadding)
                    .padding(bottom = TvPlayerOverlayAppearance.ContextualActionBottomInset + castInset),
            ) {
                contextualActions()
            }
        }

        TvPlayerSeekPulse(seekFeedback, chapterFeedback, seekFeedbackSeconds)
    }
}

@Composable
internal fun TvPlayerCinematicScrim(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(TvPlayerOverlayAppearance.ScrimBrush)
            .testTag(PlayerOverlayScrimTag),
    )
}

@Composable
internal fun TvPlayerClockBlock(
    clock: String,
    endsAt: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = InfusionTvDetailsMetrics.PageHorizontalPadding),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = clock,
            style = InfusionTvScaleCalibration.Type.Headline,
            modifier = Modifier.testTag(PlayerClockTag),
        )
        if (endsAt != null) {
            Spacer(Modifier.height(TvPlayerOverlayAppearance.ClockLineGap))
            Text(
                text = endsAt,
                style = InfusionTvScaleCalibration.Type.Title,
                modifier = Modifier.testTag(PlayerEndsAtTag),
            )
        }
    }
}

@Composable
internal fun TvPlayerMainFrame(
    mediaHeader: @Composable () -> Unit,
    mediaActions: @Composable RowScope.() -> Unit,
    seeker: @Composable () -> Unit,
    more: (@Composable () -> Unit)? = null,

    bottomIndicator: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.weight(1f)) { mediaHeader() }
            Row(
                modifier = Modifier.testTag(PlayerActionRowTag),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TvPlayerOverlayAppearance.ControlGap),
                content = mediaActions,
            )
        }

        Spacer(Modifier.height(TvPlayerOverlayAppearance.HeaderToSeekerGap))

        seeker()

        if (more != null) {
            Spacer(Modifier.height(TvPlayerOverlayAppearance.ControlGap))
            Box(Modifier.align(Alignment.CenterHorizontally)) {
                Column(
                    modifier = Modifier.testTag(PlayerMoreTag),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    more()
                }
            }
        }

        if (bottomIndicator != null) {
            Spacer(Modifier.height(TvPlayerOverlayAppearance.CastAffordanceGap))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                bottomIndicator()
            }
        }
    }
}

@Composable
internal fun TvPlayerCanonicalSuite(
    metadata: PlayerMetadataPresentation,
    modifier: Modifier = Modifier,
) {
    val hero = metadata.hero
    val genreText = hero?.genres?.takeIf { it.isNotEmpty() }?.let(::genreLine)
    val synopsis = metadata.synopsis
    val factLine = listOfNotNull(
        metadata.episodeCoordinate,
        hero?.metadataLine?.takeIf { it.isNotEmpty() },
    ).takeIf { it.isNotEmpty() }?.joinToString(GenreSeparator)
    val ratings = detailsRatingItems(metadata.playbackRatings)
    if (hero == null && genreText == null && synopsis == null && factLine == null && ratings.isEmpty()) {
        return
    }
    TvCanonicalHero(
        hero = hero,
        titleFallback = hero?.title,
        modifier = modifier.testTag(PlayerMediaHeaderTag),

        minHeight = 0.dp,
        logoWidthFractionOfScreen = InfusionTvScaleCalibration.ClearLogo.MaxScreenWidthFraction,
        headingAlignment = Alignment.Start,

    ) {
        if (genreText != null) {
            Text(
                text = genreText,
                style = InfusionTvScaleCalibration.Type.Body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(PlayerGenreLineTag),
            )
        }
        if (synopsis != null) {
            Box(Modifier.testTag(PlayerSynopsisTag)) {
                TvCanonicalOverviewBlock(synopsis = synopsis, canonical = null)
            }
        }
        if (factLine != null) {
            Text(
                text = factLine,
                style = InfusionTvScaleCalibration.Type.Body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(PlayerFactLineTag),
            )
        }
        if (ratings.isNotEmpty()) {
            DetailsStageOneRatingsRow(items = ratings)
        }
    }
}

@Composable
internal fun TvPlayerSeeker(
    positionMs: Long,
    durationMs: Long?,
    bufferedPositionMs: Long?,
    playPauseGlyph: ImageVector,
    playPauseContentDescription: String,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    playPauseModifier: Modifier = Modifier,

    segments: PlaybackSegmentTimeline? = null,
    displayMode: SegmentDisplayMode = SegmentDisplayMode.DEFAULT,
    legendEnabled: Boolean = true,
) {
    val fractions = playerTimelineFractions(positionMs, durationMs, bufferedPositionMs)
    val remaining = remainingPlaybackTime(positionMs, durationMs)
    val renderable = segments?.let { timeline ->
        val duration = timeline.durationMs
        if (duration != null && duration > 0L && timeline.segments.isNotEmpty()) timeline else null
    }

    val showStrip = renderable != null && displayMode == SegmentDisplayMode.ABOVE_PROGRESS_BAR
    val showOnBar = renderable != null && displayMode == SegmentDisplayMode.ON_PROGRESS_BAR
    val showLegend = renderable != null &&
        displayMode != SegmentDisplayMode.OFF &&
        legendEnabled
    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(PlayerSeekerTag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvPlayerIconButton(
                glyph = playPauseGlyph,
                contentDescription = playPauseContentDescription,
                onClick = onPlayPause,
                modifier = playPauseModifier,
            )
            TvPlayerControllerText(
                text = formatPlaybackTime(positionMs),
                modifier = Modifier
                    .padding(horizontal = TvPlayerOverlayAppearance.TimelineLabelGap)
                    .testTag(PlayerElapsedTag),
            )

            Column(Modifier.weight(1f)) {
                if (showStrip) {
                    TvPlayerSegmentStrip(
                        timeline = renderable,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = TvPlayerOverlayAppearance.TimelineHorizontalPadding)
                            .testTag(PlayerSegmentStripTag),
                    )
                }
                TvPlayerTimeline(
                    played = fractions.played,
                    buffered = fractions.buffered,
                    modifier = Modifier.fillMaxWidth(),

                    segments = if (showOnBar) renderable else null,
                )
            }

            if (remaining != null) {
                TvPlayerControllerText(
                    text = remaining,
                    modifier = Modifier
                        .padding(horizontal = TvPlayerOverlayAppearance.TimelineLabelGap)
                        .testTag(PlayerRemainingTag),
                )
            }
        }
        if (showLegend) {
            TvPlayerSegmentLegend(
                timeline = renderable,
                modifier = Modifier
                    .padding(top = TvPlayerOverlayAppearance.SegmentLegendGap)
                    .padding(start = TvPlayerOverlayAppearance.SegmentLegendStartInset)
                    .testTag(PlayerSegmentLegendTag),
            )
        }
    }
}

@Composable
internal fun TvPlayerSegmentStrip(
    timeline: PlaybackSegmentTimeline,
    modifier: Modifier = Modifier,
) {
    val items = segmentItems(timeline)
    if (items.isEmpty()) return
    Canvas(
        modifier = modifier
            .height(TvPlayerOverlayAppearance.SegmentStripHeight),
    ) {
        val thicknesses = segmentThicknesses()
        drawSegmentLayer(
            items = items,
            bandThickness = thicknesses.band,
            bandDash = thicknesses.dash,
            bandGap = thicknesses.gap,
            markerThickness = thicknesses.marker,
        )
    }
}

internal data class SegmentGeometry(
    val startFraction: Float,

    val endFraction: Float?,

    val dashed: Boolean,
)

internal fun segmentGeometry(timeline: PlaybackSegmentTimeline): List<SegmentGeometry> {
    val duration = timeline.durationMs ?: return emptyList()
    if (duration <= 0L) return emptyList()
    fun fractionOf(ms: Long): Float =
        (ms.toDouble() / duration.toDouble()).coerceIn(0.0, 1.0).toFloat()
    return timeline.segments.mapNotNull { segment ->
        val start = fractionOf(segment.startMs)
        val end = segment.endMs
        if (end != null && end > segment.startMs) {
            val endFraction = fractionOf(end)
            if (endFraction <= start) null else SegmentGeometry(start, endFraction, segment.isInferred())
        } else {
            SegmentGeometry(start, null, segment.isInferred())
        }
    }
}

internal data class SegmentItem(
    val geometry: SegmentGeometry,
    val segment: PlaybackSegment,
)

internal data class SegmentDrawLayers(
    val ranges: List<SegmentItem>,
    val chapters: List<SegmentItem>,
)

internal fun segmentItems(timeline: PlaybackSegmentTimeline): List<SegmentItem> =
    segmentGeometry(timeline).zip(timeline.segments) { geometry, segment -> SegmentItem(geometry, segment) }

internal fun segmentDrawLayers(timeline: PlaybackSegmentTimeline): SegmentDrawLayers {
    val ranges = ArrayList<SegmentItem>()
    val chapters = ArrayList<SegmentItem>()
    for (item in segmentItems(timeline)) {
        if (item.segment.type == SegmentType.CHAPTER) chapters += item else ranges += item
    }
    return SegmentDrawLayers(ranges = ranges, chapters = chapters)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.segmentThicknesses(): SegmentThicknesses {
    val base = TvPlayerOverlayAppearance.SegmentStripHeight.toPx()
    return SegmentThicknesses(
        band = base * TvPlayerOverlayAppearance.SegmentBandThicknessFraction,
        dash = base * TvPlayerOverlayAppearance.SegmentBandDashFraction,
        gap = base * TvPlayerOverlayAppearance.SegmentBandGapFraction,
        marker = base * TvPlayerOverlayAppearance.SegmentMarkerThicknessFraction,
    )
}

private data class SegmentThicknesses(
    val band: Float,
    val dash: Float,
    val gap: Float,
    val marker: Float,
)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSegmentLayer(
    items: List<SegmentItem>,
    bandThickness: Float,
    bandDash: Float,
    bandGap: Float,
    markerThickness: Float,
) {
    val y = size.height / 2f
    for (item in items) {
        val geometry = item.geometry
        val color = segmentColor(item.segment)
        if (geometry.endFraction == null) {

            drawLine(
                color = color,
                start = Offset(size.width * geometry.startFraction, 0f),
                end = Offset(size.width * geometry.startFraction, size.height),
                strokeWidth = markerThickness,
                cap = StrokeCap.Round,
            )
        } else {
            drawBand(
                left = size.width * geometry.startFraction,
                right = size.width * geometry.endFraction,
                y = y,
                color = color,
                dashed = geometry.dashed,
                thickness = bandThickness,
                dash = bandDash,
                gap = bandGap,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBand(
    left: Float,
    right: Float,
    y: Float,
    color: androidx.compose.ui.graphics.Color,
    dashed: Boolean,
    thickness: Float,
    dash: Float,
    gap: Float,
) {
    if (!dashed) {
        drawLine(
            color = color,
            start = Offset(left, y),
            end = Offset(right, y),
            strokeWidth = thickness,
            cap = StrokeCap.Round,
        )
        return
    }
    var x = left
    while (x < right) {
        val segEnd = (x + dash).coerceAtMost(right)
        drawLine(
            color = color,
            start = Offset(x, y),
            end = Offset(segEnd, y),
            strokeWidth = thickness,
            cap = StrokeCap.Butt,
        )
        x = segEnd + gap
    }
}

@Composable
internal fun TvPlayerSegmentLegend(
    timeline: PlaybackSegmentTimeline,
    modifier: Modifier = Modifier,
) {
    val entries = segmentLegendEntries(timeline)
    if (entries.isEmpty()) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TvPlayerOverlayAppearance.SegmentLegendGap),
    ) {
        entries.forEach { entry ->
            Canvas(
                modifier = Modifier
                    .size(TvPlayerOverlayAppearance.SegmentLegendSwatchSize),
            ) {
                drawRect(
                    color = entry.color,
                    topLeft = Offset.Zero,
                    size = androidx.compose.ui.geometry.Size(size.width, size.height),
                )
            }
            Text(
                text = entry.label,
                style = InfusionTvScaleCalibration.Type.Body,
            )
        }
    }
}

internal data class SegmentLegendEntry(
    val label: String,
    val color: androidx.compose.ui.graphics.Color,
)

internal fun segmentLegendEntries(timeline: PlaybackSegmentTimeline): List<SegmentLegendEntry> {
    val out = LinkedHashMap<String, SegmentLegendEntry>()
    for (segment in timeline.segments) {
        val (base, color) = when (segment.type) {
            SegmentType.INTRO -> "Intro" to SegmentColorIntro
            SegmentType.RECAP -> "Recap" to SegmentColorRecap
            SegmentType.CREDITS -> "Credits" to SegmentColorCredits
            SegmentType.PREVIEW -> "Preview" to SegmentColorPreview
            SegmentType.MID_CREDITS_SCENE -> "Mid-credits" to SegmentColorCredits
            SegmentType.POST_CREDITS_SCENE -> "Post-credits" to SegmentColorCredits
            SegmentType.CHAPTER -> "Chapters" to SegmentColorChapter
        }
        val label = when {
            segment.type == SegmentType.CHAPTER -> base
            segment.provenance == SegmentProvenance.INFERRED -> "$base (inferred)"
            segment.provenance == SegmentProvenance.MANUAL -> "$base (manual)"
            else -> base
        }
        val key = "$label|${color.value}"
        out.putIfAbsent(key, SegmentLegendEntry(label, color))
    }
    return out.values.toList()
}

private fun segmentColor(segment: PlaybackSegment): androidx.compose.ui.graphics.Color = when (segment.type) {
    SegmentType.INTRO -> SegmentColorIntro
    SegmentType.RECAP -> SegmentColorRecap
    SegmentType.CREDITS -> SegmentColorCredits
    SegmentType.PREVIEW -> SegmentColorPreview
    SegmentType.MID_CREDITS_SCENE,
    SegmentType.POST_CREDITS_SCENE,
    -> SegmentColorCredits
    SegmentType.CHAPTER -> SegmentColorChapter
}.let { if (segment.isInferred()) it.copy(alpha = 0.65f) else it }

private fun PlaybackSegment.isInferred(): Boolean =
    provenance == SegmentProvenance.INFERRED || provenance == SegmentProvenance.MANUAL

private val SegmentColorIntro = androidx.compose.ui.graphics.Color(0xFF4FC3F7)
private val SegmentColorRecap = androidx.compose.ui.graphics.Color(0xFFBA68C8)
private val SegmentColorCredits = androidx.compose.ui.graphics.Color(0xFFFFB74D)
private val SegmentColorPreview = androidx.compose.ui.graphics.Color(0xFF81C784)
private val SegmentColorChapter = androidx.compose.ui.graphics.Color(0xFF90A4AE)

@Composable
internal fun TvPlayerTimeline(
    played: Float,
    buffered: Float,
    modifier: Modifier = Modifier,
    segments: PlaybackSegmentTimeline? = null,
) {
    val accent = LocalInfusionAccent.current
    val content = InfusionTvAppearance.TextPrimary
    val layers = segments?.let(::segmentDrawLayers)
    Canvas(
        modifier = modifier
            .height(TvPlayerOverlayAppearance.TimelineHeight)
            .padding(horizontal = TvPlayerOverlayAppearance.TimelineHorizontalPadding)
            .testTag(PlayerTimelineTag),
    ) {
        val y = size.height / 2f
        val thicknesses = segmentThicknesses()
        fun band(fraction: Float, color: androidx.compose.ui.graphics.Color) {
            if (fraction <= 0f) return
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(size.width * fraction, y),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
        }

        band(1f, content.copy(alpha = TvPlayerOverlayAppearance.TimelineTrackAlpha))
        band(buffered, content.copy(alpha = TvPlayerOverlayAppearance.TimelineBufferedAlpha))

        if (layers != null && layers.ranges.isNotEmpty()) {
            drawSegmentLayer(
                items = layers.ranges,
                bandThickness = thicknesses.band,
                bandDash = thicknesses.dash,
                bandGap = thicknesses.gap,
                markerThickness = thicknesses.marker,
            )
        }

        band(played, accent)

        if (layers != null && layers.chapters.isNotEmpty()) {
            drawSegmentLayer(
                items = layers.chapters,
                bandThickness = thicknesses.band,
                bandDash = thicknesses.dash,
                bandGap = thicknesses.gap,
                markerThickness = thicknesses.marker,
            )
        }
    }
}

@Composable
internal fun TvPlayerControllerText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = InfusionTvScaleCalibration.Type.Body,
        modifier = modifier,
    )
}

@Composable
internal fun TvPlayerStingerNotice(
    stinger: dev.infusion.feature.vertical.model.StingerUi,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                color = InfusionTvAppearance.OledBlack.copy(alpha = TvPlayerOverlayAppearance.StingerNoticeAlpha),
                shape = InfusionTvContour.Shape,
            )
            .padding(
                horizontal = TvPlayerOverlayAppearance.StingerNoticeHorizontalPadding,
                vertical = TvPlayerOverlayAppearance.StingerNoticeVerticalPadding,
            )
            .testTag(PlayerStingerNoticeTag),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvPlayerOverlayAppearance.ControlGap),
        ) {
            Icon(
                imageVector = TvPlayerGlyphs.Stinger,
                contentDescription = null,
                tint = LocalInfusionAccent.current,
                modifier = Modifier.size(TvPlayerOverlayAppearance.StingerNoticeGlyphSize),
            )
            Text(
                text = stinger.message,
                style = InfusionTvScaleCalibration.Type.Emphasis,
            )
        }
    }
}

@Composable
internal fun TvPlayerIconButton(
    glyph: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InfusionTvButton(
        onClick = onClick,

        modifier = Modifier.size(TvPlayerOverlayAppearance.IconButtonSize).then(modifier),
        contentPadding = TvPlayerOverlayAppearance.IconButtonContentPadding,
    ) {
        Icon(
            imageVector = glyph,
            contentDescription = contentDescription,
            modifier = Modifier.size(TvPlayerOverlayAppearance.IconGlyphSize),
        )
    }
}

@Composable
internal fun TvPlayerCastAffordance(modifier: Modifier = Modifier) {
    Icon(
        imageVector = TvPlayerGlyphs.CastDown,
        contentDescription = "Cast — press down",
        modifier = modifier
            .size(TvPlayerOverlayAppearance.CastAffordanceSize)
            .testTag(PlayerCastAffordanceTag),
    )
}

@Composable
internal fun TvPlayerSeekPulse(
    feedback: TvPlayerSeekFeedback,
    chapterFeedback: TvPlayerChapterFeedback? = null,

    seekSeconds: Int? = null,
    modifier: Modifier = Modifier,
) {
    val accent = LocalInfusionAccent.current
    if (chapterFeedback != null) {
        TvPlayerChapterPulse(feedback = chapterFeedback, accent = accent, modifier = modifier)
        return
    }
    val glyph = when (feedback) {
        TvPlayerSeekFeedback.BACKWARD -> TvPlayerGlyphs.SeekBackward
        TvPlayerSeekFeedback.FORWARD -> TvPlayerGlyphs.SeekForward
        TvPlayerSeekFeedback.PAUSED -> TvPlayerGlyphs.Pause
        TvPlayerSeekFeedback.NONE -> null
    } ?: return
    val numeral = if (feedback == TvPlayerSeekFeedback.PAUSED) {
        null
    } else {
        seekSeconds?.let(::seekFeedbackSecondsLabel)
    }
    Box(
        modifier = modifier
            .size(TvPlayerOverlayAppearance.PulseDiscSize)
            .background(
                color = InfusionTvAppearance.OledBlack.copy(
                    alpha = TvPlayerOverlayAppearance.PulseDiscAlpha,
                ),
                shape = CircleShape,
            )
            .testTag(PlayerSeekPulseTag),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvPlayerOverlayAppearance.PulseNumeralGap),
        ) {
            Icon(
                imageVector = glyph,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(TvPlayerOverlayAppearance.PulseGlyphSize),
            )
            if (numeral != null) {
                Text(
                    text = numeral,
                    style = InfusionTvScaleCalibration.Type.Title,
                    color = accent,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.testTag(PlayerSeekNumeralTag),
                )
            }
        }
    }
}

@Composable
private fun TvPlayerChapterPulse(
    feedback: TvPlayerChapterFeedback,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(TvPlayerOverlayAppearance.PulseDiscSize)
                .background(
                    color = InfusionTvAppearance.OledBlack.copy(
                        alpha = TvPlayerOverlayAppearance.PulseDiscAlpha,
                    ),
                    shape = CircleShape,
                )
                .testTag(PlayerSeekPulseTag),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (feedback.forward) {
                        TvPlayerGlyphs.ChapterUp
                    } else {
                        TvPlayerGlyphs.ChapterDown
                    },
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(TvPlayerOverlayAppearance.ChapterPulseGlyphSize),
                )
                Spacer(Modifier.height(TvPlayerOverlayAppearance.ChapterPulseInsideGap))
                Text(
                    text = feedback.insideLabel,
                    style = InfusionTvScaleCalibration.Type.Title,
                    color = accent,
                    modifier = Modifier.testTag(PlayerChapterPulseInsideTag),
                )
            }
        }
        if (feedback.belowLabel != null) {
            Spacer(Modifier.height(TvPlayerOverlayAppearance.ChapterPulseBelowGap))
            Text(
                text = feedback.belowLabel,
                style = InfusionTvScaleCalibration.Type.Body,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = TvPlayerOverlayAppearance.ChapterPulseBelowMaxWidth)
                    .testTag(PlayerChapterPulseBelowTag),
            )
        }
    }
}

internal const val PlayerChapterPulseInsideTag: String = "player:chapter-pulse-inside"

internal const val PlayerChapterPulseBelowTag: String = "player:chapter-pulse-below"

internal const val PlayerSeekNumeralTag: String = "player:seek-numeral"

internal fun seekFeedbackSecondsLabel(seconds: Int): String = "${seconds}s"

internal data class PlayerTimelineFractions(val played: Float, val buffered: Float)

internal fun playerTimelineFractions(
    positionMs: Long,
    durationMs: Long?,
    bufferedPositionMs: Long?,
): PlayerTimelineFractions {
    val duration = durationMs ?: return PlayerTimelineFractions(0f, 0f)
    if (duration <= 0L) return PlayerTimelineFractions(0f, 0f)
    val played = (positionMs.coerceAtLeast(0L).toDouble() / duration.toDouble()).toFloat().coerceIn(0f, 1f)
    val buffered = bufferedPositionMs
        ?.coerceAtLeast(positionMs.coerceAtLeast(0L))
        ?.coerceAtMost(duration)
        ?.let { (it.toDouble() / duration.toDouble()).toFloat().coerceIn(0f, 1f) }
        ?: played
    return PlayerTimelineFractions(played = played, buffered = buffered.coerceAtLeast(played))
}

internal fun remainingPlaybackTime(positionMs: Long, durationMs: Long?): String? {
    val duration = durationMs?.takeIf { it > 0L } ?: return null
    val remaining = (duration - positionMs.coerceAtLeast(0L)).coerceAtLeast(0L)
    return "-${formatPlaybackTime(remaining)}"
}

internal fun formatPlaybackTime(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "$hours:${minutes.padToTwoDigits()}:${seconds.padToTwoDigits()}"
    } else {
        "${minutes.padToTwoDigits()}:${seconds.padToTwoDigits()}"
    }
}

internal fun formatPlaybackClock(now: LocalTime): String =
    "${now.hour.padToTwoDigits()}:${now.minute.padToTwoDigits()}"

internal fun formatPlaybackEndsAt(now: LocalTime, remainingMs: Long?): String? {
    val remaining = remainingMs?.takeIf { it > 0L } ?: return null

    return "$EndsAtLabelPrefix${formatPlaybackClock(now.plusSeconds(remaining / 1_000L))}"
}

internal const val EndsAtLabelPrefix: String = "Ends at "

private fun Int.padToTwoDigits(): String = toString().padStart(2, '0')

private fun Long.padToTwoDigits(): String = toString().padStart(2, '0')
