package dev.infusion.tv.ui.screens

import dev.infusion.core.playback.api.RelativeSeekOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TvPlayerSeekFeedbackState(
    private val scope: CoroutineScope,
    private val holdMillis: Long = TvPlayerOverlayAppearance.SeekFeedbackMillis,
) {

    private val _feedback = MutableStateFlow(TvPlayerSeekFeedback.NONE)

    val feedback: StateFlow<TvPlayerSeekFeedback> = _feedback.asStateFlow()

    private val _seekSeconds = MutableStateFlow<Int?>(null)

    val seekSeconds: StateFlow<Int?> = _seekSeconds.asStateFlow()

    private val _chapterFeedback = MutableStateFlow<TvPlayerChapterFeedback?>(null)

    val chapterFeedback: StateFlow<TvPlayerChapterFeedback?> = _chapterFeedback.asStateFlow()

    private var clearJob: Job? = null

    private var burstId: Long = 0L

    private var accumulatedMs: Long = 0L

    private var accumulatedStepMs: Long = 0L

    private var burstDirection: TvPlayerSeekFeedback? = null

    fun show(feedback: TvPlayerSeekFeedback) {
        if (feedback == TvPlayerSeekFeedback.NONE) return
        resetAccumulation()
        _chapterFeedback.value = null
        _feedback.value = feedback
        arm()
    }

    fun showSeek(outcome: RelativeSeekOutcome) {
        val direction = when {
            outcome.commandedMs > 0L -> TvPlayerSeekFeedback.FORWARD
            outcome.commandedMs < 0L -> TvPlayerSeekFeedback.BACKWARD
            else -> return
        }

        if (direction != burstDirection || outcome.requestedStepMs != accumulatedStepMs) {
            accumulatedMs = 0L
            accumulatedStepMs = outcome.requestedStepMs
            burstDirection = direction
        }
        accumulatedMs += if (outcome.commandedMs < 0L) -outcome.commandedMs else outcome.commandedMs
        _chapterFeedback.value = null
        _feedback.value = direction
        _seekSeconds.value = accumulatedSeconds()
        arm()
    }

    fun showChapter(feedback: TvPlayerChapterFeedback) {
        resetAccumulation()
        _feedback.value = TvPlayerSeekFeedback.NONE
        _chapterFeedback.value = feedback
        arm()
    }

    fun resetBurst() {
        resetAccumulation()
        _feedback.value = TvPlayerSeekFeedback.NONE
        _seekSeconds.value = null
        clearJob?.cancel()
        clearJob = null
        burstId++
    }

    private fun arm() {
        clearJob?.cancel()
        val id = ++burstId
        clearJob = scope.launch {
            delay(holdMillis)

            if (id != burstId) return@launch
            resetAccumulation()
            _feedback.value = TvPlayerSeekFeedback.NONE
            _seekSeconds.value = null
            _chapterFeedback.value = null
        }
    }

    fun clear() {
        resetAccumulation()
        clearJob?.cancel()
        clearJob = null
        burstId++
        _feedback.value = TvPlayerSeekFeedback.NONE
        _seekSeconds.value = null
        _chapterFeedback.value = null
    }

    private fun resetAccumulation() {
        accumulatedMs = 0L
        accumulatedStepMs = 0L
        burstDirection = null
        _seekSeconds.value = null
    }

    private fun accumulatedSeconds(): Int =
        ((accumulatedMs + 500L) / 1_000L).toInt().coerceAtLeast(0)
}

enum class TvPlayerSeekFeedback {
    NONE,

    BACKWARD,

    FORWARD,

    PAUSED,
}

data class TvPlayerChapterFeedback(
    val forward: Boolean,
    val insideLabel: String,
    val belowLabel: String?,
)
