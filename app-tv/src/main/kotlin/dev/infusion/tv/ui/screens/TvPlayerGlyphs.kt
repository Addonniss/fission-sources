package dev.infusion.tv.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object TvPlayerGlyphs {

    private val Size = 24.dp

    private fun builder(name: String) = ImageVector.Builder(
        name = name,
        defaultWidth = Size,
        defaultHeight = Size,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )

    val Play: ImageVector by lazy {
        builder("TvPlayerPlay").apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(8f, 5f)
                verticalLineToRelative(14f)
                lineToRelative(11f, -7f)
                close()
            }
        }.build()
    }

    val Pause: ImageVector by lazy {
        builder("TvPlayerPause").apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(6f, 19f)
                horizontalLineToRelative(4f)
                verticalLineTo(5f)
                horizontalLineTo(6f)
                verticalLineToRelative(14f)
                close()
                moveTo(14f, 5f)
                verticalLineToRelative(14f)
                horizontalLineToRelative(4f)
                verticalLineTo(5f)
                horizontalLineToRelative(-4f)
                close()
            }
        }.build()
    }

    val SeekBackward: ImageVector by lazy {
        builder("TvPlayerSeekBackward").apply {
            path(fill = SolidColor(Color.White)) {

                moveTo(20f, 18f)
                lineTo(11.5f, 12f)
                lineTo(20f, 6f)
                verticalLineToRelative(12f)
                close()
                moveTo(11f, 18f)
                lineTo(2.5f, 12f)
                lineTo(11f, 6f)
                verticalLineToRelative(12f)
                close()
            }
        }.build()
    }

    val SeekForward: ImageVector by lazy {
        builder("TvPlayerSeekForward").apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(4f, 6f)
                lineTo(12.5f, 12f)
                lineTo(4f, 18f)
                close()
                moveTo(13f, 6f)
                lineTo(21.5f, 12f)
                lineTo(13f, 18f)
                close()
            }
        }.build()
    }

    val Subtitles: ImageVector by lazy {
        builder("TvPlayerSubtitles").apply {
            path(
                fill = SolidColor(Color.White),
                pathFillType = PathFillType.EvenOdd,
            ) {
                moveTo(4f, 5f)
                horizontalLineToRelative(16f)
                arcTo(2f, 2f, 0f, false, true, 22f, 7f)
                verticalLineToRelative(10f)
                arcTo(2f, 2f, 0f, false, true, 20f, 19f)
                horizontalLineTo(4f)
                arcTo(2f, 2f, 0f, false, true, 2f, 17f)
                verticalLineTo(7f)
                arcTo(2f, 2f, 0f, false, true, 4f, 5f)
                close()

                moveTo(4.4f, 6.4f)
                verticalLineTo(17.6f)
                horizontalLineTo(19.6f)
                verticalLineTo(6.4f)
                close()
            }

            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.3f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(10.9f, 10.6f)
                curveTo(10.2f, 10.6f, 9.7f, 11.2f, 9.7f, 12f)
                curveTo(9.7f, 12.8f, 10.2f, 13.4f, 10.9f, 13.4f)
            }
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.3f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(16.1f, 10.6f)
                curveTo(15.4f, 10.6f, 14.9f, 11.2f, 14.9f, 12f)
                curveTo(14.9f, 12.8f, 15.4f, 13.4f, 16.1f, 13.4f)
            }
        }.build()
    }

    val Audio: ImageVector by lazy {
        builder("TvPlayerAudio").apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(3f, 9f)
                horizontalLineToRelative(3.4f)
                lineTo(11.5f, 5f)
                verticalLineToRelative(14f)
                lineTo(6.4f, 15f)
                horizontalLineTo(3f)
                close()
            }
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(14.4f, 9.6f)
                curveTo(15.6f, 10.7f, 15.6f, 13.3f, 14.4f, 14.4f)
            }
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(17.2f, 7.4f)
                curveTo(19.6f, 9.6f, 19.6f, 14.4f, 17.2f, 16.6f)
            }
        }.build()
    }

    val Back: ImageVector by lazy {
        builder("TvPlayerBack").apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(20f, 11f)
                horizontalLineTo(7.8f)
                lineToRelative(5.6f, -5.6f)
                lineTo(12f, 4f)
                lineToRelative(-8f, 8f)
                lineToRelative(8f, 8f)
                lineToRelative(1.4f, -1.4f)
                lineTo(7.8f, 13f)
                horizontalLineTo(20f)
                close()
            }
        }.build()
    }

    val CastDown: ImageVector by lazy {
        builder("TvPlayerCastDown").apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(6f, 9.5f)
                lineTo(12f, 15.5f)
                lineTo(18f, 9.5f)
            }
        }.build()
    }

    val ChapterUp: ImageVector by lazy {
        builder("TvPlayerChapterUp").apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(6f, 12.5f)
                lineTo(12f, 4f)
                lineTo(18f, 12.5f)
                close()
                moveTo(6f, 21.5f)
                lineTo(12f, 13f)
                lineTo(18f, 21.5f)
                close()
            }
        }.build()
    }

    val ChapterDown: ImageVector by lazy {
        builder("TvPlayerChapterDown").apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(6f, 11.5f)
                lineTo(12f, 20f)
                lineTo(18f, 11.5f)
                close()
                moveTo(6f, 2.5f)
                lineTo(12f, 11f)
                lineTo(18f, 2.5f)
                close()
            }
        }.build()
    }

    val Stinger: ImageVector by lazy {
        builder("TvPlayerStinger").apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 2.5f)
                lineToRelative(2.3f, 7.2f)
                lineToRelative(7.2f, 2.3f)
                lineToRelative(-7.2f, 2.3f)
                lineToRelative(-2.3f, 7.2f)
                lineToRelative(-2.3f, -7.2f)
                lineToRelative(-7.2f, -2.3f)
                lineToRelative(7.2f, -2.3f)
                close()
            }
        }.build()
    }
}
