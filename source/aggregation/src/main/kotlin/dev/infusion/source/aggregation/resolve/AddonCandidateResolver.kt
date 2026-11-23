package dev.infusion.source.aggregation.resolve

import dev.infusion.core.domain.candidate.CandidateResolverPort
import dev.infusion.core.domain.candidate.ResolutionPhase
import dev.infusion.core.model.EditionId
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.ProviderNotice
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.playback.FailureRecoverability
import dev.infusion.core.model.playback.PlaybackFailure
import dev.infusion.core.model.playback.PlaybackFailureKind
import dev.infusion.core.model.playback.PlaybackStage
import dev.infusion.source.aggregation.AddonAvailabilityStore
import dev.infusion.source.aggregation.AddonRegistryPort
import dev.infusion.source.aggregation.AddonRuntimeDescriptor
import dev.infusion.source.aggregation.AUTHORITY_IMDB
import dev.infusion.source.aggregation.CanonicalTitleLookup
import dev.infusion.source.aggregation.resource.AddonStreamRepository
import dev.infusion.source.aggregation.stremioWireId
import dev.infusion.source.stremio.StremioResult
import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.wire.StremioStreamCandidate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

sealed interface ProviderResolutionState {
    data object Pending : ProviderResolutionState
    data class Resolved(val candidateCount: Int) : ProviderResolutionState
    data class Failed(
        val reason: String,
        val guard: dev.infusion.core.network.DiagnosticLimitGuard? = null,
        val diagnosticDetails: Map<dev.infusion.core.network.DiagnosticDetailKey, Long> = emptyMap(),
    ) : ProviderResolutionState {
        init {
            require(reason.isNotBlank()) { "reason must not be blank" }
        }
    }
}

data class ProviderResolutionObservation(
    val titleId: String?,
    val imdbId: String?,
    val providers: List<ProviderObservationEntry>,

    val resourceType: String? = null,

    val skipped: List<ProviderSkipEntry> = emptyList(),

    val providersOmitted: Int = 0,

    val skippedOmitted: Int = 0,
) {
    init {
        require(providers.size <= MAX_PROVIDERS) { "observation provider count exceeds cap" }
        require(imdbId == null || imdbId.startsWith("tt")) { "imdb alias must be a tt... id" }
        require(skipped.size <= MAX_PROVIDERS) { "observation skip count exceeds cap" }
        require(providers.all { it.displayName == ProviderObservationEntry.safeLabel(it.displayName) }) {
            "provider display names must be sanitized for the observation"
        }
        require(skipped.all { it.addonId == ProviderObservationEntry.safeLabel(it.addonId) }) {
            "skipped add-on ids must be sanitized for the observation"
        }
        require(providersOmitted >= 0) { "omitted provider count must be non-negative" }
        require(skippedOmitted >= 0) { "omitted skip count must be non-negative" }
    }

    fun toLogLines(): List<String> = buildList {
        add(
            "stream-limit-observation title=${titleId ?: "-"} imdb=${imdbId ?: "-"} " +
                "type=${resourceType ?: "-"} providers=${providers.size} skipped=${skipped.size}" +

                (if (providersOmitted > 0) " providersOmitted=$providersOmitted" else "") +
                (if (skippedOmitted > 0) " skippedOmitted=$skippedOmitted" else ""),
        )
        for (p in providers) {
            val state = when (p.state) {
                is ProviderResolutionState.Pending -> "pending"
                is ProviderResolutionState.Resolved -> "resolved:${p.state.candidateCount}"
                is ProviderResolutionState.Failed -> "failed:${p.state.reason}"
            }
            val line = StringBuilder()
                .append("  addon=").append(p.addonId)
                .append(" manifest=").append(p.manifestId ?: "-")
                .append(" name=").append(p.displayName)
                .append(" wire=").append(p.wireId ?: "-")
                .append(" state=").append(state)
            val guard = (p.state as? ProviderResolutionState.Failed)?.guard
            guard?.let { line.append(" guard=").append(it.name) }
            p.bytesRead?.let { line.append(" bytes=").append(it) }
            p.streamArrayCount?.let { line.append(" streams=").append(it) }
            p.acceptedCount?.let { line.append(" accepted=").append(it) }
            p.rejectedCount?.let { line.append(" rejected=").append(it) }
            add(line.toString())
        }
        for (s in skipped) {
            add("  skip addon=${s.addonId} reason=${s.reason}")
        }
    }

    companion object {
        const val MAX_PROVIDERS = 64

        fun bounded(
            titleId: String?,
            imdbId: String?,
            providers: List<ProviderObservationEntry>,
            resourceType: String? = null,
            skipped: List<ProviderSkipEntry> = emptyList(),
        ): ProviderResolutionObservation {
            val boundedProviders = providers.take(MAX_PROVIDERS)
            val boundedSkipped = skipped.take(MAX_PROVIDERS)
            return ProviderResolutionObservation(
                titleId = titleId,
                imdbId = imdbId,
                providers = boundedProviders,
                resourceType = resourceType,
                skipped = boundedSkipped,
                providersOmitted = providers.size - boundedProviders.size,
                skippedOmitted = skipped.size - boundedSkipped.size,
            )
        }
    }
}

data class ProviderSkipEntry(
    val addonId: String,
    val reason: String,
) {
    init {
        require(reason.isNotBlank()) { "skip reason must not be blank" }
    }
}

data class ProviderObservationEntry(
    val addonId: String,
    val manifestId: String?,
    val displayName: String,
    val state: ProviderResolutionState,

    val wireId: String? = null,
    val bytesRead: Long? = null,
    val streamArrayCount: Int? = null,
    val acceptedCount: Int? = null,
    val rejectedCount: Int? = null,
) {
    init {
        require(bytesRead == null || bytesRead >= 0) { "bytesRead must be non-negative" }
        require(streamArrayCount == null || streamArrayCount >= 0) { "streamArrayCount must be non-negative" }
        require(acceptedCount == null || acceptedCount >= 0) { "acceptedCount must be non-negative" }
        require(rejectedCount == null || rejectedCount >= 0) { "rejectedCount must be non-negative" }
    }

    companion object {

        private val DENIED_WORDS = setOf(
            "http", "https", "bearer", "token", "secret", "password",
            "passwd", "api_key", "apikey", "authorization", "cookie",
            "magnet", "btih", "xt=",
        )

        private val IPV4 = Regex(
            "^(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}$",
        )

        private val HEX_TOKEN = Regex("^[0-9a-fA-F]{8,}$")

        fun safeLabel(raw: String?): String? {
            if (raw == null) return null
            val cleaned = raw.map { c ->
                when {
                    c.isLetterOrDigit() || c == ' ' || c == '.' || c == '_' || c == '-' || c == '\'' -> c
                    else -> ' '
                }
            }.joinToString("")
                .split(' ')
                .filter { word ->
                    word.isNotBlank() &&
                        word.lowercase() !in DENIED_WORDS &&
                        !IPV4.matches(word) &&
                        !HEX_TOKEN.matches(word)
                }
                .joinToString(" ")
                .take(MAX_LABEL_CHARS)
            return cleaned.ifEmpty { null }
        }

        const val MAX_LABEL_CHARS = 64

        fun safeWireId(raw: String?): String? {
            if (raw == null) return null
            val cleaned = raw.map { c ->
                when {
                    c.isLetterOrDigit() || c == ':' || c == '-' || c == '_' || c == '.' -> c
                    else -> ' '
                }
            }.joinToString("")
                .split(' ')
                .filter { word ->
                    word.isNotBlank() &&
                        word.lowercase() !in DENIED_WORDS &&
                        !IPV4.matches(word) &&
                        !HEX_TOKEN.matches(word)
                }
                .joinToString(":")
                .take(MAX_WIRE_ID_CHARS)
            return cleaned.ifEmpty { null }
        }

        const val MAX_WIRE_ID_CHARS = 96
    }
}

data class EpisodeAddress(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val seriesTitleId: TitleId? = null,
)

class AddonCandidateResolver(
    private val registry: AddonRegistryPort,
    private val availabilityStore: AddonAvailabilityStore,
    private val streamRepository: AddonStreamRepository,
    private val lookup: CanonicalTitleLookup,
    private val viewer: dev.infusion.core.model.profile.Profile,
    private val primary: dev.infusion.core.model.profile.Profile,
    private val providerTimeoutMs: Long = 10_000,

    private val episodeAddress: EpisodeAddress? = null,

    private val aliasCompletion: dev.infusion.core.domain.metadata.CanonicalAliasCompletion =
        dev.infusion.core.domain.metadata.CanonicalAliasCompletion.NONE,
) : CandidateResolverPort {

    private val _providerStates = MutableStateFlow<Map<String, ProviderResolutionState>>(emptyMap())

    val providerStates: StateFlow<Map<String, ProviderResolutionState>> = _providerStates.asStateFlow()

    private val _lastObservation = MutableStateFlow<ProviderResolutionObservation?>(null)
    val lastObservation: StateFlow<ProviderResolutionObservation?> = _lastObservation.asStateFlow()

    override fun resolve(titleId: TitleId, editionId: EditionId?): Flow<ResolutionPhase> = channelFlow {
        _providerStates.value = emptyMap()
        send(ResolutionPhase.Loading)

        val runtimes = registry.runtimesFor(viewer, primary)

        val completedTitles = LinkedHashMap<TitleId, dev.infusion.core.model.Title>()
        fun titleAnswering(id: TitleId): dev.infusion.core.model.Title? =
            completedTitles[id] ?: lookup.byId(id)

        var title = titleAnswering(titleId)
        var type = requestType(title, episodeAddress)
        val titleAvailability = availabilityStore.byTitle(titleId)
        var selection = selectProviders(runtimes, type, title, titleAvailability, episodeAddress, ::titleAnswering)

        if (selection.aliasShapedSkip) {
            val addressed = aliasAddressTitle(title, episodeAddress, ::titleAnswering)
            if (addressed != null && completable(addressed)) {

                val completed = runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(providerTimeoutMs) {
                        aliasCompletion.complete(addressed)
                    }
                }.onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                }.getOrNull()
                if (completed != null && completed.aliases != addressed.aliases) {
                    completedTitles[completed.titleId] = completed
                    title = titleAnswering(titleId)
                    type = requestType(title, episodeAddress)
                    selection = selectProviders(
                        runtimes,
                        type,
                        title,
                        titleAvailability,
                        episodeAddress,
                        ::titleAnswering,
                    )
                }
            }
        }
        val providers = selection.providers

        if (providers.isEmpty()) {
            val observedNone = ProviderResolutionObservation.bounded(
                titleId = titleId.value.toString(),
                imdbId = canonicalImdbId(aliasAddressTitle(title, episodeAddress, ::titleAnswering)),
                providers = emptyList(),
                resourceType = type.wire,
                skipped = selection.skipped,
            )
            _lastObservation.value = observedNone

            if (!addressableSelection(title, titleAvailability)) {
                send(cannotIdentifyResolution())
                return@channelFlow
            }
            send(ResolutionPhase.Candidates(candidates = emptyList(), complete = true))
            return@channelFlow
        }

        val entries = providers.values.toList()
        val completions =
            Channel<Pair<Int, Pair<Pair<AddonRuntimeDescriptor, String>, ProviderBatch>>>(entries.size)
        entries.forEachIndexed { index, provider ->
            launch {
                completions.send(index to (provider to resolveProvider(provider.first, type, provider.second, titleId, editionId)))
            }
        }

        val slots = arrayOfNulls<Pair<Pair<AddonRuntimeDescriptor, String>, ProviderBatch>>(entries.size)
        val all = LinkedHashMap<String, PlayableCandidate>()
        repeat(entries.size) {
            val (index, entry) = completions.receive()
            slots[index] = entry
            val (provider, batch) = entry
            val runtime = provider.first
            _providerStates.value = _providerStates.value + (runtime.addonId to batch.state)
            all.clear()
            for (slot in slots) {
                if (slot != null) {
                    for (candidate in slot.second.candidates) {
                        all[candidate.candidateId.value.toString()] = candidate
                    }
                }
            }
            send(
                ResolutionPhase.Candidates(
                    candidates = all.values.toList(),
                    complete = false,
                    notices = noticesOf(slots),
                ),
            )
        }

        val providersObserved = slots.mapNotNull { slot ->
            if (slot == null) return@mapNotNull null
            val (provider, batch) = slot
            val runtime = provider.first
            ProviderObservationEntry(
                addonId = ProviderObservationEntry.safeLabel(runtime.addonId) ?: "addon",
                manifestId = ProviderObservationEntry.safeLabel(runtime.manifestId),
                displayName = ProviderObservationEntry.safeLabel(runtime.displayName)
                    ?: ProviderObservationEntry.safeLabel(runtime.addonId)
                    ?: "addon",
                state = batch.state,
                wireId = ProviderObservationEntry.safeWireId(provider.second),
                bytesRead = batch.bytesRead,
                streamArrayCount = batch.streamArrayCount,
                acceptedCount = batch.acceptedCount,
                rejectedCount = batch.rejectedCount,
            )
        }
        _lastObservation.value = ProviderResolutionObservation.bounded(
            titleId = titleId.value.toString(),
            imdbId = canonicalImdbId(aliasAddressTitle(title, episodeAddress, ::titleAnswering)),
            providers = providersObserved,
            resourceType = type.wire,
            skipped = selection.skipped.filter { skip ->
                providersObserved.none { it.addonId == (ProviderObservationEntry.safeLabel(skip.addonId) ?: "addon") }
            },
        )

        if (all.isEmpty()) {
            val failures = slots.mapNotNull { slot ->
                slot?.second?.state as? ProviderResolutionState.Failed
            }
            if (failures.isNotEmpty()) {
                send(unavailableResolution(failures))
                return@channelFlow
            }
        }
        send(ResolutionPhase.Candidates(candidates = all.values.toList(), complete = true, notices = noticesOf(slots)))
    }

    private fun unavailableResolution(
        failures: List<ProviderResolutionState.Failed>,
    ): ResolutionPhase.Failed {
        val reasons = failures.map { it.reason }.distinct().sorted()
        val transient = reasons.any { it in TRANSIENT_REASONS }
        return ResolutionPhase.Failed(
            PlaybackFailure(
                kind = failureKindOf(reasons),
                recoverability = if (transient) {
                    FailureRecoverability.TRANSIENT
                } else {
                    FailureRecoverability.PERMANENT
                },
                stage = PlaybackStage.RESOLVE,
                message = "no source could be queried right now (${reasons.joinToString(",")})",
                remedyHint = "Retry — a provider that was never reached may still have the source.",
            ),
        )
    }

    private fun addressableSelection(
        title: dev.infusion.core.model.Title?,
        titleAvailability: List<dev.infusion.core.model.Availability>,
    ): Boolean = title?.aliases?.isNotEmpty() == true ||
        titleAvailability.any { it.kind == dev.infusion.core.model.AvailabilityKind.ADDON }

    private fun cannotIdentifyResolution(): ResolutionPhase.Failed = ResolutionPhase.Failed(
        PlaybackFailure(
            kind = PlaybackFailureKind.SOURCE_UNAVAILABLE,
            recoverability = FailureRecoverability.TRANSIENT,
            stage = PlaybackStage.RESOLVE,
            message = "this selection's canonical identity is not available yet",
            remedyHint = "Retry — the selection's identity may still be resolving.",
        ),
    )

    private fun failureKindOf(reasons: List<String>): PlaybackFailureKind = when {
        reasons.any { it == "unauthorized" || it == "forbidden" } -> PlaybackFailureKind.AUTH
        reasons.any { it == "not_found" } -> PlaybackFailureKind.NOT_FOUND
        reasons.any { it == "timeout" } -> PlaybackFailureKind.TIMEOUT
        reasons.any { it in NETWORK_REASONS } -> PlaybackFailureKind.NETWORK
        else -> PlaybackFailureKind.SOURCE_UNAVAILABLE
    }

    private fun canonicalImdbId(title: dev.infusion.core.model.Title?): String? =
        title?.aliases
            ?.firstOrNull { it.authority == "imdb" && it.value.startsWith("tt") }
            ?.value

    private data class ProviderBatch(
        val candidates: List<PlayableCandidate>,
        val notices: List<ProviderNotice>,
        val state: ProviderResolutionState,
        val bytesRead: Long? = null,
        val streamArrayCount: Int? = null,
        val acceptedCount: Int? = null,
        val rejectedCount: Int? = null,
    )

    private fun noticesOf(
        slots: Array<Pair<Pair<AddonRuntimeDescriptor, String>, ProviderBatch>?>,
    ): List<ProviderNotice> {
        val acc = LinkedHashSet<ProviderNotice>()
        for (slot in slots) if (slot != null) acc.addAll(slot.second.notices)
        return acc.toList()
    }

    private data class ProviderSelection(
        val providers: LinkedHashMap<String, Pair<AddonRuntimeDescriptor, String>>,
        val aliasShapedSkip: Boolean = false,

        val skipped: List<ProviderSkipEntry> = emptyList(),
    )

    private fun selectProviders(
        runtimes: List<AddonRuntimeDescriptor>,
        type: StremioContentType,
        title: dev.infusion.core.model.Title?,
        titleAvailability: List<dev.infusion.core.model.Availability>,
        episodeAddress: EpisodeAddress?,
        titleOf: (TitleId) -> dev.infusion.core.model.Title? = lookup::byId,
    ): ProviderSelection {
        val providers = LinkedHashMap<String, Pair<AddonRuntimeDescriptor, String>>()
        var aliasShapedSkip = false
        val skipped = ArrayList<ProviderSkipEntry>()
        fun skippedProvider(runtime: AddonRuntimeDescriptor, reason: String) {
            skipped += ProviderSkipEntry(
                ProviderObservationEntry.safeLabel(runtime.addonId) ?: "addon",
                reason,
            )
        }
        val addressed = aliasAddressTitle(title, episodeAddress, titleOf)
        for (runtime in runtimes) {
            if (type !in runtime.streamTypes) {

                skippedProvider(runtime, SKIP_NOT_CAPABLE)
                continue
            }
            val nativeId = ownedAvailabilityId(runtime, titleAvailability)
                ?: episodeStreamIdFor(runtime, title, titleAvailability)
                ?: showEpisodeStreamIdFor(runtime, title, episodeAddress, titleOf)
                ?: rootExternalStreamIdFor(runtime, title, type)
            if (nativeId != null && nativeId.isNotBlank()) {
                providers[runtime.addonId] = runtime to nativeId
                continue
            }
            if (aliasShapedBlocked(runtime, addressed)) {
                aliasShapedSkip = true
                skippedProvider(runtime, SKIP_NAMESPACE_MISMATCH)
            } else {
                skippedProvider(runtime, SKIP_NO_ADDRESSED_ID)
            }
        }
        return ProviderSelection(providers, aliasShapedSkip, skipped)
    }

    private fun aliasAddressTitle(
        title: dev.infusion.core.model.Title?,
        address: EpisodeAddress?,
        titleOf: (TitleId) -> dev.infusion.core.model.Title? = lookup::byId,
    ): dev.infusion.core.model.Title? = when {
        title?.kind == dev.infusion.core.model.TitleKind.EPISODE ->
            address?.seriesTitleId?.let { titleOf(it) }
        title == null && address?.seriesTitleId != null -> titleOf(address.seriesTitleId)
        else -> title
    }

    private fun aliasShapedBlocked(
        runtime: AddonRuntimeDescriptor,
        addressed: dev.infusion.core.model.Title?,
    ): Boolean {
        if (runtime.streamIdPrefixes.isEmpty()) return false
        val wireIds = addressed?.aliases
            ?.mapNotNull { alias -> alias.stremioWireId() }
            ?.distinct()
            .orEmpty()
        if (wireIds.isEmpty()) return false
        return wireIds.none { wire -> runtime.streamIdPrefixes.any { prefix -> wire.startsWith(prefix) } }
    }

    private fun completable(addressed: dev.infusion.core.model.Title): Boolean {
        if (addressed.aliases.isEmpty()) return false
        val authorities = addressed.aliases.map { it.authority.lowercase() }.toSet()
        return METADATA_AUTHORITIES.any { it !in authorities }
    }

    private suspend fun resolveProvider(
        runtime: AddonRuntimeDescriptor,
        type: StremioContentType,
        nativeId: String,
        titleId: TitleId,
        editionId: EditionId?,
    ): ProviderBatch {

        val outcome = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(providerTimeoutMs) {
                streamRepository.streamsFrom(runtime.addonId, type, nativeId)
            }
        }
        val unexpected = outcome.exceptionOrNull()
        if (unexpected is kotlinx.coroutines.CancellationException) throw unexpected
        if (unexpected != null) {
            return ProviderBatch(
                emptyList(),
                emptyList(),
                ProviderResolutionState.Failed(reason = "exception"),
            )
        }
        val result = outcome.getOrNull()
        return when (result) {
            null -> ProviderBatch(emptyList(), emptyList(), ProviderResolutionState.Failed("timeout"))
            is StremioResult.Err -> ProviderBatch(
                emptyList(),
                emptyList(),
                ProviderResolutionState.Failed(
                    reason = result.failure.reason.name.lowercase(),
                    guard = result.failure.diagnostic.limitGuard,
                    diagnosticDetails = result.failure.diagnostic.diagnosticDetails,
                ),
                bytesRead = result.failure.diagnostic.bytesRead.takeIf { it > 0 },
            )
            is StremioResult.Ok -> {
                val factory = AddonCandidateFactory(
                    titleId = titleId,
                    editionId = editionId,
                    sourceId = runtime.sourceId,
                    nativeItemId = nativeId,
                    addonDisplayName = runtime.displayName,
                )

                val notices = result.value.streams
                    .filterIsInstance<StremioStreamCandidate.Notice>()
                    .map(factory::toNotice)
                val candidates = result.value.streams
                    .filterNot { it is StremioStreamCandidate.Notice }
                    .map(factory::toCandidate)
                ProviderBatch(
                    candidates = candidates,
                    notices = notices,
                    state = ProviderResolutionState.Resolved(candidates.size),
                    bytesRead = result.value.bytesRead.takeIf { it > 0 },
                    streamArrayCount = result.value.streamArrayCount,
                    acceptedCount = result.value.acceptedCount,
                    rejectedCount = result.value.rejectedCount,
                )
            }
        }
    }

    private fun ownedAvailabilityId(
        runtime: AddonRuntimeDescriptor,
        titleAvailability: List<dev.infusion.core.model.Availability>,
    ): String? {
        val owned = titleAvailability
            .firstOrNull { it.sourceId == runtime.sourceId }
            ?.nativeResourceId
            ?: return null
        if (runtime.streamIdPrefixes.isEmpty()) return owned
        return owned.takeIf { nativeId ->
            runtime.streamIdPrefixes.any { nativeId.startsWith(it) }
        }
    }

    private fun externalStreamIdFor(
        runtime: AddonRuntimeDescriptor,
        title: dev.infusion.core.model.Title?,
        type: StremioContentType,
    ): String? {
        val resolvedTitle = title ?: return null
        if (type !in runtime.streamTypes) return null

        val aliases = resolvedTitle.aliases.sortedWith(
            compareByDescending<dev.infusion.core.model.TypedExternalId> { it.authority == AUTHORITY_IMDB }
                .thenBy { "${it.authority}:${it.value}" },
        )
        val addressable = aliases.mapNotNull { alias: dev.infusion.core.model.TypedExternalId ->
            alias.stremioWireId()?.let { wire: String -> alias to wire }
        }
        if (addressable.isEmpty()) return null
        if (runtime.streamIdPrefixes.isEmpty()) return addressable.first().second
        val addressableMatch = addressable.firstOrNull { pair ->
            runtime.streamIdPrefixes.any { prefix -> pair.second.startsWith(prefix) }
        }
        return addressableMatch?.second
    }

    private fun episodeStreamIdFor(
        runtime: AddonRuntimeDescriptor,
        title: dev.infusion.core.model.Title?,
        titleAvailability: List<dev.infusion.core.model.Availability>,
    ): String? {
        if (title == null) return null
        val kind = title.kind
        if (kind != dev.infusion.core.model.TitleKind.EPISODE &&
            kind != dev.infusion.core.model.TitleKind.SEASON
        ) {
            return null
        }
        val prefixes = runtime.streamIdPrefixes
        return titleAvailability.asSequence()
            .filter { it.kind == dev.infusion.core.model.AvailabilityKind.ADDON }
            .sortedWith(
                compareBy(
                    { it.sourceId.value.toString() },
                    { it.nativeResourceId.orEmpty() },
                ),
            )
            .map { it.nativeResourceId.orEmpty() }
            .firstOrNull { nativeId ->
                nativeId.isNotBlank() &&
                    (prefixes.isEmpty() || prefixes.any { nativeId.startsWith(it) })
            }
    }

    private fun showEpisodeStreamIdFor(
        runtime: AddonRuntimeDescriptor,
        title: dev.infusion.core.model.Title?,
        address: EpisodeAddress?,
        titleOf: (TitleId) -> dev.infusion.core.model.Title? = lookup::byId,
    ): String? {
        val coordinates = address ?: return null

        val show = when (title?.kind) {
            dev.infusion.core.model.TitleKind.SERIES, dev.infusion.core.model.TitleKind.SEASON -> title
            else -> address.seriesTitleId?.let { titleOf(it) }
        } ?: return null
        if (show.kind != dev.infusion.core.model.TitleKind.SERIES &&
            show.kind != dev.infusion.core.model.TitleKind.SEASON
        ) {
            return null
        }
        val showWireId = externalStreamIdFor(runtime, show, StremioContentType.SERIES) ?: return null
        return "$showWireId:${coordinates.seasonNumber}:${coordinates.episodeNumber}"
    }

    private fun rootExternalStreamIdFor(
        runtime: AddonRuntimeDescriptor,
        title: dev.infusion.core.model.Title?,
        type: StremioContentType,
    ): String? {
        val kind = title?.kind
        if (kind != null &&
            kind != dev.infusion.core.model.TitleKind.MOVIE &&
            kind != dev.infusion.core.model.TitleKind.SERIES
        ) {
            return null
        }
        return externalStreamIdFor(runtime, title, type)
    }

    private fun requestType(
        title: dev.infusion.core.model.Title?,
        address: EpisodeAddress?,
    ): StremioContentType = StremioRouteType.of(title?.kind, address)

    private companion object {

        val TRANSIENT_REASONS = setOf(
            "concurrency_exceeded",
            "timeout",
            "connection_failed",
            "dns_failed",
            "rate_limited",
            "server_error",
            "cancelled",
            "exception",
        )

        val NETWORK_REASONS = setOf(
            "connection_failed",
            "dns_failed",
            "rate_limited",
            "server_error",
            "cleartext_blocked",
            "redirect_policy",
            "private_destination",
            "concurrency_exceeded",
            "cancelled",
        )

        val METADATA_AUTHORITIES = setOf("imdb", "tmdb", "tvdb")

        const val SKIP_NOT_CAPABLE = "not-capable"
        const val SKIP_NO_ADDRESSED_ID = "no-addressed-id"
        const val SKIP_NAMESPACE_MISMATCH = "namespace-mismatch"
    }
}
