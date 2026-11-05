package dev.infusion.core.domain.watchlist

import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.watch.WatchKey
import java.nio.charset.StandardCharsets
import java.time.Instant

enum class WatchlistKind { MOVIE, SHOW }

enum class WatchlistRemoteKind {
    MOVIE,
    SHOW,
    SEASON,
    EPISODE,
    ;

    fun normalizesTo(): WatchlistKind =
        if (this == MOVIE) WatchlistKind.MOVIE else WatchlistKind.SHOW
}

enum class RemoteMembershipStatus { PENDING, DELIVERED, FAILED, UNSUPPORTED }

data class WatchlistContribution(
    val providerId: String,
    val accountRef: String?,
    val remoteKind: WatchlistRemoteKind,
    val remoteRef: String,
    val remoteAddedAt: Instant? = null,
    val firstObservedAt: Instant,
    val sourceOrder: Int = 0,
) {
    init {
        require(providerId.isNotBlank()) { "a contribution requires the provider's stable id" }
        require(remoteRef.isNotBlank()) { "a contribution requires the provider's own reference" }
    }

    val orderingInstant: Instant get() = remoteAddedAt ?: firstObservedAt
}

data class WatchlistEntry(
    val titleId: TitleId,
    val kind: WatchlistKind,
    val title: String,
    val year: Int? = null,
    val artworkLocator: String? = null,
    val artworkPolicy: ArtworkPolicy = ArtworkPolicy.POSTER,
    val landscapeLocator: String? = null,
    val localAddedAt: Instant? = null,
    val contributions: List<WatchlistContribution> = emptyList(),
) {
    init {
        require(title.isNotBlank()) { "an entry requires a stated title" }
    }

    val entryId: String get() = "${kind.name}:${titleId.value}"

    val isLocal: Boolean get() = localAddedAt != null

    val orderingInstant: Instant?
        get() = (listOfNotNull(localAddedAt) + contributions.map { it.orderingInstant })
            .maxOrNull()

    fun remoteRefsFor(providerId: String, accountRef: String?): List<String> =
        contributions
            .filter { it.providerId == providerId && it.accountRef == accountRef }
            .map { it.remoteRef }
            .distinct()

    fun hasRemoteMembership(providerId: String, accountRef: String?): Boolean =
        contributions.any { it.providerId == providerId && it.accountRef == accountRef }

    val isEmpty: Boolean get() = localAddedAt == null && contributions.isEmpty()
}

data class WatchlistSuppression(
    val titleId: TitleId,
    val kind: WatchlistKind,
    val providerId: String,
    val accountRef: String?,
    val suppressedRefs: Set<String>,
    val suppressedAt: Instant,
) {
    init {
        require(providerId.isNotBlank()) { "a suppression requires the provider's stable id" }
    }

    val entryId: String get() = "${kind.name}:${titleId.value}"

    fun covers(entryId: String, providerId: String, accountRef: String?): Boolean =
        this.entryId == entryId && this.providerId == providerId && this.accountRef == accountRef
}

enum class ResumeResetDelivery { PENDING, DELIVERED, UNSUPPORTED }

enum class WatchlistRemoteState { IDLE, LOADING, LOADED, FAILED }

data class ResumeResetGuard(
    val watchKey: WatchKey,
    val providerId: String?,
    val accountRef: String?,
    val resetAt: Instant,
    val sequence: Long,
    val delivery: ResumeResetDelivery,
)

data class WatchlistSnapshot(
    val entries: List<WatchlistEntry> = emptyList(),
    val suppressions: List<WatchlistSuppression> = emptyList(),
    val guards: List<ResumeResetGuard> = emptyList(),
    val unresolvedCount: Int = 0,

    val remoteReadComplete: Boolean = true,
) {

    fun row(kind: WatchlistKind): List<WatchlistEntry> =
        entries.filter { it.kind == kind }.sortedWith(WatchlistOrdering)

    fun entry(entryId: String): WatchlistEntry? = entries.firstOrNull { it.entryId == entryId }
}

object WatchlistOrdering : Comparator<WatchlistEntry> {
    override fun compare(a: WatchlistEntry, b: WatchlistEntry): Int {
        val at = a.orderingInstant
        val bt = b.orderingInstant
        return when {
            at == null && bt == null -> a.entryId.compareTo(b.entryId)
            at == null -> 1
            bt == null -> -1
            else -> {
                val byTime = bt.compareTo(at)
                if (byTime != 0) byTime else a.entryId.compareTo(b.entryId)
            }
        }
    }
}

object WatchlistLimits {

    const val MAX_ENTRIES: Int = 500

    const val MAX_CONTRIBUTIONS_PER_ENTRY: Int = 64

    const val MAX_SUPPRESSIONS: Int = 500
    const val MAX_GUARDS: Int = 200

    const val MAX_TITLE_LENGTH: Int = 200

    const val MAX_REF_LENGTH: Int = 512
}

object WatchlistOps {

    fun addLocal(
        snapshot: WatchlistSnapshot,
        titleId: TitleId,
        kind: WatchlistKind,
        title: String,
        year: Int?,
        artwork: WatchlistArtwork?,
        now: Instant,
    ): WatchlistSnapshot {
        val existing = snapshot.entry(id(kind, titleId))
        val entry = if (existing == null) {
            WatchlistEntry(
                titleId = titleId,
                kind = kind,
                title = title,
                year = year,
                artworkLocator = artwork?.locator,
                artworkPolicy = artwork?.policy ?: ArtworkPolicy.POSTER,
                landscapeLocator = artwork?.landscapeLocator,
                localAddedAt = now,
            )
        } else {
            existing.copy(
                title = title.ifBlank { existing.title },
                year = year ?: existing.year,
                artworkLocator = artwork?.locator ?: existing.artworkLocator,
                artworkPolicy = artwork?.policy ?: existing.artworkPolicy,
                landscapeLocator = artwork?.landscapeLocator ?: existing.landscapeLocator,

                localAddedAt = existing.localAddedAt ?: now,
            )
        }

        val entries = upsert(snapshot.entries, entry)
        val suppressions = snapshot.suppressions.filterNot { it.entryId == entry.entryId }
        return snapshot.copy(entries = entries, suppressions = suppressions)
    }

    fun remove(
        snapshot: WatchlistSnapshot,
        entryId: String,
        providerId: String?,
        accountRef: String?,
        now: Instant,
    ): RemoveResult {
        val entry = snapshot.entry(entryId) ?: return RemoveResult(snapshot, emptyList())
        val targeted = providerId?.let { entry.remoteRefsFor(it, accountRef) } ?: emptyList()

        val remainingContributions = entry.contributions.filterNot {
            providerId != null && it.providerId == providerId && it.accountRef == accountRef
        }
        val next = entry.copy(localAddedAt = null, contributions = remainingContributions)
        val entries = if (next.isEmpty) snapshot.entries.filterNot { it.entryId == entryId }
        else upsert(snapshot.entries, next)
        val suppressions = if (providerId == null) {
            snapshot.suppressions
        } else {
            val record = WatchlistSuppression(
                titleId = entry.titleId,
                kind = entry.kind,
                providerId = providerId,
                accountRef = accountRef,
                suppressedRefs = targeted.toSet(),
                suppressedAt = now,
            )
            upsertSuppression(snapshot.suppressions, record)
        }
        return RemoveResult(
            snapshot.copy(entries = entries, suppressions = suppressions),
            targeted,
            removedEntry = next.isEmpty,
        )
    }

    fun reconcile(
        snapshot: WatchlistSnapshot,
        providerId: String,
        accountRef: String?,
        resolved: List<ResolvedRemote>,
        unresolved: Int,
        completed: Boolean,
        now: Instant,
        completeKinds: Set<WatchlistKind> = WatchlistKind.entries.toSet(),
        unresolvedMemberships: Set<UnresolvedMembershipRef> = emptySet(),
    ): WatchlistSnapshot {

        val byEntry = LinkedHashMap<String, MutableList<ResolvedRemote>>()
        for (record in resolved) {
            val entryId = id(record.kind, record.titleId)

            if (isSuppressed(snapshot, entryId, providerId, accountRef)) continue
            byEntry.getOrPut(entryId) { mutableListOf() }.add(record)
        }

        val updated = snapshot.entries.map { entry ->
            val incoming = byEntry[entry.entryId]

            val withdrawable = entry.contributions.filter { contribution ->
                contribution.providerId == providerId &&
                    contribution.accountRef == accountRef &&
                    UnresolvedMembershipRef(contribution.remoteKind, contribution.remoteRef) !in
                    unresolvedMemberships
            }
            val otherContribs = entry.contributions - withdrawable.toSet()
            if (incoming == null) {

                if (completed && entry.kind in completeKinds && withdrawable.isNotEmpty()) {
                    entry.copy(contributions = otherContribs)
                } else {
                    entry
                }
            } else {

                val prior = entry.contributions
                    .filter { it.providerId == providerId && it.accountRef == accountRef }
                    .associateBy { it.remoteRef }
                val merged = incoming.mapIndexed { index, record ->
                    val previous = prior[record.remoteRef]
                    WatchlistContribution(
                        providerId = providerId,
                        accountRef = accountRef,
                        remoteKind = record.remoteKind,
                        remoteRef = record.remoteRef,
                        remoteAddedAt = record.remoteAddedAt,
                        firstObservedAt = previous?.firstObservedAt ?: now,
                        sourceOrder = index,
                    )
                }
                entry.copy(

                    title = entry.title.ifBlank { record0Title(incoming) },
                    contributions = (otherContribs + merged)
                        .take(WatchlistLimits.MAX_CONTRIBUTIONS_PER_ENTRY),
                )
            }
        }

        val known = updated.map { it.entryId }.toSet()
        val addedEntries = byEntry.entries
            .filter { it.key !in known }
            .map { (entryId, records) ->
                val first = records.first()
                WatchlistEntry(
                    titleId = first.titleId,
                    kind = first.kind,
                    title = first.title,
                    year = first.year,
                    artworkLocator = first.artworkLocator,
                    artworkPolicy = first.artworkPolicy,
                    landscapeLocator = first.landscapeLocator,
                    localAddedAt = null,
                    contributions = records.mapIndexed { index, record ->
                        WatchlistContribution(
                            providerId = providerId,
                            accountRef = accountRef,
                            remoteKind = record.remoteKind,
                            remoteRef = record.remoteRef,
                            remoteAddedAt = record.remoteAddedAt,
                            firstObservedAt = now,
                            sourceOrder = index,
                        )
                    }.take(WatchlistLimits.MAX_CONTRIBUTIONS_PER_ENTRY),
                )
            }

        val merged = (updated + addedEntries)
            .filterNot { it.isEmpty }
            .take(WatchlistLimits.MAX_ENTRIES)
        return snapshot.copy(
            entries = merged,
            unresolvedCount = unresolved,
            remoteReadComplete = completed,
        )
    }

    fun withdrawProvider(
        snapshot: WatchlistSnapshot,
        providerId: String,
        accountRef: String?,
    ): WatchlistSnapshot {
        val entries = snapshot.entries
            .map { entry ->
                entry.copy(contributions = entry.contributions.filterNot {
                    it.providerId == providerId && (accountRef == null || it.accountRef == accountRef)
                })
            }
            .filterNot { it.isEmpty }
        val suppressions = snapshot.suppressions.filterNot {
            it.providerId == providerId && (accountRef == null || it.accountRef == accountRef)
        }
        return snapshot.copy(entries = entries, suppressions = suppressions)
    }

    fun withdrawOtherAccounts(
        snapshot: WatchlistSnapshot,
        activeProviderId: String?,
        activeAccountRef: String?,
    ): WatchlistSnapshot {
        val entries = snapshot.entries
            .map { entry ->
                entry.copy(contributions = entry.contributions.filter {
                    it.providerId == activeProviderId && it.accountRef == activeAccountRef
                })
            }
            .filterNot { it.isEmpty }
        return snapshot.copy(entries = entries)
    }

    fun isSuppressed(
        snapshot: WatchlistSnapshot,
        entryId: String,
        providerId: String,
        accountRef: String?,
    ): Boolean = snapshot.suppressions.any { it.covers(entryId, providerId, accountRef) }

    fun retireSuppressionOnAbsence(
        snapshot: WatchlistSnapshot,
        entryId: String,
        providerId: String,
        accountRef: String?,
        completed: Boolean,
    ): WatchlistSnapshot {
        if (!completed) return snapshot
        return snapshot.copy(suppressions = snapshot.suppressions.filterNot {
            it.covers(entryId, providerId, accountRef)
        })
    }

    fun retireAbsentSuppressions(
        snapshot: WatchlistSnapshot,
        providerId: String,
        accountRef: String?,
        seenEntryIds: Set<String>,
        completed: Boolean,
        completeKinds: Set<WatchlistKind> = WatchlistKind.entries.toSet(),
    ): WatchlistSnapshot {
        if (!completed) return snapshot
        return snapshot.copy(suppressions = snapshot.suppressions.filterNot { suppression ->
            suppression.providerId == providerId &&
                suppression.accountRef == accountRef &&
                suppression.kind in completeKinds &&
                suppression.entryId !in seenEntryIds
        })
    }

    fun recordResumeGuard(
        snapshot: WatchlistSnapshot,
        watchKey: WatchKey,
        providerId: String?,
        accountRef: String?,
        now: Instant,
        sequence: Long,
        delivery: ResumeResetDelivery,
    ): WatchlistSnapshot {
        val guard = ResumeResetGuard(watchKey, providerId, accountRef, now, sequence, delivery)
        val others = snapshot.guards.filterNot { it.watchKey == watchKey }
        return snapshot.copy(guards = (others + guard).takeLast(WatchlistLimits.MAX_GUARDS))
    }

    fun updateGuardDelivery(
        snapshot: WatchlistSnapshot,
        watchKey: WatchKey,
        delivery: ResumeResetDelivery,
    ): WatchlistSnapshot = snapshot.copy(
        guards = snapshot.guards.map { if (it.watchKey == watchKey) it.copy(delivery = delivery) else it },
    )

    fun guardBlocksInbound(snapshot: WatchlistSnapshot, watchKey: WatchKey): Boolean =
        snapshot.guards.any { it.watchKey == watchKey }

    fun retireResumeGuard(snapshot: WatchlistSnapshot, watchKey: WatchKey): WatchlistSnapshot =
        snapshot.copy(guards = snapshot.guards.filterNot { it.watchKey == watchKey })

    private fun id(kind: WatchlistKind, titleId: TitleId): String = "${kind.name}:${titleId.value}"

    private fun record0Title(records: List<ResolvedRemote>): String = records.first().title

    private fun upsert(entries: List<WatchlistEntry>, entry: WatchlistEntry): List<WatchlistEntry> {
        val index = entries.indexOfFirst { it.entryId == entry.entryId }
        return if (index < 0) entries + entry else entries.toMutableList().also { it[index] = entry }
    }

    private fun upsertSuppression(
        suppressions: List<WatchlistSuppression>,
        record: WatchlistSuppression,
    ): List<WatchlistSuppression> {
        val filtered = suppressions.filterNot { it.covers(record.entryId, record.providerId, record.accountRef) }
        return (filtered + record).takeLast(WatchlistLimits.MAX_SUPPRESSIONS)
    }
}

data class UnresolvedMembershipRef(
    val remoteKind: WatchlistRemoteKind,
    val remoteRef: String,
)

data class RemoveResult(
    val snapshot: WatchlistSnapshot,
    val remoteRefs: List<String>,
    val removedEntry: Boolean = false,
)

data class WatchlistArtwork(
    val locator: String,
    val policy: ArtworkPolicy = ArtworkPolicy.POSTER,
    val landscapeLocator: String? = null,
)

data class ResolvedRemote(
    val titleId: TitleId,
    val kind: WatchlistKind,
    val remoteKind: WatchlistRemoteKind,
    val remoteRef: String,
    val title: String,
    val year: Int? = null,
    val artworkLocator: String? = null,
    val artworkPolicy: ArtworkPolicy = ArtworkPolicy.POSTER,
    val landscapeLocator: String? = null,
    val remoteAddedAt: Instant? = null,
)

object WatchlistCodec {

    const val ENTRIES_VERSION: String = "wl1"
    const val SUPPRESSIONS_VERSION: String = "ws1"
    const val GUARDS_VERSION: String = "wg1"

    private const val REC = '\n'
    private const val FIELD = '\u0001'
    private const val LIST = '\u0002'
    private const val SUB = '\u0003'

    private const val UNRESERVED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-"
    private const val HEX = "0123456789ABCDEF"

    fun encodeEntries(entries: List<WatchlistEntry>): String {
        val body = entries.take(WatchlistLimits.MAX_ENTRIES).joinToString(REC.toString()) { encodeEntry(it) }
        return if (body.isEmpty()) ENTRIES_VERSION else "$ENTRIES_VERSION$REC$body"
    }

    fun decodeEntries(stored: String?): List<WatchlistEntry> {
        if (stored == null) return emptyList()
        if (!stored.startsWith(ENTRIES_VERSION + REC)) return emptyList()
        return stored.substring(ENTRIES_VERSION.length + 1)
            .split(REC)
            .mapNotNull { decodeEntry(it) }
    }

    fun isPayloadOfVersion(stored: String?, version: String): Boolean {
        if (stored == null) return true
        return stored == version || stored.startsWith("$version$REC")
    }

    private fun encodeEntry(entry: WatchlistEntry): String {
        val contributions = entry.contributions
            .take(WatchlistLimits.MAX_CONTRIBUTIONS_PER_ENTRY)
            .joinToString(LIST.toString()) { encodeContribution(it) }

        val scalars = listOf(
            "E",
            entry.titleId.value.toString(),
            entry.kind.name,
            entry.title,
            entry.year?.toString() ?: "",
            entry.artworkLocator ?: "",
            entry.artworkPolicy.name,
            entry.landscapeLocator ?: "",
            entry.localAddedAt?.toEpochMilli()?.toString() ?: "",
            entry.contributions.size.coerceAtMost(WatchlistLimits.MAX_CONTRIBUTIONS_PER_ENTRY).toString(),
        ).joinToString(FIELD.toString()) { escape(it) }
        return "$scalars$FIELD$contributions"
    }

    private fun encodeContribution(c: WatchlistContribution): String = listOf(
        c.providerId,
        c.accountRef ?: "",
        c.remoteKind.name,
        c.remoteRef,
        c.remoteAddedAt?.toEpochMilli()?.toString() ?: "",
        c.firstObservedAt.toEpochMilli().toString(),
        c.sourceOrder.toString(),
    ).joinToString(SUB.toString()) { escape(it) }

    private fun decodeEntry(raw: String): WatchlistEntry? {
        val f = raw.split(FIELD)
        if (f.size < 11) return null
        val marker = unescape(f[0]) ?: return null
        if (marker != "E") return null
        val titleId = unescape(f[1])?.let { runCatching { TitleId.fromString(it) }.getOrNull() } ?: return null
        val kind = enumOrNull<WatchlistKind>(unescape(f[2])) ?: return null
        val title = unescape(f[3])?.takeIf { it.isNotBlank() && it.length <= WatchlistLimits.MAX_TITLE_LENGTH }
            ?: return null
        val year = f[4].toIntOrNull()
        val artLocator = unescape(f[5])?.takeIf { it.isNotBlank() }
        val artPolicy = enumOrNull<ArtworkPolicy>(unescape(f[6])) ?: ArtworkPolicy.POSTER
        val landscape = unescape(f[7])?.takeIf { it.isNotBlank() }
        val localAddedAt = f[8].toLongOrNull()?.let { Instant.ofEpochMilli(it) }
        val count = f[9].toIntOrNull()?.coerceIn(0, WatchlistLimits.MAX_CONTRIBUTIONS_PER_ENTRY)
            ?: return null
        val contributions = if (f[10].isEmpty()) {
            emptyList()
        } else {
            f[10].split(LIST).mapNotNull { decodeContribution(it) }.take(count)
        }
        return runCatching {
            WatchlistEntry(
                titleId = titleId,
                kind = kind,
                title = title,
                year = year,
                artworkLocator = artLocator,
                artworkPolicy = artPolicy,
                landscapeLocator = landscape,
                localAddedAt = localAddedAt,
                contributions = contributions,
            )
        }.getOrNull()
    }

    private fun decodeContribution(raw: String): WatchlistContribution? {
        val f = raw.split(SUB)
        if (f.size < 7) return null
        val providerId = unescape(f[0])?.takeIf { it.isNotBlank() } ?: return null
        val accountRef = unescape(f[1])?.takeIf { it.isNotBlank() }
        val remoteKind = enumOrNull<WatchlistRemoteKind>(unescape(f[2])) ?: return null
        val remoteRef = unescape(f[3])?.takeIf { it.isNotBlank() && it.length <= WatchlistLimits.MAX_REF_LENGTH }
            ?: return null
        val remoteAddedAt = f[4].toLongOrNull()?.let { Instant.ofEpochMilli(it) }
        val firstObserved = f[5].toLongOrNull()?.let { Instant.ofEpochMilli(it) } ?: return null
        val order = f[6].toIntOrNull() ?: 0
        return runCatching {
            WatchlistContribution(providerId, accountRef, remoteKind, remoteRef, remoteAddedAt, firstObserved, order)
        }.getOrNull()
    }

    fun encodeSuppressions(list: List<WatchlistSuppression>): String {
        val body = list.take(WatchlistLimits.MAX_SUPPRESSIONS).joinToString(REC.toString()) { s ->
            val refs = s.suppressedRefs.joinToString(LIST.toString()) { escape(it) }
            val scalars = listOf(
                "S",
                s.titleId.value.toString(),
                s.kind.name,
                s.providerId,
                s.accountRef ?: "",
                s.suppressedAt.toEpochMilli().toString(),
            ).joinToString(FIELD.toString()) { escape(it) }
            "$scalars$FIELD$refs"
        }
        return if (body.isEmpty()) SUPPRESSIONS_VERSION else "$SUPPRESSIONS_VERSION$REC$body"
    }

    fun decodeSuppressions(stored: String?): List<WatchlistSuppression> {
        if (stored == null) return emptyList()
        if (!stored.startsWith(SUPPRESSIONS_VERSION + REC)) return emptyList()
        return stored.substring(SUPPRESSIONS_VERSION.length + 1)
            .split(REC)
            .mapNotNull { raw ->
                val f = raw.split(FIELD)
                if (f.size < 7 || unescape(f[0]) != "S") return@mapNotNull null
                val titleId = unescape(f[1])?.let { runCatching { TitleId.fromString(it) }.getOrNull() }
                    ?: return@mapNotNull null
                val kind = enumOrNull<WatchlistKind>(unescape(f[2])) ?: return@mapNotNull null
                val providerId = unescape(f[3])?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val accountRef = unescape(f[4])?.takeIf { it.isNotBlank() }
                val at = f[5].toLongOrNull()?.let { Instant.ofEpochMilli(it) } ?: return@mapNotNull null
                val refs = if (f[6].isEmpty()) emptySet()
                else f[6].split(LIST).mapNotNull { unescape(it)?.takeIf { s -> s.isNotBlank() } }.toSet()
                WatchlistSuppression(titleId, kind, providerId, accountRef, refs, at)
            }
    }

    fun encodeGuards(list: List<ResumeResetGuard>): String {
        val body = list.take(WatchlistLimits.MAX_GUARDS).joinToString(REC.toString()) { g ->
            listOf(
                "G",
                encodeWatchKey(g.watchKey),
                g.providerId ?: "",
                g.accountRef ?: "",
                g.resetAt.toEpochMilli().toString(),
                g.sequence.toString(),
                g.delivery.name,
            ).joinToString(FIELD.toString()) { escape(it) }
        }
        return if (body.isEmpty()) GUARDS_VERSION else "$GUARDS_VERSION$REC$body"
    }

    fun decodeGuards(stored: String?): List<ResumeResetGuard> {
        if (stored == null) return emptyList()
        if (!stored.startsWith(GUARDS_VERSION + REC)) return emptyList()
        return stored.substring(GUARDS_VERSION.length + 1)
            .split(REC)
            .mapNotNull { raw ->
                val f = raw.split(FIELD)
                if (f.size < 7 || unescape(f[0]) != "G") return@mapNotNull null
                val watchKey = unescape(f[1])?.let { decodeWatchKey(it) } ?: return@mapNotNull null
                val providerId = unescape(f[2])?.takeIf { it.isNotBlank() }
                val accountRef = unescape(f[3])?.takeIf { it.isNotBlank() }
                val at = f[4].toLongOrNull()?.let { Instant.ofEpochMilli(it) } ?: return@mapNotNull null
                val sequence = f[5].toLongOrNull() ?: return@mapNotNull null
                val delivery = enumOrNull<ResumeResetDelivery>(unescape(f[6])) ?: ResumeResetDelivery.PENDING
                ResumeResetGuard(watchKey, providerId, accountRef, at, sequence, delivery)
            }
    }

    fun encodeWatchKey(key: WatchKey): String {
        val alias = key.alternateOrderAlias
        return when {
            key.isEpisode && key.seasonNumber != null -> "e:${key.seriesId!!.value}:${key.seasonNumber}:${key.episodeNumber}"
            key.isEpisode && alias != null -> "ea:${key.seriesId!!.value}:${alias.authority}:${alias.value}"
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
                    dev.infusion.core.model.TypedExternalId(parts[2], parts[3]),
                )
                else -> null
            }
        }.getOrNull()
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } }

    private fun escape(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val out = StringBuilder(bytes.size)
        bytes.forEach { byte ->
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

data class WatchlistRemoteRecord(
    val remoteKind: WatchlistRemoteKind,
    val externalIds: TrackingExternalIds,
    val remoteRef: String,
    val title: String? = null,
    val year: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val seriesExternalIds: TrackingExternalIds? = null,
    val remoteAddedAt: Instant? = null,
    val artworkLocator: String? = null,
)

data class WatchlistRemotePage(
    val records: List<WatchlistRemoteRecord>,
    val malformed: Int = 0,
    val next: String? = null,
    val truncating: Boolean = false,
    val incompleteRemoteKinds: Set<WatchlistRemoteKind> = emptySet(),
)
