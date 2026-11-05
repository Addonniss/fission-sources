package dev.infusion.tv.ui.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.infusion.core.domain.format.AirDateFormat
import dev.infusion.core.model.EpisodeCardIdentification
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.LocalInfusionAccent
import java.time.LocalDate

internal object ContentCardTitle {

    private val SCHEME_PREFIX = Regex("^(?:[a-z][a-z0-9+.\\-]*:)?//", RegexOption.IGNORE_CASE)

    private val HOST_SHAPE = Regex(
        "^(?:" +
            "[a-z0-9](?:[a-z0-9\\-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9\\-]*[a-z0-9])?)*\\.[a-z0-9]\\S*" +
            "|localhost" +
            "|\\d{1,3}(?:\\.\\d{1,3}){3}" +
            ")(?::\\d{1,5})?(?:[/?#]\\S*)?$",
        RegexOption.IGNORE_CASE,
    )

    private val IP_OR_LOCALHOST = Regex(
        "^(?:localhost|\\d{1,3}(?:\\.\\d{1,3}){3})$",
        RegexOption.IGNORE_CASE,
    )

    private val COMMON_TLDS = setOf(
        "com", "net", "org", "io", "tv", "me", "co", "app", "dev", "xyz", "info", "biz",
        "stream", "watch", "link", "site", "online", "cloud", "media", "video", "live",
        "gg", "cc", "ly", "to", "sh", "fm", "top", "club", "space", "website", "store",
    )

    fun visibleOrNull(displayTitle: String?): String? {
        val title = displayTitle?.trim().orEmpty()
        if (title.isEmpty()) return null
        if (looksLikeTransportGarbage(title)) return null
        return title
    }

    private fun looksLikeTransportGarbage(candidate: String): Boolean {
        if (candidate.any { it.isWhitespace() }) return false
        if (SCHEME_PREFIX.containsMatchIn(candidate)) return true
        return looksLikeBareHost(candidate)
    }

    private fun looksLikeBareHost(candidate: String): Boolean {
        if (!HOST_SHAPE.matches(candidate)) return false
        val authority = candidate.substringBefore('/').substringBefore('?').substringBefore('#')
        if (IP_OR_LOCALHOST.matches(authority)) return true
        if (authority.contains(':')) return true
        if (candidate.length > authority.length) return true
        return authority.substringAfterLast('.').lowercase() in COMMON_TLDS
    }
}

@Composable
fun ColumnScope.InfusionCardBelowArtwork(
    title: String?,
    year: Int?,
    airDate: LocalDate? = null,
) {
    if (title != null) {
        Text(
            title,
            style = InfusionTvScaleCalibration.Type.Emphasis,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val secondary = airDate?.let { AirDateFormat.format(it) } ?: year?.toString()
    secondary?.let {
        Text(
            it,
            style = InfusionTvScaleCalibration.Type.Body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ColumnScope.InfusionEpisodeCardIdentification(
    identification: EpisodeCardIdentification,
) {

    val seriesContext = ContentCardTitle.visibleOrNull(identification.seriesContext)
    if (seriesContext != null) {
        Text(
            seriesContext,
            style = InfusionTvScaleCalibration.Type.Emphasis,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    Text(
        episodeIdentityLine(identification),
        style = InfusionTvScaleCalibration.Type.Title,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )

    val facts = episodeFactsLineOrNull(identification)
    if (facts != null) {
        Text(
            facts,
            style = InfusionTvScaleCalibration.Type.Body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun episodeIdentityLine(identification: EpisodeCardIdentification): String {
    val episodeTitle = ContentCardTitle.visibleOrNull(identification.episodeTitle)
    return if (episodeTitle != null) {
        "${identification.coordinate}$FactsSeparator$episodeTitle"
    } else {
        identification.coordinate
    }
}

internal fun episodeFactsLineOrNull(identification: EpisodeCardIdentification): String? =
    listOfNotNull(
        identification.runtimeMinutes?.let { "${it}min" },
        identification.airDate?.toString(),
    ).takeIf { it.isNotEmpty() }?.joinToString(FactsSeparator)

internal fun episodeIdentificationLabel(
    identification: EpisodeCardIdentification,
): String? = listOfNotNull(
    ContentCardTitle.visibleOrNull(identification.seriesContext),
    episodeIdentityLine(identification),
    episodeFactsLineOrNull(identification),
).takeIf { it.isNotEmpty() }?.joinToString(FactsSeparator)

private const val FactsSeparator: String = " · "

@Composable
fun BoxScope.InfusionCardWatchedMark(
    artworkSize: IntSize,
    tag: String? = null,
) {
    if (artworkSize.width <= 0 || artworkSize.height <= 0) return

    val minorSide = minOf(artworkSize.width, artworkSize.height).toFloat()
    val density = LocalDensity.current
    val glyphSize = with(density) { (minorSide * ContentCardPresentation.WatchedMarkGlyphFraction).toDp() }
    val fieldPadding = with(density) { (minorSide * ContentCardPresentation.WatchedMarkPaddingFraction).toDp() }
    val cornerInset = with(density) { (minorSide * ContentCardPresentation.WatchedMarkInsetFraction).toDp() }

    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(cornerInset)

            .clip(InfusionTvContour.Shape)
            .background(InfusionTvContour.CardBadgeScrim)
            .padding(fieldPadding)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = infusionCardWatchedMarkTint(),
            modifier = Modifier.size(glyphSize),
        )
    }
}

@Composable
fun infusionCardWatchedMarkTint(): Color = LocalInfusionAccent.current
