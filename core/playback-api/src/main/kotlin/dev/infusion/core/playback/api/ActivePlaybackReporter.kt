package dev.infusion.core.playback.api

interface ActivePlaybackReporter {
    suspend fun bind()
    suspend fun onPlayerState(positionMs: Long, isPaused: Boolean, canSeek: Boolean, durationMs: Long?)
    suspend fun onPause(positionMs: Long, canSeek: Boolean, durationMs: Long?)
    suspend fun onUnpause(positionMs: Long, canSeek: Boolean, durationMs: Long?)
    suspend fun onSeek(positionMs: Long, isPaused: Boolean, canSeek: Boolean, durationMs: Long?)
    suspend fun stop(positionMs: Long, durationMs: Long?, failed: Boolean = false)
}
