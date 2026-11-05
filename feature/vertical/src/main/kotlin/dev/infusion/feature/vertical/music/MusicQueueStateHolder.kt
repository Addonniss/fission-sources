package dev.infusion.feature.vertical.music

import dev.infusion.core.domain.music.MusicAudioFocus
import dev.infusion.core.domain.music.MusicAudioFocusChange
import dev.infusion.core.domain.music.MusicAudioFocusListener
import dev.infusion.core.domain.music.MusicLyricLine
import dev.infusion.core.domain.music.MusicLyrics
import dev.infusion.core.domain.music.MusicLyricsResult
import dev.infusion.core.domain.music.MusicPageFailure
import dev.infusion.core.domain.music.MusicPageResult
import dev.infusion.core.domain.music.MusicQueueContext
import dev.infusion.core.domain.music.MusicQueueEntry
import dev.infusion.core.domain.music.MusicQueuePort
import dev.infusion.core.domain.music.MusicQueueSlotId
import dev.infusion.core.domain.music.MusicQueueSourceIdentity
import dev.infusion.core.domain.music.MusicReportingIdentity
import dev.infusion.core.domain.music.MusicTransportFailure
import dev.infusion.core.domain.music.MusicTransportResult
import dev.infusion.core.domain.music.QueueEntryId
import dev.infusion.core.playback.api.ActivePlaybackReporter
import dev.infusion.core.playback.api.AttemptReason
import dev.infusion.core.playback.api.EndReason
import dev.infusion.core.playback.api.PlaybackEngine
import dev.infusion.core.playback.api.PlaybackEvent
import dev.infusion.core.playback.api.PlaybackEventListener
import dev.infusion.core.playback.api.PlaybackSession
import dev.infusion.core.playback.api.PlaybackState
import dev.infusion.core.playback.api.SessionId
import dev.infusion.core.playback.api.SessionLineage
import dev.infusion.core.playback.api.StopReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class MusicQueuePhase {

    IDLE,

    READY,

    PREPARING,

    PLAYING,

    PAUSED,

    ENDED,

    ERROR,
}

enum class MusicRepeatMode { OFF, ALL, ONE }

data class MusicQueueError(
    val message: String,
    val phase: MusicQueuePhase = MusicQueuePhase.ERROR,
    val canRetry: Boolean = true,
    val canSkip: Boolean = true,
)

data class MusicQueueRow(
    val position: Int,
    val slot: MusicQueueSlotId,
    val entry: MusicQueueEntry?,
)

data class MusicQueueWindow(
    val rows: List<MusicQueueRow>,
    val total: Int,

    val currentPosition: Int,
)

data class MusicQueueState(
    val queueId: String? = null,
    val contextLabel: String? = null,
    val current: MusicQueueEntry? = null,

    val currentSlot: MusicQueueSlotId? = null,

    val orderPosition: Int = 0,

    val absoluteIndex: Int = 0,

    val total: Int? = null,
    val phase: MusicQueuePhase = MusicQueuePhase.IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val canSeek: Boolean = false,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
    val shuffleEnabled: Boolean = false,

    val shuffleAvailable: Boolean = false,
    val repeat: MusicRepeatMode = MusicRepeatMode.OFF,
    val error: MusicQueueError? = null,

    val sourceChanged: Boolean = false,

    val sourceGone: Boolean = false,

    val currentSessionId: String? = null,

    val window: MusicQueueWindow? = null,
) {
    val hasQueue: Boolean get() = queueId != null
    val isPlaying: Boolean get() = phase == MusicQueuePhase.PLAYING
    val isPaused: Boolean get() = phase == MusicQueuePhase.PAUSED
    val endOfQueue: Boolean get() = phase == MusicQueuePhase.ENDED

    val isReady: Boolean get() = phase == MusicQueuePhase.READY

    val queueSize: Int get() = window?.total ?: 0
}

enum class MusicLyricsPhase { IDLE, LOADING, READY, UNAVAILABLE, FAILED }

data class MusicLyricsState(
    val occurrenceId: QueueEntryId? = null,
    val phase: MusicLyricsPhase = MusicLyricsPhase.IDLE,
    val lyrics: MusicLyrics = MusicLyrics.None,
    val message: String? = null,
) {

    fun activeLineIndex(positionMs: Long): Int? {
        val synced = lyrics as? MusicLyrics.Synced ?: return null
        if (synced.lines.isEmpty()) return null
        var index: Int? = null
        synced.lines.forEachIndexed { i, line ->
            if (line.startMs <= positionMs) index = i
        }
        return index ?: 0
    }
}

class MusicQueueStateHolder(
    private val port: MusicQueuePort,
    private val engine: PlaybackEngine,
    private val scope: CoroutineScope,

    private val profileId: () -> String,

    private val reporterFor: (MusicReportingIdentity) -> ActivePlaybackReporter? = { null },
    private val audioFocus: MusicAudioFocus? = null,

    private val onTakeAudibleOwnership: () -> Unit = {},

    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {

        const val WINDOW_BEFORE: Int = 20
        const val WINDOW_AFTER: Int = 40
        const val PAGE_SIZE: Int = 25

        const val MAX_LOADED_PAGES: Int = 48

        private const val PROTECTED_POSITION_SPAN: Int = 64

        const val PREVIOUS_RESTART_THRESHOLD_MS: Long = 3_000L

        const val MAX_TRACK_SCAN: Int = 500

        const val STOP_AWAIT_TIMEOUT_MS: Long = 3_000L
    }

    private val _state = MutableStateFlow(MusicQueueState())
    val state: StateFlow<MusicQueueState> = _state.asStateFlow()

    private val _lyrics = MutableStateFlow(MusicLyricsState())
    val lyrics: StateFlow<MusicLyricsState> = _lyrics.asStateFlow()

    private sealed interface Msg {
        data class Start(val context: MusicQueueContext, val startIndex: Int, val shuffle: Boolean) : Msg
        data class StartAtTrack(
            val context: MusicQueueContext,
            val trackNativeId: String,
            val shuffle: Boolean,
        ) : Msg
        data class PlayEntryAt(val absoluteIndex: Int) : Msg
        data object TogglePlayPause : Msg
        data object Play : Msg
        data object Pause : Msg
        data class Seek(val positionMs: Long) : Msg
        data object Next : Msg
        data object Previous : Msg

        data class Stop(val ack: CompletableDeferred<Unit>? = null) : Msg
        data object ToggleShuffle : Msg
        data object CycleRepeat : Msg
        data object Retry : Msg
        data object Skip : Msg
        data object LoadLyrics : Msg
        data object RetryLyrics : Msg

        data class RevealWindow(val fromPosition: Int, val toPosition: Int) : Msg
        data class PlayOccurrence(val slot: MusicQueueSlotId) : Msg
        data class MoveOccurrence(val slot: MusicQueueSlotId, val delta: Int) : Msg
        data class RemoveOccurrence(val slot: MusicQueueSlotId) : Msg
        data class PlayNextOccurrence(val slot: MusicQueueSlotId) : Msg
        data class InsertOccurrence(val entry: MusicQueueEntry, val playNext: Boolean) : Msg
        data class Event(val event: PlaybackEvent) : Msg
        data class Focus(val change: MusicAudioFocusChange) : Msg
    }

    private val inbox = Channel<Msg>(Channel.UNLIMITED)

    var onHandlerFailure: ((Throwable) -> Unit)? = null

    @Volatile
    private var generation: Long = 0L

    @Volatile
    private var stopRequested: Boolean = false

    private var queueId: String? = null
    private var sourceIdentity: MusicQueueSourceIdentity? = null
    private var total: Int? = null
    private var repeat = MusicRepeatMode.OFF
    private var orderPosition: Int = 0

    private var order: MusicQueueOrder? = null

    private var currentSlot: MusicQueueSlotId? = null

    private var localSequence: Int = 0

    private val loaded = mutableMapOf<Int, MusicQueueEntry>()

    private val loadedPages = LinkedHashSet<Int>()

    private var revealRange: IntRange? = null

    private var session: PlaybackSession? = null
    private var reporter: ActivePlaybackReporter? = null
    private var sessionId: String? = null
    private var attemptSequence: Int = 0
    private var positionMs: Long = 0L
    private var lastReportedPositionMs: Long = -1L
    private var durationMs: Long? = null
    private var canSeek: Boolean = false
    private var hasStartedPlayback = false
    private var userPaused = false
    private var resumeOnFocusGain = false
    private var lyricsRequest: Long = 0L

    private val listener = PlaybackEventListener { event -> inbox.trySend(Msg.Event(event)) }

    init {
        scope.launch(dispatcher) {
            for (message in inbox) {

                runCatching { handle(message) }.onFailure { failure -> onHandlerFailure?.invoke(failure) }
            }
        }
    }

    fun start(context: MusicQueueContext, startIndex: Int, shuffle: Boolean = false) {
        inbox.trySend(Msg.Start(context, startIndex.coerceAtLeast(0), shuffle))
    }

    fun startAtTrack(context: MusicQueueContext, trackNativeId: String, shuffle: Boolean = false) {
        inbox.trySend(Msg.StartAtTrack(context, trackNativeId, shuffle))
    }

    fun playEntryAt(absoluteIndex: Int) {
        inbox.trySend(Msg.PlayEntryAt(absoluteIndex.coerceAtLeast(0)))
    }

    fun togglePlayPause() {
        inbox.trySend(Msg.TogglePlayPause)
    }

    fun play() {
        inbox.trySend(Msg.Play)
    }

    fun pause() {
        inbox.trySend(Msg.Pause)
    }

    fun seekTo(positionMs: Long) {
        inbox.trySend(Msg.Seek(positionMs.coerceAtLeast(0L)))
    }

    fun next() {
        inbox.trySend(Msg.Next)
    }

    fun previous() {
        inbox.trySend(Msg.Previous)
    }

    fun stop() {
        stopRequested = true
        inbox.trySend(Msg.Stop())
    }

    suspend fun stopAndAwait(timeoutMs: Long = STOP_AWAIT_TIMEOUT_MS) {
        val ack = CompletableDeferred<Unit>()
        stopRequested = true
        if (inbox.trySend(Msg.Stop(ack)).isFailure) return
        withTimeoutOrNull(timeoutMs) { ack.await() }
    }

    fun stopForVideoOwnership() {
        stopRequested = true
        inbox.trySend(Msg.Stop())
    }

    fun toggleShuffle() {
        inbox.trySend(Msg.ToggleShuffle)
    }

    fun cycleRepeat() {
        inbox.trySend(Msg.CycleRepeat)
    }

    fun retry() {
        inbox.trySend(Msg.Retry)
    }

    fun skip() {
        inbox.trySend(Msg.Skip)
    }

    fun ensureLyrics() {
        inbox.trySend(Msg.LoadLyrics)
    }

    fun retryLyrics() {
        inbox.trySend(Msg.RetryLyrics)
    }

    fun revealWindow(fromPosition: Int? = null, toPosition: Int? = null) {
        val current = _state.value.orderPosition
        val from = fromPosition ?: (current - WINDOW_BEFORE)
        val to = toPosition ?: (current + WINDOW_AFTER)
        inbox.trySend(Msg.RevealWindow(from.coerceAtLeast(0), to.coerceAtLeast(0)))
    }

    fun playOccurrence(slot: MusicQueueSlotId) {
        inbox.trySend(Msg.PlayOccurrence(slot))
    }

    fun moveOccurrence(slot: MusicQueueSlotId, delta: Int) {
        if (delta == 0) return
        inbox.trySend(Msg.MoveOccurrence(slot, delta))
    }

    fun removeOccurrence(slot: MusicQueueSlotId) {
        inbox.trySend(Msg.RemoveOccurrence(slot))
    }

    fun playNextOccurrence(slot: MusicQueueSlotId) {
        inbox.trySend(Msg.PlayNextOccurrence(slot))
    }

    fun insertOccurrence(entry: MusicQueueEntry, playNext: Boolean) {
        inbox.trySend(Msg.InsertOccurrence(entry, playNext))
    }

    fun attachAudioFocus() {
        audioFocus?.setListener(MusicAudioFocusListener { change -> inbox.trySend(Msg.Focus(change)) })
    }

    private suspend fun handle(message: Msg) {
        when (message) {
            is Msg.Start -> handleStart(message.context, message.startIndex, message.shuffle)
            is Msg.StartAtTrack -> handleStartAtTrack(
                message.context,
                message.trackNativeId,
                message.shuffle,
            )
            is Msg.PlayEntryAt -> handlePlayEntryAt(message.absoluteIndex)
            Msg.TogglePlayPause -> if (_state.value.isPlaying) pauseInternal(user = true) else playInternal(user = true)
            Msg.Play -> playInternal(user = true)
            Msg.Pause -> pauseInternal(user = true)
            is Msg.Seek -> seekInternal(message.positionMs)
            Msg.Next -> moveRelative(+1, userInitiated = true)
            Msg.Previous -> previousInternal()
            is Msg.Stop -> stopInternal(clearQueue = true, ack = message.ack)
            Msg.ToggleShuffle -> toggleShuffleInternal()
            Msg.CycleRepeat -> cycleRepeatInternal()
            Msg.Retry -> retryInternal()
            Msg.Skip -> skipInternal()
            Msg.LoadLyrics -> loadLyricsInternal(force = false)
            Msg.RetryLyrics -> loadLyricsInternal(force = true)
            is Msg.RevealWindow -> handleRevealWindow(message.fromPosition, message.toPosition)
            is Msg.PlayOccurrence -> handlePlayOccurrence(message.slot)
            is Msg.MoveOccurrence -> handleMoveOccurrence(message.slot, message.delta)
            is Msg.RemoveOccurrence -> handleRemoveOccurrence(message.slot)
            is Msg.PlayNextOccurrence -> handlePlayNextOccurrence(message.slot)
            is Msg.InsertOccurrence -> handleInsertOccurrence(message.entry, message.playNext)
            is Msg.Event -> handleEvent(message.event)
            is Msg.Focus -> handleFocus(message.change)
        }
    }

    private suspend fun handleStart(context: MusicQueueContext, startIndex: Int, shuffleMode: Boolean) {
        stopAttempt(report = true)
        audioFocus?.abandon()
        generation += 1
        val thisGeneration = generation
        queueId = "music-$thisGeneration-${now()}"
        sourceIdentity = MusicQueueSourceIdentity(context, profileId(), now())
        order = null
        currentSlot = null
        clearPayloadWindow()
        total = null
        repeat = MusicRepeatMode.OFF
        orderPosition = 0
        positionMs = 0L
        durationMs = null
        canSeek = false
        userPaused = false
        resumeOnFocusGain = false
        hasStartedPlayback = false
        localSequence = 0
        lyricsRequest += 1
        _lyrics.value = MusicLyricsState()

        _state.value = MusicQueueState(
            queueId = queueId,
            contextLabel = context.label,
            phase = MusicQueuePhase.PREPARING,
            shuffleEnabled = false,
            shuffleAvailable = false,
            repeat = repeat,

            orderPosition = orderPosition,
            absoluteIndex = startIndex,
            total = null,
        )

        val probe = port.page(context, 0, 1)
        if (thisGeneration != generation) return

        if (stopRequested) return
        total = when (probe) {
            is MusicPageResult.Ok -> probe.page.total
            is MusicPageResult.Failed -> null
        }
        when (probe) {
            is MusicPageResult.Ok -> probe.page.entries.firstOrNull()?.let { loaded.putIfAbsent(0, it) }
            is MusicPageResult.Failed -> if (probe.kind == MusicPageFailure.DISCONNECTED) {
                failQueue(probe.message, sourceGone = true)
                return
            }
        }

        val materialised = MusicQueueOrder.sourceOrder(total ?: (startIndex + WINDOW_AFTER + 1))
        order = materialised
        val startSlot = MusicQueueSlotId.Source(startIndex)
        currentSlot = startSlot
        val shuffled = shuffleMode && total != null
        if (shuffled) materialised.shuffle(startSlot, seed = now())
        orderPosition = materialised.indexOf(startSlot).coerceAtLeast(0)

        ensureWindow(startIndex)
        if (thisGeneration != generation) return
        if (stopRequested) return
        val entry = entryAt(startIndex, thisGeneration)
        if (entry == null) {
            failQueue("This track is no longer available.", sourceGone = false)
            return
        }
        startAttempt(entry, startAtMs = 0L)
        publishOrderState(shuffled = shuffled)
    }

    private suspend fun handleStartAtTrack(
        context: MusicQueueContext,
        trackNativeId: String,
        shuffleMode: Boolean,
    ) {
        val startedGeneration = generation
        var offset = 0
        var index: Int? = null

        while (index == null && offset < MAX_TRACK_SCAN && !stopRequested) {
            when (val result = port.page(context, offset, PAGE_SIZE)) {
                is MusicPageResult.Ok -> {
                    val page = result.page
                    val local = page.entries.indexOfFirst { it.trackNativeId == trackNativeId }
                    if (local >= 0) {
                        index = offset + local
                        break
                    }
                    if (page.entries.isEmpty()) break
                    offset += page.entries.size
                    val total = page.total
                    if (total != null && offset >= total) break
                }
                is MusicPageResult.Failed -> {

                    if (generation != startedGeneration) return
                    if (result.kind == MusicPageFailure.DISCONNECTED) {
                        failQueue(result.message, sourceGone = true)
                    } else {
                        failQueue(result.message, sourceGone = false)
                    }
                    return
                }
            }
        }
        if (generation != startedGeneration) return
        val absolute = index
        if (absolute == null) {
            failQueue("This track is no longer in its album.", sourceGone = false)
            return
        }
        handleStart(context, absolute, shuffleMode)
    }

    private suspend fun handlePlayEntryAt(absoluteIndex: Int) {
        val identity = sourceIdentity ?: return
        ensureWindow(absoluteIndex)
        val entry = entryAt(absoluteIndex, generation) ?: return
        val slot = MusicQueueSlotId.Source(absoluteIndex)
        currentSlot = slot
        orderPosition = order?.indexOf(slot)?.takeIf { it >= 0 } ?: absoluteIndex
        startAttempt(entry, startAtMs = 0L)
        _state.value = _state.value.copy(contextLabel = identity.context.label)
    }

    private suspend fun playInternal(user: Boolean) {
        if (!_state.value.hasQueue) return
        if (session == null) {

            val entry = _state.value.current ?: return

            val startAt = if (_state.value.phase == MusicQueuePhase.ENDED) 0L else _state.value.positionMs
            startAttempt(entry, startAtMs = startAt)
            return
        }
        if (user) userPaused = false
        resumeOnFocusGain = false
        audioFocus?.request()
        session?.play()
    }

    private suspend fun pauseInternal(user: Boolean) {
        if (user) userPaused = true
        session?.pause()
    }

    private suspend fun seekInternal(target: Long) {
        val s = session ?: return
        s.seekTo(target)
        positionMs = target
        report { it.onSeek(target, isPaused = !_state.value.isPlaying, canSeek = canSeek, durationMs = durationMs) }
        _state.value = _state.value.copy(positionMs = target, canSeek = canSeek)
    }

    private suspend fun previousInternal() {
        if (!_state.value.hasQueue) return
        if (_state.value.current == null) return
        if (positionMs > PREVIOUS_RESTART_THRESHOLD_MS) {

            session?.seekTo(0L)
            positionMs = 0L
            report { it.onSeek(0L, isPaused = !_state.value.isPlaying, canSeek = canSeek, durationMs = durationMs) }
            _state.value = _state.value.copy(positionMs = 0L)
            return
        }
        if (orderPosition <= 0) return
        moveTo(orderPosition - 1)
    }

    private suspend fun moveRelative(delta: Int, userInitiated: Boolean) {
        if (!_state.value.hasQueue) return
        moveTo(orderPosition + delta)
    }

    private suspend fun moveTo(targetPosition: Int) {
        val identity = sourceIdentity ?: return
        val materialised = order ?: return
        val count = materialised.size
        val position = when {
            targetPosition >= 0 && targetPosition < count -> targetPosition
            targetPosition >= count && total == null -> {

                val page = targetPosition / PAGE_SIZE
                if (page !in loadedPages) readPage(identity.context, page)
                if (materialised.size <= targetPosition) {
                    if (repeat == MusicRepeatMode.ALL && materialised.size > 0) 0 else {
                        endQueue()
                        return
                    }
                } else {
                    targetPosition
                }
            }
            targetPosition >= count && repeat == MusicRepeatMode.ALL && count > 0 -> 0
            else -> {
                endQueue()
                return
            }
        }
        val slot = materialised.slotAt(position) ?: run { endQueue(); return }
        currentSlot = slot.id
        orderPosition = position
        val entry = resolveEntry(slot)
        if (entry == null) {
            failQueue("This track is no longer available.", sourceGone = false)
            return
        }
        startAttempt(entry, startAtMs = 0L)

        _state.value = _state.value.copy(contextLabel = identity.context.label)
    }

    private suspend fun resolveEntry(slot: MusicQueueOrder.Slot): MusicQueueEntry? {
        slot.entry?.let { return it }
        val absolute = (slot.id as? MusicQueueSlotId.Source)?.absoluteIndex ?: return null
        ensureWindow(absolute)
        return entryAt(absolute, generation)
    }

    private suspend fun handleRevealWindow(fromPosition: Int, toPosition: Int) {
        val materialised = order ?: return
        if (materialised.isEmpty()) {
            publishWindow()
            return
        }
        val last = toPosition.coerceAtMost(materialised.size - 1)
        val first = fromPosition.coerceAtMost(last).coerceAtLeast(0)

        revealRange = first..last
        val read = ensurePagesFor(first, last)

        val window = _state.value.window
        if (read || window == null || window.currentPosition != orderPosition) publishWindow()
    }

    private suspend fun handlePlayOccurrence(slot: MusicQueueSlotId) {
        val materialised = order ?: return
        val position = materialised.indexOf(slot)
        if (position < 0) return

        if (slot == currentSlot) return
        val target = materialised.slotAt(position) ?: return
        currentSlot = slot
        orderPosition = position
        val entry = resolveEntry(target) ?: run {
            failQueue("This track is no longer available.", sourceGone = false)
            return
        }
        startAttempt(entry, startAtMs = 0L)
        publishWindow()
    }

    private fun handleMoveOccurrence(slot: MusicQueueSlotId, delta: Int) {
        val materialised = order ?: return
        val from = materialised.indexOf(slot)
        if (from < 0) return
        val target = from + delta
        if (target !in 0 until materialised.size) return
        materialised.moveTo(slot, target)

        orderPosition = materialised.indexOf(currentSlot ?: slot).coerceAtLeast(0)
        publishOrderState()
    }

    private fun handlePlayNextOccurrence(slot: MusicQueueSlotId) {
        val materialised = order ?: return
        val from = materialised.indexOf(slot)
        if (from < 0) return
        val current = currentSlot
        if (slot == current) return
        val anchor = if (current != null) materialised.indexOf(current) else -1

        val target = when {
            anchor < 0 -> 0
            from < anchor -> anchor
            else -> anchor + 1
        }
        materialised.moveTo(slot, target)
        orderPosition = materialised.indexOf(currentSlot ?: slot).coerceAtLeast(0)
        publishOrderState()
    }

    private suspend fun handleRemoveOccurrence(slot: MusicQueueSlotId) {
        val materialised = order ?: return
        val position = materialised.indexOf(slot)
        if (position < 0) return
        val wasCurrent = slot == currentSlot
        val intent = _state.value.phase
        if (wasCurrent) {

            stopAttempt(report = true)
            audioFocus?.abandon()
        }
        materialised.remove(slot)
        if (materialised.isEmpty()) {
            stopInternal(clearQueue = true)
            return
        }
        if (!wasCurrent) {
            orderPosition = materialised.indexOf(currentSlot ?: return).coerceAtLeast(0)
            publishOrderState()
            return
        }

        val nextPosition = position.coerceAtMost(materialised.size - 1)
        val nextSlot = materialised.slotAt(nextPosition) ?: run { stopInternal(clearQueue = true); return }
        currentSlot = nextSlot.id
        orderPosition = nextPosition
        val entry = resolveEntry(nextSlot)
        if (entry == null) {
            failQueue("This track is no longer available.", sourceGone = false)
            publishOrderState()
            return
        }
        when (intent) {
            MusicQueuePhase.PLAYING, MusicQueuePhase.PREPARING -> startAttempt(entry, startAtMs = 0L)
            MusicQueuePhase.PAUSED, MusicQueuePhase.ENDED, MusicQueuePhase.READY -> {

                positionMs = 0L
                durationMs = entry.durationMs
                canSeek = false
                val phase = when (intent) {
                    MusicQueuePhase.PAUSED -> MusicQueuePhase.PAUSED
                    MusicQueuePhase.ENDED -> MusicQueuePhase.ENDED
                    else -> MusicQueuePhase.READY
                }
                _state.value = _state.value.copy(
                    current = entry,
                    currentSlot = nextSlot.id,
                    phase = phase,
                    positionMs = 0L,
                    durationMs = durationMs,
                    canSeek = false,
                    currentSessionId = null,
                    error = null,
                )
                publishOrderState()
            }
            MusicQueuePhase.ERROR, MusicQueuePhase.IDLE -> publishOrderState()
        }
    }

    private suspend fun handleInsertOccurrence(entry: MusicQueueEntry, playNext: Boolean) {
        val occurrence = entry.copy(occurrenceId = newLocalOccurrenceId())
        val materialised = order
        if (queueId == null) {
            startLocalQueue(occurrence)
            return
        }

        if (materialised == null) return
        if (!playNext) {
            materialised.appendLocal(occurrence)
            publishOrderState()
            return
        }
        val current = currentSlot
        val anchor = if (current != null) materialised.indexOf(current) else -1

        materialised.insertLocalAt(if (anchor < 0) 0 else anchor + 1, occurrence)
        orderPosition = materialised.indexOf(current ?: currentSlot ?: return).coerceAtLeast(0)
        publishOrderState()
    }

    private fun startLocalQueue(entry: MusicQueueEntry) {
        stopAttempt(report = true)
        audioFocus?.abandon()
        generation += 1
        queueId = "music-local-$generation-${now()}"
        sourceIdentity = null
        clearPayloadWindow()
        total = 1
        repeat = MusicRepeatMode.OFF
        order = MusicQueueOrder.localOrder(entry)
        currentSlot = MusicQueueSlotId.Local(entry.occurrenceId)
        orderPosition = 0
        positionMs = 0L
        durationMs = entry.durationMs
        canSeek = false
        userPaused = false
        resumeOnFocusGain = false
        hasStartedPlayback = false
        lyricsRequest += 1
        _lyrics.value = MusicLyricsState()

        _state.value = MusicQueueState(
            queueId = queueId,
            contextLabel = entry.albumName ?: "Queue",
            current = entry,
            currentSlot = currentSlot,
            orderPosition = 0,
            absoluteIndex = -1,
            total = 1,
            phase = MusicQueuePhase.READY,
            durationMs = durationMs,
            shuffleEnabled = false,
            shuffleAvailable = false,
            repeat = repeat,
            canPrevious = false,
            canNext = false,
        )
        publishOrderState()
    }

    private fun newLocalOccurrenceId(): QueueEntryId =
        QueueEntryId("local:${queueId ?: "pending"}:${++localSequence}")

    private suspend fun stopInternal(clearQueue: Boolean, ack: CompletableDeferred<Unit>? = null) {
        val terminalReport = stopAttempt(report = true)
        audioFocus?.abandon()
        if (clearQueue) {
            generation += 1
            queueId = null
            sourceIdentity = null
            clearPayloadWindow()
            total = null
            order = null
            currentSlot = null
            repeat = MusicRepeatMode.OFF
            orderPosition = 0
            positionMs = 0L
            durationMs = null
            canSeek = false
            userPaused = false
            resumeOnFocusGain = false
            hasStartedPlayback = false
            lyricsRequest += 1
            _lyrics.value = MusicLyricsState()
            _state.value = MusicQueueState()
        }

        stopRequested = false

        if (ack != null) {
            terminalReport?.join()
            ack.complete(Unit)
        }
    }

    private fun toggleShuffleInternal() {
        if (total == null) return
        val materialised = order ?: return
        if (_state.value.current == null) return
        val enabled = !_state.value.shuffleEnabled
        if (enabled) materialised.shuffle(currentSlot, seed = now()) else materialised.restoreAuthored()
        orderPosition = materialised.indexOf(currentSlot ?: return).coerceAtLeast(0)
        _state.value = _state.value.copy(shuffleEnabled = enabled)
        publishOrderState(shuffled = enabled)
    }

    private fun cycleRepeatInternal() {
        repeat = when (repeat) {
            MusicRepeatMode.OFF -> MusicRepeatMode.ALL
            MusicRepeatMode.ALL -> MusicRepeatMode.ONE
            MusicRepeatMode.ONE -> MusicRepeatMode.OFF
        }
        _state.value = _state.value.copy(repeat = repeat, canNext = computeCanNext())
    }

    private suspend fun retryInternal() {
        val entry = _state.value.current ?: return
        val startAt = _state.value.positionMs
        startAttempt(entry, startAtMs = startAt)
    }

    private suspend fun skipInternal() {
        if (_state.value.current == null) return
        moveTo(orderPosition + 1)
    }

    private suspend fun startAttempt(entry: MusicQueueEntry, startAtMs: Long) {
        stopAttempt(report = true)
        val thisGeneration = generation
        val requestId = "music-$thisGeneration-${++attemptSequence}-${now()}"

        val transport = when (val resolved = port.transportFor(entry, requestId)) {
            is MusicTransportResult.Ok -> resolved.transport
            is MusicTransportResult.Failed -> {
                when (resolved.kind) {
                    MusicTransportFailure.DISCONNECTED -> failQueue(resolved.message, sourceGone = true)
                    MusicTransportFailure.SOURCE_GONE -> failQueue(resolved.message, sourceGone = true)
                    else -> failOccurrence(entry, resolved.message)
                }
                return
            }
        }

        if (stopRequested) return
        onTakeAudibleOwnership()
        audioFocus?.request()

        val lineage = SessionLineage.first(
            sessionId = SessionId(requestId),
            reason = AttemptReason.USER_INITIATED,
            requestId = requestId,
            nowEpochMs = now(),
        )
        val created = try {
            engine.createSession(lineage)
        } catch (e: IllegalStateException) {
            failOccurrence(entry, "Playback could not be prepared.")
            return
        }
        created.addListener(listener)
        session = created
        sessionId = requestId
        reporter = reporterFor(transport.reporting)?.also { active -> scope.launch(dispatcher) { active.bind() } }
        hasStartedPlayback = false

        userPaused = false
        positionMs = startAtMs.coerceAtLeast(0L)
        durationMs = entry.durationMs
        canSeek = true
        _state.value = _state.value.copy(
            current = entry,
            currentSlot = currentSlot,
            phase = MusicQueuePhase.PREPARING,
            positionMs = positionMs,
            durationMs = durationMs,
            canSeek = canSeek,
            error = null,
            sourceGone = false,
            currentSessionId = requestId,
            orderPosition = orderPosition,
            absoluteIndex = absoluteIndexOf(currentSlot),

            total = order?.size ?: total,
            shuffleEnabled = _state.value.shuffleEnabled,
            shuffleAvailable = total != null,
            repeat = repeat,
            canPrevious = positionMs > PREVIOUS_RESTART_THRESHOLD_MS || orderPosition > 0,
            canNext = computeCanNext(),
        )
        lyricsRequest += 1
        _lyrics.value = MusicLyricsState(occurrenceId = entry.occurrenceId)
        created.prepare(transport.request)

        runCatching { created.play() }
    }

    private fun stopAttempt(report: Boolean): Job? {
        val activeSession = session
        val activeReporter = reporter
        if (activeSession == null && activeReporter == null) {
            sessionId = null
            return null
        }
        var terminal: Job? = null
        if (report && activeReporter != null && hasStartedPlayback) {
            val position = positionMs
            val duration = durationMs
            terminal = scope.launch(dispatcher) { runCatching { activeReporter.stop(position, duration, failed = false) } }
        }
        activeSession?.removeListener(listener)
        runCatching { activeSession?.stop(StopReason.SUPERSEDED) }
        runCatching { activeSession?.release() }
        session = null
        reporter = null
        sessionId = null
        hasStartedPlayback = false
        _state.value = _state.value.copy(currentSessionId = null)
        return terminal
    }

    private suspend fun handleEvent(event: PlaybackEvent) {
        if (event is PlaybackEvent.StateChanged) {
            when (event.state) {
                PlaybackState.PLAYING -> onPlaybackStarted()
                PlaybackState.PAUSED -> onPlaybackPaused()
                PlaybackState.BUFFERING -> _state.value = _state.value.copy(
                    phase = if (_state.value.isPlaying) MusicQueuePhase.PLAYING else _state.value.phase,
                )
                PlaybackState.ENDED -> Unit
                PlaybackState.FAILED -> Unit
                PlaybackState.CANCELLED, PlaybackState.RELEASED -> Unit
                else -> Unit
            }
            return
        }
        when (event) {
            is PlaybackEvent.PositionChanged -> {
                positionMs = event.positionMs
                val state = _state.value
                if (state.hasQueue) {
                    _state.value = state.copy(
                        positionMs = positionMs,
                        canPrevious = positionMs > PREVIOUS_RESTART_THRESHOLD_MS || orderPosition > 0,
                    )
                }

                if (hasStartedPlayback && positionMs - lastReportedPositionMs >= 1_000L) {
                    lastReportedPositionMs = positionMs
                    report { it.onPlayerState(positionMs, isPaused = !_state.value.isPlaying, canSeek = canSeek, durationMs = durationMs) }
                }
            }
            is PlaybackEvent.TimelineChanged -> {
                durationMs = event.timeline.durationMs ?: durationMs
                canSeek = event.timeline.isSeekable
                _state.value = _state.value.copy(durationMs = durationMs, canSeek = canSeek)
            }
            is PlaybackEvent.Failed -> {
                val message = event.failure.message
                val failedEntry = _state.value.current
                val activeReporter = reporter
                val position = positionMs
                val duration = durationMs
                if (activeReporter != null) report { it.stop(position, duration, failed = true) }
                releaseFailedAttempt()
                if (failedEntry != null) failOccurrence(failedEntry, message) else failQueue(message, sourceGone = false)
            }
            is PlaybackEvent.Cancelled -> {

            }
            is PlaybackEvent.Ended -> onNaturalEnd()
            else -> Unit
        }
    }

    private fun onPlaybackStarted() {
        _state.value = _state.value.copy(phase = MusicQueuePhase.PLAYING)
        if (!hasStartedPlayback) {
            hasStartedPlayback = true
            report { it.onPlayerState(positionMs, isPaused = false, canSeek = canSeek, durationMs = durationMs) }
        } else {
            report { it.onUnpause(positionMs, canSeek = canSeek, durationMs = durationMs) }
        }
        resumeOnFocusGain = false
    }

    private fun onPlaybackPaused() {
        _state.value = _state.value.copy(phase = MusicQueuePhase.PAUSED)
        if (hasStartedPlayback) {
            report { it.onPause(positionMs, canSeek = canSeek, durationMs = durationMs) }
        }
    }

    private suspend fun onNaturalEnd() {
        val activeReporter = reporter
        val position = positionMs
        val duration = durationMs
        if (activeReporter != null) report { it.stop(position, duration, failed = false) }
        releaseFinishedAttempt()
        when (repeat) {
            MusicRepeatMode.ONE -> {
                val entry = _state.value.current ?: return endQueue()
                startAttempt(entry, startAtMs = 0L)
            }
            else -> {
                val next = orderPosition + 1
                val count = order?.size

                if (count != null && next >= count && repeat == MusicRepeatMode.OFF) {
                    endQueue()
                } else {
                    moveTo(next)
                }
            }
        }
    }

    private fun releaseFinishedAttempt() {
        session?.removeListener(listener)
        runCatching { session?.release() }
        session = null
        reporter = null
        sessionId = null
        hasStartedPlayback = false
        _state.value = _state.value.copy(currentSessionId = null)
    }

    private fun releaseFailedAttempt() {
        session?.removeListener(listener)
        runCatching { session?.stop(StopReason.ERROR_RECOVERY) }
        runCatching { session?.release() }
        session = null
        reporter = null
        sessionId = null
        hasStartedPlayback = false
        _state.value = _state.value.copy(currentSessionId = null)
    }

    private suspend fun endQueue() {
        val entry = _state.value.current
        stopAttempt(report = true)
        audioFocus?.abandon()
        resumeOnFocusGain = false
        _state.value = _state.value.copy(
            phase = MusicQueuePhase.ENDED,
            positionMs = durationMs ?: positionMs,
            canNext = false,
            canPrevious = entry != null,
            error = null,
            currentSessionId = null,
        )
    }

    private fun failOccurrence(entry: MusicQueueEntry, message: String) {
        _state.value = _state.value.copy(
            phase = MusicQueuePhase.ERROR,
            current = entry,
            error = MusicQueueError(message = message, canRetry = true, canSkip = true),
            canNext = computeCanNext(),
            canPrevious = positionMs > PREVIOUS_RESTART_THRESHOLD_MS || orderPosition > 0,
            currentSessionId = null,
        )
    }

    private fun failQueue(message: String, sourceGone: Boolean) {
        stopAttempt(report = true)
        audioFocus?.abandon()
        resumeOnFocusGain = false
        _state.value = _state.value.copy(
            phase = MusicQueuePhase.ERROR,
            error = MusicQueueError(message = message, canRetry = sourceGone.not(), canSkip = false),
            sourceGone = sourceGone,
            currentSessionId = null,
        )
    }

    private suspend fun handleFocus(change: MusicAudioFocusChange) {
        when (change) {
            MusicAudioFocusChange.GAIN -> {
                if (resumeOnFocusGain && !userPaused && _state.value.isPaused) {
                    resumeOnFocusGain = false
                    session?.play()
                }
            }
            MusicAudioFocusChange.LOSS_TRANSIENT, MusicAudioFocusChange.LOSS_TRANSIENT_CAN_DUCK -> {
                if (_state.value.isPlaying) {
                    resumeOnFocusGain = true
                    session?.pause()
                }
            }
            MusicAudioFocusChange.LOSS_PERMANENT -> {
                resumeOnFocusGain = false
                if (_state.value.isPlaying) session?.pause()
                audioFocus?.abandon()
            }
        }
    }

    private suspend fun loadLyricsInternal(force: Boolean) {
        val entry = _state.value.current ?: return
        val existing = _lyrics.value
        if (!force && existing.occurrenceId == entry.occurrenceId &&
            existing.phase != MusicLyricsPhase.IDLE && existing.phase != MusicLyricsPhase.FAILED
        ) {
            return
        }
        val request = ++lyricsRequest
        _lyrics.value = MusicLyricsState(
            occurrenceId = entry.occurrenceId,
            phase = MusicLyricsPhase.LOADING,
        )
        val result = port.lyrics(entry.trackNativeId)

        if (request != lyricsRequest) return
        _lyrics.value = when (result) {
            is MusicLyricsResult.Ok -> when (val lyrics = result.lyrics) {
                is MusicLyrics.Synced -> MusicLyricsState(
                    occurrenceId = entry.occurrenceId,
                    phase = if (lyrics.lines.isEmpty()) MusicLyricsPhase.UNAVAILABLE else MusicLyricsPhase.READY,
                    lyrics = lyrics,
                )
                is MusicLyrics.Plain -> MusicLyricsState(
                    occurrenceId = entry.occurrenceId,
                    phase = if (lyrics.lines.isEmpty()) MusicLyricsPhase.UNAVAILABLE else MusicLyricsPhase.READY,
                    lyrics = lyrics,
                )
                MusicLyrics.None -> MusicLyricsState(
                    occurrenceId = entry.occurrenceId,
                    phase = MusicLyricsPhase.UNAVAILABLE,
                )
            }
            is MusicLyricsResult.Failed -> MusicLyricsState(
                occurrenceId = entry.occurrenceId,
                phase = MusicLyricsPhase.FAILED,
                message = result.message,
            )
        }
    }

    private suspend fun ensureWindow(center: Int) {
        val identity = sourceIdentity ?: return
        val count = total
        val first = (center - WINDOW_BEFORE).coerceAtLeast(0)
        val last = (center + WINDOW_AFTER).let { end -> if (count != null) end.coerceAtMost(count - 1) else end }
        if (last < first) return
        val firstPage = first / PAGE_SIZE
        val lastPage = last / PAGE_SIZE
        for (pageIndex in firstPage..lastPage) {
            if (pageIndex in loadedPages) {
                touchPage(pageIndex)
                continue
            }
            readPage(identity.context, pageIndex)
        }
    }

    private suspend fun ensurePagesFor(fromPosition: Int, toPosition: Int): Boolean {
        val identity = sourceIdentity ?: return false
        val materialised = order ?: return false
        val pages = LinkedHashSet<Int>()
        for (position in fromPosition..toPosition) {
            val slot = materialised.slotAt(position) ?: continue
            val absolute = (slot.id as? MusicQueueSlotId.Source)?.absoluteIndex ?: continue
            pages += absolute / PAGE_SIZE
        }
        var read = false
        for (pageIndex in pages) {
            if (pageIndex in loadedPages) {
                touchPage(pageIndex)
                continue
            }
            if (readPage(identity.context, pageIndex) == null) return read
            read = true
        }
        return read
    }

    private suspend fun readPage(context: MusicQueueContext, pageIndex: Int): Int? {
        val result = port.page(context, pageIndex * PAGE_SIZE, PAGE_SIZE)
        return when (result) {
            is MusicPageResult.Ok -> {
                result.page.entries.forEachIndexed { offset, entry ->
                    loaded.putIfAbsent(pageIndex * PAGE_SIZE + offset, entry)
                }
                retainPage(pageIndex)
                if (result.page.total != null && total == null) {
                    total = result.page.total
                    _state.value = _state.value.copy(total = total, shuffleAvailable = total != null)
                }
                extendOrderTo(pageIndex * PAGE_SIZE + result.page.entries.size)
                if (result.page.sourceChanged && !_state.value.sourceChanged) {
                    _state.value = _state.value.copy(sourceChanged = true)
                }
                result.page.entries.size
            }
            is MusicPageResult.Failed -> {
                if (result.kind == MusicPageFailure.DISCONNECTED || result.kind == MusicPageFailure.SOURCE_GONE) {
                    failQueue(result.message, sourceGone = true)
                }
                null
            }
        }
    }

    private fun extendOrderTo(size: Int) {
        val materialised = order ?: return
        val target = total ?: size
        if (target <= materialised.size) return
        for (index in materialised.size until target) materialised.appendSource(index)
    }

    private suspend fun entryAt(absoluteIndex: Int, expectedGeneration: Long): MusicQueueEntry? {
        loaded[absoluteIndex]?.let { entry ->
            touchPage(absoluteIndex / PAGE_SIZE)
            return entry
        }
        val identity = sourceIdentity ?: return null
        val pageIndex = absoluteIndex / PAGE_SIZE
        val result = port.page(identity.context, pageIndex * PAGE_SIZE, PAGE_SIZE)
        if (expectedGeneration != generation) return null
        return when (result) {
            is MusicPageResult.Ok -> {
                result.page.entries.forEachIndexed { offset, entry ->
                    loaded.putIfAbsent(pageIndex * PAGE_SIZE + offset, entry)
                }
                retainPage(pageIndex)
                if (result.page.total != null && total == null) {
                    total = result.page.total
                    _state.value = _state.value.copy(total = total, shuffleAvailable = total != null)
                }
                loaded[absoluteIndex]
            }
            is MusicPageResult.Failed -> {
                if (result.kind != MusicPageFailure.TRANSPORT) failQueue(result.message, sourceGone = true)
                null
            }
        }
    }

    private fun clearPayloadWindow() {
        loaded.clear()
        loadedPages.clear()
        revealRange = null
    }

    private fun retainPage(pageIndex: Int) {
        loadedPages.remove(pageIndex)
        loadedPages.add(pageIndex)
        evictPagesBeyondBound()
    }

    private fun touchPage(pageIndex: Int) {
        if (pageIndex !in loadedPages) return
        loadedPages.remove(pageIndex)
        loadedPages.add(pageIndex)
    }

    private fun evictPagesBeyondBound() {
        if (loadedPages.size <= MAX_LOADED_PAGES) return
        val protected = protectedPages()
        val iterator = loadedPages.iterator()
        while (loadedPages.size > MAX_LOADED_PAGES && iterator.hasNext()) {
            val pageIndex = iterator.next()
            if (pageIndex in protected) continue
            iterator.remove()
            val first = pageIndex * PAGE_SIZE
            for (index in first until first + PAGE_SIZE) loaded.remove(index)
        }
    }

    private fun protectedPages(): Set<Int> {
        val protected = HashSet<Int>(8)
        (currentSlot as? MusicQueueSlotId.Source)?.let { protected += it.absoluteIndex / PAGE_SIZE }
        val materialised = order ?: return protected
        val range = revealRange ?: return protected

        val last = minOf(range.last, range.first + PROTECTED_POSITION_SPAN)
        for (position in range.first..last) {
            (materialised.slotAt(position)?.id as? MusicQueueSlotId.Source)
                ?.let { protected += it.absoluteIndex / PAGE_SIZE }
        }
        return protected
    }

    private fun absoluteIndexOf(slot: MusicQueueSlotId?): Int =
        (slot as? MusicQueueSlotId.Source)?.absoluteIndex ?: -1

    private fun computeCanNext(): Boolean {
        val count = order?.size ?: return true
        return orderPosition + 1 < count || repeat == MusicRepeatMode.ALL || total == null
    }

    private fun publishOrderState(shuffled: Boolean? = null) {
        val materialised = order ?: return
        orderPosition = materialised.indexOf(currentSlot ?: return).coerceAtLeast(0)
        _state.value = _state.value.copy(
            orderPosition = orderPosition,
            currentSlot = currentSlot,
            absoluteIndex = absoluteIndexOf(currentSlot),

            total = if (materialised.size > 0) materialised.size else total,
            shuffleEnabled = shuffled ?: _state.value.shuffleEnabled,
            shuffleAvailable = total != null && materialised.size > 1,
            repeat = repeat,
            canPrevious = positionMs > PREVIOUS_RESTART_THRESHOLD_MS || orderPosition > 0,
            canNext = computeCanNext(),
        )
        publishWindow()
    }

    private fun publishWindow() {
        val materialised = order ?: return
        val rows = ArrayList<MusicQueueRow>(materialised.size)
        for (position in 0 until materialised.size) {
            val slot = materialised.slotAt(position) ?: continue
            val entry = slot.entry ?: (slot.id as? MusicQueueSlotId.Source)
                ?.let { loaded[it.absoluteIndex] }
            rows += MusicQueueRow(position = position, slot = slot.id, entry = entry)
        }
        _state.value = _state.value.copy(
            window = MusicQueueWindow(rows = rows, total = materialised.size, currentPosition = orderPosition),
        )
    }

    private fun report(block: suspend (ActivePlaybackReporter) -> Unit) {
        val active = reporter ?: return
        scope.launch(dispatcher) { runCatching { block(active) } }
    }

    internal fun retainedPayloadEntryCount(): Int = loaded.size

    internal fun retainedPayloadPageCount(): Int = loadedPages.size
}

internal class ShuffleOrder private constructor(
    private val count: Int,
    private val start: Int,
    private val step: Int,
    private val pinnedSource: Int,
) {
    companion object {
        fun disabled(): ShuffleOrder = ShuffleOrder(count = 0, start = 0, step = 1, pinnedSource = -1)

        fun create(count: Int, start: Int, seed: Long): ShuffleOrder {
            if (count <= 1) return disabled()
            val pinned = start.coerceIn(0, count - 1)
            var candidate = ((seed xor (seed ushr 17) xor 0x5DEECE66DL) and 0x7FFFFFFF).toInt()
            if (candidate <= 0) candidate = 1
            var step = candidate % count
            if (step == 0) step = 1
            var guard = 0
            while (gcd(step, count) != 1 && guard < count + 2) {
                step = (step + 1) % count
                if (step == 0) step = 1
                guard += 1
            }

            val inverse = modularInverse(step, count)
            val q = if (inverse == null) 0 else ((pinned.toLong() * inverse) % count).toInt()
            return ShuffleOrder(count = count, start = pinned, step = step, pinnedSource = q)
        }

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) kotlin.math.abs(a) else gcd(b, a % b)

        private fun modularInverse(a: Int, m: Int): Int? {
            var t = 0
            var newT = 1
            var r = m
            var newR = ((a % m) + m) % m
            while (newR != 0) {
                val quotient = r / newR
                val tmpT = t - quotient * newT
                t = newT
                newT = tmpT
                val tmpR = r - quotient * newR
                r = newR
                newR = tmpR
            }
            if (r != 1) return null
            return ((t % m) + m) % m
        }
    }

    val enabled: Boolean get() = count > 1 && pinnedSource >= 0

    val size: Int get() = count

    fun absoluteAt(position: Int): Int {
        if (!enabled) return position
        val normalized = ((position % count) + count) % count
        if (normalized == 0) return start
        val affine = ((normalized.toLong() * step) % count).toInt()
        return if (affine == start) 0 else affine
    }

    fun positionOf(absoluteIndex: Int): Int {
        if (!enabled) return absoluteIndex.coerceAtLeast(0)
        if (absoluteIndex == start) return 0
        val inverse = modularInverse(step, count) ?: return -1
        val position = ((absoluteIndex.toLong() * inverse) % count).toInt()
        return if (position == 0) pinnedSource else position
    }
}
