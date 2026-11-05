package dev.infusion.tv.ui.screens

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import dev.infusion.feature.vertical.addon.AddonItemOperation
import dev.infusion.feature.vertical.addon.AddonManagementItemUi
import dev.infusion.feature.vertical.addon.AddonManagementUiState
import dev.infusion.feature.vertical.addon.normalizedAddonDisplayName
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.presentation.NO_PRESS_DOWN_TIME
import dev.infusion.tv.ui.presentation.OpeningPressOwnership
import dev.infusion.tv.ui.presentation.centerHoldMenuModifier
import dev.infusion.tv.ui.presentation.consumeOpeningPress
import dev.infusion.tv.ui.theme.InfusionTvAppearance
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.infusionTvButtonColors

internal enum class AddonMenuAction(val label: String) {
    RENAME("Rename"),
    INFO("Info"),
    REFRESH("Refresh"),
    UNINSTALL("Uninstall"),
}

internal data class OpenAddonMenu(val addonId: String, val anchorKey: String)

internal class AddonContextMenuHostState {
    var open: OpenAddonMenu? by mutableStateOf(null)
        private set

    var infoOpen: Boolean by mutableStateOf(false)
        private set

    var renameOpen: Boolean by mutableStateOf(false)
        private set

    val anyOverlayOpen: Boolean get() = infoOpen || renameOpen

    private val openingPress = OpeningPressOwnership()

    private val infoPress = OpeningPressOwnership()

    private val renamePress = OpeningPressOwnership()

    private var activePressDownTime: Long = NO_PRESS_DOWN_TIME

    fun openOnPress(addonId: String, anchorKey: String, pressDownTime: Long) {
        open = OpenAddonMenu(addonId, anchorKey)
        infoOpen = false
        renameOpen = false
        activePressDownTime = NO_PRESS_DOWN_TIME
        infoPress.clear()
        renamePress.clear()
        openingPress.record(pressDownTime)
    }

    fun openAccessible(addonId: String, anchorKey: String) {
        open = OpenAddonMenu(addonId, anchorKey)
        infoOpen = false
        renameOpen = false
        activePressDownTime = NO_PRESS_DOWN_TIME
        infoPress.clear()
        renamePress.clear()
        openingPress.recordAccessible()
    }

    fun noteActivePress(downTime: Long) {
        activePressDownTime = downTime
    }

    fun openInfo() {
        if (open == null) return
        infoPress.record(activePressDownTime)
        infoOpen = true
    }

    fun closeInfo() {
        infoOpen = false
        infoPress.clear()
    }

    fun openRename() {
        if (open == null) return
        renamePress.record(activePressDownTime)
        renameOpen = true
    }

    fun closeRename() {
        renameOpen = false
        renamePress.clear()
    }

    fun dismiss() {
        open = null
        infoOpen = false
        renameOpen = false
        activePressDownTime = NO_PRESS_DOWN_TIME
        openingPress.clear()
        infoPress.clear()
        renamePress.clear()
    }

    fun ownsCenter(downTime: Long): Boolean =
        open != null && openingPress.owns(downTime)

    fun ownsInfoPress(downTime: Long): Boolean =
        infoOpen && infoPress.owns(downTime)

    fun ownsRenamePress(downTime: Long): Boolean =
        renameOpen && renamePress.owns(downTime)
}

internal fun addonContextMenuPanelKey(): String = "addon-context-menu:panel"

internal fun addonMenuActionKey(action: AddonMenuAction): String =
    "addon-context-menu:action:${action.name.lowercase()}"

internal fun addonInfoOverlayPanelKey(): String = "addon-info-overlay:panel"

internal fun addonInfoOverlayContentKey(): String = "addon-info-overlay:content"

internal fun addonRenameOverlayPanelKey(): String = "addon-rename-overlay:panel"

internal fun addonRenameOverlayFieldKey(): String = "addon-rename-overlay:field"

internal fun addonRenameOverlayFeedbackKey(): String = "addon-rename-overlay:feedback"

internal fun addonContextMenuRefreshFeedbackKey(): String = "addon-context-menu:refresh-feedback"

internal fun AddonManagementUiState.installedAddon(addonId: String): AddonManagementItemUi? =
    installed.firstOrNull { it.addonId == addonId }

internal fun addonMenuActionBlockedReason(
    state: AddonManagementUiState,
    item: AddonManagementItemUi,
    action: AddonMenuAction,
): String? = when (action) {
    AddonMenuAction.INFO -> null
    AddonMenuAction.RENAME,
    AddonMenuAction.REFRESH,
    AddonMenuAction.UNINSTALL,
    -> when {
        !item.canManage -> "Read-only add-on"
        state.isBusy(item.addonId) -> "Busy"
        else -> null
    }
}

internal fun addonMenuActionText(action: AddonMenuAction, blockedReason: String?): String =
    if (blockedReason == null) action.label else "${action.label} — $blockedReason"

internal fun addonInfoLines(item: AddonManagementItemUi): List<String> = buildList {
    add("Name: ${item.displayName}")
    add(item.origin.takeIf { it.isNotBlank() }?.let { "URL: $it" } ?: "URL unavailable")
    add(
        item.healthLabel.takeIf { it.isNotBlank() }?.let { "Health: $it" }
            ?: "Health: unknown",
    )
    add(
        if (item.requestedResourceTypes.isEmpty()) {
            "Capabilities: none reported"
        } else {
            "Capabilities: ${item.requestedResourceTypes.joinToString(", ")}"
        },
    )
    if (item.refreshFailed) {
        add("Last refresh failed (${item.refreshFailureReason ?: "unknown"}) — last validated manifest kept")
    }
}

@Composable
internal fun AddonContextMenuOverlay(
    host: AddonContextMenuHostState,
    state: AddonManagementUiState,
    onDismiss: () -> Unit,
    onAction: (AddonMenuAction, AddonManagementItemUi) -> Unit,
) {
    val open = host.open ?: return

    val item = state.installedAddon(open.addonId) ?: return
    val requesters = remember { mutableMapOf<String, FocusRequester>() }

    val entryAction = AddonMenuAction.entries.first()

    var refreshedHere by remember(open) { mutableStateOf(false) }

    LaunchedEffect(open) {
        repeat(30) {
            withFrameNanos { }
            if (requesters[addonMenuActionKey(entryAction)]?.requestFocus() == true) {
                return@LaunchedEffect
            }
        }
    }

    var openOverlayAction by remember(open) { mutableStateOf<AddonMenuAction?>(null) }
    LaunchedEffect(host.infoOpen, host.renameOpen) {
        val current = when {
            host.infoOpen -> AddonMenuAction.INFO
            host.renameOpen -> AddonMenuAction.RENAME
            else -> null
        }
        val previous = openOverlayAction
        openOverlayAction = current
        if (previous != null && current == null) {
            val target = addonMenuActionKey(previous)
            repeat(30) {
                withFrameNanos { }
                if (requesters[target]?.requestFocus() == true) return@LaunchedEffect
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = !host.anyOverlayOpen) {
        onDismiss()
    }

    Dialog(

        onDismissRequest = { onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val localView = LocalView.current

        SideEffect {
            val window = (localView.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            window.setDimAmount(0f)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(addonContextMenuPanelKey())
                .background(InfusionTvAppearance.OledBlack.copy(alpha = AddonMenuScrimAlpha))

                .consumeOpeningPress(host::ownsCenter)

                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
                        host.noteActivePress(event.nativeKeyEvent.downTime)
                    }
                    false
                }

                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || event.key != Key.Back) {
                        return@onPreviewKeyEvent false
                    }
                    onDismiss()
                    true
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.widthIn(min = AddonMenuMinWidth, max = AddonMenuMaxWidth),
                shape = MaterialTheme.shapes.medium,
                colors = SurfaceDefaults.colors(
                    containerColor = InfusionTvAppearance.Panel.copy(alpha = AddonMenuSurfaceAlpha),
                ),
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = AddonMenuMinWidth, max = AddonMenuMaxWidth)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        item.displayName,
                        style = InfusionTvScaleCalibration.Type.Headline,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )

                    if (refreshedHere) {
                        val operation = state.operationFor(item.addonId)
                        val feedback = when (operation) {
                            is AddonItemOperation.Refreshing -> "Refreshing…"
                            is AddonItemOperation.Failed -> operation.message
                            else -> state.actionMessage
                        }
                        if (feedback != null) {
                            Text(
                                feedback,
                                style = InfusionTvScaleCalibration.Type.Body,
                                color = if (operation is AddonItemOperation.Failed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    androidx.tv.material3.LocalContentColor.current
                                },
                                modifier = Modifier.testTag(addonContextMenuRefreshFeedbackKey()),
                            )
                        }
                    }

                    AddonMenuAction.entries.forEach { action ->
                        val blocked = addonMenuActionBlockedReason(state, item, action)
                        InfusionTvButton(
                            onClick = {
                                when (action) {

                                    AddonMenuAction.RENAME -> host.openRename()
                                    AddonMenuAction.INFO -> host.openInfo()
                                    AddonMenuAction.REFRESH -> {
                                        refreshedHere = true
                                        onAction(AddonMenuAction.REFRESH, item)
                                    }
                                    AddonMenuAction.UNINSTALL -> onAction(AddonMenuAction.UNINSTALL, item)
                                }
                            },
                            colors = infusionTvButtonColors(containerColor = Color.Transparent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(stableFocusModifier(addonMenuActionKey(action), requesters, {}))
                                .then(
                                    if (blocked == null) Modifier
                                    else Modifier.focusProperties { canFocus = false },
                                ),
                        ) {
                            Text(addonMenuActionText(action, blocked))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AddonInfoOverlay(
    host: AddonContextMenuHostState,
    state: AddonManagementUiState,
) {
    if (!host.infoOpen) return
    val open = host.open ?: return
    val item = state.installedAddon(open.addonId) ?: return

    val requester = remember { FocusRequester() }
    LaunchedEffect(open.addonId) {
        repeat(30) {
            withFrameNanos { }
            if (requester.requestFocus()) return@LaunchedEffect
        }
    }

    androidx.activity.compose.BackHandler(enabled = true) { host.closeInfo() }

    Dialog(
        onDismissRequest = { host.closeInfo() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val localView = LocalView.current
        SideEffect {
            val window = (localView.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            window.setDimAmount(0f)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(addonInfoOverlayPanelKey())
                .background(InfusionTvAppearance.OledBlack.copy(alpha = AddonMenuScrimAlpha))

                .consumeOpeningPress(host::ownsInfoPress)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || event.key != Key.Back) {
                        return@onPreviewKeyEvent false
                    }
                    host.closeInfo()
                    true
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.widthIn(min = AddonMenuMinWidth, max = AddonMenuMaxWidth),
                shape = MaterialTheme.shapes.medium,
                colors = SurfaceDefaults.colors(
                    containerColor = InfusionTvAppearance.Panel.copy(alpha = AddonMenuSurfaceAlpha),
                ),
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = AddonMenuMinWidth, max = AddonMenuMaxWidth)
                        .padding(16.dp)
                        .testTag(addonInfoOverlayContentKey())
                        .focusRequester(requester)

                        .focusable(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Info", style = InfusionTvScaleCalibration.Type.Headline)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        addonInfoLines(item).forEach { line ->
                            Text(line, style = InfusionTvScaleCalibration.Type.Body)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AddonRenameOverlay(
    host: AddonContextMenuHostState,
    state: AddonManagementUiState,
    onSave: (AddonManagementItemUi, String) -> Unit,
) {
    if (!host.renameOpen) return
    val open = host.open ?: return

    val item = state.installedAddon(open.addonId) ?: return

    var name by remember(open.addonId) { mutableStateOf(item.displayName) }
    var rejected by remember(open.addonId) { mutableStateOf(false) }

    var saved by remember(open.addonId) { mutableStateOf(false) }

    val requester = remember { FocusRequester() }
    LaunchedEffect(open.addonId) {
        repeat(30) {
            withFrameNanos { }
            if (requester.requestFocus()) return@LaunchedEffect
        }
    }

    androidx.activity.compose.BackHandler(enabled = true) { host.closeRename() }

    Dialog(

        onDismissRequest = { host.closeRename() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val localView = LocalView.current
        SideEffect {
            val window = (localView.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            window.setDimAmount(0f)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(addonRenameOverlayPanelKey())
                .background(InfusionTvAppearance.OledBlack.copy(alpha = AddonMenuScrimAlpha))

                .consumeOpeningPress(host::ownsRenamePress)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || event.key != Key.Back) {
                        return@onPreviewKeyEvent false
                    }
                    host.closeRename()
                    true
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.widthIn(min = AddonMenuMinWidth, max = AddonMenuMaxWidth),
                shape = MaterialTheme.shapes.medium,
                colors = SurfaceDefaults.colors(
                    containerColor = InfusionTvAppearance.Panel.copy(alpha = AddonMenuSurfaceAlpha),
                ),
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = AddonMenuMinWidth, max = AddonMenuMaxWidth)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Rename", style = InfusionTvScaleCalibration.Type.Headline)
                    LabeledField(
                        label = "Add-on name",
                        value = name,
                        onChange = {
                            name = it

                            rejected = false
                        },
                        testTag = addonRenameOverlayFieldKey(),
                        focusRequester = requester,
                        imeAction = ImeAction.Done,
                        onImeSubmit = {
                            if (!saved) {
                                val normalized = normalizedAddonDisplayName(name)
                                if (normalized == null) {

                                    rejected = true
                                } else {
                                    saved = true

                                    host.closeRename()
                                    onSave(item, normalized)
                                }
                            }
                        },
                    )
                    if (rejected) {
                        Text(
                            "Enter a name.",
                            style = InfusionTvScaleCalibration.Type.Body,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(addonRenameOverlayFeedbackKey()),
                        )
                    }
                }
            }
        }
    }
}

private val AddonMenuMinWidth = 260.dp
private val AddonMenuMaxWidth = 360.dp

private const val AddonMenuSurfaceAlpha = 0.72f

private const val AddonMenuScrimAlpha = 0.30f
