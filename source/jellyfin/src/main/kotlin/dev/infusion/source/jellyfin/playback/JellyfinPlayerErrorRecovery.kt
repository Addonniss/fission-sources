package dev.infusion.source.jellyfin.playback

import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.network.NetworkLimits
import dev.infusion.source.jellyfin.auth.JellyfinAuthenticator
import dev.infusion.source.jellyfin.auth.JellyfinSession
import dev.infusion.source.jellyfin.client.ApiResult
import dev.infusion.source.jellyfin.client.JellyfinClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JellyfinPlayerErrorRecovery(
    private val client: JellyfinClient,
    private val authenticator: JellyfinAuthenticator,
    private val sessionProvider: () -> JellyfinSession?,
    private val serverVersion: String,
    private val limits: NetworkLimits = NetworkLimits.DEFAULT,
) {

    suspend fun recover(candidate: PlayableCandidate, positionMs: Long): PlayableCandidate? =
        withContext(Dispatchers.IO) {
            val payload = JellyfinTransportCodec.decode(candidate.transport) ?: return@withContext null

            if (payload.playMethod !in ELIGIBLE_PLAY_METHODS) return@withContext null
            val session = sessionProvider() ?: return@withContext null
            val token = authenticator.token(session) ?: return@withContext null

            val serverStartOffsetMs = positionMs.takeIf { it > 0L }
            val body = JellyfinPlaybackInfoBody.build(
                userId = session.userId,
                mediaSourceId = payload.mediaSourceId,
                audioStreamIndex = payload.audioStreamIndex,
                subtitleStreamIndex = payload.subtitleStreamIndex,
                enableDirectPlay = false,
                enableDirectStream = false,
                startPositionMs = serverStartOffsetMs,
            )
            when (val result = client.playbackInfo(payload.itemId, session.userId, token, body, limits)) {
                is ApiResult.Err -> null
                is ApiResult.Ok -> {
                    val factory = JellyfinCandidateFactory(
                        sourceId = candidate.sourceId,
                        titleId = candidate.titleId,
                        nativeItemId = candidate.nativeItemId,
                        serverVersion = serverVersion,
                    )
                    factory.candidates(

                        mediaSources = result.value.mediaSources.take(1),
                        playSessionId = result.value.playSessionId,
                        editionId = candidate.editionId,
                        resumePositionMs = positionMs.takeIf { it > 0L },

                        serverStartOffsetMs = serverStartOffsetMs,
                    ).firstOrNull()
                }
            }
        }

    private companion object {

        val ELIGIBLE_PLAY_METHODS = setOf("DirectPlay", "DirectStream")
    }
}
