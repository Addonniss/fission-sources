package dev.infusion.tracking.wetrakr

import dev.infusion.core.domain.ratings.PersonalRatingCapabilities
import dev.infusion.core.domain.ratings.PersonalRatingEntry
import dev.infusion.core.domain.ratings.PersonalRatingLibraryRequest
import dev.infusion.core.domain.ratings.PersonalRatingPage
import dev.infusion.core.domain.ratings.PersonalRatingPort
import dev.infusion.core.domain.ratings.PersonalRatingRef
import dev.infusion.core.domain.ratings.PersonalRatingWriteResult
import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.model.sync.PersonalRatingKind
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

class WeTrakrPersonalRatings(
    private val client: WeTrakrHttpClient,
    private val auth: WeTrakrAuthRepository,

    private val trace: ((String) -> Unit)? = null,
) : PersonalRatingPort {

    override val providerId: String = WeTrakrAuthRepository.WETRAKR_PROVIDER_ID

    override fun capabilities(): PersonalRatingCapabilities = PersonalRatingCapabilities(
        read = true,
        writeKinds = setOf(PersonalRatingKind.MOVIE, PersonalRatingKind.EPISODE),
        scaleMin = 1,
        scaleMax = 10,
        sideEffectNote = EPISODE_AUTO_RATING_NOTE,
    )

    override fun accountConnected(): Boolean = auth.isConnected()

    override suspend fun resolveAccountIdentity(): String? {
        if (!auth.isConnected()) return null
        auth.accountId()?.let { return it }
        val outcome = send(
            WeTrakrRequest(
                method = WeTrakrMethod.GET,
                path = WeTrakrHttpClient.ACCOUNT_SETTINGS_PATH,
                authenticated = true,
            ),
        )
        val success = outcome as? WeTrakrOutcome.Success ?: return null
        val identity = WeTrakrAccountIdentity.parse(success.response.body) ?: return null
        auth.recordAccountIdentity(identity.accountId)
        return identity.accountId
    }

    override suspend fun readLibrary(
        request: PersonalRatingLibraryRequest,
    ): TrackingInboundOutcome<PersonalRatingPage> {
        val limit = request.limit.coerceIn(1, PROVIDER_MAX_LIMIT)
        val walk = walkOf(request)
        val entries = ArrayList<PersonalRatingEntry>()
        var malformed = 0
        var unrepresentable = 0

        val owed = LinkedHashSet(walk.owedTargets)
        var unusableSeen = walk.unusableSeen
        var unrepresentableSeen = walk.unrepresentableSeen
        val remaining = LinkedHashMap<String, Attempt>()
        for ((target, attempt) in walk.plans) {
            when (val outcome = send(readRequest(target, limit, attempt.after))) {
                is WeTrakrOutcome.Success -> {
                    val parsed = parseRatings(outcome.response.body, target)
                        ?: return TrackingInboundOutcome.Rejected(SHAPE_CHANGED)
                    entries += parsed.entries
                    malformed += parsed.malformed
                    unrepresentable += parsed.unrepresentable

                    if (parsed.malformed > 0) unusableSeen = true
                    if (parsed.unrepresentable > 0) unrepresentableSeen = true

                    owed -= target
                    val nextCursor = nextCursorOf(outcome.response.headers)
                    trace?.invoke(
                        "wetrakr ratings_read target=$target " +
                            "rows=${parsed.rows} adopted=${parsed.entries.size} " +
                            "fractional=${parsed.fractional} malformed=${parsed.malformed} " +
                            "unrepresentable=${parsed.unrepresentable} " +
                            "missing_show_ids=${parsed.missingShowIds} " +
                            "retry=${attempt is Attempt.Retry} more=${nextCursor != null}",
                    )
                    if (nextCursor != null) remaining[target] = Attempt.More(nextCursor)
                }
                is WeTrakrOutcome.Unauthorized -> return TrackingInboundOutcome.Unauthorized(outcome.reason)

                is WeTrakrOutcome.Retry, is WeTrakrOutcome.Rejected -> {
                    trace?.invoke(
                        "wetrakr ratings_read target=$target class=" +
                            outcome::class.simpleName,
                    )
                    owed += target

                    if (attempt !is Attempt.Retry) remaining[target] = Attempt.Retry(attempt.after)
                }
            }
        }

        val next = if (remaining.isEmpty()) {
            null
        } else {
            encodeWalk(remaining, owed, unusableSeen, unrepresentableSeen)
        }

        val truncating = next == null &&
            (owed.isNotEmpty() || unusableSeen || unrepresentableSeen)
        trace?.invoke(
            "wetrakr ratings_read_end more=${next != null} truncating=$truncating " +
                "owed=${owed.joinToString(",").ifEmpty { "NONE" }} " +
                "unusable=$unusableSeen unrepresentable=$unrepresentableSeen",
        )
        return TrackingInboundOutcome.Ok(
            PersonalRatingPage(
                entries = entries,
                next = next,
                malformed = malformed,
                truncating = truncating,
            ),
        )
    }

    override suspend fun write(ref: PersonalRatingRef, score: Int): PersonalRatingWriteResult {
        if (score !in SCALE) return PersonalRatingWriteResult.Unsupported(SCORE_OUT_OF_RANGE)
        val body = when (ref.kind) {
            PersonalRatingKind.MOVIE -> movieBody(ref, score)
            PersonalRatingKind.EPISODE -> {
                val season = ref.seasonNumber
                val episode = ref.episodeNumber
                if (season == null || season < 0 || episode == null || episode < 0) {
                    return PersonalRatingWriteResult.Unsupported(EPISODE_NEEDS_COORDINATES)
                }
                episodeBody(ref, season, episode, score)
            }
        }
        return when (
            val outcome = send(
                WeTrakrRequest(
                    method = WeTrakrMethod.POST,
                    path = RATINGS_PATH,
                    json = body,
                    authenticated = true,
                ),
            )
        ) {
            is WeTrakrOutcome.Success -> classifyWrite(outcome.response.body, ref.kind)
            is WeTrakrOutcome.Retry -> PersonalRatingWriteResult.Retryable(outcome.reason)
            is WeTrakrOutcome.Rejected -> PersonalRatingWriteResult.Rejected(outcome.reason)
            is WeTrakrOutcome.Unauthorized -> PersonalRatingWriteResult.Unauthorized(outcome.reason)
        }
    }

    private fun classifyWrite(body: String, kind: PersonalRatingKind): PersonalRatingWriteResult {
        val summary = writeSummary(body) ?: return PersonalRatingWriteResult.Rejected(SHAPE_CHANGED)
        trace?.invoke(
            "wetrakr ratings_write kind=${kind.name.lowercase()} added=${summary.addedTotal} " +
                "not_found=${summary.notFound} errored=${summary.errored} " +
                "codes=${summary.errorCodes.joinToString(",").ifEmpty { "NONE" }}",
        )
        return when {
            summary.errored > 0 -> PersonalRatingWriteResult.Rejected(refused(summary.errorCodes))
            summary.notFound > 0 -> PersonalRatingWriteResult.Rejected(NOT_FOUND)
            summary.addedTotal > 0 -> PersonalRatingWriteResult.Delivered

            else -> PersonalRatingWriteResult.Rejected(SHAPE_CHANGED)
        }
    }

    private fun refused(codes: List<String>): String =
        "$REFUSED:${codes.joinToString(",").ifEmpty { "unspecified" }}"

    private suspend fun send(request: WeTrakrRequest): WeTrakrOutcome {
        val token = readToken { auth.accessToken() }
            ?: return WeTrakrOutcome.Unauthorized(NO_AUTHORIZATION)
        val outcome = client.execute(request, token)
        if (outcome !is WeTrakrOutcome.Unauthorized) return outcome

        val refreshed = readToken { auth.refresh() } ?: return outcome
        return client.execute(request, refreshed)
    }

    private suspend fun readToken(read: suspend () -> String?): String? = try {
        read()?.takeIf { it.isNotBlank() }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        null
    }

    private fun readRequest(target: String, limit: Int, after: String?): WeTrakrRequest = WeTrakrRequest(
        method = WeTrakrMethod.GET,
        path = "$RATINGS_PATH/$target",
        query = buildMap {
            put("limit", limit.toString())
            put("compact", "true")
            after?.let { put(AFTER_KEY, it) }
        },
        authenticated = true,
        limits = WeTrakrHttpClient.READ_LIMITS,
    )

    private fun nextCursorOf(headers: Map<String, String>): String? = headers.entries
        .firstOrNull { it.key.equals(PAGINATION_NEXT_HEADER, ignoreCase = true) }
        ?.value?.trim()?.takeIf { it.isNotEmpty() }

    private fun walkOf(request: PersonalRatingLibraryRequest): Walk {
        val cursor = request.cursor ?: return firstWalk()
        val plans = LinkedHashMap<String, Attempt>()
        val owed = LinkedHashSet<String>()
        var unusable = false
        var unrepresentable = false
        for (part in cursor.split(SEPARATOR)) {
            val key = part.substringBefore(ASSIGNMENT).trim()
            val value = part.substringAfter(ASSIGNMENT, "").trim()
            if (key in TARGETS) {
                plans[key] = attemptOf(value) ?: return firstWalk()
            } else if (key == RISK_KEY) {
                val payload = unhex(value) ?: return firstWalk()
                for (code in payload.split(RISK_SEPARATOR)) {
                    when {
                        code == RISK_UNUSABLE -> unusable = true
                        code == RISK_UNREPRESENTABLE -> unrepresentable = true
                        code.startsWith(RISK_OWED_PREFIX) -> {
                            val target = code.removePrefix(RISK_OWED_PREFIX)
                            if (target !in TARGETS) return firstWalk()
                            owed += target
                        }
                        else -> return firstWalk()
                    }
                }
            } else {
                return firstWalk()
            }
        }
        return Walk(
            plans = plans,
            owedTargets = owed,
            unusableSeen = unusable,
            unrepresentableSeen = unrepresentable,
        )
    }

    private fun attemptOf(value: String): Attempt? = when {
        value == RETRY_MARK -> Attempt.Retry(null)
        value.startsWith(RETRY_MARK) ->
            unhex(value.substring(RETRY_MARK.length))?.let { Attempt.Retry(it) }
        value.startsWith(MORE_MARK) ->
            unhex(value.substring(MORE_MARK.length))?.let { Attempt.More(it) }
        else -> null
    }

    private fun firstWalk(): Walk = Walk(
        plans = LinkedHashMap<String, Attempt>().apply {
            TARGETS.forEach { put(it, Attempt.Start) }
        },
        owedTargets = emptySet(),
        unusableSeen = false,
        unrepresentableSeen = false,
    )

    private fun encodeWalk(
        remaining: Map<String, Attempt>,
        owed: Set<String>,
        unusableSeen: Boolean,
        unrepresentableSeen: Boolean,
    ): String {
        val parts = ArrayList<String>(remaining.size + 1)
        for ((target, attempt) in remaining) {
            when (attempt) {
                is Attempt.More ->
                    parts += "$target$ASSIGNMENT$MORE_MARK${hex(attempt.after)}"
                is Attempt.Retry -> parts += attempt.after
                    ?.let { "$target$ASSIGNMENT$RETRY_MARK${hex(it)}" }
                    ?: "$target$ASSIGNMENT$RETRY_MARK"

                Attempt.Start -> Unit
            }
        }
        val risk = ArrayList<String>(TARGETS.size + 2)
        owed.forEach { risk += "$RISK_OWED_PREFIX$it" }
        if (unusableSeen) risk += RISK_UNUSABLE
        if (unrepresentableSeen) risk += RISK_UNREPRESENTABLE
        if (risk.isNotEmpty()) {
            parts += "$RISK_KEY$ASSIGNMENT${hex(risk.joinToString(RISK_SEPARATOR))}"
        }
        return parts.joinToString(SEPARATOR)
    }

    private fun hex(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size * 2)
        bytes.forEach { byte ->
            val unsigned = byte.toInt() and 0xFF
            out.append(HEX_DIGITS[unsigned shr 4]).append(HEX_DIGITS[unsigned and 0x0F])
        }
        return out.toString()
    }

    private fun unhex(value: String): String? {
        if (value.isEmpty() || value.length % 2 != 0) return null
        if (!value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val bytes = ByteArray(value.length / 2)
        for (index in bytes.indices) {
            val high = Character.digit(value[index * 2], 16)
            val low = Character.digit(value[index * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun parseRatings(body: String, target: String): ParsedRatings? {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return null
        val entries = ArrayList<PersonalRatingEntry>()
        var malformed = 0
        var unrepresentable = 0
        var fractional = 0
        var missingShowIds = 0
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index)
            if (row == null) {
                malformed++
                continue
            }
            when (val parsed = parseRow(row, target)) {
                is Row.Adopted -> {
                    entries += parsed.entry
                    if (parsed.entry.preciseScore != null) fractional++
                }
                Row.Unrepresentable -> unrepresentable++
                Row.MissingShowIds -> missingShowIds++
                Row.Malformed -> malformed++
            }
        }
        return ParsedRatings(
            entries = entries,
            rows = array.length(),
            malformed = malformed + missingShowIds,
            unrepresentable = unrepresentable,
            fractional = fractional,
            missingShowIds = missingShowIds,
        )
    }

    private fun parseRow(row: JSONObject, target: String): Row {
        val expectedKind = if (target == TARGET_EPISODES) {
            PersonalRatingKind.EPISODE
        } else {
            PersonalRatingKind.MOVIE
        }
        val statedType = row.optStringOrNull(TYPE_KEY)?.trim()?.lowercase()
        if (statedType != null && statedType != expectedKind.name.lowercase()) return Row.Malformed
        val exact = statedScore(row) ?: return Row.Malformed

        if (exact < LOWEST_EXPRESSIBLE || exact > HIGHEST_EXPRESSIBLE) return Row.Unrepresentable
        val whole = exact.toInt().takeIf { exact == it.toDouble() }
        val preciseOnly = if (whole == null) exact else null
        return when (expectedKind) {
            PersonalRatingKind.MOVIE -> {
                val ids = row.optJSONObject(IDS_KEY).toExternalIds()
                if (ids.isEmpty) return Row.Malformed
                Row.Adopted(
                    PersonalRatingEntry(
                        kind = PersonalRatingKind.MOVIE,
                        externalIds = ids,
                        score = whole,
                        preciseScore = preciseOnly,
                    ),
                )
            }
            PersonalRatingKind.EPISODE -> {
                val showIds = row.optJSONObject(SHOW_IDS_KEY)
                    ?: row.optJSONObject(SHOW_KEY)?.optJSONObject(IDS_KEY)
                    ?: return Row.MissingShowIds
                val ids = showIds.toExternalIds()
                if (ids.isEmpty) return Row.MissingShowIds
                val season = row.coordinateOrNull(SEASON_NUMBER_KEY)
                val number = row.coordinateOrNull(NUMBER_KEY)
                if (season == null || number == null || number < 1) return Row.Malformed
                Row.Adopted(
                    PersonalRatingEntry(
                        kind = PersonalRatingKind.EPISODE,
                        externalIds = ids,
                        score = whole,
                        seasonNumber = season,
                        episodeNumber = number,
                        preciseScore = preciseOnly,
                    ),
                )
            }
        }
    }

    private fun statedScore(row: JSONObject): Double? =
        row.optDoubleOrNull(RATING_KEY)
            ?: row.optJSONObject(INTERACTIONS_KEY)
                ?.optJSONObject(USER_KEY)
                ?.optJSONObject(RATING_KEY)
                ?.optDoubleOrNull(RATING_KEY)

    private fun writeSummary(body: String): WriteSummary? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val added = root.optJSONObject(ADDED_KEY)
        val notFound = root.optJSONObject(NOT_FOUND_KEY)
        val errored = root.optJSONObject(ERRORED_KEY)
        if (added == null && notFound == null && errored == null) return null
        return WriteSummary(
            addedTotal = added?.optIntOrNull(TOTAL_KEY) ?: 0,
            notFound = countRows(notFound),
            errored = countRows(errored),
            errorCodes = errorCodes(errored),
        )
    }

    private fun countRows(bucket: JSONObject?): Int {
        if (bucket == null) return 0
        var total = 0
        val keys = bucket.keys()
        while (keys.hasNext()) {
            val array = bucket.optJSONArray(keys.next())
            if (array != null) total += array.length()
        }
        return total
    }

    private fun errorCodes(bucket: JSONObject?): List<String> {
        if (bucket == null) return emptyList()
        val codes = LinkedHashSet<String>()
        val keys = bucket.keys()
        while (keys.hasNext()) {
            val array = bucket.optJSONArray(keys.next()) ?: continue
            for (index in 0 until array.length()) {
                val code = array.optJSONObject(index)?.optStringOrNull(ERROR_KEY)?.trim()
                if (!code.isNullOrEmpty()) codes += code
            }
        }
        return codes.toList()
    }

    private fun movieBody(ref: PersonalRatingRef, score: Int): String {
        val item = JSONObject()
            .put(IDS_KEY, idsObject(ref.externalIds))
            .put(RATING_KEY, score)
        return JSONObject().put(TARGET_MOVIES, JSONArray().put(item)).toString()
    }

    private fun episodeBody(ref: PersonalRatingRef, season: Int, episode: Int, score: Int): String {
        val episodeObject = JSONObject().put(NUMBER_KEY, episode).put(RATING_KEY, score)
        val seasonObject = JSONObject()
            .put(NUMBER_KEY, season)
            .put(EPISODES_KEY, JSONArray().put(episodeObject))
        val showObject = JSONObject()
            .put(IDS_KEY, idsObject(ref.externalIds))
            .put(SEASONS_KEY, JSONArray().put(seasonObject))
        return JSONObject().put(TARGET_SHOWS, JSONArray().put(showObject)).toString()
    }

    private fun idsObject(ids: TrackingExternalIds): JSONObject {
        val out = JSONObject()
        ids.tmdbId?.let { out.put("tmdb", it) }
        ids.imdbId?.let { out.put("imdb", it) }
        ids.tvdbId?.let { out.put("tvdb", it) }
        return out
    }

    private sealed interface Attempt {

        val after: String?

        data object Start : Attempt {
            override val after: String? get() = null
        }

        data class More(override val after: String) : Attempt

        data class Retry(override val after: String?) : Attempt
    }

    private data class Walk(
        val plans: LinkedHashMap<String, Attempt>,
        val owedTargets: Set<String>,
        val unusableSeen: Boolean,
        val unrepresentableSeen: Boolean,
    )

    private sealed interface Row {
        data class Adopted(val entry: PersonalRatingEntry) : Row
        data object Unrepresentable : Row
        data object MissingShowIds : Row
        data object Malformed : Row
    }

    private data class ParsedRatings(
        val entries: List<PersonalRatingEntry>,
        val rows: Int,
        val malformed: Int,
        val unrepresentable: Int,
        val fractional: Int,
        val missingShowIds: Int,
    )

    private data class WriteSummary(
        val addedTotal: Int,
        val notFound: Int,
        val errored: Int,
        val errorCodes: List<String>,
    )

    internal companion object {

        val SCALE: IntRange = 1..10

        private const val LOWEST_EXPRESSIBLE = 1.0
        private const val HIGHEST_EXPRESSIBLE = 10.0

        const val RATINGS_PATH = "/sync/ratings"

        const val TARGET_MOVIES = "movies"
        const val TARGET_EPISODES = "episodes"
        const val TARGET_SHOWS = "shows"

        private val TARGETS = listOf(TARGET_MOVIES, TARGET_EPISODES)

        const val PROVIDER_MAX_LIMIT = 5_000

        private const val SEPARATOR = ";"
        private const val ASSIGNMENT = "="
        private const val AFTER_KEY = "after"
        private const val PAGINATION_NEXT_HEADER = "X-Pagination-Next"

        private const val MORE_MARK = "M"
        private const val RETRY_MARK = "R"

        private const val RISK_KEY = "risk"
        private const val RISK_SEPARATOR = ","
        private const val RISK_UNUSABLE = "u"
        private const val RISK_UNREPRESENTABLE = "x"
        private const val RISK_OWED_PREFIX = "o:"

        private const val HEX_DIGITS = "0123456789ABCDEF"

        private const val TYPE_KEY = "type"
        private const val IDS_KEY = "ids"
        private const val SHOW_IDS_KEY = "show_ids"
        private const val SHOW_KEY = "show"
        private const val SEASON_NUMBER_KEY = "season_number"
        private const val NUMBER_KEY = "number"
        private const val RATING_KEY = "rating"
        private const val INTERACTIONS_KEY = "interactions"
        private const val USER_KEY = "user"
        private const val ADDED_KEY = "added"
        private const val NOT_FOUND_KEY = "notFound"
        private const val ERRORED_KEY = "errored"
        private const val TOTAL_KEY = "total"
        private const val ERROR_KEY = "error"
        private const val SEASONS_KEY = "seasons"
        private const val EPISODES_KEY = "episodes"

        internal const val EPISODE_AUTO_RATING_NOTE =
            "Rating an episode also updates WeTrakr's automatic rating for that season and show."

        internal const val SHAPE_CHANGED = "wetrakr_ratings_response_shape_changed"
        internal const val NO_AUTHORIZATION = "no valid WeTrakr authorization for this profile"
        internal const val SCORE_OUT_OF_RANGE = "wetrakr_personal_rating_offered_scale_is_1_to_10"
        internal const val EPISODE_NEEDS_COORDINATES =
            "wetrakr_episode_rating_needs_season_and_episode"
        internal const val NOT_FOUND = "wetrakr_catalog_holds_no_such_title"
        internal const val REFUSED = "wetrakr_refused"
    }
}

private fun JSONObject?.toExternalIds(): TrackingExternalIds {
    if (this == null) return TrackingExternalIds()
    return TrackingExternalIds(
        tmdbId = numericId("tmdb"),
        imdbId = optStringOrNull("imdb")?.trim()?.takeIf { it.isNotEmpty() },
        tvdbId = numericId("tvdb"),
    )
}

private fun JSONObject.coordinateOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toInt().takeIf { it >= 0 }
        is String -> value.trim().toIntOrNull()?.takeIf { it >= 0 }
        else -> null
    }
}

private fun JSONObject.numericId(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toLong().takeIf { it > 0L }
        is String -> value.trim().toLongOrNull()?.takeIf { it > 0L }
        else -> null
    }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toDouble().takeIf { it.isFinite() }
        is String -> value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }
}
