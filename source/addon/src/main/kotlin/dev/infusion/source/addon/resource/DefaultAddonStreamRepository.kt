package dev.infusion.source.addon.resource

import dev.infusion.source.addon.repository.InstalledAddonRuntime
import dev.infusion.source.aggregation.resource.AddonStreamRepository
import dev.infusion.source.aggregation.resource.ProviderResourceState
import dev.infusion.source.aggregation.resource.StreamBatch
import dev.infusion.source.stremio.StremioFailure
import dev.infusion.source.stremio.StremioResult
import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.wire.Resource
import dev.infusion.source.stremio.wire.StremioStreamResponse
import java.util.LinkedHashMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class DefaultAddonStreamRepository(
    private val runtimes: AddonResourceRuntimeAccessor,
    private val providerTimeoutMs: Long = DEFAULT_PROVIDER_TIMEOUT_MS,
    private val inlineMetaFallback: Boolean = true,
) : AddonStreamRepository {

    private val _states = MutableStateFlow<Map<String, ProviderResourceState<StremioStreamResponse>>>(emptyMap())
    override val states: StateFlow<Map<String, ProviderResourceState<StremioStreamResponse>>> = _states.asStateFlow()

    override fun streams(type: StremioContentType, id: String): Flow<StreamBatch> = channelFlow {
        val installed = runtimes.enabledInstalled()
        val eligible = installed.mapNotNull { addon ->
            if (!AddonResourceMatcher.eligible(addon.snapshot, Resource.STREAM, type, id)) return@mapNotNull null
            runtimes.runtimeFor(addon.addonId)
        }

        if (eligible.isEmpty()) {
            _states.value = emptyMap()
            return@channelFlow
        }
        _states.value = LinkedHashMap<String, ProviderResourceState<StremioStreamResponse>>().apply {
            for (r in eligible) this[r.addonId] = ProviderResourceState.Pending
        }
        val completions = Channel<Pair<Int, Pair<InstalledAddonRuntime, ProviderResourceState<StremioStreamResponse>>>>(eligible.size)
        eligible.forEachIndexed { index, runtime ->
            launch {
                completions.send(index to (runtime to resolveProvider(runtime, type, id)))
            }
        }

        val slots = arrayOfNulls<Pair<InstalledAddonRuntime, ProviderResourceState<StremioStreamResponse>>>(eligible.size)
        val emitted = LinkedHashMap<String, ProviderResourceState<StremioStreamResponse>>()
        var nextToEmit = 0
        repeat(eligible.size) {
            val (index, entry) = completions.receive()
            slots[index] = entry
            val (runtime, state) = entry
            emitted[runtime.addonId] = state
            _states.value = LinkedHashMap(emitted)

            while (nextToEmit < slots.size && slots[nextToEmit] != null) {
                val (slotRuntime, slotState) = slots[nextToEmit]!!
                nextToEmit++
                send(
                    StreamBatch(
                        addonId = slotRuntime.addonId,
                        addonDisplayName = slotRuntime.installed.displayName,
                        state = slotState,
                    ),
                )
            }
        }
    }

    override suspend fun streamsFrom(
        addonId: String,
        type: StremioContentType,
        id: String,
    ): StremioResult<StremioStreamResponse> {
        val runtime = runtimes.runtimeFor(addonId)
            ?: return StremioResult.Err(notInstalled())
        if (!AddonResourceMatcher.eligible(runtime.installed.snapshot, Resource.STREAM, type, id)) {

            return StremioResult.Ok(emptyStreamResponse(), status = NO_HTTP_STATUS)
        }
        return resolveProvider(runtime, type, id).let { state ->
            when (state) {
                is ProviderResourceState.Loaded -> StremioResult.Ok(state.value, state.status)
                is ProviderResourceState.Failed -> StremioResult.Err(state.failure)
                ProviderResourceState.Pending, ProviderResourceState.Skipped -> StremioResult.Err(cancelled())
            }
        }
    }

    private suspend fun resolveProvider(
        runtime: InstalledAddonRuntime,
        type: StremioContentType,
        id: String,
    ): ProviderResourceState<StremioStreamResponse> {
        if (!runtime.providerState.isEnabled) return ProviderResourceState.Skipped
        val result = withTimeoutOrNull(providerTimeoutMs) {
            runtime.useClient {
                it.streams(type, id, cancellation = runtime.providerState.currentScope())
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
            is StremioResult.Ok -> {
                if (inlineMetaFallback && result.value.streams.isEmpty()) {

                    fallbackFromMeta(runtime, type, id)
                        ?: ProviderResourceState.Loaded(result.value, result.status)
                } else {
                    ProviderResourceState.Loaded(result.value, result.status)
                }
            }
        }
    }

    private suspend fun fallbackFromMeta(
        runtime: InstalledAddonRuntime,
        type: StremioContentType,
        id: String,
    ): ProviderResourceState<StremioStreamResponse>? {
        if (!AddonResourceMatcher.eligible(runtime.installed.snapshot, Resource.META, type, id)) return null
        val meta = withTimeoutOrNull(providerTimeoutMs) {
            runtime.useClient {
                it.meta(type, id, cancellation = runtime.providerState.currentScope())
            }
        } ?: return null
        if (meta is StremioResult.Err) return null
        val response = (meta as StremioResult.Ok).value

        val policy = dev.infusion.source.stremio.policy.StremioLocatorPolicy(
            trust = runtime.installed.trust,
            approvedLanHost = runtime.installed.lanTrustHost,
        )
        val parsed = parseEmbeddedStreams(response.meta.videos, policy) ?: return null

        return ProviderResourceState.Loaded(parsed, meta.status)
    }

    private fun parseEmbeddedStreams(
        videos: List<dev.infusion.source.stremio.wire.StremioVideo>,
        policy: dev.infusion.source.stremio.policy.StremioLocatorPolicy,
    ): dev.infusion.source.stremio.wire.StremioStreamResponse? {
        val items = ArrayList<dev.infusion.source.stremio.wire.JsonValue>()
        for (video in videos) {
            val streams = video.raw.fields["streams"] as? dev.infusion.source.stremio.wire.JsonValue.Arr ?: continue
            items.addAll(streams.elements)
        }
        if (items.isEmpty()) return null
        val envelope = dev.infusion.source.stremio.wire.JsonValue.Obj(
            LinkedHashMap<String, dev.infusion.source.stremio.wire.JsonValue>().apply {
                put("streams", dev.infusion.source.stremio.wire.JsonValue.Arr(items))
            },
        )
        return runCatching {
            dev.infusion.source.stremio.wire.StreamsParser.parse(envelope, policy)
        }.getOrNull()
    }

    private fun notInstalled(): StremioFailure =
        StremioFailure(
            reason = dev.infusion.core.network.DiagnosticReason.NOT_FOUND,
            status = null,
            message = "stream add-on not installed",
            retryable = false,
            diagnostic = dev.infusion.core.network.RedactedDiagnostic(
                correlationId = dev.infusion.core.network.CorrelationIds.newId(),
                reason = dev.infusion.core.network.DiagnosticReason.NOT_FOUND,
                origin = null,
            ),
        )

    private fun emptyStreamResponse(): StremioStreamResponse =
        StremioStreamResponse(
            raw = dev.infusion.source.stremio.wire.JsonValue.Obj(LinkedHashMap()),
            streams = emptyList(),
            rejected = emptyList(),
        )

    private fun cancelled(): StremioFailure =
        StremioFailure(
            reason = dev.infusion.core.network.DiagnosticReason.CANCELLED,
            status = null,
            message = "request cancelled",
            retryable = false,
            diagnostic = dev.infusion.core.network.RedactedDiagnostic(
                correlationId = dev.infusion.core.network.CorrelationIds.newId(),
                reason = dev.infusion.core.network.DiagnosticReason.CANCELLED,
                origin = null,
            ),
        )

    private fun timeout(): StremioFailure =
        StremioFailure(
            reason = dev.infusion.core.network.DiagnosticReason.TIMEOUT,
            status = null,
            message = "stream provider timed out",
            retryable = true,
            diagnostic = dev.infusion.core.network.RedactedDiagnostic(
                correlationId = dev.infusion.core.network.CorrelationIds.newId(),
                reason = dev.infusion.core.network.DiagnosticReason.TIMEOUT,
                origin = null,
            ),
        )

    companion object {
        const val DEFAULT_PROVIDER_TIMEOUT_MS = 10_000L

        const val NO_HTTP_STATUS = 0
    }
}
