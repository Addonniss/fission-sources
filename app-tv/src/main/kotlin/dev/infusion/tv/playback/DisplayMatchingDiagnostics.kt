package dev.infusion.tv.playback

fun interface DisplayMatchingDiagnostics {

    fun log(message: String)

    companion object {

        const val TAG = "InfusionDispMatch"

        val NONE: DisplayMatchingDiagnostics = DisplayMatchingDiagnostics { }

        fun androidLog(tag: String = TAG): DisplayMatchingDiagnostics =
            DisplayMatchingDiagnostics { message -> android.util.Log.i(tag, message) }
    }
}

internal fun dev.infusion.core.playback.api.VideoTrack.describeForDiagnostics(): String =
    "id=${id.value} ${width ?: "?"}x${height ?: "?"}@${frameRate ?: "?"}fps" +
        " rot=${rotationDegrees ?: 0} par=${pixelWidthHeightRatio ?: 1f} codec=${codec ?: "?"}"

internal fun dev.infusion.core.playback.api.MeasuredVideoEvidence.describeForDiagnostics(): String {
    val cadence = frameRateMilliHz?.let { "${it}mHz" } ?: "?mHz"
    val size = if (widthPx != null && heightPx != null) "${widthPx}x$heightPx" else "?x?"
    return "$cadence $size"
}
