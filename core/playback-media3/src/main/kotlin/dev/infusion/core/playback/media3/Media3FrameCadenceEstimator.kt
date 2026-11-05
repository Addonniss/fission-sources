/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.infusion.core.playback.media3

import dev.infusion.core.playback.api.VideoCadenceNaming
import kotlin.math.abs

internal class Media3FrameCadenceEstimator {

    private var currentMatcher = Matcher()
    private var candidateMatcher = Matcher()
    private var candidateMatcherActive = false
    private var lastFramePresentationTimeNs = TIME_UNSET

    var formatFrameRateHz: Float = RATE_UNSET
        private set

    var frameIndex: Long = 0L
        private set

    var reportedFrameDurationNs: Long = TIME_UNSET
        private set

    var framesWithoutSync: Int = 0
        private set

    val isHighConfidence: Boolean
        get() = isSynced &&
            matchedDurationSumNs >= MINIMUM_MATCHING_FRAME_DURATION_FOR_HIGH_CONFIDENCE_NS

    val isSynced: Boolean get() = currentMatcher.isSynced

    val frameDurationNs: Long
        get() = if (isSynced) currentMatcher.frameDurationNs else TIME_UNSET

    val matchedIntervals: Int get() = currentMatcher.matchedIntervals

    val matchedDurationSumNs: Long get() = currentMatcher.matchedDurationSumNs

    val recentOutliers: Int get() = currentMatcher.recentOutlierCount

    fun onFormatChanged(formatFrameRateHz: Float) {
        this.formatFrameRateHz = if (formatFrameRateHz.isFinite() && formatFrameRateHz > 0f) {
            formatFrameRateHz
        } else {
            RATE_UNSET
        }
        currentMatcher.reset()
        candidateMatcher.reset()
        candidateMatcherActive = false
        lastFramePresentationTimeNs = TIME_UNSET
        framesWithoutSync = 0

        reportedFrameDurationNs = TIME_UNSET
    }

    fun reset() = onFormatChanged(RATE_UNSET)

    fun onNextFrame(presentationTimeNs: Long) {

        if (presentationTimeNs == lastFramePresentationTimeNs) return
        frameIndex++
        currentMatcher.onNextFrame(presentationTimeNs)
        if (currentMatcher.isSynced) {
            candidateMatcherActive = false
        } else if (lastFramePresentationTimeNs != TIME_UNSET) {

            if (!candidateMatcherActive || candidateMatcher.isLastFrameOutlier) {
                candidateMatcher.reset()
                candidateMatcher.onNextFrame(lastFramePresentationTimeNs)
            }
            candidateMatcherActive = true
            candidateMatcher.onNextFrame(presentationTimeNs)
        }
        if (candidateMatcherActive && candidateMatcher.isSynced) {

            val previous = currentMatcher
            currentMatcher = candidateMatcher
            candidateMatcher = previous
            candidateMatcherActive = false
        }
        lastFramePresentationTimeNs = presentationTimeNs
        updateReportedFrameDuration()
    }

    private fun updateReportedFrameDuration() {
        if (!isSynced) {
            framesWithoutSync++
            if (framesWithoutSync >= MINIMUM_FRAMES_WITHOUT_SYNC_TO_CLEAR_FRAME_RATE_ESTIMATE) {
                reportedFrameDurationNs = TIME_UNSET
            }
            return
        }
        framesWithoutSync = 0

        if (matchedIntervals < VideoCadenceNaming.MINIMUM_MATCHED_INTERVALS) return
        val candidate = currentMatcher.frameDurationNs
        if (candidate <= 0L) return
        val reported = reportedFrameDurationNs
        if (reported == TIME_UNSET) {
            reportedFrameDurationNs = candidate
            return
        }
        val thresholdHz = if (isHighConfidence) {
            MINIMUM_MEDIA_FRAME_RATE_CHANGE_FOR_UPDATE_HIGH_CONFIDENCE_HZ
        } else {
            MINIMUM_MEDIA_FRAME_RATE_CHANGE_FOR_UPDATE_LOW_CONFIDENCE_HZ
        }
        val candidateRateHz = NANOS_PER_SECOND_D / candidate.toDouble()
        val reportedRateHz = NANOS_PER_SECOND_D / reported.toDouble()
        if (abs(candidateRateHz - reportedRateHz) >= thresholdHz) {
            reportedFrameDurationNs = candidate
        }
    }

    private class Matcher {
        private var firstFramePresentationTimeNs = 0L
        private var firstFrameDurationNs = 0L
        private var lastFramePresentationTimeNs = 0L
        private var frameCount = 0L

        var matchedDurationSumNs = 0L
            private set

        var matchedFrameCount = 0L
            private set

        private val recentFrameOutlierFlags = BooleanArray(CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC)
        private var recentFrameOutlierCount = 0

        val recentOutlierCount: Int get() = recentFrameOutlierCount

        val isSynced: Boolean
            get() = frameCount > CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC &&
                recentFrameOutlierCount == 0

        val isLastFrameOutlier: Boolean
            get() = frameCount != 0L &&
                recentFrameOutlierFlags[recentFrameOutlierIndex(frameCount - 1)]

        val frameDurationNs: Long
            get() = if (matchedFrameCount == 0L) {
                0L
            } else {
                matchedDurationSumNs / matchedFrameCount
            }

        val matchedIntervals: Int
            get() = matchedFrameCount.coerceAtLeast(0L).toInt()

        fun reset() {
            frameCount = 0
            matchedFrameCount = 0
            matchedDurationSumNs = 0
            recentFrameOutlierCount = 0
            recentFrameOutlierFlags.fill(false)
        }

        fun onNextFrame(framePresentationTimeNs: Long) {
            when (frameCount) {
                0L -> firstFramePresentationTimeNs = framePresentationTimeNs
                1L -> {
                    firstFrameDurationNs = framePresentationTimeNs - firstFramePresentationTimeNs
                    matchedDurationSumNs = firstFrameDurationNs
                    matchedFrameCount = 1
                }
                else -> {
                    val frameDurationNs = framePresentationTimeNs - lastFramePresentationTimeNs
                    val index = recentFrameOutlierIndex(frameCount)
                    if (abs(frameDurationNs - firstFrameDurationNs) <= MAX_MATCHING_FRAME_DIFFERENCE_NS) {
                        matchedFrameCount++
                        matchedDurationSumNs += frameDurationNs
                        if (recentFrameOutlierFlags[index]) {
                            recentFrameOutlierFlags[index] = false
                            recentFrameOutlierCount--
                        }
                    } else if (!recentFrameOutlierFlags[index]) {
                        recentFrameOutlierFlags[index] = true
                        recentFrameOutlierCount++
                    }
                }
            }
            frameCount++
            lastFramePresentationTimeNs = framePresentationTimeNs
        }

        private fun recentFrameOutlierIndex(frameIndex: Long): Int =
            (frameIndex % CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC).toInt()
    }

    companion object {

        const val CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC: Int = 15

        const val MAX_MATCHING_FRAME_DIFFERENCE_NS: Long = 1_000_000L

        const val TIME_UNSET: Long = Long.MIN_VALUE + 1

        const val RATE_UNSET: Float = -1f

        const val MINIMUM_MATCHING_FRAME_DURATION_FOR_HIGH_CONFIDENCE_NS: Long = 5_000_000_000L

        const val MINIMUM_MEDIA_FRAME_RATE_CHANGE_FOR_UPDATE_HIGH_CONFIDENCE_HZ: Double = 0.1

        const val MINIMUM_MEDIA_FRAME_RATE_CHANGE_FOR_UPDATE_LOW_CONFIDENCE_HZ: Double = 1.0

        const val MINIMUM_FRAMES_WITHOUT_SYNC_TO_CLEAR_FRAME_RATE_ESTIMATE: Int = 30

        private const val NANOS_PER_SECOND_D = 1.0e9
    }
}
