package dev.infusion.tv.ui.presentation

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.infusion.core.domain.watchlist.CardAction
import dev.infusion.core.domain.watchlist.CardActionAvailability
import dev.infusion.core.domain.watchlist.CardActionId
import dev.infusion.core.domain.watchlist.CardActionOwner
import dev.infusion.core.domain.watchlist.CardActionRequest
import dev.infusion.core.domain.watchlist.CardActionSurface
import dev.infusion.core.domain.watchlist.RemoteOutcome
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.tv.ratings.formatPersonalRatingValue
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.theme.InfusionTvAppearance
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.infusionTvButtonColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val LocalCardActionOwner = compositionLocalOf<CardActionOwner?> { null }

val LocalCardContextMenuHost = compositionLocalOf<CardContextMenuHostState?> { null }

val LocalAcquisitionRevision = compositionLocalOf { 0L }

val LocalPersonalRatings = compositionLocalOf<dev.infusion.tv.ratings.PersonalRatingsCoordinator?> { null }

val LocalPersonalRatingRevision = compositionLocalOf { 0L }

class CardContextMenuHostState {
    var open: OpenMenu? by mutableStateOf(null)
        private set

    var musicOpen: OpenMusicMenu? by mutableStateOf(null)
        private set

    val anyOpen: Boolean get() = open != null || musicOpen != null

    var ratingRequest: CardActionRequest? by mutableStateOf(null)
        private set

    fun openRating(request: CardActionRequest) {
        if (open != null) ratingRequest = request
    }

    fun closeRating() {
        ratingRequest = null
    }

    private val openingPress = OpeningPressOwnership()

    fun openMenu(request: CardActionRequest, anchorKey: String, pressDownTime: Long) {
        open = OpenMenu(request, anchorKey)
        openingPress.record(pressDownTime)
    }

    fun openMenuAccessible(request: CardActionRequest, anchorKey: String) {
        open = OpenMenu(request, anchorKey)
        openingPress.recordAccessible()
    }

    fun pressOwnsCenter(downTime: Long): Boolean =
        anyOpen && openingPress.owns(downTime)

    fun dismiss() {
        open = null
        ratingRequest = null
        openingPress.clear()
    }

    fun openMusicMenu(request: MusicMenuRequest, anchorKey: String, pressDownTime: Long) {
        musicOpen = OpenMusicMenu(request, anchorKey)
        openingPress.record(pressDownTime)
    }

    fun openMusicMenuAccessible(request: MusicMenuRequest, anchorKey: String) {
        musicOpen = OpenMusicMenu(request, anchorKey)
        openingPress.recordAccessible()
    }

    fun dismissMusicMenu() {
        musicOpen = null
        openingPress.clear()
    }
}

internal const val NO_PRESS_DOWN_TIME: Long = Long.MIN_VALUE

internal class OpeningPressOwnership {
    private var downTime: Long = NO_PRESS_DOWN_TIME

    fun record(downTime: Long) {
        this.downTime = downTime
    }

    fun recordAccessible() {
        downTime = NO_PRESS_DOWN_TIME
    }

    fun clear() {
        downTime = NO_PRESS_DOWN_TIME
    }

    fun owns(downTime: Long): Boolean =
        this.downTime != NO_PRESS_DOWN_TIME && downTime == this.downTime
}

data class OpenMenu(val request: CardActionRequest, val anchorKey: String)

data class OpenMusicMenu(val request: MusicMenuRequest, val anchorKey: String)

@Composable
fun rememberCardActionRequest(
    surface: CardActionSurface,
    displayTitle: String,
    titleId: TitleId?,
    kind: TitleKind?,
    watchKey: WatchKey?,
    year: Int? = null,
    artwork: MediaArtworkRef? = null,
    enabled: Boolean = true,
    seasonNumber: Int? = null,

    playableTitleId: TitleId? = null,
): CardActionRequest? {
    val owner = LocalCardActionOwner.current
    val host = LocalCardContextMenuHost.current
    return remember(
        titleId, watchKey, kind, surface, enabled, owner, host, displayTitle, year, artwork, seasonNumber,
        playableTitleId,
    ) {
        if (!enabled || owner == null || host == null || (titleId == null && watchKey == null)) {
            null
        } else {
            CardActionRequest(
                titleId = titleId,
                kind = kind,
                watchKey = watchKey,
                surface = surface,
                displayTitle = displayTitle,
                year = year,

                artwork = artwork?.let {
                    dev.infusion.core.domain.watchlist.WatchlistArtwork(
                        locator = it.locator,
                        policy = it.policy,
                        landscapeLocator = it.landscapeLocator,
                    )
                },
                seasonNumber = seasonNumber,
                playableTitleId = playableTitleId,
            )
        }
    }
}

@Composable
fun rememberCardActionRequest(
    preview: MediaPreview,
    kind: TitleKind?,
    surface: CardActionSurface,
    enabled: Boolean = true,
): CardActionRequest? = rememberCardActionRequest(
    surface = surface,
    displayTitle = preview.displayTitle,
    titleId = preview.titleId,
    kind = kind,
    watchKey = preview.watchKey,
    year = preview.year,
    artwork = preview.artwork,
    enabled = enabled,

    playableTitleId = preview.episodeTitleId,
)

@Composable
fun cardContextActionModifier(focusKey: String, request: CardActionRequest?): Modifier {
    val host = LocalCardContextMenuHost.current
    if (request == null || host == null) return Modifier
    val owner = LocalCardActionOwner.current
    return centerHoldMenuModifier(
        focusKey = focusKey,
        isOpenForMe = { host.open?.anchorKey == focusKey },
        isAnyMenuOpen = { host.anyOpen },

        onPressStarted = { owner?.warm(request) },
        onHeldThreshold = { downTime -> host.openMenu(request, focusKey, downTime) },
        onOpenAccessible = { host.openMenuAccessible(request, focusKey) },
    )
}

@Composable
fun musicContextActionModifier(focusKey: String, request: MusicMenuRequest?): Modifier {
    val host = LocalCardContextMenuHost.current
    if (request == null || host == null) return Modifier
    return centerHoldMenuModifier(
        focusKey = focusKey,
        isOpenForMe = { host.musicOpen?.anchorKey == focusKey },
        isAnyMenuOpen = { host.anyOpen },
        onHeldThreshold = { downTime -> host.openMusicMenu(request, focusKey, downTime) },
        onOpenAccessible = { host.openMusicMenuAccessible(request, focusKey) },
    )
}

@Composable
internal fun centerHoldMenuModifier(
    focusKey: String,
    isOpenForMe: () -> Boolean,
    isAnyMenuOpen: () -> Boolean,
    onHeldThreshold: (downTime: Long) -> Unit,
    onOpenAccessible: () -> Unit,
    onPressStarted: () -> Unit = {},
): Modifier {
    val scope = rememberCoroutineScope()
    var holdJob by remember(focusKey) { mutableStateOf<Job?>(null) }
    return Modifier
        .onPreviewKeyEvent { event ->
            if (event.key != Key.DirectionCenter) return@onPreviewKeyEvent false
            val native = event.nativeKeyEvent
            val menuOpenForMe = isOpenForMe()
            when (event.type) {
                KeyEventType.KeyDown -> when {

                    menuOpenForMe -> true

                    native.isLongPress && !isAnyMenuOpen() -> {
                        holdJob?.cancel()
                        onHeldThreshold(native.downTime)
                        true
                    }

                    !native.isLongPress && native.repeatCount == 0 && !isAnyMenuOpen() -> {
                        holdJob?.cancel()
                        onPressStarted()
                        val downTime = native.downTime
                        holdJob = scope.launch {
                            delay(LONG_PRESS_MENU_MS.toLong())

                            if (!isAnyMenuOpen()) onHeldThreshold(downTime)
                        }
                        false
                    }
                    else -> false
                }
                KeyEventType.KeyUp -> {
                    holdJob?.cancel()
                    holdJob = null

                    menuOpenForMe
                }
                else -> false
            }
        }
        .semantics {

            customActions = listOf(
                CustomAccessibilityAction(label = "Actions") {
                    onOpenAccessible()
                    true
                },
            )
        }
}

internal fun Modifier.consumeOpeningPress(ownsPress: (Long) -> Boolean): Modifier =
    onPreviewKeyEvent { event ->
        if (event.key != Key.DirectionCenter) return@onPreviewKeyEvent false
        ownsPress(event.nativeKeyEvent.downTime)
    }

@Composable
fun CardContextMenuOverlay(
    host: CardContextMenuHostState,
    owner: CardActionOwner?,
    onAction: (CardAction, CardActionRequest) -> Unit,

    onRatingScore: (CardActionRequest, Int) -> Unit = { _, _ -> },

    onMusicAction: (MusicMenuActionId, MusicMenuRequest) -> Unit = { _, _ -> },
) {

    val music = host.musicOpen
    if (music != null) {
        MusicContextMenuOverlay(host = host, menu = music, onAction = onMusicAction)
        return
    }
    val state = host.open
    if (state == null || owner == null) return

    val ratingRequest = host.ratingRequest
    val ratings = LocalPersonalRatings.current

    val watchedState = LocalCardWatchedState.current
    val acquisitionRevision = LocalAcquisitionRevision.current

    val cardAvailability = dev.infusion.tv.ui.presentation.LocalCardAvailability.current

    val ratingRevision = LocalPersonalRatingRevision.current
    val actions = remember(state, watchedState, acquisitionRevision, cardAvailability, ratingRevision) {
        owner.actionsFor(state.request)
    }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }

    LaunchedEffect(state) {

        owner.warm(state.request)
        repeat(30) {
            withFrameNanos { }
            if (requesters["ctx:0"]?.requestFocus() == true) return@LaunchedEffect
        }
    }

    var ratingWasOpen by remember(state) { mutableStateOf(false) }
    LaunchedEffect(ratingRequest) {
        if (ratingRequest != null) {
            ratingWasOpen = true
        } else if (ratingWasOpen) {
            repeat(30) {
                withFrameNanos { }
                val index = actions.indexOfFirst { it.id == CardActionId.SET_RATING }
                val key = if (index >= 0) "ctx:$index" else "ctx:0"
                if (requesters[key]?.requestFocus() == true) {
                    ratingWasOpen = false
                    return@LaunchedEffect
                }
            }
            ratingWasOpen = false
        }
    }
    androidx.activity.compose.BackHandler(
        enabled = true,
        onBack = {

            if (host.ratingRequest != null) host.closeRating() else host.dismiss()
        },
    )

    Dialog(
        onDismissRequest = { host.dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val view = LocalView.current

        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            window.setDimAmount(0f)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(cardContextMenuPanelKey())

                .background(InfusionTvAppearance.OledBlack.copy(alpha = SCRIM_ALPHA))

                .consumeOpeningPress(host::pressOwnsCenter),
            contentAlignment = Alignment.Center,
        ) {

            androidx.tv.material3.Surface(
                modifier = Modifier.widthIn(min = MENU_MIN_WIDTH, max = MENU_MAX_WIDTH),
                shape = MaterialTheme.shapes.medium,
                colors = androidx.tv.material3.SurfaceDefaults.colors(
                    containerColor = InfusionTvAppearance.Panel.copy(alpha = MENU_SURFACE_ALPHA),
                ),
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = MENU_MIN_WIDTH, max = MENU_MAX_WIDTH)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {

                    if (ratingRequest != null && ratings != null) {
                        PersonalRatingPanel(
                            request = ratingRequest,
                            ratings = ratings,
                            requesters = requesters,
                            onSelected = { score -> onRatingScore(ratingRequest, score) },
                        )
                    }
                    if (ratingRequest == null) {
                    Text(
                        state.request.displayTitle,
                        style = InfusionTvScaleCalibration.Type.Headline,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    if (actions.isEmpty()) {
                        Text("No actions available.", style = InfusionTvScaleCalibration.Type.Body)
                    }
                    actions.forEachIndexed { index, action ->
                        val enabled = action.availability is CardActionAvailability.Enabled
                        InfusionTvButton(
                            onClick = {
                                if (enabled) {
                                    onAction(action, state.request)
                                }
                            },
                            colors = infusionTvButtonColors(

                                containerColor = Color.Transparent,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(cardContextMenuItemKey(index))
                                .then(stableFocusModifier("ctx:$index", requesters, {}))
                                .then(
                                    if (enabled) Modifier
                                    else Modifier.focusProperties { canFocus = false },
                                ),
                        ) {
                            val reason = (action.availability as? CardActionAvailability.Disabled)?.reason
                            val detail = action.detail?.takeIf { it.isNotBlank() }

                            val label = if (enabled || detail != null) action.label else "${action.label} — ${reason ?: "unavailable"}"
                            Text(
                                label,

                                modifier = if (detail != null) Modifier.weight(1f) else Modifier,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )

                            if (detail != null) {
                                Spacer(Modifier.width(16.dp))
                                Text(
                                    detail,
                                    style = InfusionTvScaleCalibration.Type.Body,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

internal const val LONG_PRESS_MENU_MS: Int = 550

internal fun cardContextMenuPanelKey(): String = "card-context-menu:panel"

internal fun cardContextMenuItemKey(index: Int): String = "card-context-menu:item:$index"

internal fun cardRatingStateKey(): String = "card-context-menu:rating:state"
internal fun cardRatingScoreKey(score: Int): String = "card-context-menu:rating:score:$score"

@Composable
private fun PersonalRatingPanel(
    request: CardActionRequest,
    ratings: dev.infusion.tv.ratings.PersonalRatingsCoordinator,
    requesters: MutableMap<String, FocusRequester>,
    onSelected: (Int) -> Unit,
) {
    val scale = ratings.scoresFor(request) ?: return
    val scores = scale.toList()
    if (scores.isEmpty()) return
    val state = ratings.stateFor(request)
    val known = state.knownValue

    val initial = scores.indexOf(known).takeIf { it >= 0 }
        ?: scores.indexOf(DEFAULT_SCORE).takeIf { it >= 0 }
        ?: 0

    LaunchedEffect(request, scores, known) {
        repeat(30) {
            withFrameNanos { }
            if (requesters["rate:$initial"]?.requestFocus() == true) return@LaunchedEffect
        }
    }

    Column(
        modifier = Modifier
            .widthIn(min = MENU_MIN_WIDTH, max = MENU_MAX_WIDTH)
            .testTag(cardRatingPanelKey()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            request.displayTitle,
            style = InfusionTvScaleCalibration.Type.Headline,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )

        val stateLine = personalRatingStateLine(state)
        if (stateLine.isNotBlank()) {
            Text(
                stateLine,
                style = InfusionTvScaleCalibration.Type.Body,
                modifier = Modifier.testTag(cardRatingStateKey()),
            )
        }
        val delivery = ratings.deliveryFor(request)
        if (delivery !is RemoteOutcome.None) {
            Text(personalRatingDeliveryLine(delivery), style = InfusionTvScaleCalibration.Type.Body)
        }

        ratings.sideEffectNoteFor(request)?.let {
            Text(it, style = InfusionTvScaleCalibration.Type.Body, maxLines = 2)
        }
        scores.chunked(SCORE_ROW).forEach { rowScores ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowScores.forEach { score ->
                    val index = scores.indexOf(score)
                    InfusionTvButton(
                        onClick = { onSelected(score) },
                        colors = infusionTvButtonColors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .testTag(cardRatingScoreKey(score))
                            .then(stableFocusModifier("rate:$index", requesters, {})),
                    ) {
                        Text(if (score == known) "$score ✓" else "$score")
                    }
                }
            }
        }
    }
}

internal fun personalRatingStateLine(state: dev.infusion.core.domain.ratings.PersonalRatingState): String =
    when (state) {
        is dev.infusion.core.domain.ratings.PersonalRatingState.Rated -> ""

        is dev.infusion.core.domain.ratings.PersonalRatingState.RatedFractional ->
            "Your rating: ${formatPersonalRatingValue(state.precise)}"
        dev.infusion.core.domain.ratings.PersonalRatingState.Unrated -> "Not rated"
        dev.infusion.core.domain.ratings.PersonalRatingState.Loading -> "Checking your rating…"
        dev.infusion.core.domain.ratings.PersonalRatingState.Unknown -> "Current rating unknown"

        is dev.infusion.core.domain.ratings.PersonalRatingState.Failed -> "Current rating unknown"
    }

internal fun personalRatingDeliveryLine(outcome: RemoteOutcome): String = when (outcome) {
    RemoteOutcome.None -> ""
    RemoteOutcome.Pending -> "Saving…"
    RemoteOutcome.Delivered -> "Saved to your tracker"
    is RemoteOutcome.Failed -> "Not saved — ${outcome.reason}"
    is RemoteOutcome.Unsupported -> "Not saved — ${outcome.reason}"
}

internal fun cardRatingPanelKey(): String = "card-context-menu:rating"

private const val SCORE_ROW = 5

private const val DEFAULT_SCORE = 8

private val MENU_MIN_WIDTH = 260.dp
private val MENU_MAX_WIDTH = 360.dp

private const val MENU_SURFACE_ALPHA = 0.72f

private const val SCRIM_ALPHA = 0.30f
