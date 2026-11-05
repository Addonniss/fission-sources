package dev.infusion.core.playback.media3

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import dev.infusion.core.playback.api.DolbyVisionBaseLayerDecision
import dev.infusion.core.playback.api.DolbyVisionBaseLayerPolicy
import dev.infusion.core.playback.api.DolbyVisionProfile7Fallback
import dev.infusion.core.playback.api.DolbyVisionTrackEvidence
import dev.infusion.core.playback.api.VideoColourPrimaries
import dev.infusion.core.playback.api.VideoColourTransfer

@UnstableApi
internal object DolbyVisionDeviceCapability {

    fun probe(context: Context, format: Format): Boolean? = runCatching {
        MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false)
            .filter { decoder -> decoder.mimeType == MimeTypes.VIDEO_DOLBY_VISION }
            .any { decoder -> decoder.isFormatSupported(context, format) }
    }.getOrNull()
}

internal fun neutralVideoColourTransfer(colorTransfer: Int): VideoColourTransfer? = when (colorTransfer) {
    UNDECLARED_COLOUR_VALUE -> null
    C.COLOR_TRANSFER_ST2084 -> VideoColourTransfer.PQ
    C.COLOR_TRANSFER_HLG -> VideoColourTransfer.HLG
    C.COLOR_TRANSFER_LINEAR, C.COLOR_TRANSFER_SRGB, C.COLOR_TRANSFER_GAMMA_2_2,
    C.COLOR_TRANSFER_SDR,
    -> VideoColourTransfer.SDR
    else -> VideoColourTransfer.OTHER
}

internal const val UNDECLARED_COLOUR_VALUE: Int = -1

@UnstableApi
internal class DolbyVisionProfile7FormatRewriter(
    private val context: Context,
    private val setting: DolbyVisionProfile7Fallback,
    private val log: (String) -> Unit = {},

    private val deviceCapability: (Context, Format) -> Boolean? = DolbyVisionDeviceCapability::probe,
) {

    private val deviceSupportByShape = mutableMapOf<String, Boolean>()

    fun rewrite(format: Format): Format? {
        if (setting != DolbyVisionProfile7Fallback.ON) return null
        if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return null
        val evidence = evidenceFor(format)
        val decision = DolbyVisionBaseLayerPolicy.decide(evidence, setting)
        val outcome = when (decision) {
            DolbyVisionBaseLayerDecision.KeepDolbyVision -> "keep-dolby-vision"
            is DolbyVisionBaseLayerDecision.UseBaseLayer -> "use-base-layer:${decision.mimeType}"
        }

        Media3ForensicsLog.event(
            "dv-profile7-base-layer",
            "outcome=$outcome profile=${evidence.profile} " +
                "colourTransfer=${evidence.colourTransfer} colourPrimaries=${evidence.colourPrimaries} " +
                "deviceDolbyVisionSupport=${evidence.deviceDolbyVisionSupport} " +
                "codecs=${format.codecs} ${format.width}x${format.height}",
        )
        log(
            "dv.profile7 outcome=$outcome profile=${evidence.profile} " +
                "transfer=${evidence.colourTransfer} primaries=${evidence.colourPrimaries} " +
                "deviceSupportsDV=${evidence.deviceDolbyVisionSupport}",
        )
        return when (decision) {
            DolbyVisionBaseLayerDecision.KeepDolbyVision -> null
            is DolbyVisionBaseLayerDecision.UseBaseLayer -> format.buildUpon()
                .setSampleMimeType(decision.mimeType)

                .setCodecs(null)
                .build()
        }
    }

    internal fun evidenceFor(format: Format): DolbyVisionTrackEvidence {
        if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return DolbyVisionTrackEvidence()
        val colorInfo = format.colorInfo
        return DolbyVisionTrackEvidence(
            dolbyVision = true,
            profile = dolbyVisionProfileNumber(format),
            colourTransfer = colorInfo?.colorTransfer?.let(::neutralVideoColourTransfer),
            colourPrimaries = colorInfo?.colorSpace?.toVideoColourPrimaries(),
            deviceDolbyVisionSupport = deviceDolbyVisionSupport(format),
        )
    }

    private fun dolbyVisionProfileNumber(format: Format): Int? {
        val profileConstant = CodecSpecificDataUtil.getCodecProfileAndLevel(format)?.first ?: return null

        return runCatching {
            CodecSpecificDataUtil.dolbyVisionConstantToProfileNumber(profileConstant)
        }.getOrNull()
    }

    private fun deviceDolbyVisionSupport(format: Format): Boolean? {
        val shape = "${format.codecs}|${format.width}x${format.height}@${format.frameRate}"
        deviceSupportByShape[shape]?.let { return it }
        val probed = deviceCapability(context, format)
        if (probed != null) deviceSupportByShape[shape] = probed
        return probed
    }

    private companion object {
        fun Int.toVideoColourPrimaries(): VideoColourPrimaries? = when (this) {
            UNDECLARED_COLOUR_VALUE -> null
            C.COLOR_SPACE_BT709 -> VideoColourPrimaries.BT709
            C.COLOR_SPACE_BT2020 -> VideoColourPrimaries.BT2020
            else -> VideoColourPrimaries.OTHER
        }
    }
}
