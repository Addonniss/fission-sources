package dev.infusion.tv.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object MusicTransportGlyphs {

    private val Size = 24.dp

    private fun builder(name: String) = ImageVector.Builder(
        name = name,
        defaultWidth = Size,
        defaultHeight = Size,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )

    val Previous: ImageVector by lazy {
        builder("MusicTransportPrevious").apply {
            path(fill = SolidColor(Color.White)) {

                moveTo(6f, 6f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(12f)
                horizontalLineTo(6f)
                close()

                moveTo(18f, 6f)
                verticalLineToRelative(12f)
                lineTo(9.5f, 12f)
                close()
            }
        }.build()
    }

    val Next: ImageVector by lazy {
        builder("MusicTransportNext").apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(6f, 6f)
                verticalLineToRelative(12f)
                lineTo(14.5f, 12f)
                close()
                moveTo(16f, 6f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(12f)
                horizontalLineToRelative(-2f)
                close()
            }
        }.build()
    }

    val Stop: ImageVector by lazy {
        builder("MusicTransportStop").apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(6f, 6f)
                horizontalLineToRelative(12f)
                verticalLineToRelative(12f)
                horizontalLineTo(6f)
                close()
            }
        }.build()
    }

    val Shuffle: ImageVector by lazy {
        builder("MusicTransportShuffle").apply {
            shuffleArrows()
        }.build()
    }

    val ShuffleOn: ImageVector by lazy {
        builder("MusicTransportShuffleOn").apply {
            shuffleArrows()
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 1f)
                curveTo(13.1f, 1f, 14f, 1.9f, 14f, 3f)
                curveTo(14f, 4.1f, 13.1f, 5f, 12f, 5f)
                curveTo(10.9f, 5f, 10f, 4.1f, 10f, 3f)
                curveTo(10f, 1.9f, 10.9f, 1f, 12f, 1f)
                close()
            }
        }.build()
    }

    val Queue: ImageVector by lazy {
        builder("MusicTransportQueue").apply {
            path(fill = SolidColor(Color.White)) {

                moveTo(3f, 6f)
                horizontalLineToRelative(10f)
                verticalLineToRelative(1.8f)
                horizontalLineTo(3f)
                close()
                moveTo(3f, 10.6f)
                horizontalLineToRelative(10f)
                verticalLineToRelative(1.8f)
                horizontalLineTo(3f)
                close()
                moveTo(3f, 15.2f)
                horizontalLineToRelative(7f)
                verticalLineToRelative(1.8f)
                horizontalLineTo(3f)
                close()
            }
            path(fill = SolidColor(Color.White)) {

                val cx = 16.4f
                val cy = 17.6f
                val r = 2.4f
                val k = 1.3255f
                moveTo(cx, cy - r)
                curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
                curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
                curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
                curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
                close()

                moveTo(18.1f, 17.6f)
                horizontalLineToRelative(1.6f)
                verticalLineTo(8.4f)
                horizontalLineToRelative(-1.6f)
                close()

                moveTo(18.1f, 8.4f)
                lineTo(22f, 10.2f)
                lineTo(22f, 13.4f)
                lineTo(19.7f, 11.9f)
                lineTo(19.7f, 8.4f)
                close()
            }
        }.build()
    }

    private fun ImageVector.Builder.shuffleArrows() {

        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(3.5f, 7f)
            horizontalLineTo(8.5f)
            lineTo(15.5f, 17f)
            horizontalLineTo(19f)
        }

        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(3.5f, 17f)
            horizontalLineTo(8.5f)
            lineTo(15.5f, 7f)
            horizontalLineTo(19f)
        }

        path(fill = SolidColor(Color.White)) {
            moveTo(17.5f, 4f)
            lineTo(22f, 7f)
            lineTo(17.5f, 10f)
            close()
            moveTo(17.5f, 14f)
            lineTo(22f, 17f)
            lineTo(17.5f, 20f)
            close()
        }
    }
}
