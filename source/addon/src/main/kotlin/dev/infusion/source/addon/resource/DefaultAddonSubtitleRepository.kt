package dev.infusion.source.addon.resource

import dev.infusion.source.addon.repository.InstalledAddonRuntime
import dev.infusion.source.aggregation.resource.AddonSubtitleRepository
import dev.infusion.source.aggregation.resource.ProviderResourceState
import dev.infusion.source.aggregation.resource.SubtitleBatch
import dev.infusion.source.aggregation.resource.SubtitleQueryState
import dev.infusion.source.stremio.StremioFailure
import dev.infusion.source.stremio.StremioResult
import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.wire.Resource
import dev.infusion.source.stremio.wire.StremioSubtitlesResponse
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class DefaultAddonSubtitleRepository(
    private val runtimes: AddonResourceRuntimeAccessor,
    private val providerTimeoutMs: Long = DEFAULT_PROVIDER_TIMEOUT_MS,
) : AddonSubtitleRepository {

    private val _states = MutableStateFlow<SubtitleQueryState?>(null)
    override val states: StateFlow<SubtitleQueryState?> = _states.asStateFlow()

    override fun subtitles(
        type: StremioContentType,
        id: String,
        videoId: String?,
        hash: String?,
        size: Long?,
        filename: String?,
    ): Flow<SubtitleBatch> = channelFlow {
        val installed = runtimes.enabledInstalled()

        val actualId = videoId?.takeIf { it.isNotBlank() } ?: id
        val eligible = installed.mapNotNull { addon ->
            if (!AddonResourceMatcher.eligible(addon.snapshot, Resource.SUBTITLES, type, id)) return@mapNotNull null
            runtimes.runtimeFor(addon.addonId)
        }
        if (eligible.isEmpty()) {
            _states.value = SubtitleQueryState(type = type, id = id, providers = emptyList())
            return@channelFlow
        }

        val slots = eligible.map { runtime ->
            SubtitleBatch(
                addonId = runtime.addonId,
                addonDisplayName = runtime.installed.displayName,
                state = ProviderResourceState.Pending,
            )
        }.toMutableList()
        _states.value = SubtitleQueryState(type = type, id = id, providers = slots.toList())
        val completions = Channel<Pair<Int, ProviderResourceState<StremioSubtitlesResponse>>>(eligible.size)
        eligible.forEachIndexed { index, runtime ->
            launch {
                completions.send(
                    index to resolveProvider(runtime, type, actualId, hash, size, filename),
                )
            }
        }
        var nextToEmit = 0
        repeat(eligible.size) {
            val (index, state) = completions.receive()

            slots[index] = slots[index].copy(state = state)
            _states.value = SubtitleQueryState(type = type, id = id, providers = slots.toList())

            while (nextToEmit < slots.size && slots[nextToEmit].state !is ProviderResourceState.Pending) {
                send(slots[nextToEmit])
                nextToEmit++
            }
        }
    }

    private suspend fun resolveProvider(
        runtime: InstalledAddonRuntime,
        type: StremioContentType,
        id: String,
        hash: String?,
        size: Long?,
        filename: String?,
    ): ProviderResourceState<StremioSubtitlesResponse> {
        if (!runtime.providerState.isEnabled) return ProviderResourceState.Skipped

        val extras = buildList {
            hash?.let { add("videoHash" to it) }
            size?.let { add("videoSize" to it.toString()) }
            filename?.let { add("filename" to it) }
        }
        val result = withTimeoutOrNull(providerTimeoutMs) {
            runtime.useClient { client ->
                client.subtitles(
                    type = type,
                    id = id,
                    extras = extras,
                    cancellation = runtime.providerState.currentScope(),
                )
            }
        } ?: return ProviderResourceState.Failed(timeout())
        return when (result) {
            is StremioResult.Err -> {
                if (result.failure.reason == dev.infusion.core.network.DiagnosticReason.CANCELLED) {
                    ProviderResourceState.Skipped
                } else {
                    ProviderResourceState.Failed(result.failure)
                }
            }
            is StremioResult.Ok -> ProviderResourceState.Loaded(result.value, result.status)
        }
    }

    private fun timeout(): StremioFailure =
        StremioFailure(
            reason = dev.infusion.core.network.DiagnosticReason.TIMEOUT,
            status = null,
            message = "subtitle provider timed out",
            retryable = true,
            diagnostic = dev.infusion.core.network.RedactedDiagnostic(
                correlationId = dev.infusion.core.network.CorrelationIds.newId(),
                reason = dev.infusion.core.network.DiagnosticReason.TIMEOUT,
                origin = null,
            ),
        )

    companion object {
        const val DEFAULT_PROVIDER_TIMEOUT_MS = 10_000L
    }
}
