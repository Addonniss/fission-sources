package dev.infusion.source.jellyfin.playback

import dev.infusion.core.domain.candidate.CandidateResolverPort
import dev.infusion.core.domain.candidate.ResolutionPhase
import dev.infusion.core.domain.source.SourceItemStore
import dev.infusion.core.model.EditionId
import dev.infusion.core.model.NativeItemId
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.playback.FailureRecoverability
import dev.infusion.core.model.playback.PlaybackFailure
import dev.infusion.core.model.playback.PlaybackFailureKind
import dev.infusion.core.model.playback.PlaybackStage
import dev.infusion.core.network.NetworkLimits
import dev.infusion.source.jellyfin.auth.JellyfinAuthenticator
import dev.infusion.source.jellyfin.auth.JellyfinSession
import dev.infusion.source.jellyfin.catalog.JellyfinMapper
import dev.infusion.source.jellyfin.client.ApiResult
import dev.infusion.source.jellyfin.client.FailureKind
import dev.infusion.source.jellyfin.client.JellyfinClient
import dev.infusion.source.jellyfin.client.JellyfinFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

class JellyfinCandidateResolver(
    private val sourceId: dev.infusion.core.model.SourceId,
    private val store: SourceItemStore,
    private val client: JellyfinClient,
    private val authenticator: JellyfinAuthenticator,
    private val mapper: JellyfinMapper,
    private val sessionProvider: () -> JellyfinSession?,
    private val serverVersion: String,
    private val limits: NetworkLimits = NetworkLimits.DEFAULT,
) : CandidateResolverPort {

    override fun resolve(titleId: TitleId, editionId: EditionId?): Flow<ResolutionPhase> = flow {
        val session = sessionProvider()
        if (session == null) {
            emit(failed(PlaybackFailureKind.AUTH, FailureRecoverability.PERMANENT, "no jellyfin session"))
            return@flow
        }
        val token = authenticator.token(session)
        if (token == null) {
            emit(failed(PlaybackFailureKind.AUTH, FailureRecoverability.PERMANENT, "jellyfin token missing"))
            return@flow
        }

        val serverItems = store.serverItemsByTitle(titleId)
        if (serverItems.isEmpty()) {
            emit(ResolutionPhase.Candidates(emptyList(), complete = true))
            return@flow
        }

        val candidates = mutableListOf<dev.infusion.core.model.PlayableCandidate>()
        var anyFailure: JellyfinFailure? = null
        for (item in serverItems) {
            val resumePositionMs = when (val detail = client.item(session.userId, item.nativeId, token, limits)) {
                is ApiResult.Ok -> detail.value.userData?.playbackPositionTicks
                    ?.div(10_000L)
                    ?.takeIf { it > 0L }
                is ApiResult.Err -> null
            }
            val body = JellyfinPlaybackInfoBody.build(
                userId = session.userId,

            )
            when (val result = client.playbackInfo(item.nativeId, session.userId, token, body, limits)) {
                is ApiResult.Err -> {
                    anyFailure = result.failure

                }
                is ApiResult.Ok -> {
                    val factory = JellyfinCandidateFactory(
                        sourceId = sourceId,
                        titleId = titleId,
                        nativeItemId = NativeItemId(sourceId, item.nativeId),
                        serverVersion = serverVersion,
                    )
                    candidates += factory.candidates(

                        mediaSources = result.value.mediaSources.take(1),
                        playSessionId = result.value.playSessionId,
                        editionId = editionId,
                        resumePositionMs = resumePositionMs,
                    )
                }
            }
        }

        if (candidates.isEmpty() && anyFailure != null) {
            emit(failed(anyFailure))
        } else {
            emit(ResolutionPhase.Candidates(candidates, complete = true))
        }
    }

    private fun failed(failure: JellyfinFailure): ResolutionPhase.Failed =
        failed(failureKindOf(failure.kind), recoverabilityOf(failure), failure.message)

    private fun failed(
        kind: PlaybackFailureKind,
        recoverability: FailureRecoverability,
        message: String,
    ): ResolutionPhase.Failed = ResolutionPhase.Failed(
        PlaybackFailure(
            kind = kind,
            recoverability = recoverability,
            stage = PlaybackStage.RESOLVE,
            message = message,
            remedyHint = when (kind) {
                PlaybackFailureKind.AUTH -> "Re-authenticate the server in Settings."
                else -> "Check the server connection and retry."
            },
        ),
    )

    private fun failureKindOf(kind: FailureKind): PlaybackFailureKind = when (kind) {
        FailureKind.UNAUTHORIZED, FailureKind.FORBIDDEN -> PlaybackFailureKind.AUTH
        FailureKind.NOT_FOUND -> PlaybackFailureKind.NOT_FOUND
        FailureKind.TIMEOUT -> PlaybackFailureKind.TIMEOUT
        FailureKind.NETWORK, FailureKind.RATE_LIMITED, FailureKind.SERVER_ERROR -> PlaybackFailureKind.NETWORK

        FailureKind.NEGOTIATION -> PlaybackFailureKind.SOURCE_UNAVAILABLE
        else -> PlaybackFailureKind.SOURCE_UNAVAILABLE
    }

    private fun recoverabilityOf(failure: JellyfinFailure): FailureRecoverability = when (failure.kind) {
        FailureKind.UNAUTHORIZED, FailureKind.FORBIDDEN, FailureKind.NOT_FOUND,
        FailureKind.NEGOTIATION,
        -> FailureRecoverability.PERMANENT
        FailureKind.TIMEOUT, FailureKind.NETWORK, FailureKind.RATE_LIMITED, FailureKind.SERVER_ERROR ->
            FailureRecoverability.TRANSIENT
        else -> FailureRecoverability.UNKNOWN
    }
}
