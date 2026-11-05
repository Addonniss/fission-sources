package dev.infusion.source.acquisition

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

internal object AcquisitionMediaWire {

    class Malformed(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class LookupMovie(
        val id: Int?,
        val tmdbId: Int?,
        val title: String,
        val year: Int?,
        val raw: JSONObject,
    )

    data class LookupMovies(val rows: List<LookupMovie>)

    fun parseLookupMovies(body: String): List<LookupMovie> {
        val arr = arrayOf(body, "movie/lookup")
        val out = ArrayList<LookupMovie>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw Malformed("movie/lookup[$i] is not an object")
            out += LookupMovie(
                id = o.optIntOrNull("id"),
                tmdbId = o.optIntOrNull("tmdbId"),
                title = o.optString("title", "").trim(),
                year = o.optIntOrNull("year"),
                raw = o,
            )
        }
        return out
    }

    data class MovieRow(
        val id: Int,
        val tmdbId: Int?,
        val hasFile: Boolean,
        val monitored: Boolean,
    )

    fun parseMovies(body: String): List<MovieRow> {
        val arr = arrayOf(body, "movie")
        val out = ArrayList<MovieRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw Malformed("movie[$i] is not an object")
            out += movieRow(o) ?: continue
        }
        return out
    }

    fun parseMovie(body: String): MovieRow? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: throw Malformed("movie is not an object")
        return movieRow(o)
    }

    private fun movieRow(o: JSONObject): MovieRow? {
        val id = o.optIntOrNull("id") ?: return null
        return MovieRow(
            id = id,
            tmdbId = o.optIntOrNull("tmdbId"),
            hasFile = o.optBoolean("hasFile", false),
            monitored = o.optBoolean("monitored", false),
        )
    }

    data class LookupSeries(
        val id: Int?,
        val tvdbId: Int?,
        val tmdbId: Int?,
        val title: String,
        val year: Int?,
        val genres: List<String>,
        val seasons: List<SeasonRow>,
        val raw: JSONObject,
    )

    fun parseLookupSeries(body: String): List<LookupSeries> {
        val arr = arrayOf(body, "series/lookup")
        val out = ArrayList<LookupSeries>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw Malformed("series/lookup[$i] is not an object")
            out += LookupSeries(
                id = o.optIntOrNull("id"),
                tvdbId = o.optIntOrNull("tvdbId"),
                tmdbId = o.optIntOrNull("tmdbId"),
                title = o.optString("title", "").trim(),
                year = o.optIntOrNull("year"),
                genres = o.optJSONArray("genres")?.toStringList().orEmpty(),
                seasons = parseSeasons(o.optJSONArray("seasons")),
                raw = o,
            )
        }
        return out
    }

    data class SeriesRow(
        val id: Int,
        val tvdbId: Int?,
        val tmdbId: Int?,
        val seasons: List<SeasonRow>,
    )

    fun parseSeries(body: String): List<SeriesRow> {
        val arr = arrayOf(body, "series")
        val out = ArrayList<SeriesRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw Malformed("series[$i] is not an object")
            val id = o.optIntOrNull("id") ?: continue
            out += SeriesRow(
                id = id,
                tvdbId = o.optIntOrNull("tvdbId"),
                tmdbId = o.optIntOrNull("tmdbId"),
                seasons = parseSeasons(o.optJSONArray("seasons")),
            )
        }
        return out
    }

    data class SeasonRow(val seasonNumber: Int, val monitored: Boolean)

    private fun parseSeasons(arr: JSONArray?): List<SeasonRow> {
        if (arr == null) return emptyList()
        val out = ArrayList<SeasonRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optIntOrNull("seasonNumber") ?: continue
            out += SeasonRow(n, o.optBoolean("monitored", false))
        }
        return out
    }

    data class EpisodeRow(
        val id: Int,
        val seasonNumber: Int,
        val episodeNumber: Int,
        val monitored: Boolean,
        val hasFile: Boolean,

        val airDateUtc: Instant?,
    )

    fun parseEpisodes(body: String): List<EpisodeRow> {
        val arr = arrayOf(body, "episode")
        val out = ArrayList<EpisodeRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw Malformed("episode[$i] is not an object")
            val id = o.optIntOrNull("id") ?: continue
            val season = o.optIntOrNull("seasonNumber") ?: continue
            val ep = o.optIntOrNull("episodeNumber") ?: continue
            out += EpisodeRow(
                id = id,
                seasonNumber = season,
                episodeNumber = ep,
                monitored = o.optBoolean("monitored", false),
                hasFile = o.optBoolean("hasFile", false),
                airDateUtc = parseInstant(o.optString("airDateUtc", "")),
            )
        }
        return out
    }

    data class CommandRow(
        val id: Int,
        val name: String,
        val status: String,
        val queuedAt: Instant?,
        val movieIds: Set<Int>,
        val seriesId: Int?,
        val episodeIds: Set<Int>,
    )

    fun parseCommands(body: String): List<CommandRow> {
        val arr = arrayOf(body, "command")
        val out = ArrayList<CommandRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val bodyObj = o.optJSONObject("body")
            out += CommandRow(
                id = o.optIntOrNull("id") ?: continue,
                name = o.optString("name", "").ifEmpty { o.optString("commandName", "") },
                status = o.optString("status", ""),
                queuedAt = parseInstant(o.optString("queued", "")),
                movieIds = bodyObj?.optJSONArray("movieIds")?.toIntSet().orEmpty(),
                seriesId = bodyObj?.optIntOrNull("seriesId"),
                episodeIds = bodyObj?.optJSONArray("episodeIds")?.toIntSet().orEmpty(),
            )
        }
        return out
    }

    fun parseCreatedId(body: String): Int? {
        val text = body.trim()
        if (!text.startsWith("{")) return null
        return try {
            JSONObject(text).optIntOrNull("id")
        } catch (e: JSONException) {
            null
        }
    }

    private fun arrayOf(body: String, label: String): JSONArray {
        val text = body.trim()
        if (text.isEmpty()) throw Malformed("empty $label body")
        if (!text.startsWith("[")) throw Malformed("expected a JSON array for $label")
        return try {
            JSONArray(text)
        } catch (e: JSONException) {
            throw Malformed("malformed JSON array for $label", e)
        }
    }

    private fun JSONArray.toIntSet(): Set<Int> {
        val out = LinkedHashSet<Int>(length())
        for (i in 0 until length()) {
            val n = optInt(i, Int.MIN_VALUE)
            if (n != Int.MIN_VALUE) out += n
        }
        return out
    }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) optString(i, "").takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null

    fun parseInstant(raw: String): Instant? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return runCatching { OffsetDateTime.parse(text).toInstant() }
            .recoverCatching { Instant.parse(text) }
            .getOrNull()
    }
}
