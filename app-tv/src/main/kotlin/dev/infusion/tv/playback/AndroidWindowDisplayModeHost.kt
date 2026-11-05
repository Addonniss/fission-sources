package dev.infusion.tv.playback

import android.app.Activity
import android.os.Build
import android.view.Display
import dev.infusion.core.playback.api.DisplayModeOption

class AndroidWindowDisplayModeHost(
    private val activity: Activity,
    private val diagnostics: DisplayMatchingDiagnostics = DisplayMatchingDiagnostics.NONE,
) : DisplayModeHost {

    override fun advertisedModes(): List<DisplayModeOption> =
        display()?.supportedModes.orEmpty().mapNotNull { mode ->
            DisplayModeOption.of(
                modeId = mode.modeId,
                widthPx = mode.physicalWidth,
                heightPx = mode.physicalHeight,
                refreshRateHz = mode.refreshRate.toDouble(),
            )
        }

    override fun currentMode(): DisplayModeOption? =
        display()?.mode?.let { mode ->
            DisplayModeOption.of(
                modeId = mode.modeId,
                widthPx = mode.physicalWidth,
                heightPx = mode.physicalHeight,
                refreshRateHz = mode.refreshRate.toDouble(),
            )
        }

    override fun requestMode(modeId: Int) {
        try {
            val attributes = activity.window.attributes
            val before = attributes.preferredDisplayModeId

            if (before != modeId) {
                activity.window.attributes = attributes.apply { preferredDisplayModeId = modeId }
            }

            diagnostics.log(
                "window.requestMode preferredDisplayModeId: $before -> $modeId " +
                    "(display now ${currentMode()?.describeForDiagnostics() ?: "unknown"}, " +
                    "advertised=${advertisedModes().size})",
            )
        } catch (failure: RuntimeException) {

            diagnostics.log("window.requestMode $modeId failed: ${failure.javaClass.simpleName}")
            android.util.Log.w(TAG, "display mode request ignored: ${failure.javaClass.simpleName}")
        }
    }

    private fun DisplayModeOption.describeForDiagnostics(): String =
        "id=$modeId ${widthPx}x$heightPx@${refreshRateMilliHz}mHz"

    @Suppress("DEPRECATION")
    private fun display(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

            runCatching { activity.display }.getOrNull() ?: activity.windowManager.defaultDisplay
        } else {

            activity.windowManager.defaultDisplay
        }

    private companion object {
        const val TAG = "DisplayModeHost"
    }
}
