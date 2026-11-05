package dev.infusion.tv.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.tv.material3.Text
import dev.infusion.feature.vertical.media.ReviewUi

internal enum class ReviewAttributionRole { SCORE, AUTHOR, SOURCE, DATE }

internal data class ReviewAttributionPart(val text: String, val role: ReviewAttributionRole)

internal const val ReviewAnonymousAuthor: String = "Anonymous"

internal fun reviewScoreText(review: ReviewUi): String? =
    review.authorRating?.trim()?.takeIf { it.isNotEmpty() }?.let { "★ $it" }

internal fun reviewAttributionParts(
    review: ReviewUi,
    includeDate: Boolean = false,
): List<ReviewAttributionPart> = listOfNotNull(
    reviewScoreText(review)?.let { ReviewAttributionPart(text = it, role = ReviewAttributionRole.SCORE) },
    ReviewAttributionPart(
        text = review.author.trim().takeIf { it.isNotEmpty() } ?: ReviewAnonymousAuthor,
        role = ReviewAttributionRole.AUTHOR,
    ),
    review.providerLabel?.trim()?.takeIf { it.isNotEmpty() }?.let {
        ReviewAttributionPart(text = it, role = ReviewAttributionRole.SOURCE)
    },
    review.dateLabel?.trim()?.takeIf { it.isNotEmpty() && includeDate }?.let {
        ReviewAttributionPart(text = it, role = ReviewAttributionRole.DATE)
    },
)

internal fun reviewAttributionText(review: ReviewUi, includeDate: Boolean = false): String =
    reviewAttributionParts(review, includeDate).joinToString(CommentHeaderSeparator) { it.text }

@Composable
internal fun ReviewAttribution(
    review: ReviewUi,
    authorStyle: TextStyle,
    supportingStyle: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    includeDate: Boolean = false,
) {
    val text = remember(review, authorStyle, supportingStyle, includeDate) {
        reviewAttributionAnnotated(review, authorStyle, supportingStyle, includeDate)
    }
    Text(
        text = text,

        style = supportingStyle,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

internal fun reviewScoreStyle(authorStyle: TextStyle): TextStyle =
    authorStyle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)

internal fun reviewAttributionAnnotated(
    review: ReviewUi,
    authorStyle: TextStyle,
    supportingStyle: TextStyle,
    includeDate: Boolean = false,
): AnnotatedString = buildAnnotatedString {
    val parts = reviewAttributionParts(review, includeDate)
    val scoreStyle = reviewScoreStyle(authorStyle)
    parts.forEachIndexed { index, part ->
        if (index > 0) {
            withStyle(supportingStyle.toSpanStyle()) { append(CommentHeaderSeparator) }
        }
        val style = when (part.role) {
            ReviewAttributionRole.AUTHOR -> authorStyle
            ReviewAttributionRole.SCORE -> scoreStyle
            ReviewAttributionRole.SOURCE, ReviewAttributionRole.DATE -> supportingStyle
        }
        withStyle(style.toSpanStyle()) { append(part.text) }
    }
}
