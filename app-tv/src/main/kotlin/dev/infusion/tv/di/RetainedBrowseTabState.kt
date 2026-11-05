package dev.infusion.tv.di

import dev.infusion.feature.vertical.discover.DiscoverStateHolder
import dev.infusion.feature.vertical.home.HomeCatalogRowsStateHolder
import dev.infusion.feature.vertical.home.HomeContinueWatchingStateHolder
import dev.infusion.feature.vertical.home.HomePresentationStateHolder
import dev.infusion.feature.vertical.home.HomeUpNextStateHolder
import dev.infusion.feature.vertical.watchlist.RecentlyWatchedStateHolder
import dev.infusion.tv.ui.screens.HomeSpotlightStateHolder
import dev.infusion.tv.watchlist.WatchlistMembershipArtworkMemory

class RetainedHomeTabState internal constructor(

    val continueWatching: HomeContinueWatchingStateHolder,

    val catalogRows: HomeCatalogRowsStateHolder,

    val upNext: HomeUpNextStateHolder,

    val presentation: HomePresentationStateHolder,

    val spotlight: HomeSpotlightStateHolder,
)

class RetainedDiscoverTabState internal constructor(
    val holder: DiscoverStateHolder,
)

class RetainedWatchlistTabState internal constructor(

    val recentlyWatched: RecentlyWatchedStateHolder,

    val membershipArtwork: WatchlistMembershipArtworkMemory,
)
