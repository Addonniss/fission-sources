package dev.infusion.core.database.watch

import dev.infusion.core.model.EditionId
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.watch.WatchKey
import java.util.Base64

object WatchKeyCodec {

    const val MOVIE_PREFIX = "T:"

    const val EPISODE_PREFIX = "S:"

    private const val EDITION_SEP = "#E:"
    private const val NUMBERED_SEP = "#N:"
    private const val ALIAS_SEP = "#A:"

    fun encode(key: WatchKey): String = when {
        key.isEpisode -> encodeEpisode(key)
        else -> encodeMovie(key)
    }

    private fun encodeMovie(key: WatchKey): String {
        val title = key.titleId!!.value.toString()
        val edition = key.editionId?.value?.toString()
        return if (edition == null) {
            "$MOVIE_PREFIX$title"
        } else {
            "$MOVIE_PREFIX$title$EDITION_SEP$edition"
        }
    }

    private fun encodeEpisode(key: WatchKey): String {
        val series = key.seriesId!!.value.toString()
        val alias = key.alternateOrderAlias
        return if (alias == null) {
            "$EPISODE_PREFIX$series$NUMBERED_SEP${key.seasonNumber}:${key.episodeNumber}"
        } else {
            val auth = b64(alias.authority)
            val value = b64(alias.value)
            "$EPISODE_PREFIX$series$ALIAS_SEP$auth:$value"
        }
    }

    fun decode(raw: String): WatchKey {
        require(raw.isNotBlank()) { "watch key must not be blank" }
        return when {
            raw.startsWith(MOVIE_PREFIX) -> decodeMovie(raw)
            raw.startsWith(EPISODE_PREFIX) -> decodeEpisode(raw)
            else -> throw IllegalArgumentException("unknown watch key encoding: $raw")
        }
    }

    private fun decodeMovie(raw: String): WatchKey {
        val body = raw.removePrefix(MOVIE_PREFIX)
        val editionPart = body.substringAfter(EDITION_SEP, missingDelimiterValue = "")
        val titlePart = if (editionPart.isEmpty()) body else body.substringBefore(EDITION_SEP)
        val titleId = TitleId.fromString(titlePart)
        val editionId = if (editionPart.isEmpty()) null else EditionId.fromString(editionPart)
        return WatchKey.movie(titleId, editionId)
    }

    private fun decodeEpisode(raw: String): WatchKey {
        val body = raw.removePrefix(EPISODE_PREFIX)
        val seriesId = TitleId.fromString(body.substringBefore('#'))
        return when {
            NUMBERED_SEP in body -> {
                val nums = body.substringAfter(NUMBERED_SEP)
                val season = nums.substringBefore(':').toInt()
                val episode = nums.substringAfter(':').toInt()
                WatchKey.episode(seriesId, season, episode)
            }
            ALIAS_SEP in body -> {
                val parts = body.substringAfter(ALIAS_SEP).split(':')
                require(parts.size == 2) { "malformed alias watch key: $raw" }
                val alias = TypedExternalId(unb64(parts[0]), unb64(parts[1]))
                WatchKey.episodeByAlternateOrder(seriesId, alias)
            }
            else -> throw IllegalArgumentException("unknown episode watch key encoding: $raw")
        }
    }

    private fun b64(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun unb64(s: String): String =
        String(Base64.getUrlDecoder().decode(s), Charsets.UTF_8)
}
