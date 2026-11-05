package dev.infusion.tv.ui.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.infusion.core.domain.music.MusicQueueEntry
import dev.infusion.core.domain.music.MusicQueueSlotId
import dev.infusion.feature.vertical.jellyfin.JellyfinTabRowItem
import dev.infusion.tv.music.asQueueOccurrence
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.theme.InfusionTvAppearance
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.infusionTvButtonColors

enum class MusicMenuActionId {

    PLAY_NEXT_INSERT,

    ADD_TO_QUEUE,

    PLAY_NEXT_MOVE,

    MOVE_UP,

    MOVE_DOWN,

    REMOVE,
}

data class MusicMenuAction(
    val id: MusicMenuActionId,
    val label: String,
    val enabled: Boolean = true,
)

data class MusicMenuRequest(
    val title: String,
    val actions: List<MusicMenuAction>,
    val insertable: MusicQueueEntry? = null,
    val slot: MusicQueueSlotId? = null,
)

fun musicContextMenuItemKey(index: Int): String = "music-context-menu:item:$index"

fun musicContextMenuPanelKey(): String = "music-context-menu:panel"

fun browsingSongMenuRequest(row: JellyfinTabRowItem): MusicMenuRequest = MusicMenuRequest(
    title = row.displayTitle,
    actions = listOf(
        MusicMenuAction(MusicMenuActionId.PLAY_NEXT_INSERT, "Play next"),
        MusicMenuAction(MusicMenuActionId.ADD_TO_QUEUE, "Add to queue"),
    ),
    insertable = row.asQueueOccurrence(),
)

fun queueRowMenuRequest(
    title: String,
    slot: MusicQueueSlotId,
    position: Int,
    total: Int,
    isCurrent: Boolean,
): MusicMenuRequest = MusicMenuRequest(
    title = title,
    actions = listOf(
        MusicMenuAction(
            id = MusicMenuActionId.PLAY_NEXT_MOVE,
            label = "Play next",
            enabled = !isCurrent && total > 1,
        ),
        MusicMenuAction(
            id = MusicMenuActionId.MOVE_UP,
            label = "Move up",
            enabled = position > 0,
        ),
        MusicMenuAction(
            id = MusicMenuActionId.MOVE_DOWN,
            label = "Move down",
            enabled = position < total - 1,
        ),
        MusicMenuAction(id = MusicMenuActionId.REMOVE, label = "Remove"),
    ),
    slot = slot,
)

@Composable
fun MusicContextMenuOverlay(
    host: CardContextMenuHostState,
    menu: OpenMusicMenu,
    onAction: (MusicMenuActionId, MusicMenuRequest) -> Unit,
) {
    val request = menu.request
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val enabled = request.actions.filter { it.enabled }

    LaunchedEffect(menu) {
        repeat(30) {
            androidx.compose.runtime.withFrameNanos { }
            if (requesters["music-ctx:0"]?.requestFocus() == true) return@LaunchedEffect
        }
    }
    androidx.activity.compose.BackHandler(enabled = true, onBack = { host.dismissMusicMenu() })

    Dialog(
        onDismissRequest = { host.dismissMusicMenu() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(musicContextMenuPanelKey())
                .background(InfusionTvAppearance.OledBlack.copy(alpha = MUSIC_MENU_SCRIM_ALPHA))

                .consumeOpeningPress(host::pressOwnsCenter),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.widthIn(min = MUSIC_MENU_MIN_WIDTH, max = MUSIC_MENU_MAX_WIDTH),
                shape = MaterialTheme.shapes.medium,
                colors = androidx.tv.material3.SurfaceDefaults.colors(
                    containerColor = InfusionTvAppearance.Panel.copy(alpha = MUSIC_MENU_SURFACE_ALPHA),
                ),
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = MUSIC_MENU_MIN_WIDTH, max = MUSIC_MENU_MAX_WIDTH)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        request.title,
                        style = InfusionTvScaleCalibration.Type.Headline,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    if (enabled.isEmpty()) {
                        Text("No actions available.", style = InfusionTvScaleCalibration.Type.Body)
                    }
                    request.actions.forEachIndexed { index, action ->
                        val focusKey = "music-ctx:$index"
                        InfusionTvButton(
                            onClick = {
                                if (action.enabled) {
                                    host.dismissMusicMenu()
                                    onAction(action.id, request)
                                }
                            },
                            colors = infusionTvButtonColors(containerColor = Color.Transparent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(musicContextMenuItemKey(index))
                                .then(stableFocusModifier(focusKey, requesters, {}))
                                .then(
                                    if (action.enabled) Modifier
                                    else Modifier.focusProperties { canFocus = false },
                                ),
                        ) {
                            Text(
                                if (action.enabled) action.label else "${action.label} — unavailable",
                                style = InfusionTvScaleCalibration.Type.Emphasis,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val MUSIC_MENU_MIN_WIDTH = 260.dp
private val MUSIC_MENU_MAX_WIDTH = 360.dp

private const val MUSIC_MENU_SURFACE_ALPHA = 0.72f

private const val MUSIC_MENU_SCRIM_ALPHA = 0.30f
