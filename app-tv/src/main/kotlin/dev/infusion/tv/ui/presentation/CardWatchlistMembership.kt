package dev.infusion.tv.ui.presentation

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import dev.infusion.core.domain.watchlist.WatchlistEntry
import dev.infusion.core.domain.watchlist.WatchlistKind
import dev.infusion.core.domain.watchlist.WatchlistSnapshot
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleKind

enum class WatchlistBadgeVariant {
    LOCAL,
    REMOTE,
}

@Immutable
data class CardWatchlistMembership(

    val activeTracker: String? = null,

    val badges: Map<String, WatchlistBadgeVariant> = emptyMap(),
) {

    fun variantFor(entryKey: String?): WatchlistBadgeVariant? = entryKey?.let { badges[it] }

    companion object {

        val None: CardWatchlistMembership = CardWatchlistMembership()

        fun of(
            snapshot: WatchlistSnapshot,
            activeTracker: String?,
            activeAccountRef: String?,
        ): CardWatchlistMembership {
            val badges = LinkedHashMap<String, WatchlistBadgeVariant>()
            for (entry in snapshot.entries) {
                val remote = activeTracker != null &&
                    entry.hasRemoteMembership(activeTracker, activeAccountRef)
                when {
                    remote -> badges[entry.entryId] = WatchlistBadgeVariant.REMOTE
                    entry.isLocal -> badges[entry.entryId] = WatchlistBadgeVariant.LOCAL
                    else -> Unit
                }
            }
            return CardWatchlistMembership(activeTracker = activeTracker, badges = badges)
        }
    }
}

fun watchlistEntryKeyFor(preview: MediaPreview, kind: TitleKind?): String? = when (kind) {
    TitleKind.MOVIE -> preview.titleId?.let { watchlistEntryKey(WatchlistKind.MOVIE, it.value.toString()) }
    TitleKind.SERIES -> preview.titleId?.let { watchlistEntryKey(WatchlistKind.SHOW, it.value.toString()) }
    TitleKind.EPISODE -> preview.watchKey?.seriesId?.let {
        watchlistEntryKey(WatchlistKind.SHOW, it.value.toString())
    }
    TitleKind.SEASON, TitleKind.CLIP, null -> null
}

fun watchlistEntryKey(kind: WatchlistKind, titleId: String): String = "${kind.name}:$titleId"

fun ContentCardPresentation.watchlistBadge(
    preview: MediaPreview,
    kind: TitleKind?,
    membership: CardWatchlistMembership,
): WatchlistBadgeVariant? = watchlistBadgeFor(
    entryKey = watchlistEntryKeyFor(preview, kind),
    membership = membership,
)

fun ContentCardPresentation.watchlistBadgeFor(
    entryKey: String?,
    membership: CardWatchlistMembership,
): WatchlistBadgeVariant? {
    if (!cardBadges || !watchlistBadge) return null
    return membership.variantFor(entryKey)
}

val LocalCardWatchlistMembership = compositionLocalOf { CardWatchlistMembership.None }
