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
import dev.infusion.core.network.OkHttpHardenedClient
import java.io.Closeable
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

class RadarrAcquisitionAdapter internal constructor(
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
        kinds = setOf(AcquisitionKind.MOVIE),
    )

    override fun connected(): Boolean = connection.url.isNotBlank()

    override suspend fun request(request: AcquisitionRequest): AcquisitionOutcome {
        if (request.kind != AcquisitionKind.MOVIE) return unsupported(request, "Radarr only handles movies")
        val term = movieTerm(request) ?: return unavailable(request, "no proven movie identifier (tmdb/imdb)")
        val issuedAt = nowMs()

        val lookup = when (val r = http.get("/api/v3/movie/lookup?term=${encode(term)}")) {
            is AcquisitionHttp.Result.Err -> return failed(request, r)
            is AcquisitionHttp.Result.Ok -> r.body
        }
        val rows = try {
            AcquisitionMediaWire.parseLookupMovies(lookup)
        } catch (e: AcquisitionMediaWire.Malformed) {
            return failed(request, AcquisitionFailureKind.MALFORMED, "invalid lookup result")
        }
        val row = rows.firstOrNull() ?: return failed(request, AcquisitionFailureKind.NOT_FOUND, "movie not found on Radarr")
        val tmdbId = row.tmdbId ?: return failed(request, AcquisitionFailureKind.MALFORMED, "invalid lookup result")

        val existingId = row.id?.takeIf { it > 0 }
        if (existingId != null) {

            val ref = ref(request, existingId)

            val search = submitCommand(
                "MoviesSearch",
                JSONObject().put("movieIds", JSONArray().put(existingId)),
                issuedAt,
            )
            return when (search) {
                is SubmitResult.Submitted -> outcome(
                    request, AcquisitionStatus.ALREADY_MANAGED, ref,
                    titleAdded = false, alreadyManaged = true, monitored = true, searchSubmitted = true,
                    reason = "already managed — search started",
                )
                is SubmitResult.Ambiguous -> {
                    if (searchReconciled(request, existingId, issuedAt)) {
                        outcome(
                            request, AcquisitionStatus.ALREADY_MANAGED, ref,
                            alreadyManaged = true, monitored = true, searchSubmitted = true,
                            reason = "already managed — search reconciled",
                        )
                    } else {
                        outcome(
                            request, AcquisitionStatus.UNKNOWN, ref,
                            alreadyManaged = true, monitored = true,
                            reason = "search outcome unknown — not repeated",
                        )
                    }
                }
                is SubmitResult.Failed -> outcome(
                    request, AcquisitionStatus.PARTIAL, ref,
                    alreadyManaged = true, monitored = true,
                    reason = search.reason,
                )
            }
        }

        val payload = JSONObject(row.raw.toString()).apply {
            put("title", row.title.ifEmpty { request.title.orEmpty() })
            put("qualityProfileId", connection.qualityProfileId)
            put("rootFolderPath", connection.rootFolderPath)
            put("tmdbId", tmdbId)
            put("monitored", true)
            put("addOptions", JSONObject().put("searchForMovie", true))
        }
        when (val add = http.post("/api/v3/movie", payload.toString())) {
            is AcquisitionHttp.Result.Ok -> {
                val createdId = AcquisitionMediaWire.parseCreatedId(add.body) ?: return outcome(
                    request, AcquisitionStatus.PARTIAL, null,
                    titleAdded = true,
                    reason = "added, but the server returned no id to track the search",
                )
                return outcome(
                    request, AcquisitionStatus.ACCEPTED, ref(request, createdId),
                    titleAdded = true, monitored = true, searchSubmitted = true,
                    reason = "added — search started",
                )
            }
            is AcquisitionHttp.Result.Err -> {
                if (!isAmbiguous(add.kind)) return failed(request, add)

                val reconciled = reconcileAdd(request)
                if (reconciled == null) {
                    return outcome(request, AcquisitionStatus.UNKNOWN, null, reason = "add outcome unknown — not repeated")
                }
                val searchOk = searchReconciled(request, reconciled, issuedAt)
                return outcome(
                    request,
                    if (searchOk) AcquisitionStatus.ACCEPTED else AcquisitionStatus.PARTIAL,
                    ref(request, reconciled),
                    titleAdded = true, monitored = true, searchSubmitted = searchOk,
                    reason = if (searchOk) "add reconciled — search confirmed" else "add reconciled; search evidence unknown",
                )
            }
        }
    }

    override suspend fun reconcile(
        request: AcquisitionRequest,
        operation: AcquisitionOperation,
        issuedAtMs: Long,
    ): AcquisitionOutcome {
        if (request.kind != AcquisitionKind.MOVIE) return unsupported(request, "Radarr only handles movies")
        movieTerm(request) ?: return unavailable(request, "no proven movie identifier (tmdb/imdb)")
        return when (operation) {
            AcquisitionOperation.ADD_TITLE -> {
                val id = reconcileAdd(request)
                if (id == null) {
                    outcome(request, AcquisitionStatus.UNKNOWN, null, reason = "title add could not be reconciled")
                } else {
                    outcome(request, AcquisitionStatus.ACCEPTED, ref(request, id), titleAdded = true, monitored = true)
                }
            }
            AcquisitionOperation.SEARCH -> {
                val id = reconcileAdd(request)
                if (id != null && searchReconciled(request, id, issuedAtMs)) {
                    outcome(request, AcquisitionStatus.ACCEPTED, ref(request, id), searchSubmitted = true)
                } else {
                    outcome(request, AcquisitionStatus.UNKNOWN, id?.let { ref(request, it) }, reason = "no matching search command")
                }
            }
            AcquisitionOperation.MONITOR ->
                outcome(request, AcquisitionStatus.UNSUPPORTED, null, reason = "Radarr has no separate monitor write")
        }
    }

    override suspend fun observe(refs: List<AcquisitionRef>): List<AcquisitionObservation> {
        val movies = refs.filter { it.kind == AcquisitionKind.MOVIE }
        if (movies.isEmpty()) return emptyList()
        return movies.map { ref -> observeMovie(ref) }
    }

    private suspend fun observeMovie(ref: AcquisitionRef): AcquisitionObservation =
        when (val r = http.get("/api/v3/movie/${ref.serverId}")) {
            is AcquisitionHttp.Result.Err -> {
                if (r.kind == AcquisitionFailureKind.NOT_FOUND) {

                    AcquisitionObservation(ref, known = true, managed = false, filePresent = false, reason = r.message)
                } else {
                    AcquisitionObservation(ref, known = false, managed = false, filePresent = false, reason = r.message)
                }
            }
            is AcquisitionHttp.Result.Ok -> {
                val row = try {
                    AcquisitionMediaWire.parseMovie(r.body)
                } catch (e: AcquisitionMediaWire.Malformed) {
                    null
                }
                if (row == null) {
                    AcquisitionObservation(ref, known = false, managed = false, filePresent = false, reason = "malformed movie")
                } else {
                    AcquisitionObservation(ref, known = true, managed = true, filePresent = row.hasFile)
                }
            }
        }

    override fun close() = http.close()

    private sealed interface SubmitResult {
        data object Submitted : SubmitResult
        data object Ambiguous : SubmitResult
        data class Failed(val reason: String) : SubmitResult
    }

    private suspend fun submitCommand(name: String, body: JSONObject, issuedAt: Long): SubmitResult {
        val payload = JSONObject().put("name", name).apply { body.keys().forEach { put(it, body.get(it)) } }
        return when (val r = http.post("/api/v3/command", payload.toString())) {
            is AcquisitionHttp.Result.Ok -> SubmitResult.Submitted
            is AcquisitionHttp.Result.Err ->
                if (isAmbiguous(r.kind)) SubmitResult.Ambiguous else SubmitResult.Failed("search failed: ${r.message}")
        }
    }

    private suspend fun searchReconciled(request: AcquisitionRequest, movieId: Int, issuedAt: Long): Boolean {
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
            c.name == "MoviesSearch" &&
                movieId in c.movieIds &&
                c.status.lowercase() !in FAILED_STATUSES &&
                (c.queuedAt == null || c.queuedAt.toEpochMilli() >= issuedAt - COMMAND_SLACK_MS)
        }
    }

    private suspend fun reconcileAdd(request: AcquisitionRequest): Int? {
        val term = movieTerm(request) ?: return null
        val read = when (val r = http.get("/api/v3/movie/lookup?term=${encode(term)}")) {
            is AcquisitionHttp.Result.Err -> return null
            is AcquisitionHttp.Result.Ok -> r.body
        }
        val rows = try {
            AcquisitionMediaWire.parseLookupMovies(read)
        } catch (e: AcquisitionMediaWire.Malformed) {
            return null
        }

        val wantTmdb = request.externalIds.tmdbId?.toInt()
        return rows.firstOrNull { row ->
            val id = row.id ?: return@firstOrNull false
            id > 0 && (wantTmdb == null || row.tmdbId == wantTmdb)
        }?.id
    }

    private fun movieTerm(request: AcquisitionRequest): String? {
        request.externalIds.tmdbId?.let { return "tmdb:$it" }
        request.externalIds.imdbId?.let { return "imdb:$it" }
        return null
    }

    private fun ref(request: AcquisitionRequest, serverId: Int) = AcquisitionRef(
        serviceId = serviceId,
        serviceInstance = serviceInstance,
        kind = AcquisitionKind.MOVIE,
        titleId = request.titleId.value.toString(),
        serverId = serverId,
    )

    private fun unsupported(request: AcquisitionRequest, reason: String) =
        outcome(request, AcquisitionStatus.UNSUPPORTED, null, reason = reason)

    private fun unavailable(request: AcquisitionRequest, reason: String) =
        outcome(request, AcquisitionStatus.UNAVAILABLE, null, reason = reason)

    private fun failed(request: AcquisitionRequest, r: AcquisitionHttp.Result.Err) =
        outcome(request, AcquisitionStatus.FAILED, null, reason = r.message)

    private fun failed(request: AcquisitionRequest, kind: AcquisitionFailureKind, reason: String) =
        outcome(request, AcquisitionStatus.FAILED, null, reason = reason)

    private fun outcome(
        request: AcquisitionRequest,
        status: AcquisitionStatus,
        ref: AcquisitionRef?,
        titleAdded: Boolean = false,
        alreadyManaged: Boolean = false,
        monitored: Boolean = false,
        searchSubmitted: Boolean = false,
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
        reason = reason,
    )

    private companion object {
        val FAILED_STATUSES = setOf("failed", "aborted", "cancelled")
        const val COMMAND_SLACK_MS = 120_000L

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}

internal fun isAmbiguous(kind: AcquisitionFailureKind): Boolean = when (kind) {
    AcquisitionFailureKind.TIMEOUT,
    AcquisitionFailureKind.NETWORK,
    AcquisitionFailureKind.SERVER_ERROR,
    AcquisitionFailureKind.UNKNOWN,
    -> true
    else -> false
}
