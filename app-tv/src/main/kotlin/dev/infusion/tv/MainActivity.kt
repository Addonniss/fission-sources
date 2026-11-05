package dev.infusion.tv

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.infusion.feature.vertical.addon.AddonDeepLink
import dev.infusion.tv.navigation.TvNavHost
import dev.infusion.tv.ui.presentation.LocalCardWatchedDemand
import dev.infusion.tv.ui.presentation.LocalCardWatchedState
import dev.infusion.tv.ui.presentation.LocalSeriesLandscapeArtwork
import dev.infusion.tv.ui.presentation.toContentCardPresentation
import dev.infusion.tv.ui.theme.InfusionTvAppearance
import dev.infusion.tv.ui.theme.InfusionTvTheme

class MainActivity : ComponentActivity() {

    private val actionScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )

    private val displayModeHost: dev.infusion.tv.playback.AndroidWindowDisplayModeHost by lazy {
        dev.infusion.tv.playback.AndroidWindowDisplayModeHost(
            activity = this,
            diagnostics = (application as InfusionTvApplication).container.displayMatchingDiagnostics,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        val container = (application as InfusionTvApplication).container

        container.musicMediaSessionHost

        container.displayMatchingCoordinator.attachHost(displayModeHost)

        handleAddonDeepLink(intent, container)
        setContent {

            val appearance by container.appearanceStateHolder.preferences.collectAsStateWithLifecycle()

            val cardWatchedState by container.cardWatchedState.collectAsStateWithLifecycle()

            val cardWatchlistMembership by container.cardWatchlistMembership
                .collectAsStateWithLifecycle(initialValue = dev.infusion.tv.ui.presentation.CardWatchlistMembership.None)
            val cardAvailability by container.cardAvailability
                .collectAsStateWithLifecycle(initialValue = dev.infusion.tv.ui.presentation.CardAvailability.None)

            val cardProgress by container.cardProgress
                .collectAsStateWithLifecycle(initialValue = dev.infusion.tv.ui.presentation.CardProgress.None)

            val acquisitionRevision by container.acquisitionCoordinator.revision
                .collectAsStateWithLifecycle(initialValue = 0L)

            val personalRatingRevision by container.personalRatingsRevision
                .collectAsStateWithLifecycle(initialValue = 0L)

            val globalOverlayAccess = remember { dev.infusion.tv.ui.shell.TvGlobalOverlayAccess() }

            val musicMenuContext = LocalContext.current

            CompositionLocalProvider(
                LocalSeriesLandscapeArtwork provides container.seriesLandscapeArtwork,

                LocalCardWatchedState provides cardWatchedState,
                LocalCardWatchedDemand provides container.cardWatchedDemand,

                dev.infusion.tv.ui.presentation.LocalCardWatchlistMembership provides cardWatchlistMembership,
                dev.infusion.tv.ui.presentation.LocalCardAvailability provides cardAvailability,

                dev.infusion.tv.ui.presentation.LocalCardProgress provides cardProgress,

                dev.infusion.tv.ui.presentation.LocalCardResumeDemand provides container.cardResumeDemand,

                dev.infusion.tv.ui.presentation.LocalCardActionOwner provides container.watchlistCoordinator,
                dev.infusion.tv.ui.presentation.LocalCardContextMenuHost provides container.cardContextMenuHost,
                dev.infusion.tv.ui.presentation.LocalAcquisitionRevision provides acquisitionRevision,

                dev.infusion.tv.ui.presentation.LocalPersonalRatings provides container.personalRatingsCoordinator,
                dev.infusion.tv.ui.presentation.LocalPersonalRatingRevision provides personalRatingRevision,

                dev.infusion.tv.ui.shell.LocalTvGlobalOverlayAccess provides globalOverlayAccess,

                dev.infusion.tv.ui.shell.LocalColdStartSplashController provides
                    container.coldStartSplash,

                dev.infusion.tv.ui.shell.LocalSplashScreenMode provides appearance.splashScreen,
            ) {
                InfusionTvTheme(
                    accent = InfusionTvAppearance.accentColor(appearance.accent),
                    cardPresentation = appearance.toContentCardPresentation(),

                    topShelfAppearance = appearance.topShelfAppearance,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        TvNavHost(container = container)

                        dev.infusion.tv.music.MusicNowPlayingChip(
                            holder = container.musicQueueStateHolder,
                            onOpen = { container.offerOpenNowPlaying(globalOverlayAccess.originKey) },
                            access = globalOverlayAccess,
                        )

                        dev.infusion.tv.ui.update.TvUpdateShellHost(container = container)

                        dev.infusion.tv.ui.presentation.CardContextMenuOverlay(
                            host = container.cardContextMenuHost,
                            owner = container.watchlistCoordinator,

                            onMusicAction = { action, request ->
                                val holder = container.musicQueueStateHolder
                                val context = musicMenuContext
                                val slot = request.slot
                                when (action) {
                                    dev.infusion.tv.ui.presentation.MusicMenuActionId.PLAY_NEXT_INSERT -> {
                                        request.insertable?.let { entry ->
                                            holder.insertOccurrence(entry, playNext = true)
                                            android.widget.Toast.makeText(
                                                context,
                                                "Playing next",
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                    dev.infusion.tv.ui.presentation.MusicMenuActionId.ADD_TO_QUEUE -> {
                                        request.insertable?.let { entry ->
                                            holder.insertOccurrence(entry, playNext = false)
                                            android.widget.Toast.makeText(
                                                context,
                                                "Added to queue",
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                    dev.infusion.tv.ui.presentation.MusicMenuActionId.PLAY_NEXT_MOVE ->
                                        slot?.let { holder.playNextOccurrence(it) }
                                    dev.infusion.tv.ui.presentation.MusicMenuActionId.MOVE_UP ->
                                        slot?.let { holder.moveOccurrence(it, -1) }
                                    dev.infusion.tv.ui.presentation.MusicMenuActionId.MOVE_DOWN ->
                                        slot?.let { holder.moveOccurrence(it, +1) }
                                    dev.infusion.tv.ui.presentation.MusicMenuActionId.REMOVE ->
                                        slot?.let { holder.removeOccurrence(it) }
                                }
                            },
                            onAction = { action, request ->

                                if (action.id == dev.infusion.core.domain.watchlist.CardActionId.SET_RATING) {
                                    container.cardContextMenuHost.openRating(request)
                                    return@CardContextMenuOverlay
                                }

                                if (action.id == dev.infusion.core.domain.watchlist.CardActionId.PLAY_FROM_BEGINNING) {
                                    val episodeTitleId = request.playableTitleId
                                    val watchKey = request.watchKey
                                    val seriesId = watchKey?.seriesId
                                    val season = watchKey?.seasonNumber
                                    val episode = watchKey?.episodeNumber
                                    if (episodeTitleId != null && seriesId != null && season != null && episode != null) {
                                        container.offerEpisodePlayFromBeginning(
                                            episodeTitleId,
                                            dev.infusion.core.domain.catalog.episodeUnitKey(
                                                seriesId,
                                                season,
                                                episode,
                                            ),
                                        )
                                        container.cardContextMenuHost.dismiss()
                                    }
                                    return@CardContextMenuOverlay
                                }

                                if (action.id == dev.infusion.core.domain.watchlist.CardActionId.BROWSE_SHOW) {
                                    request.provenShowId?.let { seriesTitleId ->
                                        container.offerEpisodeBrowseShow(seriesTitleId)
                                        container.cardContextMenuHost.dismiss()
                                    }
                                    return@CardContextMenuOverlay
                                }
                                actionScope.launch {
                                    container.watchlistCoordinator.perform(action, request)

                                    when (action.id) {
                                        dev.infusion.core.domain.watchlist.CardActionId.MARK_WATCHED,
                                        dev.infusion.core.domain.watchlist.CardActionId.MARK_UNWATCHED,

                                        dev.infusion.core.domain.watchlist.CardActionId.ADD_TO_JELLYFIN,
                                        -> Unit
                                        else -> container.cardContextMenuHost.dismiss()
                                    }
                                }
                            },
                            onRatingScore = { request, score ->

                                actionScope.launch {
                                    container.personalRatingsCoordinator.submit(request, score)
                                }
                                container.cardContextMenuHost.closeRating()
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val container = (application as InfusionTvApplication).container
        handleAddonDeepLink(intent, container)
    }

    override fun onStart() {
        super.onStart()
        (application as InfusionTvApplication).container.onAppForeground()
    }

    override fun onStop() {
        (application as InfusionTvApplication).container.onAppBackground()
        super.onStop()
    }

    override fun onDestroy() {

        (application as InfusionTvApplication).container.displayMatchingCoordinator
            .detachHost(displayModeHost)
        super.onDestroy()
    }

    private fun handleAddonDeepLink(intent: Intent?, container: dev.infusion.tv.di.AppContainer) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val link = AddonDeepLink.parse(intent.dataString) ?: return
        container.offerAddonDeepLink(link.addonUrl)
    }
}
