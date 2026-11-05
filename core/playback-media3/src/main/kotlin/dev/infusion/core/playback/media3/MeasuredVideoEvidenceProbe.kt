package dev.infusion.core.playback.media3

import androidx.media3.common.util.UnstableApi
import dev.infusion.core.playback.api.MeasuredVideoCadence
import dev.infusion.core.playback.api.MeasuredVideoEvidence
import dev.infusion.core.playback.api.VideoCadenceNaming
import kotlin.math.abs
import kotlin.math.roundToInt

@UnstableApi
internal class MeasuredVideoEvidenceProbe(
    private val onEvidence: (MeasuredVideoEvidence) -> Unit,
    private val log: (String) -> Unit = {},

    private val rawEvidenceFrameLimit: Long = RAW_EVIDENCE_FRAME_LIMIT,
) {

    private val estimator = Media3FrameCadenceEstimator()

    private var largestWidthPx: Int? = null
    private var largestHeightPx: Int? = null

    private var publishedCadenceMilliHz: Int? = null
    private var publishedWidthPx: Int? = null
    private var publishedHeightPx: Int? = null

    private var lastLoggedCadenceMilliHz: Int? = null

    private var framesObserved: Long = 0L

    private var latchedCadence: MeasuredVideoCadence? = null

    var evidence: MeasuredVideoEvidence = MeasuredVideoEvidence.NONE
        private set

    val hasNamedCadence: Boolean get() = latchedCadence != null

    fun onFramePresentationTimeUs(presentationTimeUs: Long) {
        framesObserved++

        estimator.onNextFrame(presentationTimeUs * NANOS_PER_MICROSECOND)
        updateNamedCadence()

        if (framesObserved <= rawEvidenceFrameLimit) {
            log("frame index=$framesObserved presentationTimeUs=$presentationTimeUs")
        }

        if (framesObserved == 1L || framesObserved % FRAME_LIVENESS_INTERVAL == 0L) {
            log(
                "rendered frames=$framesObserved lastPresentationTimeUs=$presentationTimeUs " +
                    "synced=${estimator.isSynced} " +
                    "frameDurationNs=${estimator.frameDurationNs.takeIf { it != Media3FrameCadenceEstimator.TIME_UNSET } ?: "none"} " +
                    "matchedIntervals=${estimator.matchedIntervals} " +
                    "recentOutliers=${estimator.recentOutliers} " +
                    "namedCadence=${namedCadence()?.frameRateMilliHz ?: "none"} " +
                    "withheld=${withheldReason() ?: "no"}",
            )
        }

        val cadence = namedCadence()
        val cadenceKey = cadence?.frameRateMilliHz
        if (cadenceKey != lastLoggedCadenceMilliHz) {
            lastLoggedCadenceMilliHz = cadenceKey
            log(
                "measured cadence: " +
                    (
                        cadence?.let {
                            "${it.frameRateMilliHz}mHz matchedIntervals=${it.sampleCount} " +
                                (if (it.isCanonical) "canonical" else "non-standard")
                        } ?: "not named (${withheldReason() ?: "no stable cadence"})"
                        ),
            )
        }
        publishIfChanged()
    }

    fun onRenderedVideoSize(
        widthPx: Int,
        heightPx: Int,
        rotationDegrees: Int,
        pixelWidthHeightRatio: Float,
    ) {
        if (widthPx <= 0 || heightPx <= 0) return
        val rotatedWidth = if (abs(rotationDegrees) % 180 == 90) heightPx else widthPx
        val rotatedHeight = if (abs(rotationDegrees) % 180 == 90) widthPx else heightPx
        val aspect = pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        val effectiveWidth = (rotatedWidth * aspect).roundToInt()
        val effectiveHeight = rotatedHeight
        if (effectiveWidth <= 0 || effectiveHeight <= 0) return
        val largestArea = (largestWidthPx ?: 0).toLong() * (largestHeightPx ?: 0).toLong()
        val observedArea = effectiveWidth.toLong() * effectiveHeight.toLong()
        if (observedArea <= largestArea) return
        largestWidthPx = effectiveWidth
        largestHeightPx = effectiveHeight
        log("measured rendered size: ${effectiveWidth}x$effectiveHeight")
        publishIfChanged()
    }

    fun onDiscontinuity() {
        log(
            "position discontinuity noted (cadence retained; " +
                "synced=${estimator.isSynced} namedCadence=${namedCadence()?.frameRateMilliHz ?: "none"})",
        )
    }

    fun onVideoFormatChanged(formatFrameRateHz: Float = Media3FrameCadenceEstimator.RATE_UNSET) {
        val hadCadence = latchedCadence != null
        estimator.onFormatChanged(formatFrameRateHz)
        latchedCadence = null
        if (!hadCadence) return
        log(
            "measured cadence reset (video format changed; declaredFrameRate=$formatFrameRateHz) " +
                "-> withdrawn",
        )

        publishIfChanged()
    }

    fun reset() {
        estimator.reset()
        latchedCadence = null
        largestWidthPx = null
        largestHeightPx = null
        publishedCadenceMilliHz = null
        publishedWidthPx = null
        publishedHeightPx = null
        lastLoggedCadenceMilliHz = null
        framesObserved = 0L
        evidence = MeasuredVideoEvidence.NONE
    }

    private fun namedCadence() = latchedCadence

    private fun updateNamedCadence() {
        val reportedNs = estimator.reportedFrameDurationNs
        if (reportedNs == Media3FrameCadenceEstimator.TIME_UNSET) {
            latchedCadence = null
            return
        }
        VideoCadenceNaming.nameCadence(
            frameDurationNs = reportedNs,
            matchedIntervals = VideoCadenceNaming.MINIMUM_MATCHED_INTERVALS,
        )?.let { latchedCadence = it }
    }

    private fun withheldReason(): String? = when {
        latchedCadence != null -> null
        estimator.reportedFrameDurationNs != Media3FrameCadenceEstimator.TIME_UNSET -> "cannot name the rate"
        !estimator.isSynced ->
            "no stable cadence (outliers=${estimator.recentOutliers}, unsyncedFrames=${estimator.framesWithoutSync})"
        else -> "insufficient matched intervals " +
            "(${estimator.matchedIntervals}/${VideoCadenceNaming.MINIMUM_MATCHED_INTERVALS})"
    }

    private fun publishIfChanged() {
        val cadence = namedCadence()?.frameRateMilliHz
        if (cadence == publishedCadenceMilliHz &&
            largestWidthPx == publishedWidthPx &&
            largestHeightPx == publishedHeightPx
        ) {
            return
        }
        publishedCadenceMilliHz = cadence
        publishedWidthPx = largestWidthPx
        publishedHeightPx = largestHeightPx
        val next = MeasuredVideoEvidence(
            frameRateMilliHz = cadence,
            widthPx = largestWidthPx,
            heightPx = largestHeightPx,
        )
        evidence = next
        log(
            "measured evidence published: " +
                "cadence=${cadence ?: "none"}mHz " +
                "size=${largestWidthPx ?: "?"}x${largestHeightPx ?: "?"}",
        )
        onEvidence(next)
    }

    private companion object {
        const val NANOS_PER_MICROSECOND = 1_000L

        const val FRAME_LIVENESS_INTERVAL = 600L

        const val RAW_EVIDENCE_FRAME_LIMIT = 1_200L
    }
}
