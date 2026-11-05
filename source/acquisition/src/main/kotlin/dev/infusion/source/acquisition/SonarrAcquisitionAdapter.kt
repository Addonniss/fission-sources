package dev.infusion.source.acquisition

import dev.infusion.core.domain.acquisition.AcquisitionCapabilities
import dev.infusion.core.domain.acquisition.AcquisitionConnection
import dev.infusion.core.domain.acquisition.AcquisitionFailureKind
import dev.infusion.core.domain.acquisition.AcquisitionKind
import dev.infusion.core.domain.acquisition.AcquisitionObservation
import dev.infusion.core.domain.acquisition.AcquisitionOperation
import dev.infusion.core.domain.acquisition.AcquisitionOutcome
import dev.infusion.core.domain.acquisition.AcquisitionPort
import dev.infusion.core.domain.acquisition.AcquisitionRef
import dev.infusion.core.domain.acquisition.AcquisitionRequest
import dev.infusion.core.domain.acquisition.AcquisitionStatus
import java.io.Closeable
import java.net.URLEncoder
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

class SonarrAcquisitionAdapter internal constructor(
    override val serviceId: String,
    override val serviceInstance: String,
    private val connection: AcquisitionConnection,
    private val http: AcquisitionHttp,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AcquisitionPort, Closeable {

    override fun capabilities(): AcquisitionCapabilities = AcquisitionCapabilities(
        add = true,
        monitor = true,
        search = true,
        kinds = setOf(AcquisitionKind.SHOW, AcquisitionKind.SEASON, AcquisitionKind.EPISODE),
    )

    override fun connected(): Boolean = connection.url.isNotBlank()

    override suspend fun request(request: AcquisitionRequest): AcquisitionOutcome {
        if (request.kind == AcquisitionKind.MOVIE) return unsupported(request, "Sonarr only handles shows")
        val term = seriesTerm(request) ?: return unavailable(request, "no proven show identifier (tvdb/tmdb)")
        val issuedAt = nowMs()

        val lookup = when (val r = http.get("/api/v3/series/lookup?term=${encode(term)}")) {
            is AcquisitionHttp.Result.Err -> return failed(request, r)
            is AcquisitionHttp.Result.Ok -> r.body
        }
        val rows = try {
            AcquisitionMediaWire.parseLookupSeries(lookup)
        } catch (e: AcquisitionMediaWire.Malformed) {
            return failed(request, AcquisitionFailureKind.MALFORMED, "invalid lookup result")
        }
        val row = rows.firstOrNull() ?: return failed(request, AcquisitionFailureKind.NOT_FOUND, "show not found on Sonarr")
        if (row.tvdbId == null && row.tmdbId == null) {
            return failed(request, AcquisitionFailureKind.MALFORMED, "invalid lookup result")
        }
        val existingId = row.id?.takeIf { it > 0 }
        return if (existingId != null) {
            handleExisting(request, existingId, issuedAt)
        } else {
            handleNew(request, row, issuedAt)
        }
    }

    private suspend fun handleExisting(
        request: AcquisitionRequest,
        seriesId: Int,
        issuedAt: Long,
    ): AcquisitionOutcome = when (request.kind) {
        AcquisitionKind.SHOW -> {
            when (val search = submitCommand("SeriesSearch", JSONObject().put("seriesId", seriesId))) {
                is SubmitResult.Submitted -> outcome(
                    request, AcquisitionStatus.ALREADY_MANAGED, ref(request, seriesId),
                    alreadyManaged = true, monitored = true, searchSubmitted = true,
                    reason = "already managed — search started",
                )
                is SubmitResult.Ambiguous ->
                    if (searchReconciled("SeriesSearch", seriesId = seriesId, issuedAt = issuedAt)) {
                        outcome(request, AcquisitionStatus.ALREADY_MANAGED, ref(request, seriesId), alreadyManaged = true, monitored = true, searchSubmitted = true, reason = "already managed — search reconciled")
                    } else {
                        outcome(request, AcquisitionStatus.UNKNOWN, ref(request, seriesId), alreadyManaged = true, monitored = true, reason = "search outcome unknown — not repeated")
                    }
                is SubmitResult.Failed -> outcome(request, AcquisitionStatus.PARTIAL, ref(request, seriesId), alreadyManaged = true, monitored = true, reason = search.reason)
            }
        }

        AcquisitionKind.SEASON -> {
            val monitored = ensureSeasonMonitored(seriesId, request.seasonNumber!!)
            val episodes = episodesOf(seriesId)
                ?: return outcome(request, AcquisitionStatus.UNKNOWN, ref(request, seriesId), monitored = monitored, reason = "episode list unavailable")

            if (episodes.isEmpty()) {
                return outcome(request, AcquisitionStatus.ACCEPTED, ref(request, seriesId), monitored = monitored, searchPending = true, reason = "episode records not populated yet")
            }
            val targetIds = releasedMissingSeasonTargets(episodes, request.seasonNumber!!)
            if (targetIds.isEmpty()) {
                return outcome(request, AcquisitionStatus.ACCEPTED, ref(request, seriesId), monitored = monitored, searchSubmitted = false, reason = "no released episodes to search")
            }
            submitEpisodesSearch(request, seriesId, targetIds, monitored, issuedAt)
        }

        AcquisitionKind.EPISODE -> {
            val episodes = episodesOf(seriesId)
                ?: return outcome(request, AcquisitionStatus.UNKNOWN, ref(request, seriesId), reason = "episode list unavailable")
            if (episodes.isEmpty()) {
                return outcome(request, AcquisitionStatus.ACCEPTED, ref(request, seriesId), searchPending = true, reason = "episode records not populated yet")
            }
            val episode = episodes.firstOrNull { it.seasonNumber == request.seasonNumber && it.episodeNumber == request.episodeNumber }
                ?: return outcome(request, AcquisitionStatus.FAILED, ref(request, seriesId), reason = "episode not found on Sonarr")
            val monitored = monitorEpisodes(listOf(episode.id))
            if (monitored == null) {
                return outcome(request, AcquisitionStatus.UNKNOWN, ref(request, seriesId, episode.id), reason = "monitor outcome unknown — not repeated")
            }
            submitEpisodesSearch(request, seriesId, listOf(episode.id), monitored, issuedAt, episodeId = episode.id)
        }

        AcquisitionKind.MOVIE -> unsupported(request, "Sonarr only handles shows")
    }

    private suspend fun handleNew(
        request: AcquisitionRequest,
        row: AcquisitionMediaWire.LookupSeries,
        issuedAt: Long,
    ): AcquisitionOutcome {
        val payload = addPayload(request, row)
        when (val add = http.post("/api/v3/series", payload.toString())) {
            is AcquisitionHttp.Result.Ok -> {
                val createdId = AcquisitionMediaWire.parseCreatedId(add.body)
                    ?: return outcome(request, AcquisitionStatus.PARTIAL, null, titleAdded = true, reason = "added, but the server returned no id")
                return finishNew(request, createdId, issuedAt)
            }
            is AcquisitionHttp.Result.Err -> {
                if (!isAmbiguous(add.kind)) return failed(request, add)
                val reconciled = reconcileAdd(request)
                    ?: return outcome(request, AcquisitionStatus.UNKNOWN, null, reason = "add outcome unknown — not repeated")
                return finishNew(request, reconciled, issuedAt)
            }
        }
    }

    private suspend fun finishNew(request: AcquisitionRequest, seriesId: Int, issuedAt: Long): AcquisitionOutcome =
        when (request.kind) {
            AcquisitionKind.SHOW -> outcome(
                request, AcquisitionStatus.ACCEPTED, ref(request, seriesId),
                titleAdded = true, monitored = true, searchSubmitted = true,
                reason = "added — search started",
            )
            AcquisitionKind.SEASON -> {
                val episodes = episodesOf(seriesId)
                    ?: return outcome(request, AcquisitionStatus.PARTIAL, ref(request, seriesId), titleAdded = true, monitored = true, reason = "added; episode list unavailable")

                if (episodes.isEmpty()) {
                    return outcome(request, AcquisitionStatus.ACCEPTED, ref(request, seriesId), titleAdded = true, monitored = true, searchPending = true, reason = "added — episode records not populated yet")
                }
                val targetIds = releasedMissingSeasonTargets(episodes, request.seasonNumber!!)
                if (targetIds.isEmpty()) {
                    return outcome(request, AcquisitionStatus.ACCEPTED, ref(request, seriesId), titleAdded = true, monitored = true, reason = "added — no released episodes to search")
                }
                submitEpisodesSearch(request, seriesId, targetIds, true, issuedAt, titleAdded = true)
            }
            AcquisitionKind.EPISODE -> {
                val episodes = episodesOf(seriesId)
                    ?: return outcome(request, AcquisitionStatus.PARTIAL, ref(request, seriesId), titleAdded = true, reason = "added; episode list unavailable")
                if (episodes.isEmpty()) {
                    return outcome(request, AcquisitionStatus.ACCEPTED, ref(request, seriesId), titleAdded = true, searchPending = true, reason = "added — episode records not populated yet")
                }
                val episode = episodes.firstOrNull { it.seasonNumber == request.seasonNumber && it.episodeNumber == request.episodeNumber }
                    ?: return outcome(request, AcquisitionStatus.PARTIAL, ref(request, seriesId), titleAdded = true, reason = "added; episode not found")
                val monitored = monitorEpisodes(listOf(episode.id))
                    ?: return outcome(request, AcquisitionStatus.PARTIAL, ref(request, seriesId, episode.id), titleAdded = true, reason = "added; monitor outcome unknown")
                submitEpisodesSearch(request, seriesId, listOf(episode.id), monitored, issuedAt, titleAdded = true, episodeId = episode.id)
            }
            AcquisitionKind.MOVIE -> unsupported(request, "Sonarr only handles shows")
        }

    private fun addPayload(request: AcquisitionRequest, row: AcquisitionMediaWire.LookupSeries): JSONObject {
        val payload = JSONObject(row.raw.toString())
        if (row.genres.any { it.equals("anime", ignoreCase = true) }) {
            payload.put("seriesType", "anime")
        }
        payload.put("qualityProfileId", connection.qualityProfileId)
        payload.put("rootFolderPath", connection.rootFolderPath)
        payload.put("monitored", true)
        val addOptions = JSONObject().put(
            "searchForMissingEpisodes",
            request.kind == AcquisitionKind.SHOW,
        )
        if (request.kind == AcquisitionKind.EPISODE) {

            addOptions.put("monitor", "none")
        }
        payload.put("addOptions", addOptions)
        if (request.kind == AcquisitionKind.SEASON) {
            payload.put("seasons", seasonsOnly(request.seasonNumber!!, row.seasons))
        }
        return payload
    }

    private fun seasonsOnly(target: Int, seasons: List<AcquisitionMediaWire.SeasonRow>): JSONArray {
        val arr = JSONArray()
        for (s in seasons) {
            arr.put(
                JSONObject()
                    .put("seasonNumber", s.seasonNumber)
                    .put("monitored", s.seasonNumber == target),
            )
        }

        if (seasons.isEmpty()) arr.put(JSONObject().put("seasonNumber", target).put("monitored", true))
        return arr
    }

    override suspend fun reconcile(
        request: AcquisitionRequest,
        operation: AcquisitionOperation,
        issuedAtMs: Long,
    ): AcquisitionOutcome {
        if (request.kind == AcquisitionKind.MOVIE) return unsupported(request, "Sonarr only handles shows")
        return when (operation) {
            AcquisitionOperation.ADD_TITLE -> {
                val id = reconcileAdd(request)
                if (id == null) outcome(request, AcquisitionStatus.UNKNOWN, null, reason = "title add could not be reconciled")
                else outcome(request, AcquisitionStatus.ACCEPTED, ref(request, id), titleAdded = true, monitored = true)
            }
            AcquisitionOperation.MONITOR -> reconcileMonitor(request)
            AcquisitionOperation.SEARCH -> {
                val id = reconcileAdd(request)
                if (id == null) {
                    outcome(request, AcquisitionStatus.UNKNOWN, null, reason = "title not reconciled")
                } else if (searchReconciledFor(request, id, issuedAtMs)) {
                    outcome(request, AcquisitionStatus.ACCEPTED, ref(request, id), searchSubmitted = true)
                } else {
                    outcome(request, AcquisitionStatus.UNKNOWN, ref(request, id), reason = "no matching search command")
                }
            }
        }
    }

    private suspend fun reconcileMonitor(request: AcquisitionRequest): AcquisitionOutcome {
        val id = reconcileAdd(request) ?: return outcome(request, AcquisitionStatus.UNKNOWN, null, reason = "title not reconciled")
        return when (request.kind) {
            AcquisitionKind.EPISODE -> {
                val episodes = episodesOf(id)
                val ep = episodes?.firstOrNull { it.seasonNumber == request.seasonNumber && it.episodeNumber == request.episodeNumber }
                if (ep != null && ep.monitored) outcome(request, AcquisitionStatus.ACCEPTED, ref(request, id, ep.id), monitored = true)
                else outcome(request, AcquisitionStatus.UNKNOWN, ref(request, id), reason = "monitor state not reconciled")
            }
            AcquisitionKind.SEASON -> {
                val series = seriesById(id)
                val season = series?.seasons?.firstOrNull { it.seasonNumber == request.seasonNumber }
                if (season?.monitored == true) outcome(request, AcquisitionStatus.ACCEPTED, ref(request, id), monitored = true)
                else outcome(request, AcquisitionStatus.UNKNOWN, ref(request, id), reason = "monitor state not reconciled")
            }
            else -> outcome(request, AcquisitionStatus.UNSUPPORTED, ref(request, id), reason = "no separate monitor op for a whole show")
        }
    }

    override suspend fun observe(refs: List<AcquisitionRef>): List<AcquisitionObservation> {
        val bySeries = refs.filter { it.kind != AcquisitionKind.MOVIE }.groupBy { it.serverId }
        if (bySeries.isEmpty()) return emptyList()
        val out = ArrayList<AcquisitionObservation>(refs.size)
        for ((seriesId, group) in bySeries) {
            val episodes = episodesOf(seriesId)
            if (episodes == null) {
                out += group.map { AcquisitionObservation(it, known = false, managed = false, filePresent = false, reason = "episode list unavailable") }
                continue
            }
            for (ref in group) {
                val released = episodes.filter { it.airDateUtc != null && it.airDateUtc <= Instant.ofEpochMilli(nowMs()) }
                val filePresent = when (ref.kind) {
                    AcquisitionKind.EPISODE -> episodes.firstOrNull { it.seasonNumber == ref.seasonNumber && it.episodeNumber == ref.episodeNumber }?.hasFile == true
                    AcquisitionKind.SEASON -> {
                        val seasonReleased = released.filter { it.seasonNumber == ref.seasonNumber }
                        seasonReleased.isNotEmpty() && seasonReleased.all { it.hasFile }
                    }
                    AcquisitionKind.SHOW -> released.isNotEmpty() && released.all { it.hasFile }
                    AcquisitionKind.MOVIE -> false
                }
                out += AcquisitionObservation(ref, known = true, managed = true, filePresent = filePresent)
            }
        }
        return out
    }

    override fun close() = http.close()

    private sealed interface SubmitResult {
        data object Submitted : SubmitResult
        data object Ambiguous : SubmitResult
        data class Failed(val reason: String) : SubmitResult
    }

    private suspend fun submitCommand(name: String, body: JSONObject): SubmitResult =
        when (val r = http.post("/api/v3/command", JSONObject().put("name", name).apply { body.keys().forEach { put(it, body.get(it)) } }.toString())) {
            is AcquisitionHttp.Result.Ok -> SubmitResult.Submitted
            is AcquisitionHttp.Result.Err ->
                if (isAmbiguous(r.kind)) SubmitResult.Ambiguous else SubmitResult.Failed("search failed: ${r.message}")
        }

    private suspend fun submitEpisodesSearch(
        request: AcquisitionRequest,
        seriesId: Int,
        episodeIds: List<Int>,
        monitored: Boolean,
        issuedAt: Long,
        titleAdded: Boolean = false,
        episodeId: Int? = null,
    ): AcquisitionOutcome {
        val body = JSONObject().put("episodeIds", JSONArray(episodeIds))
        return when (val search = submitCommand("EpisodeSearch", body)) {
            is SubmitResult.Submitted -> outcome(
                request, statusForSearch(monitored, titleAdded), ref(request, seriesId, episodeId),
                titleAdded = titleAdded, monitored = monitored, searchSubmitted = true,
                reason = if (titleAdded) "added — search started" else "search started",
            )
            is SubmitResult.Ambiguous ->
                if (searchReconciled("EpisodeSearch", episodeIds = episodeIds, issuedAt = issuedAt)) {
                    outcome(request, statusForSearch(monitored, titleAdded), ref(request, seriesId, episodeId), titleAdded = titleAdded, monitored = monitored, searchSubmitted = true, reason = "search reconciled")
                } else {
                    outcome(request, AcquisitionStatus.UNKNOWN, ref(request, seriesId, episodeId), titleAdded = titleAdded, monitored = monitored, reason = "search outcome unknown — not repeated")
                }
            is SubmitResult.Failed -> outcome(
                request, AcquisitionStatus.PARTIAL, ref(request, seriesId, episodeId),
                titleAdded = titleAdded, monitored = monitored,
                reason = search.reason,
            )
        }
    }

    private fun statusForSearch(monitored: Boolean, titleAdded: Boolean): AcquisitionStatus =
        if (titleAdded && !monitored) AcquisitionStatus.PARTIAL else AcquisitionStatus.ACCEPTED

    private suspend fun monitorEpisodes(episodeIds: List<Int>): Boolean? {
        val payload = JSONObject().put("episodeIds", JSONArray(episodeIds)).put("monitored", true)
        return when (val r = http.put("/api/v3/episode/monitor", payload.toString())) {
            is AcquisitionHttp.Result.Ok -> true
            is AcquisitionHttp.Result.Err -> if (isAmbiguous(r.kind)) null else false
        }
    }

    private suspend fun ensureSeasonMonitored(seriesId: Int, seasonNumber: Int): Boolean {
        val series = seriesById(seriesId) ?: return false
        if (series.seasons.firstOrNull { it.seasonNumber == seasonNumber }?.monitored == true) return true

        val seasons = JSONArray()
        val seen = HashSet<Int>()
        for (s in series.seasons) {
            seen += s.seasonNumber
            seasons.put(JSONObject().put("seasonNumber", s.seasonNumber).put("monitored", s.seasonNumber == seasonNumber))
        }
        if (seasonNumber !in seen) {
            seasons.put(JSONObject().put("seasonNumber", seasonNumber).put("monitored", true))
        }
        val body = JSONObject().put("seriesId", seriesId).put("seasons", seasons)
        return when (val r = http.put("/api/v3/series/$seriesId", body.toString())) {
            is AcquisitionHttp.Result.Ok -> true

            is AcquisitionHttp.Result.Err -> false
        }
    }

    private suspend fun episodesOf(seriesId: Int): List<AcquisitionMediaWire.EpisodeRow>? {
        val read = when (val r = http.get("/api/v3/episode?seriesId=$seriesId")) {
            is AcquisitionHttp.Result.Err -> return null
            is AcquisitionHttp.Result.Ok -> r.body
        }
        return try {
            AcquisitionMediaWire.parseEpisodes(read)
        } catch (e: AcquisitionMediaWire.Malformed) {
            null
        }
    }

    private suspend fun seriesById(id: Int): AcquisitionMediaWire.SeriesRow? =
        seriesList()?.firstOrNull { it.id == id }

    private suspend fun reconcileAdd(request: AcquisitionRequest): Int? {
        val list = seriesList() ?: return null
        val tvdb = request.externalIds.tvdbId
        val tmdb = request.externalIds.tmdbId
        return list.firstOrNull { row ->
            (tvdb != null && row.tvdbId == tvdb.toInt()) ||
                (tmdb != null && row.tmdbId == tmdb.toInt())
        }?.id
    }

    private suspend fun seriesList(): List<AcquisitionMediaWire.SeriesRow>? {
        val read = when (val r = http.get("/api/v3/series")) {
            is AcquisitionHttp.Result.Err -> return null
            is AcquisitionHttp.Result.Ok -> r.body
        }
        return try {
            AcquisitionMediaWire.parseSeries(read)
        } catch (e: AcquisitionMediaWire.Malformed) {
            null
        }
    }

    private suspend fun searchReconciledFor(request: AcquisitionRequest, seriesId: Int, issuedAt: Long): Boolean =
        when (request.kind) {
            AcquisitionKind.SHOW -> searchReconciled("SeriesSearch", seriesId = seriesId, issuedAt = issuedAt)
            AcquisitionKind.SEASON -> {
                val episodes = episodesOf(seriesId) ?: return false
                val ids = releasedMissingSeasonTargets(episodes, request.seasonNumber!!)
                ids.isNotEmpty() && searchReconciled("EpisodeSearch", episodeIds = ids, issuedAt = issuedAt)
            }
            AcquisitionKind.EPISODE -> {
                val episodes = episodesOf(seriesId) ?: return false
                val ep = episodes.firstOrNull { it.seasonNumber == request.seasonNumber && it.episodeNumber == request.episodeNumber }
                    ?: return false
                searchReconciled("EpisodeSearch", episodeIds = listOf(ep.id), issuedAt = issuedAt)
            }
            AcquisitionKind.MOVIE -> false
        }

    private suspend fun searchReconciled(
        name: String,
        seriesId: Int? = null,
        episodeIds: List<Int> = emptyList(),
        issuedAt: Long,
    ): Boolean {
        val read = when (val r = http.get("/api/v3/command")) {
            is AcquisitionHttp.Result.Err -> return false
            is AcquisitionHttp.Result.Ok -> r.body
        }
        val commands = try {
            AcquisitionMediaWire.parseCommands(read)
        } catch (e: AcquisitionMediaWire.Malformed) {
            return false
        }
        return commands.any { c ->
            c.name == name &&
                c.status.lowercase() !in FAILED_STATUSES &&
                (c.queuedAt == null || c.queuedAt.toEpochMilli() >= issuedAt - COMMAND_SLACK_MS) &&
                (seriesId == null || c.seriesId == seriesId) &&
                (episodeIds.isEmpty() || episodeIds.any { it in c.episodeIds })
        }
    }

    private fun releasedMissingSeasonTargets(
        episodes: List<AcquisitionMediaWire.EpisodeRow>,
        seasonNumber: Int,
    ): List<Int> {
        val now = Instant.ofEpochMilli(nowMs())
        return episodes.asSequence()
            .filter { it.seasonNumber == seasonNumber }
            .filter { !it.hasFile }
            .filter { it.airDateUtc != null && !it.airDateUtc.isAfter(now) }
            .map { it.id }
            .toList()
    }

    private fun seriesTerm(request: AcquisitionRequest): String? {
        request.externalIds.tvdbId?.let { return "tvdb:$it" }
        request.externalIds.tmdbId?.let { return "tmdb:$it" }
        request.externalIds.imdbId?.let { return "imdb:$it" }
        return null
    }

    private fun ref(request: AcquisitionRequest, serverId: Int, episodeId: Int? = null) = AcquisitionRef(
        serviceId = serviceId,
        serviceInstance = serviceInstance,
        kind = request.kind,
        titleId = request.titleId.value.toString(),
        serverId = serverId,
        seasonNumber = request.seasonNumber,
        episodeNumber = request.episodeNumber,
    )

    private fun unsupported(request: AcquisitionRequest, reason: String) = outcome(request, AcquisitionStatus.UNSUPPORTED, null, reason = reason)
    private fun unavailable(request: AcquisitionRequest, reason: String) = outcome(request, AcquisitionStatus.UNAVAILABLE, null, reason = reason)
    private fun failed(request: AcquisitionRequest, r: AcquisitionHttp.Result.Err) = outcome(request, AcquisitionStatus.FAILED, null, reason = r.message)
    private fun failed(request: AcquisitionRequest, kind: AcquisitionFailureKind, reason: String) = outcome(request, AcquisitionStatus.FAILED, null, reason = reason)

    private fun outcome(
        request: AcquisitionRequest,
        status: AcquisitionStatus,
        ref: AcquisitionRef?,
        titleAdded: Boolean = false,
        alreadyManaged: Boolean = false,
        monitored: Boolean = false,
        searchSubmitted: Boolean = false,
        searchPending: Boolean = false,
        reason: String? = null,
    ) = AcquisitionOutcome(
        serviceId = serviceId,
        serviceInstance = serviceInstance,
        status = status,
        ref = ref,
        titleAdded = titleAdded,
        alreadyManaged = alreadyManaged,
        monitored = monitored,
        searchSubmitted = searchSubmitted,
        searchPending = searchPending,
        reason = reason,
    )

    private companion object {
        val FAILED_STATUSES = setOf("failed", "aborted", "cancelled")
        const val COMMAND_SLACK_MS = 120_000L

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
