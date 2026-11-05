package dev.infusion.core.model.sync

import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.watch.WatchKey
import java.nio.charset.StandardCharsets
import java.time.Instant

object OutboxPayloadCodec {

    const val VERSION: String = "wlp1"

    private const val REC = '\n'
    private const val FLD = '\u0001'
    private const val LST = '\u0002'
    private const val SUB = '\u0003'

    private const val MARKER_WATCHLIST = "W"
    private const val MARKER_RESUME = "R"
    private const val MARKER_RATING = "P"
    private const val MARKER_POSITION = "U"

    private const val MAX_FIELD = 2048
    private const val MAX_ALIASES = 8

    private const val UNRESERVED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-"
    private const val HEX = "0123456789ABCDEF"

    fun encode(payload: OutboxPayload): String = when (payload) {
        is OutboxPayload.WatchAction ->
            throw IllegalArgumentException("a WatchAction rides the column-wise payload, not the blob")
        is OutboxPayload.WatchlistWrite -> VERSION + REC + encodeWatchlistWrite(payload)
        is OutboxPayload.ResumeReset -> VERSION + REC + encodeResumeReset(payload)
        is OutboxPayload.PersonalRating -> VERSION + REC + encodePersonalRating(payload)
        is OutboxPayload.PositionUpdate -> VERSION + REC + encodePositionUpdate(payload)
    }

    fun decode(kind: String, blob: String?): OutboxPayload? {
        if (blob == null) return null
        if (!blob.startsWith(VERSION + REC)) return null
        val body = blob.substring(VERSION.length + 1)
        return when (kind) {
            OutboxWriteKind.WATCHLIST_ADD, OutboxWriteKind.WATCHLIST_REMOVE ->
                decodeWatchlistWrite(body, kind)
            OutboxWriteKind.WATCHLIST_RESUME_RESET -> decodeResumeReset(body)
            OutboxWriteKind.PERSONAL_RATING -> decodePersonalRating(body)
            OutboxWriteKind.POSITION_UPDATE -> decodePositionUpdate(body)
            else -> null
        }
    }

    private fun encodeWatchlistWrite(p: OutboxPayload.WatchlistWrite): String {
        val scalars = listOf(
            MARKER_WATCHLIST,
            p.direction.name,
            p.kind.name,
            p.titleId,
            p.remoteType.name,
            p.remoteRef ?: "",
            p.seasonNumber?.toString() ?: "",
            p.episodeNumber?.toString() ?: "",
            p.title ?: "",
            p.year?.toString() ?: "",
            p.accountRef ?: "",
            p.occurredAt.toEpochMilli().toString(),
        ).joinToString(FLD.toString()) { escape(it) }
        return "$scalars$FLD${encodeAliases(p.aliases)}"
    }

    private fun decodeWatchlistWrite(body: String, kind: String): OutboxPayload.WatchlistWrite? {
        val f = body.split(FLD)
        if (f.size < 13) return null
        if (unescape(f[0]) != MARKER_WATCHLIST) return null
        val direction = enumOrNull<WatchlistWriteDirection>(unescape(f[1])) ?: return null

        val expected = if (direction == WatchlistWriteDirection.ADD) {
            OutboxWriteKind.WATCHLIST_ADD
        } else {
            OutboxWriteKind.WATCHLIST_REMOVE
        }
        if (kind != expected) return null
        val entryKind = enumOrNull<WatchlistMembershipKind>(unescape(f[2])) ?: return null
        val titleId = unescape(f[3])?.takeIf { isTitleId(it) } ?: return null
        val remoteType = enumOrNull<WatchlistRemoteType>(unescape(f[4])) ?: return null
        val remoteRef = unescape(f[5])?.takeIf { it.isNotBlank() }
        val season = f[6].toIntOrNull()
        val episode = f[7].toIntOrNull()
        val title = unescape(f[8])?.takeIf { it.isNotBlank() }
        val year = f[9].toIntOrNull()
        val accountRef = unescape(f[10])?.takeIf { it.isNotBlank() }
        val occurredAt = f[11].toLongOrNull()?.let { Instant.ofEpochMilli(it) } ?: return null
        val aliases = decodeAliases(f[12]) ?: return null
        if (aliases.isEmpty()) return null
        return runCatching {
            OutboxPayload.WatchlistWrite(
                direction = direction,
                kind = entryKind,
                titleId = titleId,
                aliases = aliases,
                remoteType = remoteType,
                remoteRef = remoteRef,
                seasonNumber = season,
                episodeNumber = episode,
                title = title,
                year = year,
                accountRef = accountRef,
                occurredAt = occurredAt,
            )
        }.getOrNull()
    }

    private fun encodeResumeReset(p: OutboxPayload.ResumeReset): String {
        val scalars = listOf(
            MARKER_RESUME,
            p.kind.name,
            p.titleId ?: "",
            p.seasonNumber?.toString() ?: "",
            p.episodeNumber?.toString() ?: "",
            p.accountRef ?: "",
            p.occurredAt.toEpochMilli().toString(),
            encodeWatchKey(p.watchKey),
        ).joinToString(FLD.toString()) { escape(it) }
        return "$scalars$FLD${encodeAliases(p.aliases)}"
    }

    private fun decodeResumeReset(body: String): OutboxPayload.ResumeReset? {
        val f = body.split(FLD)
        if (f.size < 9) return null
        if (unescape(f[0]) != MARKER_RESUME) return null
        val kind = enumOrNull<WatchlistMembershipKind>(unescape(f[1])) ?: return null
        val titleId = unescape(f[2])?.takeIf { it.isNotBlank() }?.takeIf { isTitleId(it) }
        val season = f[3].toIntOrNull()
        val episode = f[4].toIntOrNull()
        val accountRef = unescape(f[5])?.takeIf { it.isNotBlank() }
        val occurredAt = f[6].toLongOrNull()?.let { Instant.ofEpochMilli(it) } ?: return null
        val watchKey = unescape(f[7])?.let { decodeWatchKey(it) } ?: return null
        val aliases = decodeAliases(f[8]) ?: return null
        if (aliases.isEmpty()) return null
        return runCatching {
            OutboxPayload.ResumeReset(
                watchKey = watchKey,
                kind = kind,
                aliases = aliases,
                titleId = titleId,
                seasonNumber = season,
                episodeNumber = episode,
                accountRef = accountRef,
                occurredAt = occurredAt,
            )
        }.getOrNull()
    }

    private fun encodePersonalRating(p: OutboxPayload.PersonalRating): String {
        val scalars = listOf(
            MARKER_RATING,
            p.kind.name,
            p.titleId,
            p.seasonNumber?.toString() ?: "",
            p.episodeNumber?.toString() ?: "",
            p.score.toString(),
            p.title ?: "",
            p.year?.toString() ?: "",
            p.accountRef ?: "",
            p.occurredAt.toEpochMilli().toString(),
        ).joinToString(FLD.toString()) { escape(it) }
        return "$scalars$FLD${encodeAliases(p.aliases)}"
    }

    private fun decodePersonalRating(body: String): OutboxPayload.PersonalRating? {
        val f = body.split(FLD)
        if (f.size < 11) return null
        if (unescape(f[0]) != MARKER_RATING) return null
        val kind = enumOrNull<PersonalRatingKind>(unescape(f[1])) ?: return null
        val titleId = unescape(f[2])?.takeIf { isTitleId(it) } ?: return null
        val season = f[3].toIntOrNull()
        val episode = f[4].toIntOrNull()
        val score = f[5].toIntOrNull() ?: return null
        val title = unescape(f[6])?.takeIf { it.isNotBlank() }
        val year = f[7].toIntOrNull()
        val accountRef = unescape(f[8])?.takeIf { it.isNotBlank() }
        val occurredAt = f[9].toLongOrNull()?.let { Instant.ofEpochMilli(it) } ?: return null
        val aliases = decodeAliases(f[10]) ?: return null
        if (aliases.isEmpty()) return null
        return runCatching {
            OutboxPayload.PersonalRating(
                kind = kind,
                titleId = titleId,
                aliases = aliases,
                score = score,
                seasonNumber = season,
                episodeNumber = episode,
                title = title,
                year = year,
                accountRef = accountRef,
                occurredAt = occurredAt,
            )
        }.getOrNull()
    }

    private fun encodePositionUpdate(p: OutboxPayload.PositionUpdate): String =
        listOf(
            MARKER_POSITION,
            encodeWatchKey(p.watchKey),
            p.positionMs.toString(),
            p.durationMs?.toString() ?: "",
            if (p.completed) "1" else "0",
            p.occurredAt.toEpochMilli().toString(),
        ).joinToString(FLD.toString()) { escape(it) }

    private fun decodePositionUpdate(body: String): OutboxPayload.PositionUpdate? {
        val f = body.split(FLD)
        if (f.size < 6) return null
        if (unescape(f[0]) != MARKER_POSITION) return null
        val watchKey = unescape(f[1])?.let { decodeWatchKey(it) } ?: return null
        val positionMs = f[2].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val durationMs = f[3].toLongOrNull()?.takeIf { it > 0L }
        val completed = when (unescape(f[4])) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val occurredAt = f[5].toLongOrNull()?.let { Instant.ofEpochMilli(it) } ?: return null
        return runCatching {
            OutboxPayload.PositionUpdate(
                watchKey = watchKey,
                positionMs = positionMs,
                durationMs = durationMs,
                completed = completed,
                occurredAt = occurredAt,
            )
        }.getOrNull()
    }

    private fun encodeAliases(aliases: List<TypedExternalId>): String =
        aliases.take(MAX_ALIASES).joinToString(LST.toString()) { alias ->
            escape(alias.authority) + SUB + escape(alias.value)
        }

    private fun decodeAliases(raw: String): List<TypedExternalId>? {
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<TypedExternalId>(2)
        for (part in raw.split(LST)) {
            val pair = part.split(SUB)
            if (pair.size != 2) return null
            val authority = unescape(pair[0])?.takeIf { it.isNotBlank() && it.length <= 32 } ?: return null
            val value = unescape(pair[1])?.takeIf { it.isNotBlank() && it.length <= MAX_FIELD } ?: return null
            out += TypedExternalId(authority, value)
        }
        return out
    }

    fun encodeWatchKey(key: WatchKey): String {
        val alias = key.alternateOrderAlias
        return when {
            key.isEpisode && key.seasonNumber != null ->
                "e:${key.seriesId!!.value}:${key.seasonNumber}:${key.episodeNumber}"
            key.isEpisode && alias != null -> "ea:${key.seriesId!!.value}:${b64(alias.authority)}:${b64(alias.value)}"
            else -> "m:${key.titleId!!.value}"
        }
    }

    fun decodeWatchKey(encoded: String): WatchKey? {
        val parts = encoded.split(":")
        return runCatching {
            when (parts.getOrNull(0)) {
                "m" -> WatchKey.movie(TitleId.fromString(parts[1]))
                "e" -> WatchKey.episode(TitleId.fromString(parts[1]), parts[2].toInt(), parts[3].toInt())
                "ea" -> WatchKey.episodeByAlternateOrder(
                    TitleId.fromString(parts[1]),
                    TypedExternalId(unb64(parts[2]), unb64(parts[3])),
                )
                else -> null
            }
        }.getOrNull()
    }

    private fun b64(s: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(StandardCharsets.UTF_8))

    private fun unb64(s: String): String =
        String(java.util.Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8)

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } }

    private fun isTitleId(raw: String): Boolean =
        runCatching { TitleId.fromString(raw) }.isSuccess

    private fun escape(value: String): String {
        val clamped = if (value.length > MAX_FIELD) value.substring(0, MAX_FIELD) else value
        val bytes = clamped.toByteArray(StandardCharsets.UTF_8)
        val out = StringBuilder(bytes.size)
        for (byte in bytes) {
            val char = byte.toInt().toChar()
            if (byte >= 0 && char in UNRESERVED) {
                out.append(char)
            } else {
                val unsigned = byte.toInt() and 0xFF
                out.append('%').append(HEX[unsigned shr 4]).append(HEX[unsigned and 0x0F])
            }
        }
        return out.toString()
    }

    private fun unescape(value: String): String? {
        if ('%' !in value) return value
        val out = java.io.ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '%') {
                if (char.code > 0x7F) return null
                out.write(char.code)
                index += 1
                continue
            }
            if (index + 3 > value.length) return null
            val high = Character.digit(value[index + 1], 16)
            val low = Character.digit(value[index + 2], 16)
            if (high < 0 || low < 0) return null
            out.write((high shl 4) or low)
            index += 3
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }
}
