package dev.infusion.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import dev.infusion.core.artworkimage.ArtworkImage
import dev.infusion.core.artworkimage.ArtworkRequest
import dev.infusion.core.domain.jellyfin.JellyfinDiscoveredServer
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.feature.vertical.acquisition.AcquisitionConnectionStateHolder
import dev.infusion.feature.vertical.acquisition.AcquisitionConnectionUiState
import dev.infusion.feature.vertical.acquisition.Phase
import dev.infusion.feature.vertical.setup.JellyfinConnectedUser
import dev.infusion.feature.vertical.setup.JellyfinConnectionStateHolder
import dev.infusion.feature.vertical.setup.JellyfinConnectionStatus
import dev.infusion.feature.vertical.setup.JellyfinDiscoveryState
import dev.infusion.feature.vertical.setup.JellyfinServerDiscoveryStateHolder
import dev.infusion.feature.vertical.setup.JellyfinSetupStateHolder
import dev.infusion.feature.vertical.setup.JellyfinSetupStatus
import dev.infusion.feature.vertical.setup.JellyfinUserChoice
import dev.infusion.tv.ui.theme.InfusionTvAppearance
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.LocalInfusionTheme
import dev.infusion.tv.ui.theme.infusionAccentContentColor
import dev.infusion.tv.ui.theme.themeAvatarHalo
import dev.infusion.tv.ui.theme.themeStatusPill

internal const val JellyfinFocusCredentials = "jellyfin:connect:credentials"
internal const val JellyfinFocusApiKey = "jellyfin:connect:api-key"
internal const val JellyfinFocusQuickConnect = "jellyfin:connect:quick-connect"
internal const val JellyfinFocusRadarr = "jellyfin:connect:radarr"
internal const val JellyfinFocusSonarr = "jellyfin:connect:sonarr"

const val SettingsJellyfinOverlay = "settings:jellyfin:overlay"
const val SettingsJellyfinOverlayStatus = "settings:jellyfin:overlay:status"
const val SettingsJellyfinCredentialsUrl = "settings:jellyfin:credentials:url"
const val SettingsJellyfinCredentialsUsername = "settings:jellyfin:credentials:username"
const val SettingsJellyfinCredentialsPassword = "settings:jellyfin:credentials:password"
const val SettingsJellyfinCredentialsTest = "settings:jellyfin:credentials:test"
const val SettingsJellyfinCredentialsSave = "settings:jellyfin:credentials:save"
const val SettingsJellyfinApiKeyUrl = "settings:jellyfin:apikey:url"
const val SettingsJellyfinApiKeyKey = "settings:jellyfin:apikey:key"
const val SettingsJellyfinApiKeyUser = "settings:jellyfin:apikey:user"
const val SettingsJellyfinApiKeyLoadUsers = "settings:jellyfin:apikey:load-users"
const val SettingsJellyfinApiKeyTest = "settings:jellyfin:apikey:test"
const val SettingsJellyfinApiKeySave = "settings:jellyfin:apikey:save"
const val SettingsJellyfinQuickConnectUrl = "settings:jellyfin:quick-connect:url"
const val SettingsJellyfinQuickConnectCode = "settings:jellyfin:quick-connect:code"
const val SettingsJellyfinQuickConnectState = "settings:jellyfin:quick-connect:state"
const val SettingsJellyfinDiscovery = "settings:jellyfin:discovery"
const val SettingsJellyfinDiscoveryStatus = "settings:jellyfin:discovery:status"
const val SettingsJellyfinConnectedUserName = "settings:jellyfin:connected-user:name"
const val SettingsJellyfinConnectedUserAvatar = "settings:jellyfin:connected-user:avatar"
const val SettingsJellyfinConnectedUserAvatarImage = "settings:jellyfin:connected-user:avatar-image"

const val SettingsJellyfinSummary = "settings:jellyfin:summary"
const val SettingsJellyfinSummarySource = "settings:jellyfin:summary:source"

fun jellyfinDiscoveryServerTag(serverId: String): String = "settings:jellyfin:discovery:server:$serverId"

fun jellyfinAcquisitionStatusTag(serviceId: String): String = "settings:jellyfin:$serviceId:status"

fun jellyfinAcquisitionRootPickerTag(serviceId: String): String = "settings:jellyfin:$serviceId:root-picker"

fun jellyfinAcquisitionProfilePickerTag(serviceId: String): String = "settings:jellyfin:$serviceId:profile-picker"

private enum class JellyfinOverlayKind { NONE, CREDENTIALS, API_KEY, QUICK_CONNECT, RADARR, SONARR }

@Composable
internal fun TvJellyfinSection(
    setup: JellyfinSetupStateHolder?,
    connection: JellyfinConnectionStateHolder?,
    connectedUser: JellyfinConnectedUser?,
    acquisition: List<AcquisitionConnectionStateHolder>,
    discovery: JellyfinServerDiscoveryStateHolder?,
    sourceLabel: String?,
    requesters: MutableMap<String, FocusRequester>,
    entryToken: Int,
    rememberedKey: String?,
    onFocusRequested: (String) -> Unit,
    onReturnToRail: () -> Unit,
) {
    var openOverlay by remember { mutableStateOf(JellyfinOverlayKind.NONE) }
    var focusedRowKey by remember { mutableStateOf(JellyfinFocusCredentials) }
    var restoreKey by remember { mutableStateOf<String?>(null) }

    val credentialsAvailable = connection != null
    val apiKeyAvailable = connection != null
    val quickConnectAvailable = connection != null
    val radarr = acquisition.firstOrNull { it.serviceId == "radarr" }
    val sonarr = acquisition.firstOrNull { it.serviceId == "sonarr" }
    val removeAvailable = setup != null && sourceLabel != null

    val rowKeys = buildList {
        if (credentialsAvailable) add(JellyfinFocusCredentials)
        if (apiKeyAvailable) add(JellyfinFocusApiKey)
        if (quickConnectAvailable) add(JellyfinFocusQuickConnect)
        if (radarr != null) add(JellyfinFocusRadarr)
        if (sonarr != null) add(JellyfinFocusSonarr)
        if (removeAvailable) add(JellyfinFocusRemove)
    }

    RestoreSectionFocus(
        entryToken = entryToken,
        rememberedKey = rememberedKey?.takeIf { it in rowKeys },
        firstKey = rowKeys.firstOrNull() ?: JellyfinFocusCredentials,
        requesters = requesters,
    )

    LaunchedEffect(openOverlay) {
        if (openOverlay != JellyfinOverlayKind.NONE) return@LaunchedEffect
        val key = restoreKey ?: return@LaunchedEffect
        repeat(30) {
            withFrameNanos { }
            if (requesters[key]?.requestFocus() == true) {
                restoreKey = null
                return@LaunchedEffect
            }
        }
        restoreKey = null
    }

    fun open(kind: JellyfinOverlayKind, key: String) {
        restoreKey = key
        openOverlay = kind
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val delta = when (event.key) {
                    Key.DirectionUp -> -1
                    Key.DirectionDown -> 1
                    else -> return@onPreviewKeyEvent false
                }
                if (rowKeys.isEmpty()) return@onPreviewKeyEvent true
                val index = rowKeys.indexOf(focusedRowKey).coerceAtLeast(0)
                val target = rowKeys[(index + delta).coerceIn(0, rowKeys.lastIndex)]
                requesters[target]?.requestFocus()
                true
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Jellyfin server", style = InfusionTvScaleCalibration.Type.Headline)

        JellyfinConnectionSummary(sourceLabel = sourceLabel, connectedUser = connectedUser)

        JellyfinActionRow(
            label = "Connect with credentials",
            value = "Address · username · password",
            help = SettingsRowHelp.Jellyfin.Credentials,
            tag = JellyfinFocusCredentials,
            available = credentialsAvailable,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onClick = { open(JellyfinOverlayKind.CREDENTIALS, JellyfinFocusCredentials) },
        )
        JellyfinActionRow(
            label = "Connect with API key",
            value = "Address · API key · user",
            help = SettingsRowHelp.Jellyfin.ApiKey,
            tag = JellyfinFocusApiKey,
            available = apiKeyAvailable,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onClick = { open(JellyfinOverlayKind.API_KEY, JellyfinFocusApiKey) },
        )
        JellyfinActionRow(
            label = "Quick Connect",
            value = "Address · code shown here",
            help = SettingsRowHelp.Jellyfin.QuickConnect,
            tag = JellyfinFocusQuickConnect,
            available = quickConnectAvailable,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onClick = { open(JellyfinOverlayKind.QUICK_CONNECT, JellyfinFocusQuickConnect) },
        )

        if (radarr != null) {
            val radarrState by radarr.state.collectAsState()
            JellyfinActionRow(
                label = "Radarr",
                value = acquisitionStatusLine(radarrState),
                help = SettingsRowHelp.Acquisition.radarr(radarrState),
                tag = JellyfinFocusRadarr,
                statusTag = jellyfinAcquisitionStatusTag(radarrState.serviceId),
                available = true,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onClick = { open(JellyfinOverlayKind.RADARR, JellyfinFocusRadarr) },
            )
        } else {
            JellyfinActionRow(
                label = "Radarr",
                value = "Unavailable",
                help = null,
                tag = JellyfinFocusRadarr,
                available = false,
                requesters = requesters,
                onFocused = {},
                onClick = {},
            )
        }
        if (sonarr != null) {
            val sonarrState by sonarr.state.collectAsState()
            JellyfinActionRow(
                label = "Sonarr",
                value = acquisitionStatusLine(sonarrState),
                help = SettingsRowHelp.Acquisition.sonarr(sonarrState),
                tag = JellyfinFocusSonarr,
                statusTag = jellyfinAcquisitionStatusTag(sonarrState.serviceId),
                available = true,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onClick = { open(JellyfinOverlayKind.SONARR, JellyfinFocusSonarr) },
            )
        } else {
            JellyfinActionRow(
                label = "Sonarr",
                value = "Unavailable",
                help = null,
                tag = JellyfinFocusSonarr,
                available = false,
                requesters = requesters,
                onFocused = {},
                onClick = {},
            )
        }
        JellyfinActionRow(
            label = "Remove source",
            value = "Revoke sign-in",
            help = SettingsRowHelp.Jellyfin.RemoveSource,
            tag = JellyfinFocusRemove,
            available = removeAvailable,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onClick = { setup?.remove() },
        )
    }

    val dismiss = { openOverlay = JellyfinOverlayKind.NONE }
    when (openOverlay) {
        JellyfinOverlayKind.CREDENTIALS ->
            JellyfinCredentialsOverlay(connection, setup, discovery, onDismiss = dismiss)
        JellyfinOverlayKind.API_KEY ->
            JellyfinApiKeyOverlay(connection, discovery, onDismiss = dismiss)
        JellyfinOverlayKind.QUICK_CONNECT ->
            JellyfinQuickConnectOverlay(connection, discovery, onDismiss = dismiss)
        JellyfinOverlayKind.RADARR ->
            radarr?.let { AcquisitionConnectionOverlay(it, onDismiss = dismiss) }
        JellyfinOverlayKind.SONARR ->
            sonarr?.let { AcquisitionConnectionOverlay(it, onDismiss = dismiss) }
        JellyfinOverlayKind.NONE -> Unit
    }
}

@Composable
private fun JellyfinConnectionSummary(
    sourceLabel: String?,
    connectedUser: JellyfinConnectedUser?,
) {

    val tokens = LocalInfusionTheme.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SettingsJellyfinSummary),
        shape = if (tokens.premium) {
            RoundedCornerShape(tokens.panelRadius)
        } else {
            InfusionTvContour.Shape
        },
        colors = SurfaceDefaults.colors(
            containerColor = if (tokens.premium) tokens.glass else InfusionTvAppearance.Panel,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (sourceLabel != null) {
                    "Connected source: $sourceLabel"
                } else {
                    "No production source configured."
                },
                style = InfusionTvScaleCalibration.Type.SettingsContent,
                modifier = Modifier
                    .testTag(SettingsJellyfinSummarySource)
                    .themeStatusPill(),
            )
            if (connectedUser != null) {
                JellyfinConnectedUserRow(connectedUser)
            }
        }
    }
}

@Composable
private fun JellyfinConnectedUserRow(user: JellyfinConnectedUser) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val boxSize = 40.dp
        val targetSize = with(LocalDensity.current) {
            IntSize(boxSize.roundToPx(), boxSize.roundToPx())
        }
        val avatarLocator = user.avatarLocator
        Box(
            modifier = Modifier
                .size(boxSize)
                .clip(CircleShape)
                .testTag(SettingsJellyfinConnectedUserAvatar),
            contentAlignment = Alignment.Center,
        ) {

            Icon(imageVector = Icons.Filled.Person, contentDescription = null)
            if (avatarLocator != null) {
                ArtworkImage(
                    request = ArtworkRequest(
                        locator = avatarLocator,
                        policy = ArtworkPolicy.POSTER,
                        targetWidthPx = targetSize.width,
                        targetHeightPx = targetSize.height,

                        retainsPreviousSuccess = false,
                    ),
                    contentDescription = null,
                    modifier = Modifier
                        .size(boxSize)

                        .testTag(SettingsJellyfinConnectedUserAvatarImage),
                )
            }

            Box(modifier = Modifier.matchParentSize().themeAvatarHalo())
        }
        Text(
            user.userName ?: "Jellyfin user",
            style = InfusionTvScaleCalibration.Type.SettingsContent,
            modifier = Modifier.testTag(SettingsJellyfinConnectedUserName),
        )
    }
}

@Composable
private fun JellyfinActionRow(
    label: String,
    value: String,
    help: String?,
    tag: String,
    available: Boolean,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onClick: () -> Unit,
    statusTag: String? = null,
) {
    val valueModifier = if (statusTag != null) Modifier.testTag(statusTag) else Modifier
    if (!available) {
        requesters.remove(tag)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = GeneralRowHeight)
                .testTag(tag)
                .semantics {
                    role = Role.Button
                    disabled()
                },
            contentAlignment = Alignment.Center,
        ) {
            SettingsRowBody(
                presentation = SettingsRowPresentation.EXPANDED,
                label = label,

                help = null,
                value = value,
                focused = false,
                valueModifier = valueModifier,
                valueColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),

                valueChip = false,
            )
        }
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val focus = remember(tag) { FocusRequester() }
    requesters[tag] = focus
    InfusionTvButton(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = GeneralRowHeight)
            .testTag(tag)
            .focusRequester(focus)
            .onFocusChanged { state ->
                if (state.isFocused) onFocused(tag)
            }
            .then(settingsRowBringIntoView(focused)),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        SettingsRowBody(
            presentation = SettingsRowPresentation.EXPANDED,
            label = label,
            help = help,
            value = value,
            focused = focused,
            valueModifier = valueModifier,
            valueColor = infusionAccentContentColor(focused),
        )
    }
}

internal fun acquisitionStatusLine(state: AcquisitionConnectionUiState): String = when {
    state.connectedSummary != null -> buildString {
        append("Connected — ").append(state.serviceLabel)
        state.savedServerVersion?.takeIf { it.isNotBlank() }?.let { append(" · v").append(it) }
    }
    state.phase == Phase.LOADED -> "Tested — not saved"
    state.phase == Phase.FAILED -> "Not configured — the last test failed"
    else -> "Not configured"
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun JellyfinOverlay(
    title: String,
    onDismiss: () -> Unit,
    initialFocus: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val dismissOrHideKeyboard: () -> Unit = {
        if (imeVisible) keyboardController?.hide() else onDismiss()
    }
    BackHandler(enabled = true) { dismissOrHideKeyboard() }
    LaunchedEffect(initialFocus) {
        if (initialFocus == null) return@LaunchedEffect
        repeat(30) {
            withFrameNanos { }
            if (initialFocus.requestFocus()) return@LaunchedEffect
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        JellyfinOverlayPanel(
            modifier = Modifier.testTag(SettingsJellyfinOverlay),
            onBack = dismissOrHideKeyboard,
        ) {
            Text(title, style = InfusionTvScaleCalibration.Type.Headline)
            content()
        }
    }
}

@Composable
internal fun JellyfinOverlayPanel(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .width(OverlayWidth)

            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                    onBack()
                    true
                } else {
                    false
                }
            },
        shape = InfusionTvContour.Shape,
        colors = SurfaceDefaults.colors(containerColor = JellyfinOverlayPanelColor),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = OverlayMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (event.key == Key.Back) {
                        onBack()
                        true
                    } else {
                        false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

internal val JellyfinOverlayPanelColor: Color = Color.Black

private val OverlayWidth = 560.dp
private val OverlayMaxHeight = 640.dp

@Composable
private fun FocusOnCompletion(busy: Boolean, target: FocusRequester) {
    var wasBusy by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (wasBusy && !busy) {
            repeat(30) {
                withFrameNanos { }
                if (target.requestFocus()) return@LaunchedEffect
            }
        }
        wasBusy = busy
    }
}

@Composable
private fun FocusOnStatus(
    status: JellyfinConnectionStatus,
    predicate: (JellyfinConnectionStatus) -> Boolean,
    target: FocusRequester,
) {
    LaunchedEffect(status) {
        if (!predicate(status)) return@LaunchedEffect
        repeat(30) {
            withFrameNanos { }
            if (target.requestFocus()) return@LaunchedEffect
        }
    }
}

@Composable
private fun jellyfinHasDiscoveredServers(discovery: JellyfinServerDiscoveryStateHolder?): Boolean {
    if (discovery == null) return false
    val state by discovery.state.collectAsState()
    val ready = state as? JellyfinDiscoveryState.Ready ?: return false
    return ready.servers.isNotEmpty()
}

@Composable
private fun JellyfinDiscoverySection(
    discovery: JellyfinServerDiscoveryStateHolder?,
    firstRowFocus: FocusRequester,
    onSelect: (String) -> Unit,
) {
    if (discovery == null) return
    val state by discovery.state.collectAsState()

    LaunchedEffect(Unit) { discovery.start() }
    DisposableEffect(Unit) { onDispose { discovery.stop() } }

    Column(
        modifier = Modifier.testTag(SettingsJellyfinDiscovery),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val current = state) {
            JellyfinDiscoveryState.Idle -> Unit
            JellyfinDiscoveryState.Searching ->
                Text(
                    "Searching for a Jellyfin server on the local network…",
                    style = InfusionTvScaleCalibration.Type.SettingsContent,
                    modifier = Modifier.testTag(SettingsJellyfinDiscoveryStatus),
                )
            is JellyfinDiscoveryState.Ready ->
                if (current.servers.isEmpty()) {
                    Text(
                        "No Jellyfin server was discovered. Enter the address manually.",
                        style = InfusionTvScaleCalibration.Type.SettingsContent,
                        modifier = Modifier.testTag(SettingsJellyfinDiscoveryStatus),
                    )
                } else {

                    Text(
                        "Select a discovered server.",
                        style = InfusionTvScaleCalibration.Type.SettingsContent,
                        modifier = Modifier.testTag(SettingsJellyfinDiscoveryStatus),
                    )
                    current.servers.forEachIndexed { index, server ->
                        JellyfinDiscoveredServerRow(
                            server = server,
                            focusRequester = if (index == 0) firstRowFocus else remember(server.id) { FocusRequester() },
                            onSelect = { onSelect(server.address) },
                        )
                    }
                }
        }
    }
}

@Composable
private fun JellyfinDiscoveredServerRow(
    server: JellyfinDiscoveredServer,
    focusRequester: FocusRequester,
    onSelect: () -> Unit,
) {
    val tag = jellyfinDiscoveryServerTag(server.id)
    InfusionTvButton(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .focusRequester(focusRequester),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                server.name,
                style = InfusionTvScaleCalibration.Type.SettingsContent,
            )
            Text(
                server.address,
                style = InfusionTvScaleCalibration.Type.SettingsContent,
                modifier = Modifier.alpha(0.7f),
            )
        }
    }
}

@Composable
private fun JellyfinOverlayStatus(
    connection: JellyfinConnectionStateHolder?,
    setup: JellyfinSetupStateHolder?,
    workingText: String = "Testing…",
) {
    val testStatus = jellyfinConnectionStatus(connection)
    val setupStatus = jellyfinSetupStatus(setup)
    val line: String? = when {
        setupStatus is JellyfinSetupStatus.Connecting -> "Connecting…"
        setupStatus is JellyfinSetupStatus.Failed -> "Failed: ${setupStatus.message}"
        setupStatus is JellyfinSetupStatus.Removed -> "Source removed"
        testStatus is JellyfinConnectionStatus.Working -> workingText
        testStatus is JellyfinConnectionStatus.TestOk ->
            "Test OK${testStatus.versionLabel?.let { " — server $it (${testStatus.qualificationLabel ?: "UNKNOWN"})" }.orEmpty()}. No changes saved."
        testStatus is JellyfinConnectionStatus.TestFailed -> "Test failed: ${testStatus.message}"
        testStatus is JellyfinConnectionStatus.UsersReady -> "${testStatus.users.size} user(s) found."
        testStatus is JellyfinConnectionStatus.UsersUnavailable -> "User list unavailable: ${testStatus.message}"
        testStatus is JellyfinConnectionStatus.QuickConnectWaiting ->
            "Waiting for approval. Code: ${testStatus.code}"
        testStatus is JellyfinConnectionStatus.Connected ->
            "Connected${testStatus.userName?.let { " — $it" }.orEmpty()}."
        testStatus is JellyfinConnectionStatus.Failed -> "Failed: ${testStatus.message}"
        setupStatus is JellyfinSetupStatus.Connected ->
            if (setupStatus.versionLabel != null) {
                "Connected — server ${setupStatus.versionLabel} (${setupStatus.qualificationLabel ?: "UNKNOWN"})"
            } else {
                "Connected"
            }
        else -> null
    }
    if (line != null) {
        Text(
            line,
            style = InfusionTvScaleCalibration.Type.SettingsContent,
            modifier = Modifier.testTag(SettingsJellyfinOverlayStatus),
        )
    }
}

@Composable
private fun jellyfinConnectionStatus(holder: JellyfinConnectionStateHolder?): JellyfinConnectionStatus =
    if (holder != null) holder.state.collectAsState().value else JellyfinConnectionStatus.Idle

@Composable
private fun jellyfinSetupStatus(holder: JellyfinSetupStateHolder?): JellyfinSetupStatus =
    if (holder != null) holder.state.collectAsState().value else JellyfinSetupStatus.Idle

@Composable
private fun jellyfinBusy(connection: JellyfinConnectionStateHolder?, setup: JellyfinSetupStateHolder?): Boolean {
    val testStatus = jellyfinConnectionStatus(connection)
    val setupStatus = jellyfinSetupStatus(setup)
    return setupStatus is JellyfinSetupStatus.Connecting ||
        testStatus is JellyfinConnectionStatus.Working ||
        testStatus is JellyfinConnectionStatus.QuickConnectWaiting
}

@Composable
private fun JellyfinCredentialsOverlay(
    connection: JellyfinConnectionStateHolder?,
    setup: JellyfinSetupStateHolder?,
    discovery: JellyfinServerDiscoveryStateHolder?,
    onDismiss: () -> Unit,
) {

    var serverUrl by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val busy = jellyfinBusy(connection, setup)
    val complete = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotEmpty()
    val canTest = !busy && complete && connection != null
    val localRequesters = remember { mutableMapOf<String, FocusRequester>() }

    val urlFocus = remember { FocusRequester() }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val testFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    val discoveryFirstRow = remember { FocusRequester() }
    val hasDiscovered = jellyfinHasDiscoveredServers(discovery)

    FocusOnCompletion(busy = busy, target = testFocus)

    JellyfinOverlay(title = "Connect with credentials", onDismiss = onDismiss, initialFocus = urlFocus) {
        JellyfinDiscoverySection(
            discovery = discovery,
            firstRowFocus = discoveryFirstRow,
            onSelect = { address ->
                serverUrl = address
                connection?.invalidate()
                usernameFocus.requestFocus()
            },
        )
        LabeledField(
            label = "Server address (e.g. http://media.local:8096)",
            value = serverUrl,
            onChange = {
                serverUrl = it
                connection?.invalidate()
            },
            testTag = SettingsJellyfinCredentialsUrl,
            focusRequester = urlFocus,

            onDpadUp = { (if (hasDiscovered) discoveryFirstRow else urlFocus).requestFocus() },
            onDpadDown = { usernameFocus.requestFocus() },
            imeAction = ImeAction.Next,
            onImeNext = { usernameFocus.requestFocus() },
        )
        LabeledField(
            label = "Username",
            value = username,
            onChange = {
                username = it
                connection?.invalidate()
            },
            testTag = SettingsJellyfinCredentialsUsername,
            focusRequester = usernameFocus,
            onDpadUp = { urlFocus.requestFocus() },
            onDpadDown = { passwordFocus.requestFocus() },
            imeAction = ImeAction.Next,
            onImeNext = { passwordFocus.requestFocus() },
        )
        LabeledField(
            label = "Password",
            value = password,
            secret = true,
            onChange = {
                password = it
                connection?.invalidate()
            },
            testTag = SettingsJellyfinCredentialsPassword,
            focusRequester = passwordFocus,
            onDpadUp = { usernameFocus.requestFocus() },

            onDpadDown = { if (canTest) testFocus.requestFocus() },
            imeAction = ImeAction.Done,

            onImeSubmit = {
                if (canTest) connection?.testCredentials(serverUrl, username, password)
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            JellyfinActionCell(
                label = "Test connection",
                tag = SettingsJellyfinCredentialsTest,
                available = canTest,
                focusRequester = testFocus,
                requesters = localRequesters,
                onFocused = {},
                onClick = { connection?.testCredentials(serverUrl, username, password) },
            )
            JellyfinActionCell(
                label = "Save / Connect",
                tag = SettingsJellyfinCredentialsSave,
                available = !busy && complete && setup != null,
                focusRequester = saveFocus,
                requesters = localRequesters,
                onFocused = {},
                onClick = { setup?.connect(serverUrl, username, password, consentLan = true) },
            )
        }
        JellyfinOverlayStatus(connection, setup)
    }
}

@Composable
private fun JellyfinApiKeyOverlay(
    connection: JellyfinConnectionStateHolder?,
    discovery: JellyfinServerDiscoveryStateHolder?,
    onDismiss: () -> Unit,
) {
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var targetUserId by rememberSaveable { mutableStateOf("") }

    val status = jellyfinConnectionStatus(connection)
    val busy = jellyfinBusy(connection, null)
    val complete = serverUrl.isNotBlank() && apiKey.isNotBlank()
    val canLoad = !busy && complete && connection != null
    val localRequesters = remember { mutableMapOf<String, FocusRequester>() }

    val urlFocus = remember { FocusRequester() }
    val keyFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val loadUsersFocus = remember { FocusRequester() }
    val testFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    val firstUserFocus = remember { FocusRequester() }
    val discoveryFirstRow = remember { FocusRequester() }
    val hasDiscovered = jellyfinHasDiscoveredServers(discovery)

    FocusOnCompletion(busy = busy, target = testFocus)
    FocusOnStatus(
        status = status,
        predicate = { it is JellyfinConnectionStatus.UsersReady },
        target = firstUserFocus,
    )

    JellyfinOverlay(title = "Connect with API key", onDismiss = onDismiss, initialFocus = urlFocus) {
        JellyfinDiscoverySection(
            discovery = discovery,
            firstRowFocus = discoveryFirstRow,
            onSelect = { address ->
                serverUrl = address
                connection?.invalidate()
                keyFocus.requestFocus()
            },
        )
        LabeledField(
            label = "Server address (e.g. http://media.local:8096)",
            value = serverUrl,
            onChange = {
                serverUrl = it
                connection?.invalidate()
            },
            testTag = SettingsJellyfinApiKeyUrl,
            focusRequester = urlFocus,
            onDpadUp = { (if (hasDiscovered) discoveryFirstRow else urlFocus).requestFocus() },
            onDpadDown = { keyFocus.requestFocus() },
            imeAction = ImeAction.Next,
            onImeNext = { keyFocus.requestFocus() },
        )
        LabeledField(
            label = "API key",
            value = apiKey,
            secret = true,
            onChange = {
                apiKey = it
                connection?.invalidate()
            },
            testTag = SettingsJellyfinApiKeyKey,
            focusRequester = keyFocus,
            onDpadUp = { urlFocus.requestFocus() },
            onDpadDown = { userFocus.requestFocus() },
            imeAction = ImeAction.Next,
            onImeNext = { userFocus.requestFocus() },
        )
        LabeledField(
            label = "Target user id (Jellyfin userId; leave blank to pick from the list)",
            value = targetUserId,
            onChange = {
                targetUserId = it
                connection?.invalidate()
            },
            testTag = SettingsJellyfinApiKeyUser,
            focusRequester = userFocus,
            onDpadUp = { keyFocus.requestFocus() },

            onDpadDown = { if (canLoad) loadUsersFocus.requestFocus() },
            imeAction = ImeAction.Search,

            onImeSubmit = {
                if (canLoad) connection?.loadUsers(serverUrl, apiKey)
            },
        )
        JellyfinActionCell(
            label = "Load users",
            tag = SettingsJellyfinApiKeyLoadUsers,
            available = canLoad,
            focusRequester = loadUsersFocus,
            requesters = localRequesters,
            onFocused = {},
            onClick = { connection?.loadUsers(serverUrl, apiKey) },
        )
        if (status is JellyfinConnectionStatus.UsersReady) {

            LaunchedEffect(status.users, targetUserId) {
                if (status.users.size == 1 && targetUserId.isBlank()) {
                    targetUserId = status.users.first().userId
                }
            }
            status.users.forEachIndexed { index, user ->
                JellyfinUserChoiceRow(
                    user = user,
                    selected = targetUserId == user.userId,
                    focusRequester = if (index == 0) firstUserFocus else remember(user.userId) { FocusRequester() },
                    requesters = localRequesters,
                    onSelect = {
                        targetUserId = user.userId
                        connection?.invalidate()
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            JellyfinActionCell(
                label = "Test connection",
                tag = SettingsJellyfinApiKeyTest,
                available = !busy && complete && connection != null,
                focusRequester = testFocus,
                requesters = localRequesters,
                onFocused = {},
                onClick = { connection?.testApiKey(serverUrl, apiKey, targetUserId.ifBlank { null }) },
            )
            JellyfinActionCell(
                label = "Save / Connect",
                tag = SettingsJellyfinApiKeySave,
                available = !busy && complete && connection != null,
                focusRequester = saveFocus,
                requesters = localRequesters,
                onFocused = {},
                onClick = { connection?.connectApiKey(serverUrl, apiKey, targetUserId.ifBlank { null }) },
            )
        }
        JellyfinOverlayStatus(connection, null)
    }
}

@Composable
private fun JellyfinUserChoiceRow(
    user: JellyfinUserChoice,
    selected: Boolean,
    focusRequester: FocusRequester,
    requesters: MutableMap<String, FocusRequester>,
    onSelect: () -> Unit,
) {
    JellyfinActionCell(
        label = "${user.name ?: user.userId}${if (selected) "  ✓" else ""}",
        tag = "settings:jellyfin:apikey:user:${user.userId}",
        available = !user.disabled,
        focusRequester = focusRequester,
        requesters = requesters,
        onFocused = {},
        onClick = onSelect,
    )
}

@Composable
private fun JellyfinQuickConnectOverlay(
    connection: JellyfinConnectionStateHolder?,
    discovery: JellyfinServerDiscoveryStateHolder?,
    onDismiss: () -> Unit,
) {
    var serverUrl by rememberSaveable { mutableStateOf("") }
    val status = jellyfinConnectionStatus(connection)
    val busy = jellyfinBusy(connection, null)
    val canStart = !busy && serverUrl.isNotBlank() && connection != null
    val urlFocus = remember { FocusRequester() }
    val discoveryFirstRow = remember { FocusRequester() }
    val hasDiscovered = jellyfinHasDiscoveredServers(discovery)

    FocusOnStatus(
        status = status,
        predicate = { it is JellyfinConnectionStatus.Failed },
        target = urlFocus,
    )

    val canStartWith: (String) -> Boolean = { address -> !busy && address.isNotBlank() && connection != null }
    val awaiting = status as? JellyfinConnectionStatus.QuickConnectWaiting

    JellyfinOverlay(
        title = if (awaiting != null) "Jellyfin Quick Connect" else "Quick Connect",
        onDismiss = onDismiss,
        initialFocus = if (awaiting != null) null else urlFocus,
    ) {
        if (awaiting != null) {

            Text(
                awaiting.code,
                style = InfusionTvScaleCalibration.Type.Headline,
                modifier = Modifier.testTag(SettingsJellyfinQuickConnectCode),
            )
            Text(
                "Open Jellyfin on another device, sign in, choose Quick Connect and enter this code.",
                style = InfusionTvScaleCalibration.Type.SettingsContent,
            )
            Text(
                "Waiting for approval…",
                style = InfusionTvScaleCalibration.Type.SettingsContent,
                modifier = Modifier.testTag(SettingsJellyfinQuickConnectState),
            )
        } else {
            Text(
                "Select a discovered server, or enter its address and press Search.",
                style = InfusionTvScaleCalibration.Type.SettingsContent,
            )
            JellyfinDiscoverySection(
                discovery = discovery,
                firstRowFocus = discoveryFirstRow,
                onSelect = { address ->
                    serverUrl = address
                    connection?.invalidate()

                    if (canStartWith(address)) connection?.startQuickConnect(address)
                },
            )
            LabeledField(
                label = "Server address (e.g. http://media.local:8096)",
                value = serverUrl,
                onChange = {
                    serverUrl = it
                    connection?.invalidate()
                },
                testTag = SettingsJellyfinQuickConnectUrl,
                focusRequester = urlFocus,
                onDpadUp = { (if (hasDiscovered) discoveryFirstRow else urlFocus).requestFocus() },
                onDpadDown = null,
                imeAction = ImeAction.Search,

                onImeSubmit = {
                    if (canStart) connection?.startQuickConnect(serverUrl)
                },
            )
        }

        if (awaiting == null) {
            JellyfinOverlayStatus(connection, null, workingText = "Starting Quick Connect…")
        }
    }
}

@Composable
private fun AcquisitionConnectionOverlay(
    holder: AcquisitionConnectionStateHolder,
    onDismiss: () -> Unit,
) {
    val state: AcquisitionConnectionUiState = holder.state.collectAsState().value
    val busy = state.phase == Phase.LOADING || state.phase == Phase.SAVING
    val canTest = !busy && state.url.isNotBlank() && state.apiKey.isNotBlank()
    val loaded = state.phase == Phase.LOADED

    val selectionVisible = state.connectedSummary != null || state.roots.isNotEmpty()
    val localRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val urlFocus = remember { FocusRequester() }
    val keyFocus = remember { FocusRequester() }
    val rootPickerFocus = remember { FocusRequester() }
    val profilePickerFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    var showRootSelector by remember { mutableStateOf(false) }
    var showProfileSelector by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { holder.loadExisting() }

    FocusOnCompletion(busy = busy, target = if (state.roots.isNotEmpty()) rootPickerFocus else keyFocus)
    LaunchedEffect(state.phase) {
        if (state.phase == Phase.FAILED) {
            repeat(30) {
                withFrameNanos { }
                if (keyFocus.requestFocus()) return@LaunchedEffect
            }
        }
    }

    RequestFocusOnClose(open = showRootSelector, target = rootPickerFocus)
    RequestFocusOnClose(open = showProfileSelector, target = profilePickerFocus)

    JellyfinOverlay(
        title = state.serviceLabel,
        onDismiss = onDismiss,
        initialFocus = urlFocus,
    ) {
        LabeledField(
            label = "Server URL",
            value = state.url,
            onChange = { holder.onUrlChange(it) },
            testTag = "settings:jellyfin:${state.serviceId}:url",
            focusRequester = urlFocus,
            onDpadUp = { urlFocus.requestFocus() },
            onDpadDown = { keyFocus.requestFocus() },
            imeAction = ImeAction.Next,
            onImeNext = { keyFocus.requestFocus() },
        )
        LabeledField(
            label = "API key",
            value = state.apiKey,
            secret = true,
            onChange = { holder.onApiKeyChange(it) },
            testTag = "settings:jellyfin:${state.serviceId}:key",
            focusRequester = keyFocus,
            onDpadUp = { urlFocus.requestFocus() },

            onDpadDown = { if (selectionVisible) rootPickerFocus.requestFocus() },
            imeAction = ImeAction.Search,

            onImeSubmit = {
                if (canTest) holder.testAndLoad()
            },
        )
        if (selectionVisible) {

            AcquisitionPicker(
                label = "Root folder",
                value = state.selectedRoot ?: "Choose root folder",
                tag = jellyfinAcquisitionRootPickerTag(state.serviceId),
                focusRequester = rootPickerFocus,
                requesters = localRequesters,
                onClick = { showRootSelector = true },
            )
            AcquisitionPicker(
                label = "Quality profile",
                value = state.selectedProfileLabel ?: "Choose quality profile",
                tag = jellyfinAcquisitionProfilePickerTag(state.serviceId),
                focusRequester = profilePickerFocus,
                requesters = localRequesters,
                onClick = { showProfileSelector = true },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            JellyfinActionCell(
                label = "Save",
                tag = "settings:jellyfin:${state.serviceId}:save",
                available = !busy && loaded &&
                    state.selectedRoot != null && state.selectedProfileId != null,
                focusRequester = saveFocus,
                requesters = localRequesters,
                onFocused = {},
                onClick = { holder.save() },
            )
            JellyfinActionCell(
                label = "Remove",
                tag = "settings:jellyfin:${state.serviceId}:clear",
                available = !busy && state.connectedSummary != null,
                focusRequester = remember { FocusRequester() },
                requesters = localRequesters,
                onFocused = {},
                onClick = { holder.clear() },
            )
        }
        val line: String? = when (state.phase) {
            Phase.LOADING -> "Testing…"
            Phase.SAVING -> "Saving…"
            Phase.FAILED -> "Failed: ${state.error ?: "unknown error"}"
            Phase.SAVED -> "Saved."
            Phase.LOADED ->
                if (state.connectedSummary != null) "Saved configuration: ${state.connectedSummary}" else null
            Phase.IDLE -> state.connectedSummary?.let { "Saved configuration: $it" }
        }
        if (line != null) {
            Text(
                line,
                style = InfusionTvScaleCalibration.Type.SettingsContent,
                modifier = Modifier.testTag(SettingsJellyfinOverlayStatus),
            )
        }
        if (loaded && state.serverVersion != null) {
            Text(
                "Tested — ${state.serviceLabel} ${state.serverVersion}",
                style = InfusionTvScaleCalibration.Type.SettingsContent,
            )
        }
    }

    if (showRootSelector && state.roots.isNotEmpty()) {
        val current = state.roots.firstOrNull { it.path == state.selectedRoot } ?: state.roots.first()
        GeneralSelectorDialog(
            rowTag = jellyfinAcquisitionRootPickerTag(state.serviceId),
            choices = state.roots,
            current = current,
            choiceKey = { it.path },
            choiceLabel = { it.path },
            onSelect = { root ->
                holder.selectRoot(root.path)
                showRootSelector = false
            },
            onDismiss = { showRootSelector = false },
        )
    }
    if (showProfileSelector && state.profiles.isNotEmpty()) {
        val current = state.profiles.firstOrNull { it.id == state.selectedProfileId } ?: state.profiles.first()
        GeneralSelectorDialog(
            rowTag = jellyfinAcquisitionProfilePickerTag(state.serviceId),
            choices = state.profiles,
            current = current,
            choiceKey = { it.id.toString() },
            choiceLabel = { it.name },
            onSelect = { profile ->
                holder.selectProfile(profile.id)
                showProfileSelector = false
            },
            onDismiss = { showProfileSelector = false },
        )
    }
}

@Composable
private fun AcquisitionPicker(
    label: String,
    value: String,
    tag: String,
    focusRequester: FocusRequester,
    requesters: MutableMap<String, FocusRequester>,
    onClick: () -> Unit,
) {
    Column {
        Text(label, style = InfusionTvScaleCalibration.Type.SettingsContent)
        JellyfinActionCell(
            label = value,
            tag = tag,
            available = true,
            focusRequester = focusRequester,
            requesters = requesters,
            onFocused = {},
            onClick = onClick,
        )
    }
}

@Composable
private fun RequestFocusOnClose(open: Boolean, target: FocusRequester) {
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (wasOpen && !open) {
            repeat(30) {
                withFrameNanos { }
                if (target.requestFocus()) return@LaunchedEffect
            }
        }
        wasOpen = open
    }
}
