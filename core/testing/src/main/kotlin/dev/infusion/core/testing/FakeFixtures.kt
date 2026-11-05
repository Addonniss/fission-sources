package dev.infusion.core.testing

import dev.infusion.core.domain.candidate.CandidateFamily
import dev.infusion.core.model.Availability
import dev.infusion.core.model.AvailabilityId
import dev.infusion.core.model.AvailabilityKind
import dev.infusion.core.model.CandidateFacts
import dev.infusion.core.model.CandidateId
import dev.infusion.core.model.CapabilityAssessment
import dev.infusion.core.model.CapabilityState
import dev.infusion.core.model.DisclosureRecord
import dev.infusion.core.model.Edition
import dev.infusion.core.model.EditionId
import dev.infusion.core.model.ExpectedMode
import dev.infusion.core.model.FactConfidence
import dev.infusion.core.model.NativeItemId
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.Provenance
import dev.infusion.core.model.Reachability
import dev.infusion.core.model.Source
import dev.infusion.core.model.SourceCapability
import dev.infusion.core.model.SourceHealth
import dev.infusion.core.model.SourceId
import dev.infusion.core.model.SourceType
import dev.infusion.core.model.Title
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.TrustClass
import java.time.Instant
import java.util.UUID

object FakeFixtures {

    private const val NS = "infusion:fake-catalog:v1"

    private fun id(name: String): UUID = UUID.nameUUIDFromBytes("$NS:$name".toByteArray())

    private fun instant(epochSecond: Long): Instant = Instant.ofEpochSecond(epochSecond)

    val localSource: Source = Source(
        sourceId = SourceId(id("source:local")),
        type = SourceType.SAF,
        trustClass = TrustClass.DEVICE_LOCAL,
        displayName = "Local Files",
        enabled = true,
        capabilities = setOf(SourceCapability.BROWSE, SourceCapability.STREAM),
        health = SourceHealth.HEALTHY,
        disclosure = DisclosureRecord(
            disclosedAt = instant(1_700_000_000),
            dataSentSummary = "No network access; files stay on device.",
            networkReachSummary = "Device-local only.",
            secretUseSummary = "No credentials.",
        ),
        createdAt = instant(1_700_000_000),
    )

    val serverSource: Source = Source(
        sourceId = SourceId(id("source:server")),
        type = SourceType.JELLYFIN,
        trustClass = TrustClass.PERSONAL_SERVER,
        displayName = "Demo Server",
        endpoint = "http://demo.local:8096",
        enabled = true,
        capabilities = setOf(SourceCapability.BROWSE, SourceCapability.STREAM, SourceCapability.WATCH_STATE_SYNC),
        health = SourceHealth.HEALTHY,
        disclosure = DisclosureRecord(
            disclosedAt = instant(1_700_000_000),
            dataSentSummary = "Playback state is sent to this server.",
            networkReachSummary = "Local network only.",
            secretUseSummary = "Login token stored in Keystore.",
        ),
        createdAt = instant(1_700_000_000),
    )

    val addonSource: Source = Source(
        sourceId = SourceId(id("source:addon")),
        type = SourceType.STREMIO_REMOTE,
        trustClass = TrustClass.REMOTE_ADDON,
        displayName = "Demo Add-on",
        endpoint = "https://demo.addon.example",
        enabled = true,
        capabilities = setOf(SourceCapability.BROWSE, SourceCapability.STREAM),
        health = SourceHealth.DEGRADED,
        disclosure = DisclosureRecord(
            disclosedAt = instant(1_700_000_000),
            dataSentSummary = "Title identifiers sent to this add-on.",
            networkReachSummary = "Public HTTPS.",
            secretUseSummary = "No credentials.",
        ),
        createdAt = instant(1_700_000_000),
    )

    val sources: List<Source> = listOf(localSource, serverSource, addonSource)

    private val longWalkProvenance: Provenance = Provenance(
        sourceId = serverSource.sourceId,
        protocolVersion = "fake/1",
        retrievedAt = instant(1_700_000_000),
        normalizedBy = "FakeFixtures",
    )

    val longWalkTitle: Title = Title(
        titleId = TitleId(id("title:long-walk")),
        kind = TitleKind.MOVIE,
        displayTitle = "The Long Walk",
        originalTitle = "The Long Walk",
        year = 2023,
        provenance = longWalkProvenance,

        genres = listOf("Drama", "Thriller"),
        genresProvenance = longWalkProvenance,
        rating = "8.1",
        ratingProvenance = longWalkProvenance,
    )

    val longWalkTheatrical: Edition = Edition(
        editionId = EditionId(id("edition:long-walk:theatrical")),
        titleId = longWalkTitle.titleId,
        label = "Theatrical Cut",
        runtimeMinutes = 98,
        confidence = 1.0,
        provenance = longWalkTitle.provenance,
    )

    val longWalkDirectors: Edition = Edition(
        editionId = EditionId(id("edition:long-walk:directors")),
        titleId = longWalkTitle.titleId,
        label = "Director's Cut",
        runtimeMinutes = 112,
        confidence = 1.0,
        provenance = longWalkTitle.provenance,
    )

    val signalLostTitle: Title = Title(
        titleId = TitleId(id("title:signal-lost")),
        kind = TitleKind.SERIES,
        displayTitle = "Signal Lost",
        year = 2024,
        provenance = longWalkTitle.provenance!!.copy(sourceId = localSource.sourceId),
        synopsis = "A communications relay team on a remote outpost loses contact with Earth, " +
            "one transmission at a time.",
        synopsisProvenance = longWalkTitle.provenance!!.copy(sourceId = localSource.sourceId),

        genres = listOf("Drama", "Sci-Fi"),
        genresProvenance = longWalkTitle.provenance!!.copy(sourceId = localSource.sourceId),
        rating = "8.4",
        ratingProvenance = longWalkTitle.provenance!!.copy(sourceId = localSource.sourceId),
    )

    private fun episodeTitle(
        series: Title,
        season: Int,
        episode: Int,
        displayName: String,
        synopsis: String? = null,
    ): Title = Title(
        titleId = TitleId(id("title:${series.displayTitle.lowercase().replace(' ', '-')}:s${season}e${episode}")),
        kind = TitleKind.EPISODE,
        displayTitle = displayName,
        parentTitleId = series.titleId,
        year = series.year,
        provenance = series.provenance,
        synopsis = synopsis,
        synopsisProvenance = synopsis?.let { series.provenance },
        seasonNumber = season,
        episodeNumber = episode,
    )

    val signalLostS1E1: Title = episodeTitle(
        signalLostTitle, 1, 1, "The Last Transmission",
        synopsis = "The relay team's first blackout: a routine check-in goes silent.",
    )
    val signalLostS1E2: Title = episodeTitle(
        signalLostTitle, 1, 2, "Static",
        synopsis = "A salvage crew finds the outpost empty — and the logs still running.",
    )
    val signalLostS1E3: Title = episodeTitle(signalLostTitle, 1, 3, "Echoes")
    val signalLostS2E1: Title = episodeTitle(signalLostTitle, 2, 1, "The Return")

    val signalLostSeasons: List<Title> = listOf(
        Title(
            titleId = TitleId(id("title:signal-lost:s1")),
            kind = TitleKind.SEASON,
            displayTitle = "Season 1",
            parentTitleId = signalLostTitle.titleId,
            year = 2024,
            provenance = signalLostTitle.provenance,
            seasonNumber = 1,
        ),
        Title(
            titleId = TitleId(id("title:signal-lost:s2")),
            kind = TitleKind.SEASON,
            displayTitle = "Season 2",
            parentTitleId = signalLostTitle.titleId,
            year = 2025,
            provenance = signalLostTitle.provenance,
            seasonNumber = 2,
        ),
    )

    fun seasonEpisodes(seasonTitleId: TitleId): List<Title> = when (seasonTitleId) {
        signalLostSeasons[0].titleId -> listOf(signalLostS1E1, signalLostS1E2, signalLostS1E3)
        signalLostSeasons[1].titleId -> listOf(signalLostS2E1)
        else -> emptyList()
    }

    val nightMarketTitle: Title = Title(
        titleId = TitleId(id("title:night-market")),
        kind = TitleKind.MOVIE,
        displayTitle = "Night Market",
        year = 2022,
        provenance = longWalkTitle.provenance!!.copy(sourceId = addonSource.sourceId),
    )

    val emptyTitle: Title = Title(
        titleId = TitleId(id("title:empty")),
        kind = TitleKind.MOVIE,
        displayTitle = "The Empty Shelf",
        year = 2021,
        provenance = longWalkTitle.provenance!!.copy(sourceId = localSource.sourceId),
    )

    val titles: List<Title> = listOf(longWalkTitle, signalLostTitle, nightMarketTitle, emptyTitle)

    val allTitles: List<Title> = titles + signalLostSeasons +
        listOf(signalLostS1E1, signalLostS1E2, signalLostS1E3, signalLostS2E1)

    val longWalkAvailability: List<Availability> = listOf(
        Availability(
            availabilityId = AvailabilityId(id("avail:long-walk:server")),
            titleId = longWalkTitle.titleId,
            editionId = longWalkDirectors.editionId,
            sourceId = serverSource.sourceId,
            nativeResourceId = "item:long-walk-directors",
            kind = AvailabilityKind.SERVER,
            provenance = longWalkTitle.provenance!!,
        ),
        Availability(
            availabilityId = AvailabilityId(id("avail:long-walk:local")),
            titleId = longWalkTitle.titleId,
            editionId = longWalkTheatrical.editionId,
            sourceId = localSource.sourceId,
            nativeResourceId = "the_long_walk_theatrical.mp4",
            kind = AvailabilityKind.LOCAL,
            provenance = longWalkTitle.provenance!!,
        ),
        Availability(
            availabilityId = AvailabilityId(id("avail:long-walk:addon")),
            titleId = longWalkTitle.titleId,
            editionId = longWalkDirectors.editionId,
            sourceId = addonSource.sourceId,
            nativeResourceId = "catalog:long-walk",
            kind = AvailabilityKind.ADDON,
            provenance = longWalkTitle.provenance!!,
        ),
    )

    val signalLostAvailability: List<Availability> = listOf(
        Availability(
            availabilityId = AvailabilityId(id("avail:signal-lost:local")),
            titleId = signalLostTitle.titleId,
            sourceId = localSource.sourceId,
            nativeResourceId = "Signal.Lost.S01E01.mkv",
            kind = AvailabilityKind.LOCAL,
            provenance = signalLostTitle.provenance!!,
        ),
    )

    private val episodeAvailabilities: List<Availability> =
        listOf(signalLostS1E1, signalLostS1E2, signalLostS1E3, signalLostS2E1).map { episode ->
            Availability(
                availabilityId = AvailabilityId(id("avail:${episode.titleId.value}:local")),
                titleId = episode.titleId,
                sourceId = localSource.sourceId,
                nativeResourceId = "Signal.Lost.S${episode.seasonNumber}E${episode.episodeNumber}.mkv",
                kind = AvailabilityKind.LOCAL,
                provenance = episode.provenance!!,
            )
        }

    val nightMarketAvailability: List<Availability> = listOf(
        Availability(
            availabilityId = AvailabilityId(id("avail:night-market:addon")),
            titleId = nightMarketTitle.titleId,
            sourceId = addonSource.sourceId,
            nativeResourceId = "catalog:night-market",
            kind = AvailabilityKind.ADDON,
            provenance = nightMarketTitle.provenance!!,
        ),
    )

    val emptyTitleAvailability: List<Availability> = listOf(
        Availability(
            availabilityId = AvailabilityId(id("avail:empty:local")),
            titleId = emptyTitle.titleId,
            sourceId = localSource.sourceId,
            nativeResourceId = "empty.mp4",
            kind = AvailabilityKind.LOCAL,
            provenance = emptyTitle.provenance!!,
        ),
    )

    fun availabilityFor(titleId: TitleId): List<Availability> = when (titleId) {
        longWalkTitle.titleId -> longWalkAvailability
        signalLostTitle.titleId -> signalLostAvailability
        nightMarketTitle.titleId -> nightMarketAvailability
        emptyTitle.titleId -> emptyTitleAvailability
        else -> episodeAvailabilities.filter { it.titleId == titleId }
    }

    private fun candidate(
        name: String,
        titleId: TitleId,
        editionId: EditionId?,
        source: Source,
        originalLabel: String,
        mode: ExpectedMode,
        capability: CapabilityState,
        expiresAt: Instant? = null,
        reachability: Reachability = Reachability.REACHABLE,
    ): PlayableCandidate {
        val native = NativeItemId(source.sourceId, name)
        return PlayableCandidate(
            candidateId = CandidateId(id("candidate:$name")),
            titleId = titleId,
            editionId = editionId,
            sourceId = source.sourceId,
            nativeItemId = native,
            familyId = CandidateFamily.derive(native, versionId = null),
            providerName = source.displayName,
            originalLabel = originalLabel,
            transport = FakeTransport.descriptor(name),
            facts = CandidateFacts(
                container = "mkv",
                videoCodec = "h264",
                resolution = "1920x1080",
                audioCodec = "aac",
                confidence = FactConfidence.INFERRED,
                provenance = longWalkTitle.provenance,
            ),
            expectedMode = mode,
            capability = CapabilityAssessment(
                state = capability,
                reasons = listOf("fake capability observation for $name"),
                assessedAt = instant(1_700_000_000),
            ),
            expiresAt = expiresAt,
            reachability = reachability,
            rankExplanation = "fake deterministic rank: $name",
            createdAt = instant(1_700_000_000),
        )
    }

    val longWalkCandidates: List<PlayableCandidate> = listOf(
        candidate(
            name = "server-directors",
            titleId = longWalkTitle.titleId,
            editionId = longWalkDirectors.editionId,
            source = serverSource,
            originalLabel = "The.Long.Walk.2023.1080p.DirectorsCut.mkv",
            mode = ExpectedMode.DIRECT,
            capability = CapabilityState.SUPPORTED,
        ),
        candidate(
            name = "local-theatrical",
            titleId = longWalkTitle.titleId,
            editionId = longWalkTheatrical.editionId,
            source = localSource,
            originalLabel = "the_long_walk_theatrical_cut.mp4",
            mode = ExpectedMode.DIRECT,
            capability = CapabilityState.UNKNOWN,
        ),
        candidate(
            name = "addon-transcode",
            titleId = longWalkTitle.titleId,
            editionId = longWalkDirectors.editionId,
            source = addonSource,
            originalLabel = "The Long Walk (2023) - Directors Cut [addon].m3u8",
            mode = ExpectedMode.TRANSCODE,
            capability = CapabilityState.DEGRADED,
        ),
    )

    val expiredCandidate: PlayableCandidate = candidate(
        name = "server-expired",
        titleId = longWalkTitle.titleId,
        editionId = longWalkDirectors.editionId,
        source = serverSource,
        originalLabel = "expired-signed-url.mkv",
        mode = ExpectedMode.DIRECT,
        capability = CapabilityState.SUPPORTED,
        expiresAt = instant(1_600_000_000),
        reachability = Reachability.UNKNOWN,
    )

    val unauthorizedCandidate: PlayableCandidate = candidate(
        name = "server-unauthorized",
        titleId = longWalkTitle.titleId,
        editionId = longWalkDirectors.editionId,
        source = serverSource,
        originalLabel = "members-only.mkv",
        mode = ExpectedMode.DIRECT,
        capability = CapabilityState.UNKNOWN,
        reachability = Reachability.UNKNOWN,
    )

    val allCandidates: List<PlayableCandidate> by lazy {
        longWalkCandidates + listOf(expiredCandidate, unauthorizedCandidate) +
            candidatesFor(signalLostTitle.titleId, null) + candidatesFor(nightMarketTitle.titleId, null) +

            listOf(signalLostS1E1, signalLostS1E2, signalLostS1E3, signalLostS2E1)
                .flatMap { candidatesFor(it.titleId, null) }
    }

    fun candidateById(candidateId: CandidateId): PlayableCandidate? =
        allCandidates.firstOrNull { it.candidateId == candidateId }

    fun candidatesFor(titleId: TitleId, editionId: EditionId?): List<PlayableCandidate> = when (titleId) {
        longWalkTitle.titleId -> longWalkCandidates
        signalLostTitle.titleId -> listOf(
            candidate(
                name = "local-signal-lost-s1e1",
                titleId = titleId,
                editionId = editionId,
                source = localSource,
                originalLabel = "Signal.Lost.S01E01.1080p.mkv",
                mode = ExpectedMode.DIRECT,
                capability = CapabilityState.SUPPORTED,
            ),
        )
        nightMarketTitle.titleId -> listOf(
            candidate(
                name = "addon-night-market",
                titleId = titleId,
                editionId = editionId,
                source = addonSource,
                originalLabel = "Night Market (2022) [addon].mp4",
                mode = ExpectedMode.REMUX,
                capability = CapabilityState.UNKNOWN,
            ),
        )

        signalLostS1E1.titleId, signalLostS1E2.titleId, signalLostS1E3.titleId, signalLostS2E1.titleId ->
            listOf(
                candidate(
                    name = "local-${titleId.value}",
                    titleId = titleId,
                    editionId = editionId,
                    source = localSource,
                    originalLabel = "Signal.Lost.S${seasonOf(titleId)}E${episodeOf(titleId)}.mkv",
                    mode = ExpectedMode.DIRECT,
                    capability = CapabilityState.SUPPORTED,
                ),
            )
        else -> emptyList()
    }

    private fun seasonOf(titleId: TitleId): Int = when (titleId) {
        signalLostS1E1.titleId, signalLostS1E2.titleId, signalLostS1E3.titleId -> 1
        signalLostS2E1.titleId -> 2
        else -> 0
    }

    private fun episodeOf(titleId: TitleId): Int = when (titleId) {
        signalLostS1E1.titleId -> 1
        signalLostS1E2.titleId -> 2
        signalLostS1E3.titleId -> 3
        signalLostS2E1.titleId -> 1
        else -> 0
    }

    fun placeholderProfile(): dev.infusion.core.model.profile.Profile =
        dev.infusion.core.model.profile.Profile(
            profileId = dev.infusion.core.domain.profile.ProfilePolicy.DEFAULT_PROFILE_ID,
            name = "Placeholder",
            isDefault = true,
            profileIndex = 1,
            usesPrimarySources = true,
            pinProtected = false,
            createdAtMs = 0L,
        )
}
