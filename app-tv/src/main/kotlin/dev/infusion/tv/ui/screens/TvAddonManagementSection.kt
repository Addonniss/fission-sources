package dev.infusion.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.infusion.feature.vertical.addon.AddonCatalogEntryUi
import dev.infusion.feature.vertical.addon.AddonConsentInput
import dev.infusion.feature.vertical.addon.AddonInstallPhase
import dev.infusion.feature.vertical.addon.AddonItemOperation
import dev.infusion.feature.vertical.addon.AddonManagementCatalogKey
import dev.infusion.feature.vertical.addon.AddonManagementItemUi
import dev.infusion.feature.vertical.addon.AddonManagementRepositoryState
import dev.infusion.feature.vertical.addon.AddonManagementStateHolder
import dev.infusion.feature.vertical.addon.AddonManagementUiState
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.presentation.centerHoldMenuModifier
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration

internal fun addonMainKey(addonId: String) = "addon-main:$addonId"

internal fun addonPriorityUpKey(addonId: String) = "addon-priority-up:$addonId"

internal fun addonPriorityDownKey(addonId: String) = "addon-priority-down:$addonId"

internal fun addonCatalogUpKey(addonId: String, catalogType: String, catalogId: String) =
    "addon-catalog-up:$addonId:$catalogType:$catalogId"

internal fun addonCatalogDownKey(addonId: String, catalogType: String, catalogId: String) =
    "addon-catalog-down:$addonId:$catalogType:$catalogId"

internal fun addonCatalogToggleKey(addonId: String, catalogType: String, catalogId: String) =
    "addon-catalog-toggle:$addonId:$catalogType:$catalogId"

internal const val AddonInstallUrlKey = "url-field"

private const val AddonPriorityUpPrefix = "addon-priority-up:"
private const val AddonPriorityDownPrefix = "addon-priority-down:"
private const val AddonMainPrefix = "addon-main:"

internal fun addonRowAddonId(key: String): String? = when {
    key.startsWith(AddonPriorityUpPrefix) -> key.removePrefix(AddonPriorityUpPrefix)
    key.startsWith(AddonPriorityDownPrefix) -> key.removePrefix(AddonPriorityDownPrefix)
    key.startsWith(AddonMainPrefix) -> key.removePrefix(AddonMainPrefix)
    else -> null
}

private const val AddonCatalogUpPrefix = "addon-catalog-up:"
private const val AddonCatalogDownPrefix = "addon-catalog-down:"
private const val AddonCatalogTogglePrefix = "addon-catalog-toggle:"

internal fun addonContentAddonId(key: String): String? = when {
    key.startsWith(AddonCatalogUpPrefix) -> catalogOwnerId(key.removePrefix(AddonCatalogUpPrefix))
    key.startsWith(AddonCatalogDownPrefix) -> catalogOwnerId(key.removePrefix(AddonCatalogDownPrefix))
    key.startsWith(AddonCatalogTogglePrefix) -> catalogOwnerId(key.removePrefix(AddonCatalogTogglePrefix))
    else -> null
}

private fun catalogOwnerId(suffix: String): String = suffix.substringBefore(':')

private fun catalogControlSuffix(key: String): String? = when {
    key.startsWith(AddonCatalogUpPrefix) -> key.removePrefix(AddonCatalogUpPrefix)
    key.startsWith(AddonCatalogDownPrefix) -> key.removePrefix(AddonCatalogDownPrefix)
    key.startsWith(AddonCatalogTogglePrefix) -> key.removePrefix(AddonCatalogTogglePrefix)
    else -> null
}

internal const val CatalogColumnMain = 0
internal const val CatalogColumnUp = 1
internal const val CatalogColumnDown = 2

internal fun catalogRowColumn(key: String): Int? = when {
    key.startsWith(AddonCatalogUpPrefix) -> CatalogColumnUp
    key.startsWith(AddonCatalogDownPrefix) -> CatalogColumnDown
    key.startsWith(AddonCatalogTogglePrefix) -> CatalogColumnMain
    else -> null
}

internal fun catalogRowIndex(
    key: String,
    catalogs: List<AddonCatalogEntryUi>,
): Int? {
    val suffix = catalogControlSuffix(key) ?: return null
    return catalogs.indexOfFirst { entry ->
        suffix == "${entry.addonId}:${entry.catalogType}:${entry.catalogId}"
    }.takeIf { it >= 0 }
}

internal fun catalogColumnAvailable(
    catalogs: List<AddonCatalogEntryUi>,
    entry: AddonCatalogEntryUi,
    column: Int,
): Boolean {
    val index = catalogs.indexOfFirst { sameCatalog(it, entry) }
    if (index < 0) return false
    return when (column) {
        CatalogColumnUp -> index > 0
        CatalogColumnDown -> index < catalogs.lastIndex
        else -> true
    }
}

private fun sameCatalog(a: AddonCatalogEntryUi, b: AddonCatalogEntryUi): Boolean =
    a.addonId == b.addonId && a.catalogType == b.catalogType && a.catalogId == b.catalogId

internal fun catalogRowKeyAt(
    catalogs: List<AddonCatalogEntryUi>,
    entry: AddonCatalogEntryUi,
    column: Int,
): String = if (catalogColumnAvailable(catalogs, entry, column)) {
    when (column) {
        CatalogColumnUp -> addonCatalogUpKey(entry.addonId, entry.catalogType, entry.catalogId)
        CatalogColumnDown -> addonCatalogDownKey(entry.addonId, entry.catalogType, entry.catalogId)
        else -> addonCatalogToggleKey(entry.addonId, entry.catalogType, entry.catalogId)
    }
} else {
    addonCatalogToggleKey(entry.addonId, entry.catalogType, entry.catalogId)
}

internal fun catalogRowRightKey(
    catalogs: List<AddonCatalogEntryUi>,
    entry: AddonCatalogEntryUi,
    column: Int,
): String? = when (column) {
    CatalogColumnMain -> when {
        catalogColumnAvailable(catalogs, entry, CatalogColumnUp) ->
            addonCatalogUpKey(entry.addonId, entry.catalogType, entry.catalogId)
        catalogColumnAvailable(catalogs, entry, CatalogColumnDown) ->
            addonCatalogDownKey(entry.addonId, entry.catalogType, entry.catalogId)
        else -> null
    }
    CatalogColumnUp -> if (catalogColumnAvailable(catalogs, entry, CatalogColumnDown)) {
        addonCatalogDownKey(entry.addonId, entry.catalogType, entry.catalogId)
    } else {
        null
    }
    else -> null
}

internal fun catalogRowLeftKey(
    catalogs: List<AddonCatalogEntryUi>,
    entry: AddonCatalogEntryUi,
    column: Int,
): String? = when (column) {
    CatalogColumnDown -> if (catalogColumnAvailable(catalogs, entry, CatalogColumnUp)) {
        addonCatalogUpKey(entry.addonId, entry.catalogType, entry.catalogId)
    } else {
        addonCatalogToggleKey(entry.addonId, entry.catalogType, entry.catalogId)
    }
    CatalogColumnUp -> addonCatalogToggleKey(entry.addonId, entry.catalogType, entry.catalogId)
    else -> null
}

internal const val AddonColumnPriorityUp = 0
internal const val AddonColumnPriorityDown = 1
internal const val AddonColumnMain = 2

internal fun addonRowColumn(key: String): Int? = when {
    key.startsWith(AddonPriorityUpPrefix) -> AddonColumnPriorityUp
    key.startsWith(AddonPriorityDownPrefix) -> AddonColumnPriorityDown
    key.startsWith(AddonMainPrefix) -> AddonColumnMain
    else -> null
}

internal fun addonColumnAvailable(
    state: AddonManagementUiState,
    item: AddonManagementItemUi,
    column: Int,
): Boolean = when (column) {
    AddonColumnPriorityUp -> item.canManage && state.canMoveUpInstalled(item.addonId)
    AddonColumnPriorityDown -> item.canManage && state.canMoveDownInstalled(item.addonId)
    else -> true
}

internal fun addonRowKeyAt(
    state: AddonManagementUiState,
    item: AddonManagementItemUi,
    column: Int,
): String = if (addonColumnAvailable(state, item, column)) {
    when (column) {
        AddonColumnPriorityUp -> addonPriorityUpKey(item.addonId)
        AddonColumnPriorityDown -> addonPriorityDownKey(item.addonId)
        else -> addonMainKey(item.addonId)
    }
} else {
    addonMainKey(item.addonId)
}

internal fun addonContentControlKeys(
    item: AddonManagementItemUi,
    catalogs: List<AddonCatalogEntryUi>,
): List<String> = buildList {
    catalogs.forEach { entry ->
        add(addonCatalogToggleKey(entry.addonId, entry.catalogType, entry.catalogId))
        add(addonCatalogUpKey(entry.addonId, entry.catalogType, entry.catalogId))
        add(addonCatalogDownKey(entry.addonId, entry.catalogType, entry.catalogId))
    }
}

internal fun addonFocusableContentKeys(catalogs: List<AddonCatalogEntryUi>): List<String> =
    catalogs.flatMap { entry ->
        buildList {
            add(addonCatalogToggleKey(entry.addonId, entry.catalogType, entry.catalogId))
            if (catalogColumnAvailable(catalogs, entry, CatalogColumnUp)) {
                add(addonCatalogUpKey(entry.addonId, entry.catalogType, entry.catalogId))
            }
            if (catalogColumnAvailable(catalogs, entry, CatalogColumnDown)) {
                add(addonCatalogDownKey(entry.addonId, entry.catalogType, entry.catalogId))
            }
        }
    }

internal fun addonSectionInRowKeys(state: AddonManagementUiState): Set<String> = buildSet {
    state.installed.forEach { item ->
        add(addonPriorityUpKey(item.addonId))
        add(addonPriorityDownKey(item.addonId))
        add(addonMainKey(item.addonId))
        addAll(addonContentControlKeys(item, state.catalogsOf(item.addonId)))
    }
}

internal fun addonSectionBackKeys(state: AddonManagementUiState): Set<String> = buildSet {
    state.installed.forEach { item ->
        addAll(addonContentControlKeys(item, state.catalogsOf(item.addonId)))
    }
}

internal fun AddonManagementUiState.catalogsOf(addonId: String): List<AddonCatalogEntryUi> =
    catalog.filter { it.addonId == addonId }

internal fun addonSectionFirstKey(state: AddonManagementUiState): String =
    state.installed.firstOrNull()?.let { addonMainKey(it.addonId) } ?: AddonInstallUrlKey

internal fun addonRemovalSurvivorPreference(
    rowsBefore: List<String>,
    removedAddonId: String,
): List<String> {
    val index = rowsBefore.indexOf(removedAddonId)
    if (index < 0) return emptyList()
    return listOfNotNull(rowsBefore.getOrNull(index + 1), rowsBefore.getOrNull(index - 1))
}

internal fun addonRemovalSurvivorId(
    rowsBefore: List<String>,
    removedAddonId: String,
    surviving: List<String>,
): String? = addonSurvivorId(addonRemovalSurvivorPreference(rowsBefore, removedAddonId), surviving)

internal fun addonSurvivorId(preferredSurvivors: List<String>, surviving: List<String>): String? =
    preferredSurvivors.firstOrNull { it in surviving } ?: surviving.firstOrNull()

internal fun addonFocusInContent(focusedKey: String?, selectedContentKeys: List<String>): Boolean =
    focusedKey != null && focusedKey in selectedContentKeys

internal fun addonMainIsActiveRow(
    addonId: String,
    selectedAddonId: String?,
    focusInContent: Boolean,
): Boolean = focusInContent && addonId == selectedAddonId

internal data class AddonRowStateTreatment(

    val accentFrame: Boolean,

    val labelAlpha: Float,
)

internal fun addonRowStateTreatment(enabled: Boolean): AddonRowStateTreatment =
    AddonRowStateTreatment(
        accentFrame = false,

        labelAlpha = if (enabled) 0.75f else 0.5f,
    )

internal fun addonSectionFocusableKeys(
    state: AddonManagementUiState,
    selectedAddonId: String?,
): Set<String> = buildSet {
    add(AddonInstallUrlKey)
    state.installed.forEach { item ->
        if (addonColumnAvailable(state, item, AddonColumnPriorityUp)) add(addonPriorityUpKey(item.addonId))
        if (addonColumnAvailable(state, item, AddonColumnPriorityDown)) add(addonPriorityDownKey(item.addonId))
        add(addonMainKey(item.addonId))
    }
    state.installed.firstOrNull { it.addonId == selectedAddonId }?.let { selected ->
        addAll(addonFocusableContentKeys(state.catalogsOf(selected.addonId)))
    }
}

internal fun addonSectionEntryKey(
    state: AddonManagementUiState,
    rememberedKey: String?,
    selectedAddonId: String?,
): String {
    val focusable = addonSectionFocusableKeys(state, selectedAddonId)
    return rememberedKey?.takeIf { it in focusable } ?: addonSectionFirstKey(state)
}

internal const val AddonListPane = "addon-list-pane"

internal const val AddonFocusPlacementFrames = 30

internal data class AddonFocusPlacementRequest(
    val nonce: Int,
    val key: String,
    val targetOnly: Boolean,
)

internal data class PendingAddonRemoval(
    val removedAddonId: String,
    val preferredSurvivors: List<String>,
)

internal const val AddonContentPane = "addon-content-pane"

@Composable
internal fun AddonsSectionContent(
    stateHolder: AddonManagementStateHolder,
    requesters: MutableMap<String, FocusRequester>,
    entryToken: Int,
    rememberedKey: String?,
    onFocusRequested: (String) -> Unit,
    onReturnToRail: () -> Unit = {},

    addonMenuHost: AddonContextMenuHostState = remember { AddonContextMenuHostState() },
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    val installed = state.installed

    var focusedKey by remember { mutableStateOf<String?>(null) }
    var selectedAddonId by remember { mutableStateOf<String?>(null) }
    var uninstallRequest by remember { mutableStateOf<AddonManagementItemUi?>(null) }

    var uninstallInvokingKey by remember { mutableStateOf<String?>(null) }

    var pendingMove by remember { mutableStateOf<Pair<String, Int>?>(null) }

    var pendingCatalogMove by remember { mutableStateOf<Pair<AddonManagementCatalogKey, Int>?>(null) }

    fun reportFocus(key: String) {
        focusedKey = key

        addonRowAddonId(key)?.let { selectedAddonId = it }
        onFocusRequested(key)
    }

    val focusableKeys = addonSectionFocusableKeys(state, selectedAddonId)

    var placementNonce by remember { mutableIntStateOf(0) }
    var placementRequest by remember { mutableStateOf<AddonFocusPlacementRequest?>(null) }
    fun requestPlacement(key: String, targetOnly: Boolean = false) {
        placementNonce += 1
        placementRequest = AddonFocusPlacementRequest(placementNonce, key, targetOnly)
    }

    var sectionHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(placementRequest) {
        val request = placementRequest ?: return@LaunchedEffect
        val target = request.key
        val live = focusableKeys
        repeat(AddonFocusPlacementFrames) {
            androidx.compose.runtime.withFrameNanos { }
            requesters[target]?.requestFocus()

            if (focusedKey == target) return@LaunchedEffect

            if (!request.targetOnly && sectionHasFocus && focusedKey in live) return@LaunchedEffect
        }
        placementRequest = null
    }

    LaunchedEffect(addonMenuHost.open?.addonId, installed) {
        val openId = addonMenuHost.open?.addonId ?: return@LaunchedEffect
        if (installed.none { it.addonId == openId }) addonMenuHost.dismiss()
    }

    var menuOpenAddonId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(addonMenuHost.open?.addonId) {
        val nowOpen = addonMenuHost.open?.addonId
        val wasOpen = menuOpenAddonId
        menuOpenAddonId = nowOpen
        if (wasOpen != null && nowOpen == null && installed.any { it.addonId == wasOpen }) {
            requestPlacement(addonMainKey(wasOpen))
        }
    }

    fun runAddonMenuAction(action: AddonMenuAction, item: AddonManagementItemUi) {
        when (action) {

            AddonMenuAction.RENAME -> Unit

            AddonMenuAction.INFO -> Unit
            AddonMenuAction.REFRESH -> stateHolder.refresh(item.addonId)
            AddonMenuAction.UNINSTALL -> {

                uninstallInvokingKey = addonMainKey(item.addonId)
                uninstallRequest = item
                addonMenuHost.dismiss()
            }
        }
    }

    LaunchedEffect(entryToken) {
        if (entryToken <= 0) return@LaunchedEffect
        requestPlacement(addonSectionEntryKey(state, rememberedKey, selectedAddonId))
    }

    var installInFlight by remember { mutableStateOf(false) }
    var installedBeforeInstall by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun adoptInstallJourney() {
        if (!installInFlight) {
            installedBeforeInstall = installed.map { it.addonId }.toSet()
            installInFlight = true
        }
    }

    LaunchedEffect(state.installPhase) {
        when (val phase = state.installPhase) {
            is AddonInstallPhase.Reviewing,
            is AddonInstallPhase.ReviewReady,
            is AddonInstallPhase.Installing,
            is AddonInstallPhase.LanConsentRequired,
            -> adoptInstallJourney()

            is AddonInstallPhase.Installed -> if (installInFlight) {
                installInFlight = false
                val newId = installed.firstOrNull { it.addonId !in installedBeforeInstall }?.addonId
                requestPlacement(newId?.let { addonMainKey(it) } ?: addonSectionFirstKey(state))
            }

            is AddonInstallPhase.AlreadyInstalled,
            is AddonInstallPhase.ReviewInvalid,
            is AddonInstallPhase.ReviewFailed,
            is AddonInstallPhase.InstallFailed,
            is AddonInstallPhase.InstallConsentDenied,
            -> if (installInFlight) {
                installInFlight = false
                requestPlacement(focusedKey?.takeIf { it in focusableKeys } ?: AddonInstallUrlKey)
            }

            AddonInstallPhase.Idle -> installInFlight = false
        }
    }

    var pendingRemoval by remember { mutableStateOf<PendingAddonRemoval?>(null) }
    var rowsBefore by remember { mutableStateOf(emptyList<String>()) }

    val positionOwnerAtListChange = remember(installed) {
        focusedKey?.let { key -> addonRowAddonId(key) ?: addonContentAddonId(key) }
    }

    fun placeOnRemovalSurvivor(preferredSurvivors: List<String>) {
        val survivor = addonSurvivorId(preferredSurvivors, installed.map { it.addonId })
        requestPlacement(survivor?.let { addonMainKey(it) } ?: AddonInstallUrlKey, targetOnly = true)
    }

    LaunchedEffect(pendingRemoval, installed, state.itemOperations) {
        val pending = pendingRemoval ?: return@LaunchedEffect
        if (installed.any { it.addonId == pending.removedAddonId }) {

            if (state.operationFor(pending.removedAddonId) !is AddonItemOperation.Removing) {
                pendingRemoval = null
            }
            return@LaunchedEffect
        }
        pendingRemoval = null
        placeOnRemovalSurvivor(pending.preferredSurvivors)
    }

    LaunchedEffect(installed) {
        val rowsNow = installed.map { it.addonId }
        if (selectedAddonId == null || installed.none { it.addonId == selectedAddonId }) {
            selectedAddonId = rowsNow.firstOrNull()
        }

        val owner = positionOwnerAtListChange
        if (owner != null && owner !in rowsNow && sectionHasFocus) {

            placeOnRemovalSurvivor(addonRemovalSurvivorPreference(rowsBefore, owner))
        }
        rowsBefore = rowsNow
    }

    LaunchedEffect(installed) {
        val pending = pendingMove ?: return@LaunchedEffect
        pendingMove = null
        val (addonId, column) = pending
        val item = installed.firstOrNull { it.addonId == addonId } ?: return@LaunchedEffect
        requesters[addonRowKeyAt(state, item, column)]?.requestFocus()
    }

    LaunchedEffect(state.catalog) {
        val pending = pendingCatalogMove ?: return@LaunchedEffect
        pendingCatalogMove = null
        val (key, column) = pending
        val entry = state.catalog.firstOrNull {
            it.addonId == key.addonId &&
                it.catalogType == key.catalogType &&
                it.catalogId == key.catalogId
        } ?: return@LaunchedEffect
        val catalogs = state.catalogsOf(entry.addonId)
        requesters[catalogRowKeyAt(catalogs, entry, column)]?.requestFocus()
    }

    fun contentFirstKey(item: AddonManagementItemUi): String? =
        addonFocusableContentKeys(state.catalogsOf(item.addonId)).firstOrNull()

    fun enterContent(item: AddonManagementItemUi) {
        selectedAddonId = item.addonId
        contentFirstKey(item)?.let { requesters[it]?.requestFocus() }
    }

    fun toggle(item: AddonManagementItemUi) {
        if (!item.canManage) {

            enterContent(item)
            return
        }
        if (item.enabled) stateHolder.disable(item.addonId) else stateHolder.enable(item.addonId)
        requesters[addonMainKey(item.addonId)]?.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)

            .onFocusChanged { state -> sectionHasFocus = state.hasFocus },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        when (val repo = state.repositoryState) {
            is AddonManagementRepositoryState.Restoring -> Text(
                "Restoring installed add-ons…",
                style = InfusionTvScaleCalibration.Type.SettingsContent,
            )
            is AddonManagementRepositoryState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Add-on restore failed. Add-ons are unavailable.",
                    style = InfusionTvScaleCalibration.Type.SettingsContent,
                    color = MaterialTheme.colorScheme.error,
                )
                repo.issues.take(3).forEach { issue ->
                    Text(
                        "• $issue",
                        style = InfusionTvScaleCalibration.Type.SettingsContent,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            is AddonManagementRepositoryState.Ready -> Unit
        }

        AddonInstallJourney(
            state = state,
            stateHolder = stateHolder,
            requesters = requesters,
            onFocusedKeyChanged = ::reportFocus,

            onInstallSubmitted = ::adoptInstallJourney,
            onDpadDownFromField = {
                val first = installed.firstOrNull()
                if (first != null) requesters[addonMainKey(first.addonId)]?.requestFocus()
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {

            val selected = installed.firstOrNull { it.addonId == selectedAddonId }
            val selectedContentKeys = selected
                ?.let { addonContentControlKeys(it, state.catalogsOf(it.addonId)) }
                ?: emptyList()
            val focusInContent = addonFocusInContent(focusedKey, selectedContentKeys)

            Column(
                modifier = Modifier
                    .weight(0.55f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .testTag(AddonListPane)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val currentRowKey = focusedKey?.takeIf { addonRowAddonId(it) != null }
                            ?: return@onPreviewKeyEvent false
                        val addonId = addonRowAddonId(currentRowKey) ?: return@onPreviewKeyEvent false
                        val item = installed.firstOrNull { it.addonId == addonId }
                            ?: return@onPreviewKeyEvent false
                        val rowIndex = installed.indexOfFirst { it.addonId == addonId }
                        val column = addonRowColumn(currentRowKey) ?: return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionUp -> {

                                val target = installed.getOrNull(rowIndex - 1)
                                if (target != null) {
                                    requesters[addonRowKeyAt(state, target, column)]?.requestFocus()
                                } else {
                                    requesters[AddonInstallUrlKey]?.requestFocus()
                                }
                                true
                            }
                            Key.DirectionDown -> {

                                installed.getOrNull(rowIndex + 1)?.let { target ->
                                    requesters[addonRowKeyAt(state, target, column)]?.requestFocus()
                                }
                                true
                            }
                            Key.DirectionLeft -> {

                                val leftKey = when (column) {
                                    AddonColumnMain -> when {
                                        addonColumnAvailable(state, item, AddonColumnPriorityDown) ->
                                            addonPriorityDownKey(addonId)
                                        addonColumnAvailable(state, item, AddonColumnPriorityUp) ->
                                            addonPriorityUpKey(addonId)
                                        else -> null
                                    }
                                    AddonColumnPriorityDown ->
                                        addonPriorityUpKey(addonId)
                                            .takeIf { addonColumnAvailable(state, item, AddonColumnPriorityUp) }
                                    else -> null
                                }
                                if (leftKey != null) requesters[leftKey]?.requestFocus() else onReturnToRail()
                                true
                            }
                            Key.DirectionRight -> {

                                val rightKey = when (column) {
                                    AddonColumnPriorityUp -> when {
                                        addonColumnAvailable(state, item, AddonColumnPriorityDown) ->
                                            addonPriorityDownKey(addonId)
                                        else -> addonMainKey(addonId)
                                    }
                                    AddonColumnPriorityDown -> addonMainKey(addonId)
                                    else -> null
                                }
                                if (rightKey != null) requesters[rightKey]?.requestFocus() else enterContent(item)
                                true
                            }
                            else -> false
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (installed.isEmpty()) {
                    Text(
                        "No add-ons installed for this profile.",
                        style = InfusionTvScaleCalibration.Type.SettingsContent,
                    )
                } else {
                    installed.forEach { item ->
                        key(item.addonId) {
                            AddonRow(
                                item = item,
                                state = state,
                                requesters = requesters,
                                onFocus = ::reportFocus,

                                active = addonMainIsActiveRow(item.addonId, selectedAddonId, focusInContent),
                                onMoveUp = {
                                    pendingMove = item.addonId to AddonColumnPriorityUp
                                    stateHolder.moveUpInstalled(item.addonId)
                                },
                                onMoveDown = {
                                    pendingMove = item.addonId to AddonColumnPriorityDown
                                    stateHolder.moveDownInstalled(item.addonId)
                                },
                                onMain = { toggle(item) },

                                menuHost = addonMenuHost,
                            )
                        }
                    }
                }
            }

            if (selected != null) {
                val selectedCatalogs = state.catalogsOf(selected.addonId)
                AddonContentArea(
                    item = selected,
                    catalogs = selectedCatalogs,
                    state = state,
                    stateHolder = stateHolder,
                    requesters = requesters,
                    focusedKey = focusedKey,
                    onFocus = ::reportFocus,
                    onReturnToRow = {
                        requesters[addonMainKey(selected.addonId)]?.requestFocus()
                    },

                    onMoveCatalog = { catalogKey, column, delta ->
                        pendingCatalogMove = catalogKey to column
                        if (delta < 0) stateHolder.moveUp(catalogKey) else stateHolder.moveDown(catalogKey)
                    },
                    onToggleCatalog = { catalogKey ->
                        stateHolder.toggleCatalogVisibility(catalogKey)
                        requesters[
                            addonCatalogToggleKey(
                                catalogKey.addonId,
                                catalogKey.catalogType,
                                catalogKey.catalogId,
                            ),
                        ]?.requestFocus()
                    },

                    backEnabled = focusedKey != null &&
                        focusedKey in addonFocusableContentKeys(selectedCatalogs),
                )
            } else {

                Box(modifier = Modifier.weight(0.45f)) {
                    Text(
                        "Select an add-on to manage it.",
                        style = InfusionTvScaleCalibration.Type.Body,
                    )
                }
            }
        }
    }

    var modalWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(uninstallRequest) {
        val open = uninstallRequest != null
        val wasOpen = modalWasOpen
        modalWasOpen = open
        if (wasOpen && !open) {
            val invoking = uninstallInvokingKey
            val invokingAddonId =
                invoking?.let { addonRowAddonId(it) ?: addonContentAddonId(it) }
            val removalStarted = invokingAddonId != null &&
                state.operationFor(invokingAddonId) is AddonItemOperation.Removing
            if (invoking != null && invoking in focusableKeys && !removalStarted) {
                requestPlacement(invoking)
            }
        }
    }

    uninstallRequest?.let { item ->
        TvUninstallDialog(
            item = item,
            onDismiss = { uninstallRequest = null },
            onConfirm = {

                pendingRemoval = PendingAddonRemoval(
                    removedAddonId = item.addonId,
                    preferredSurvivors = addonRemovalSurvivorPreference(
                        installed.map { it.addonId },
                        item.addonId,
                    ),
                )
                stateHolder.remove(item.addonId)
                uninstallRequest = null
            },
        )
    }

    AddonContextMenuOverlay(
        host = addonMenuHost,
        state = state,
        onDismiss = { addonMenuHost.dismiss() },
        onAction = ::runAddonMenuAction,
    )

    AddonInfoOverlay(
        host = addonMenuHost,
        state = state,
    )

    AddonRenameOverlay(
        host = addonMenuHost,
        state = state,
        onSave = { item, name -> stateHolder.rename(item.addonId, name) },
    )
}

@Composable
private fun AddonRow(
    item: AddonManagementItemUi,
    state: AddonManagementUiState,
    requesters: MutableMap<String, FocusRequester>,
    onFocus: (String) -> Unit,

    active: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMain: () -> Unit,

    menuHost: AddonContextMenuHostState,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = HomeRowHeight),
        horizontalArrangement = Arrangement.spacedBy(HomeRowSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AddonMoveCell(
            glyph = "▲",
            tag = addonPriorityUpKey(item.addonId),
            accessibleLabel = "Move ${item.displayName} up in provider priority",
            enabled = addonColumnAvailable(state, item, AddonColumnPriorityUp),
            requesters = requesters,
            onFocused = onFocus,
            onClick = onMoveUp,
            textStyle = InfusionTvScaleCalibration.Type.SettingsContent,
        )
        AddonMoveCell(
            glyph = "▼",
            tag = addonPriorityDownKey(item.addonId),
            accessibleLabel = "Move ${item.displayName} down in provider priority",
            enabled = addonColumnAvailable(state, item, AddonColumnPriorityDown),
            requesters = requesters,
            onFocused = onFocus,
            onClick = onMoveDown,
            textStyle = InfusionTvScaleCalibration.Type.SettingsContent,
        )
        AddonMainButton(
            item = item,
            requesters = requesters,
            onFocused = onFocus,
            active = active,
            onClick = onMain,
            menuHost = menuHost,
        )
    }
}

@Composable
private fun AddonMoveCell(
    glyph: String,
    tag: String,
    accessibleLabel: String,
    enabled: Boolean,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onClick: () -> Unit,
    textStyle: TextStyle,
) {
    if (!enabled) {
        Box(
            modifier = Modifier
                .width(HomeRowActionWidth)
                .height(HomeRowHeight)
                .testTag(tag)
                .semantics {
                    this.contentDescription = accessibleLabel
                    disabled()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                glyph,
                style = textStyle,
                color = LocalContentColor.current.copy(alpha = 0.38f),
            )
        }
        return
    }
    val requester = remember(tag) { FocusRequester() }
    requesters[tag] = requester
    InfusionTvButton(
        onClick = onClick,
        modifier = Modifier
            .width(HomeRowActionWidth)
            .height(HomeRowHeight)
            .testTag(tag)
            .focusRequester(requester)
            .onFocusChanged { focusState -> if (focusState.isFocused) onFocused(tag) }
            .semantics { this.contentDescription = accessibleLabel },
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        Text(glyph, style = textStyle)
    }
}

@Composable
private fun RowScope.AddonMainButton(
    item: AddonManagementItemUi,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,

    active: Boolean,
    onClick: () -> Unit,
    menuHost: AddonContextMenuHostState,
) {
    val tag = addonMainKey(item.addonId)
    val requester = remember(tag) { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val treatment = addonRowStateTreatment(item.enabled)

    val menuModifier = centerHoldMenuModifier(
        focusKey = tag,
        isOpenForMe = { menuHost.open?.anchorKey == tag },
        isAnyMenuOpen = { menuHost.open != null },
        onHeldThreshold = { downTime -> menuHost.openOnPress(item.addonId, tag, downTime) },
        onOpenAccessible = { menuHost.openAccessible(item.addonId, tag) },
    )

    val titleBoxInWindow = remember { mutableStateOf<Rect?>(null) }
    val controlTopInWindow = remember { mutableStateOf<Float?>(null) }
    val hintVerticalCenter: () -> Float? = {
        val title = titleBoxInWindow.value
        val top = controlTopInWindow.value
        if (title == null || top == null) null else title.center.y - top
    }
    requesters[tag] = requester
    InfusionTvButton(
        onClick = onClick,
        interactionSource = interactionSource,

        contextMenuAvailable = true,

        contextMenuHintVerticalCenter = hintVerticalCenter,

        selected = active,

        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .height(HomeRowHeight)
            .testTag(tag)
            .focusRequester(requester)
            .onFocusChanged { focusState -> if (focusState.isFocused) onFocused(tag) }

            .onGloballyPositioned { coordinates ->
                controlTopInWindow.value = coordinates.positionInWindow().y
            }

            .then(menuModifier)
            .then(
                if (item.canManage) {
                    Modifier.semantics {
                        role = Role.Switch
                        toggleableState = if (item.enabled) ToggleableState.On else ToggleableState.Off
                    }
                } else {
                    Modifier.semantics { this.contentDescription = "${item.displayName}. Read-only." }
                },
            )

            .then(Modifier.semantics { this.selected = active }),
        contentPadding = PaddingValues(horizontal = HomeRowCellPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                item.displayName,
                style = InfusionTvScaleCalibration.Type.SettingsContent,

                modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned { coordinates ->
                        titleBoxInWindow.value = coordinates.boundsInWindow()
                    },
            )
            Text(
                if (item.enabled) "ON" else "OFF",
                style = InfusionTvScaleCalibration.Type.SettingsContent,

                color = LocalContentColor.current.copy(alpha = treatment.labelAlpha),
            )
        }
    }
}

internal const val AddonCatalogPanelTitle = "Catalog Management · Discover tab only"

@Composable
private fun RowScope.AddonContentArea(
    item: AddonManagementItemUi,

    catalogs: List<AddonCatalogEntryUi>,
    state: AddonManagementUiState,

    stateHolder: AddonManagementStateHolder,
    requesters: MutableMap<String, FocusRequester>,

    focusedKey: String?,
    onFocus: (String) -> Unit,
    onReturnToRow: () -> Unit,

    onMoveCatalog: (AddonManagementCatalogKey, Int, Int) -> Unit,

    onToggleCatalog: (AddonManagementCatalogKey) -> Unit,

    backEnabled: Boolean,
) {
    val id = item.addonId
    val operation = state.operationFor(id)
    val canManage = item.canManage

    androidx.activity.compose.BackHandler(enabled = backEnabled) { onReturnToRow() }

    Column(
        modifier = Modifier
            .weight(0.45f)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
            .testTag(AddonContentPane)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key == Key.Back) {
                    onReturnToRow()
                    return@onPreviewKeyEvent true
                }
                if (catalogs.isEmpty()) {

                    if (event.key == Key.DirectionLeft) {
                        onReturnToRow()
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }

                val current = focusedKey ?: return@onPreviewKeyEvent false
                val rowIndex = catalogRowIndex(current, catalogs) ?: return@onPreviewKeyEvent false
                val column = catalogRowColumn(current) ?: return@onPreviewKeyEvent false
                val entry = catalogs[rowIndex]
                when (event.key) {
                    Key.DirectionLeft -> {
                        val left = catalogRowLeftKey(catalogs, entry, column)
                        if (left != null) requesters[left]?.requestFocus() else onReturnToRow()
                        true
                    }
                    Key.DirectionRight -> {

                        catalogRowRightKey(catalogs, entry, column)
                            ?.let { requesters[it]?.requestFocus() }
                        true
                    }
                    Key.DirectionUp -> {
                        catalogs.getOrNull(rowIndex - 1)?.let { target ->
                            requesters[catalogRowKeyAt(catalogs, target, column)]?.requestFocus()
                        }
                        true
                    }
                    Key.DirectionDown -> {
                        catalogs.getOrNull(rowIndex + 1)?.let { target ->
                            requesters[catalogRowKeyAt(catalogs, target, column)]?.requestFocus()
                        }
                        true
                    }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (catalogs.isEmpty()) {

                Text(item.displayName, style = InfusionTvScaleCalibration.Type.Headline)
                Text(item.origin, style = InfusionTvScaleCalibration.Type.Body)
                Text(
                    "Health: ${item.healthLabel} · Owner: ${if (item.ownedByViewer) "this profile" else "shared (read-only)"}",
                    style = InfusionTvScaleCalibration.Type.Body,
                )
                if (item.refreshFailed) {
                    Text(
                        "Refresh failed (${item.refreshFailureReason ?: "unknown"}) — last validated manifest kept",
                        style = InfusionTvScaleCalibration.Type.Body,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "Requests: ${item.requestedResourceTypes.joinToString(", ").ifEmpty { "none" }} · Credentials: ${item.credentialUse}",
                    style = InfusionTvScaleCalibration.Type.Body,
                )
                if (!canManage) {
                    Text(
                        "Read-only — installed by another profile. You can inspect health and organize Discover catalogs.",
                        style = InfusionTvScaleCalibration.Type.Body,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                AddonItemOperationLine(operation)
            } else {

                Text(AddonCatalogPanelTitle, style = InfusionTvScaleCalibration.Type.Headline)

                if (operation !is AddonItemOperation.Idle) AddonItemOperationLine(operation)
                catalogs.forEach { entry ->

                    key(entry.catalogType, entry.catalogId) {
                        AddonCatalogRow(
                            catalogs = catalogs,
                            entry = entry,
                            requesters = requesters,
                            onFocus = onFocus,
                            onMove = { column, delta -> onMoveCatalog(entry.catalogKey(), column, delta) },
                            onToggleVisibility = { onToggleCatalog(entry.catalogKey()) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddonItemOperationLine(operation: AddonItemOperation) {
    when (operation) {
        is AddonItemOperation.Failed -> Text(
            operation.message,
            style = InfusionTvScaleCalibration.Type.Body,
            color = MaterialTheme.colorScheme.error,
        )
        is AddonItemOperation.Enabling -> Text("Enabling…", style = InfusionTvScaleCalibration.Type.Body)
        is AddonItemOperation.Disabling -> Text("Disabling…", style = InfusionTvScaleCalibration.Type.Body)
        is AddonItemOperation.Refreshing -> Text("Refreshing…", style = InfusionTvScaleCalibration.Type.Body)
        is AddonItemOperation.Moving -> Text("Moving…", style = InfusionTvScaleCalibration.Type.Body)
        is AddonItemOperation.Removing -> Text("Removing…", style = InfusionTvScaleCalibration.Type.Body)
        AddonItemOperation.Idle -> Unit
    }
}

@Composable
private fun AddonCatalogRow(
    catalogs: List<AddonCatalogEntryUi>,
    entry: AddonCatalogEntryUi,
    requesters: MutableMap<String, FocusRequester>,
    onFocus: (String) -> Unit,

    onMove: (Int, Int) -> Unit,
    onToggleVisibility: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = HomeRowHeight),
        horizontalArrangement = Arrangement.spacedBy(HomeRowSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CatalogMainButton(
            entry = entry,
            requesters = requesters,
            onFocused = onFocus,
            onClick = onToggleVisibility,
        )
        AddonMoveCell(
            glyph = "▲",
            tag = addonCatalogUpKey(entry.addonId, entry.catalogType, entry.catalogId),
            accessibleLabel = "Move ${entry.displayName} up",
            enabled = catalogColumnAvailable(catalogs, entry, CatalogColumnUp),
            requesters = requesters,
            onFocused = onFocus,
            onClick = { onMove(CatalogColumnUp, -1) },
            textStyle = InfusionTvScaleCalibration.Type.Body,
        )
        AddonMoveCell(
            glyph = "▼",
            tag = addonCatalogDownKey(entry.addonId, entry.catalogType, entry.catalogId),
            accessibleLabel = "Move ${entry.displayName} down",
            enabled = catalogColumnAvailable(catalogs, entry, CatalogColumnDown),
            requesters = requesters,
            onFocused = onFocus,
            onClick = { onMove(CatalogColumnDown, +1) },
            textStyle = InfusionTvScaleCalibration.Type.Body,
        )
    }
}

@Composable
private fun RowScope.CatalogMainButton(
    entry: AddonCatalogEntryUi,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onClick: () -> Unit,
) {
    val tag = addonCatalogToggleKey(entry.addonId, entry.catalogType, entry.catalogId)
    val treatment = addonRowStateTreatment(entry.visibleInDiscover)
    InfusionTvButton(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .height(HomeRowHeight)
            .then(
                stableFocusModifier(
                    key = tag,
                    requesters = requesters,
                    onFocusedKeyChanged = onFocused,
                ),
            )
            .semantics {
                role = Role.Switch
                toggleableState = if (entry.visibleInDiscover) ToggleableState.On else ToggleableState.Off
            },
        contentPadding = PaddingValues(horizontal = HomeRowCellPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                entry.displayName,
                style = InfusionTvScaleCalibration.Type.Body,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (entry.visibleInDiscover) "ON" else "OFF",
                style = InfusionTvScaleCalibration.Type.Body,

                color = LocalContentColor.current.copy(alpha = treatment.labelAlpha),
            )
        }
    }
}

private fun AddonCatalogEntryUi.catalogKey(): AddonManagementCatalogKey =
    AddonManagementCatalogKey(addonId = addonId, catalogType = catalogType, catalogId = catalogId)

@Composable
private fun AddonInstallJourney(
    state: AddonManagementUiState,
    stateHolder: AddonManagementStateHolder,
    requesters: MutableMap<String, FocusRequester>,
    onFocusedKeyChanged: (String) -> Unit,

    onInstallSubmitted: () -> Unit,
    onDpadDownFromField: () -> Unit,
) {
    val urlRequester = remember { FocusRequester() }
    requesters[AddonInstallUrlKey] = urlRequester
    val phase = state.installPhase
    val submitting = phase is AddonInstallPhase.Reviewing || phase is AddonInstallPhase.Installing

    LaunchedEffect(phase) {
        if (phase is AddonInstallPhase.LanConsentRequired) {
            stateHolder.onLanReviewApprovedChanged(true)
            stateHolder.startReview()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabeledField(
            label = "Install add-on URL (e.g. https://host/manifest.json)",
            value = state.manifestUrl,
            onChange = { stateHolder.onManifestUrlChanged(it) },
            testTag = AddonInstallUrlKey,
            focusRequester = urlRequester,
            onFocusChanged = { focused -> if (focused) onFocusedKeyChanged(AddonInstallUrlKey) },

            onDpadUp = {},
            onDpadDown = onDpadDownFromField,
            imeAction = ImeAction.Search,
            onImeSubmit = {

                if (!submitting) {
                    stateHolder.onLanReviewApprovedChanged(true)

                    onInstallSubmitted()
                    stateHolder.startInstall()

                    stateHolder.onManifestUrlChanged("")
                }
            },
        )

        when (phase) {
            is AddonInstallPhase.Reviewing -> Text("Fetching manifest…", style = InfusionTvScaleCalibration.Type.SettingsContent)
            is AddonInstallPhase.ReviewReady -> TvAddonReviewConsent(
                phase = phase,
                stateHolder = stateHolder,
                requesters = requesters,
                onFocusedKeyChanged = onFocusedKeyChanged,
            )
            is AddonInstallPhase.ReviewInvalid -> Text(
                "Invalid: ${phase.reason}",
                style = InfusionTvScaleCalibration.Type.SettingsContent,
                color = MaterialTheme.colorScheme.error,
            )
            is AddonInstallPhase.ReviewFailed -> Text(
                "Review failed: ${phase.reason}",
                style = InfusionTvScaleCalibration.Type.SettingsContent,
                color = MaterialTheme.colorScheme.error,
            )
            is AddonInstallPhase.Installing -> Text("Installing…", style = InfusionTvScaleCalibration.Type.SettingsContent)
            is AddonInstallPhase.Installed -> Text("Installed.", style = InfusionTvScaleCalibration.Type.SettingsContent)
            is AddonInstallPhase.AlreadyInstalled -> Text(
                "Already installed.",
                style = InfusionTvScaleCalibration.Type.SettingsContent,
            )
            is AddonInstallPhase.InstallFailed -> Text(
                "Install failed: ${phase.reason}",
                style = InfusionTvScaleCalibration.Type.SettingsContent,
                color = MaterialTheme.colorScheme.error,
            )
            is AddonInstallPhase.InstallConsentDenied -> Text(
                "Consent required: ${phase.reason}",
                style = InfusionTvScaleCalibration.Type.SettingsContent,
                color = MaterialTheme.colorScheme.error,
            )
            is AddonInstallPhase.LanConsentRequired, AddonInstallPhase.Idle -> Unit
        }
    }
}

@Composable
private fun TvUninstallDialog(
    item: AddonManagementItemUi,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val requesters = rememberFocusRequesters()
    LaunchedEffect(Unit) {
        repeat(30) {
            androidx.compose.runtime.withFrameNanos { }
            if (requesters["uninstall-cancel"]?.requestFocus() == true) return@LaunchedEffect
        }
    }
    androidx.activity.compose.BackHandler(enabled = true, onBack = onDismiss)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.width(560.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Uninstall ${item.displayName}?", style = InfusionTvScaleCalibration.Type.Headline)
                Text(
                    "This removes the add-on's configuration and cached provider data. Watch history is kept.",
                    style = InfusionTvScaleCalibration.Type.SettingsContent,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfusionTvButton(
                        onClick = onDismiss,
                        modifier = stableFocusModifier("uninstall-cancel", requesters) {},
                    ) { Text("Cancel") }
                    InfusionTvButton(
                        onClick = onConfirm,
                        modifier = stableFocusModifier("uninstall-confirm", requesters) {},
                    ) { Text("Uninstall") }
                }
            }
        }
    }
}

@Composable
private fun TvAddonReviewConsent(
    phase: AddonInstallPhase.ReviewReady,
    stateHolder: AddonManagementStateHolder,
    requesters: MutableMap<String, FocusRequester>,
    onFocusedKeyChanged: (String) -> Unit,
) {
    val review = phase.review
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Review ${review.addonName} v${review.addonVersion}", style = InfusionTvScaleCalibration.Type.Emphasis)
        review.description.takeIf { it.isNotBlank() }?.let { Text(it, style = InfusionTvScaleCalibration.Type.SettingsContent) }
        Text("Endpoint: ${review.endpoint} (${review.trustLabel})", style = InfusionTvScaleCalibration.Type.SettingsContent)
        Text(
            "Requested resource types: ${review.requestedResourceTypes.joinToString(", ")}",
            style = InfusionTvScaleCalibration.Type.SettingsContent,
        )
        Text("Catalog visibility: ${review.catalogVisibility.joinToString(", ")}", style = InfusionTvScaleCalibration.Type.SettingsContent)
        Text("Network: ${review.networkDisclosure}", style = InfusionTvScaleCalibration.Type.SettingsContent)
        Text("Credentials/headers: ${review.credentialUse}", style = InfusionTvScaleCalibration.Type.SettingsContent)
        if (review.lanRequested) {
            Text("This add-on requests LAN access.", style = InfusionTvScaleCalibration.Type.SettingsContent)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfusionTvButton(
                onClick = {
                    stateHolder.acceptConsent(
                        AddonConsentInput(
                            endpointAccepted = true,
                            resourceTypesAccepted = true,
                            catalogVisibilityAccepted = true,
                            networkDisclosureAccepted = true,
                            credentialUseNoneAccepted = true,
                            lanGranted = review.lanRequested,
                        ),
                    )
                },
                modifier = stableFocusModifier("consent-install", requesters, onFocusedKeyChanged),
            ) { Text("Install") }
            InfusionTvButton(
                onClick = { stateHolder.cancelInstall() },
                modifier = stableFocusModifier("consent-cancel", requesters, onFocusedKeyChanged),
            ) { Text("Cancel") }
        }
    }
}
