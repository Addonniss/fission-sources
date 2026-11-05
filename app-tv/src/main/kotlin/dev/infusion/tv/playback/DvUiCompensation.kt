package dev.infusion.tv.playback

import androidx.compose.runtime.Immutable
import dev.infusion.core.playback.api.PlaybackUiDolbyVisionLuminance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class DvUiCompensation(

    val luminanceScale: Float = PlaybackUiDolbyVisionLuminance.DEFAULT_SCALE,

    val debugOverride: Float? = null,
) {

    val requestedScale: Float
        get() = PlaybackUiDolbyVisionLuminance.clampScale(debugOverride ?: luminanceScale)

    val hasDebugOverride: Boolean get() = debugOverride != null

    val isBaseline: Boolean
        get() = requestedScale >= PlaybackUiDolbyVisionLuminance.IDENTITY_SCALE

    companion object {

        val Automatic: DvUiCompensation = DvUiCompensation()

        val Baseline: DvUiCompensation = DvUiCompensation(debugOverride = PlaybackUiDolbyVisionLuminance.IDENTITY_SCALE)
    }
}

object DvUiCompensationExperiment {

    private const val Tag: String = "INFUSION-DV-UI-COMPENSATION"

    private val _state = MutableStateFlow(DvUiCompensation.Automatic)

    val state: StateFlow<DvUiCompensation> = _state.asStateFlow()

    fun apply(command: String?): DvUiCompensation {
        val current = state.value
        val trimmed = command?.trim().orEmpty().lowercase()
        val next = when {
            trimmed == "on" -> DvUiCompensation(debugOverride = current.luminanceScale)
            trimmed == "off" -> current.copy(debugOverride = PlaybackUiDolbyVisionLuminance.IDENTITY_SCALE)
            trimmed == "reset" -> DvUiCompensation.Automatic
            trimmed == "status" -> current
            trimmed.startsWith("scale=") -> {
                val requested = trimmed.removePrefix("scale=").toFloatOrNull()
                if (requested == null) current
                else current.copy(debugOverride = PlaybackUiDolbyVisionLuminance.clampScale(requested))
            }
            trimmed.startsWith("step=") -> {
                val steps = trimmed.removePrefix("step=").toIntOrNull()
                if (steps == null) current
                else current.copy(
                    debugOverride = PlaybackUiDolbyVisionLuminance.steppedScale(current.requestedScale, steps),
                )
            }
            else -> current
        }
        if (next != current) _state.value = next
        val commandLabel = trimmed.ifEmpty { "<empty>" }
        val overrideLabel = next.debugOverride?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "automatic"
        android.util.Log.i(
            Tag,
            "cmd=" + commandLabel +
                " override=" + overrideLabel +
                " scale=" + String.format(java.util.Locale.US, "%.2f", next.requestedScale),
        )
        return next
    }

    fun reset() {
        apply("reset")
    }
}
