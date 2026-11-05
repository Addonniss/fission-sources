package dev.infusion.source.jellyfin.session

import dev.infusion.core.domain.music.MusicReportingIdentity
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.playback.api.ActivePlaybackReporter
import dev.infusion.source.jellyfin.auth.JellyfinSession
import dev.infusion.source.jellyfin.playback.JellyfinTransportCodec
import kotlinx.coroutines.CoroutineScope

class JellyfinActiveReporterAdapter private constructor(
    private val delegate: JellyfinActivePlaybackReporter,
) : ActivePlaybackReporter {
    override suspend fun bind() = Unit
    override suspend fun onPlayerState(positionMs: Long, isPaused: Boolean, canSeek: Boolean, durationMs: Long?) =
        delegate.onActive(JellyfinPlayerSnapshot(positionMs, isPaused, canSeek, durationMs))
    override suspend fun onPause(positionMs: Long, canSeek: Boolean, durationMs: Long?) =
        delegate.onPause(JellyfinPlayerSnapshot(positionMs, true, canSeek, durationMs))
    override suspend fun onUnpause(positionMs: Long, canSeek: Boolean, durationMs: Long?) =
        delegate.onUnpause(JellyfinPlayerSnapshot(positionMs, false, canSeek, durationMs))
    override suspend fun onSeek(positionMs: Long, isPaused: Boolean, canSeek: Boolean, durationMs: Long?) =
        delegate.onSeek(JellyfinPlayerSnapshot(positionMs, isPaused, canSeek, durationMs))
    override suspend fun stop(positionMs: Long, durationMs: Long?, failed: Boolean) =
        delegate.stop(JellyfinPlayerSnapshot(positionMs, false, true, durationMs), failed)

    companion object {
        fun create(candidate: PlayableCandidate, reporter: JellyfinSessionReporter, session: JellyfinSession,
            scope: CoroutineScope): ActivePlaybackReporter? {
            val payload = JellyfinTransportCodec.decode(candidate.transport) ?: return null
            val playSessionId = payload.playSessionId ?: return null
            val identity = JellyfinActiveSessionIdentity(
                payload.itemId, payload.mediaSourceId, playSessionId,
                payload.playMethod ?: when (payload.route) {
                    "direct" -> "DirectPlay"
                    "remux" -> "DirectStream"
                    else -> "Transcode"
                }, payload.liveStreamId, payload.audioStreamIndex, payload.subtitleStreamIndex,
            )
            return JellyfinActiveReporterAdapter(JellyfinActivePlaybackReporter(reporter, session, identity, scope))
        }

        fun create(
            identity: MusicReportingIdentity,
            reporter: JellyfinSessionReporter,
            session: JellyfinSession,
            scope: CoroutineScope,
        ): ActivePlaybackReporter? {
            if (identity.playSessionId.isBlank()) return null
            return JellyfinActiveReporterAdapter(
                JellyfinActivePlaybackReporter(
                    reporter,
                    session,
                    JellyfinActiveSessionIdentity(
                        itemId = identity.itemId,
                        mediaSourceId = identity.mediaSourceId,
                        playSessionId = identity.playSessionId,
                        playMethod = identity.playMethod,
                    ),
                    scope,
                ),
            )
        }
    }
}
