package dev.infusion.tv.ui.shell

import dev.infusion.tv.ui.focus.TvFreshEntry
import dev.infusion.tv.ui.screens.TopLevelDestination

data class TvPageJourney(
    val page: TopLevelDestination,

    val freshEntry: TvFreshEntry,

    val acceptedFreshEntry: TvFreshEntry,

    val wired: Boolean,

    val landingFreshEntry: Boolean = false,
)

object TvPageJourneys {

    fun of(page: TopLevelDestination): TvPageJourney = when (page) {
        TopLevelDestination.HOME -> TvPageJourney(
            page = page,

            freshEntry = TvFreshEntry.PAGE_DEFAULT,
            acceptedFreshEntry = TvFreshEntry.PAGE_DEFAULT,
            wired = true,
            landingFreshEntry = true,
        )

        TopLevelDestination.JELLYFIN -> TvPageJourney(
            page = page,
            freshEntry = TvFreshEntry.REMEMBERED,

            acceptedFreshEntry = TvFreshEntry.FRESH_KEY_THEN_DEFAULT,
            wired = false,
        )

        TopLevelDestination.WATCHLIST -> TvPageJourney(
            page = page,

            freshEntry = TvFreshEntry.PAGE_DEFAULT,
            acceptedFreshEntry = TvFreshEntry.PAGE_DEFAULT,
            wired = true,
            landingFreshEntry = true,
        )

        TopLevelDestination.DISCOVER -> TvPageJourney(
            page = page,

            freshEntry = TvFreshEntry.FRESH_KEY_THEN_DEFAULT,
            acceptedFreshEntry = TvFreshEntry.FRESH_KEY_THEN_DEFAULT,
            wired = true,
            landingFreshEntry = true,
        )

        TopLevelDestination.SEARCH -> TvPageJourney(
            page = page,

            freshEntry = TvFreshEntry.FRESH_KEY_THEN_DEFAULT,
            acceptedFreshEntry = TvFreshEntry.FRESH_KEY_THEN_DEFAULT,
            wired = true,
        )

        TopLevelDestination.SETTINGS -> TvPageJourney(
            page = page,

            freshEntry = TvFreshEntry.REMEMBERED,
            acceptedFreshEntry = TvFreshEntry.PAGE_DEFAULT,
            wired = true,
        )
    }
}
