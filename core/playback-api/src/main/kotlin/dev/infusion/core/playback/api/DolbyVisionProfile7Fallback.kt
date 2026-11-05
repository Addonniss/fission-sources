package dev.infusion.core.playback.api

enum class DolbyVisionProfile7Fallback(
    val storageKey: String,
) {

    ON("on"),

    OFF("off"),
    ;

    companion object {

        val DEFAULT: DolbyVisionProfile7Fallback = ON

        fun orDefault(storageKey: String?): DolbyVisionProfile7Fallback =
            entries.firstOrNull { it.storageKey == storageKey } ?: DEFAULT
    }
}

interface DolbyVisionProfile7FallbackPreferenceStore {

    fun fallback(): DolbyVisionProfile7Fallback

    fun setFallback(fallback: DolbyVisionProfile7Fallback)
}

enum class VideoColourPrimaries {

    BT709,

    BT2020,

    OTHER,
}

enum class VideoColourTransfer {

    SDR,

    PQ,

    HLG,

    OTHER,
}

data class DolbyVisionTrackEvidence(

    val dolbyVision: Boolean = false,

    val profile: Int? = null,

    val colourTransfer: VideoColourTransfer? = null,

    val colourPrimaries: VideoColourPrimaries? = null,

    val deviceDolbyVisionSupport: Boolean? = null,
)

sealed interface DolbyVisionBaseLayerDecision {

    data object KeepDolbyVision : DolbyVisionBaseLayerDecision

    data class UseBaseLayer(val mimeType: String) : DolbyVisionBaseLayerDecision
}

object DolbyVisionBaseLayerPolicy {

    const val PROFILE_7: Int = 7

    const val PROFILE_7_BASE_LAYER_MIME_TYPE: String = "video/hevc"

    fun baseLayerMimeTypeForProfile(profile: Int?): String? =
        if (profile == PROFILE_7) PROFILE_7_BASE_LAYER_MIME_TYPE else null

    fun decide(
        evidence: DolbyVisionTrackEvidence,
        setting: DolbyVisionProfile7Fallback,
    ): DolbyVisionBaseLayerDecision {

        if (setting != DolbyVisionProfile7Fallback.ON) return DolbyVisionBaseLayerDecision.KeepDolbyVision

        if (!evidence.dolbyVision) return DolbyVisionBaseLayerDecision.KeepDolbyVision

        val baseLayerMimeType = baseLayerMimeTypeForProfile(evidence.profile)
            ?: return DolbyVisionBaseLayerDecision.KeepDolbyVision

        if (evidence.colourTransfer != VideoColourTransfer.PQ) {
            return DolbyVisionBaseLayerDecision.KeepDolbyVision
        }
        if (evidence.colourPrimaries != VideoColourPrimaries.BT2020) {
            return DolbyVisionBaseLayerDecision.KeepDolbyVision
        }

        if (evidence.deviceDolbyVisionSupport != false) {
            return DolbyVisionBaseLayerDecision.KeepDolbyVision
        }
        return DolbyVisionBaseLayerDecision.UseBaseLayer(baseLayerMimeType)
    }
}
