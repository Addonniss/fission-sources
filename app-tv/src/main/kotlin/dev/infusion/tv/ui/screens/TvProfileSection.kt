package dev.infusion.tv.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvFieldLabel
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.InfusionTvTextField
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.infusion.core.domain.profile.ProfileLedgerDisposition
import dev.infusion.core.model.profile.Profile
import dev.infusion.feature.vertical.profile.PendingDeleteConfirmation
import dev.infusion.feature.vertical.profile.PinEntryStateHolder
import dev.infusion.feature.vertical.profile.PinEntryStatus
import dev.infusion.feature.vertical.profile.PinEntryUiState
import dev.infusion.feature.vertical.profile.ProfileManagementFeedback
import dev.infusion.feature.vertical.profile.ProfileManagementStateHolder
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.focus.stableFocusModifier

@Composable
fun TvProfileSection(
    management: ProfileManagementStateHolder,
    pinEntryFactory: (String) -> PinEntryStateHolder,
    modifier: Modifier = Modifier,
    requesters: MutableMap<String, FocusRequester> = rememberFocusRequesters(),
    onControlFocused: (String) -> Unit = {},
) {
    val state by management.state.collectAsStateWithLifecycle()

    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Profile?>(null) }
    var pinProfileId by remember { mutableStateOf<String?>(null) }
    var lastActionKey by remember { mutableStateOf<String?>(null) }

    val profileOrder: List<String> = state.profiles.map { it.profileId }
    val paneControlKeys: List<String> = buildList {
        add(KeyCreate)
        state.profiles.forEach { profile ->
            addAll(profileRowActionKeys(profile, isActive = profile.profileId == state.activeProfileId))
        }
    }

    val paneMemory = remember { PaneFocusMemory() }

    val reportControlFocus: (String) -> Unit = { key ->
        lastActionKey = key
        onControlFocused(key)
    }

    suspend fun requestPaneFocus(key: String): Boolean {
        repeat(FocusRetryFrames) {
            withFrameNanos { }
            if (requesters[key]?.requestFocus() == true) return true
        }
        return false
    }

    val pinHolder = pinProfileId?.let { id -> remember(id) { pinEntryFactory(id) } }

    val pinUiState by pinHolder?.state?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf<PinEntryUiState?>(null) }

    val topDialogKey: String? = when {
        creating -> KeyCreateName
        renaming != null -> KeyRenameName
        pinProfileId != null -> KeyPin1
        state.pendingDelete != null -> KeyDeleteConfirm
        else -> null
    }

    LaunchedEffect(topDialogKey, profileOrder) {
        val openDialog = topDialogKey
        val closedDialog = paneMemory.dialogKey != null && openDialog == null
        val orderBefore = paneMemory.profileOrder
        paneMemory.dialogKey = openDialog
        paneMemory.profileOrder = profileOrder

        if (openDialog != null) {
            requestPaneFocus(openDialog)
            return@LaunchedEffect
        }
        val target = when {
            orderBefore.size > profileOrder.size ->
                deletionFocusTarget(
                    profilesAfter = state.profiles,
                    activeProfileId = state.activeProfileId,
                    orderBefore = orderBefore,
                    orderAfter = profileOrder,
                ) ?: KeyCreate
            closedDialog -> lastActionKey?.takeIf { it in paneControlKeys } ?: KeyCreate
            else -> return@LaunchedEffect
        }
        lastActionKey = target
        onControlFocused(target)
        requestPaneFocus(target)
    }

    LaunchedEffect(pinUiState?.status) {
        val status = pinUiState?.status ?: return@LaunchedEffect
        if (status == PinEntryStatus.Verified) {
            val targetId = pinProfileId ?: return@LaunchedEffect
            pinHolder?.reset()
            pinProfileId = null
            management.switchTo(targetId)
        }
    }

    val bottomRowKey = state.profiles.lastOrNull()?.let { last ->
        profileRowActionKeys(last, isActive = last.profileId == state.activeProfileId).lastOrNull()
    } ?: KeyCreate

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> lastActionKey == KeyCreate
                    Key.DirectionDown -> lastActionKey == bottomRowKey
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Profiles", style = InfusionTvScaleCalibration.Type.Headline)
        FeedbackLine(state.feedback)

        ProfileActionRow(
            label = "Create profile",
            help = SettingsRowHelp.Profiles.Create,
            tag = KeyCreate,
            requesters = requesters,
            onFocused = reportControlFocus,
            onClick = {
                management.consumeFeedback()
                creating = true
            },
        )

        state.profiles.forEach { profile ->

            key(profile.profileId) {
                ProfileGroup(
                    profile = profile,
                    isActive = profile.profileId == state.activeProfileId,
                    requesters = requesters,
                    onFocused = reportControlFocus,
                    onSwitch = {
                        management.consumeFeedback()
                        if (profile.pinProtected) {
                            pinProfileId = profile.profileId
                        } else {
                            management.switchTo(profile.profileId)
                        }
                    },
                    onRename = {
                        management.consumeFeedback()
                        renaming = profile
                    },
                    onDelete = {
                        management.consumeFeedback()
                        management.requestDelete(profile.profileId)
                    },
                )
            }
        }
    }

    if (creating) {
        CreateProfileDialog(
            requesters = requesters,
            onConfirm = { name, pin ->
                management.create(name, pin)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }
    if (renaming != null) {
        RenameProfileDialog(
            profile = renaming!!,
            requesters = requesters,
            onConfirm = { name ->
                management.rename(renaming!!.profileId, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    state.pendingDelete?.let { pending ->
        DeleteProfileDialog(
            pending = pending,
            requesters = requesters,
            onDisposition = { management.selectDisposition(it) },
            onConfirm = { management.confirmDelete() },
            onDismiss = { management.cancelDelete() },
        )
    }
    if (pinProfileId != null && pinHolder != null) {
        PinEntryDialog(
            profileName = state.profiles.firstOrNull { it.profileId == pinProfileId }?.name ?: "profile",
            holder = pinHolder,
            requesters = requesters,
            onDismiss = {
                pinHolder.reset()
                pinProfileId = null
            },
        )
    }
}

internal const val KeyCreate = "profile:create"
internal const val KeyCreateConfirm = "profile:dialog:create:confirm"
internal const val KeyCreateCancel = "profile:dialog:create:cancel"
internal const val KeyCreateName = "profile:dialog:create:name"
internal const val KeyCreatePin = "profile:dialog:create:pin"
internal const val KeyRenameConfirm = "profile:dialog:rename:confirm"
internal const val KeyRenameCancel = "profile:dialog:rename:cancel"
internal const val KeyRenameName = "profile:dialog:rename:name"
internal const val KeyDeleteConfirm = "profile:dialog:delete:confirm"
internal const val KeyDeleteCancel = "profile:dialog:delete:cancel"
internal const val KeyDeletePreserve = "profile:dialog:delete:preserve"
internal const val KeyDeleteRemove = "profile:dialog:delete:remove"
internal const val KeyPin1 = "pin:1"
internal const val KeyPinCancel = "pin:cancel"
private const val FocusRetryFrames = 30

internal enum class ProfileActionKind {

    SWITCH,

    RENAME,

    DELETE,
    ;

    internal val label: String
        get() = when (this) {
            SWITCH -> "Switch"
            RENAME -> "Rename"
            DELETE -> "Delete"
        }

    internal val help: String
        get() = when (this) {
            SWITCH -> SettingsRowHelp.Profiles.Switch
            RENAME -> SettingsRowHelp.Profiles.Rename
            DELETE -> SettingsRowHelp.Profiles.Delete
        }

    internal fun key(profileId: String): String = when (this) {
        SWITCH -> profileSwitchKey(profileId)
        RENAME -> profileRenameKey(profileId)
        DELETE -> profileDeleteKey(profileId)
    }

    internal fun description(profileName: String): String = when (this) {
        SWITCH -> "Switch to $profileName"
        RENAME -> "Rename $profileName"
        DELETE -> "Delete $profileName"
    }
}

internal fun profileRowActions(profile: Profile, isActive: Boolean): List<ProfileActionKind> =
    buildList {
        if (!isActive) add(ProfileActionKind.SWITCH)
        add(ProfileActionKind.RENAME)
        if (!profile.isPrimary) add(ProfileActionKind.DELETE)
    }

internal fun profileRowActionKeys(profile: Profile, isActive: Boolean): List<String> =
    profileRowActions(profile, isActive).map { action -> action.key(profile.profileId) }

internal fun profileSwitchKey(profileId: String): String = "profile:switch:$profileId"

internal fun profileRenameKey(profileId: String): String = "profile:rename:$profileId"

internal fun profileDeleteKey(profileId: String): String = "profile:delete:$profileId"

internal fun deletionFocusTarget(
    profilesAfter: List<Profile>,
    activeProfileId: String?,
    orderBefore: List<String>,
    orderAfter: List<String>,
): String? {
    if (orderBefore.size <= orderAfter.size) return null
    val removed = orderBefore.filterNot { it in orderAfter }
    if (removed.size != 1) return null
    val index = orderBefore.indexOf(removed.single())

    fun actionKeys(profileId: String): List<String> =
        profilesAfter.firstOrNull { it.profileId == profileId }
            ?.let { profile -> profileRowActionKeys(profile, isActive = profile.profileId == activeProfileId) }
            .orEmpty()

    val next = orderAfter.getOrNull(index)?.let { actionKeys(it).firstOrNull() }
    val previous = orderAfter.getOrNull(index - 1)?.let { actionKeys(it).lastOrNull() }
    return next ?: previous ?: KeyCreate
}

private class PaneFocusMemory {
    var dialogKey: String? = null
    var profileOrder: List<String> = emptyList()
}

@Composable
private fun ProfileGroup(
    profile: Profile,
    isActive: Boolean,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onSwitch: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    ProfileHeading(profile = profile, isActive = isActive)
    profileRowActions(profile, isActive).forEach { action ->
        ProfileActionRow(
            label = action.label,
            help = action.help,
            tag = action.key(profile.profileId),
            requesters = requesters,
            onFocused = onFocused,
            onClick = when (action) {
                ProfileActionKind.SWITCH -> onSwitch
                ProfileActionKind.RENAME -> onRename
                ProfileActionKind.DELETE -> onDelete
            },
            description = action.description(profile.name),
        )
    }
}

@Composable
private fun ProfileHeading(profile: Profile, isActive: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = profile.name,
            style = InfusionTvScaleCalibration.Type.Headline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        val indicators = buildList {
            if (profile.pinProtected) add("PIN")
            if (profile.isPrimary) add("Primary")
            if (isActive) add("Active")
        }
        if (indicators.isNotEmpty()) {
            Row(

                modifier = Modifier.padding(start = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                indicators.forEach { indicator ->
                    Text(indicator, style = InfusionTvScaleCalibration.Type.SettingsContent)
                }
            }
        }
    }
}

@Composable
private fun ProfileActionRow(
    label: String,
    help: String,
    tag: String,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onClick: () -> Unit,

    description: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    InfusionTvButton(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = GeneralRowHeight)
            .focusProperties { right = FocusRequester.Cancel }
            .then(
                stableFocusModifier(
                    key = tag,
                    requesters = requesters,
                    onFocusedKeyChanged = onFocused,
                ),
            )
            .then(
                if (description != null) {
                    Modifier.semantics { contentDescription = description }
                } else {
                    Modifier
                },
            )
            .then(settingsRowBringIntoView(focused)),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        SettingsRowBody(
            presentation = SettingsRowPresentation.EXPANDED,
            label = label,
            help = help,
            value = "",
            focused = focused,
        )
    }
}

@Composable
private fun FeedbackLine(feedback: ProfileManagementFeedback?) {
    val message = when (feedback) {
        null -> null
        ProfileManagementFeedback.PrimaryProtected -> "The primary profile can't be deleted."
        ProfileManagementFeedback.CapExceeded -> "Profile limit reached."
        is ProfileManagementFeedback.Deleted ->
            if (feedback.outcome.disposition == ProfileLedgerDisposition.PRESERVE) {
                "Profile deleted — watch history preserved."
            } else {
                "Profile deleted — watch history removed."
            }
        ProfileManagementFeedback.NotFound -> "Profile not found."
    }
    if (message != null) {
        Text(message, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun CreateProfileDialog(
    requesters: MutableMap<String, FocusRequester>,
    onConfirm: (name: String, pin: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    TvDialog(
        title = "Create profile",
        onDismissRequest = onDismiss,
    ) {
        TvTextField(
            label = "Name",
            value = name,
            onChange = { name = it },
            key = KeyCreateName,
            requesters = requesters,
        )
        TvTextField(
            label = "Optional PIN (4+ digits)",
            value = pin,
            onChange = { pin = it },
            key = KeyCreatePin,
            requesters = requesters,
            secret = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfusionTvButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim(), pin.takeIf { it.length >= 4 }) },
                modifier = stableFocusModifier(KeyCreateConfirm, requesters, {}),
            ) {
                Text("Create")
            }
            InfusionTvButton(
                onClick = onDismiss,
                modifier = stableFocusModifier(KeyCreateCancel, requesters, {}),
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun RenameProfileDialog(
    profile: Profile,
    requesters: MutableMap<String, FocusRequester>,
    onConfirm: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(profile.name) }
    TvDialog(
        title = "Rename profile",
        onDismissRequest = onDismiss,
    ) {
        TvTextField(
            label = "Name",
            value = name,
            onChange = { name = it },
            key = KeyRenameName,
            requesters = requesters,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfusionTvButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) },
                modifier = stableFocusModifier(KeyRenameConfirm, requesters, {}),
            ) {
                Text("Rename")
            }
            InfusionTvButton(
                onClick = onDismiss,
                modifier = stableFocusModifier(KeyRenameCancel, requesters, {}),
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun DeleteProfileDialog(
    pending: PendingDeleteConfirmation,
    requesters: MutableMap<String, FocusRequester>,
    onDisposition: (ProfileLedgerDisposition) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val preserve = pending.disposition == ProfileLedgerDisposition.PRESERVE
    TvDialog(
        title = "Delete ${pending.profile.name}?",
        onDismissRequest = onDismiss,
    ) {
        Text(
            "Deleting a profile also deletes its sources. Choose what happens to its watch history:",
            style = InfusionTvScaleCalibration.Type.SettingsContent,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfusionTvButton(
                onClick = { onDisposition(ProfileLedgerDisposition.PRESERVE) },
                modifier = stableFocusModifier(KeyDeletePreserve, requesters, {}),
            ) {
                Text(if (preserve) "✓ Keep watch history" else "Keep watch history")
            }
            InfusionTvButton(
                onClick = { onDisposition(ProfileLedgerDisposition.DELETE) },
                modifier = stableFocusModifier(KeyDeleteRemove, requesters, {}),
            ) {
                Text(if (!preserve) "✓ Delete watch history" else "Delete watch history")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfusionTvButton(
                onClick = onConfirm,
                modifier = stableFocusModifier(KeyDeleteConfirm, requesters, {}),
            ) {
                Text("Delete profile")
            }
            InfusionTvButton(
                onClick = onDismiss,
                modifier = stableFocusModifier(KeyDeleteCancel, requesters, {}),
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun PinEntryDialog(
    profileName: String,
    holder: PinEntryStateHolder,
    requesters: MutableMap<String, FocusRequester>,
    onDismiss: () -> Unit,
) {
    val pinState by holder.state.collectAsStateWithLifecycle()
    TvDialog(
        title = "Enter PIN for $profileName",
        onDismissRequest = onDismiss,
    ) {
        val status = pinState.status
        when (status) {
            is PinEntryStatus.Entering -> Text(
                text = "PIN: " + "•".repeat(pinState.digits.length),
                style = InfusionTvScaleCalibration.Type.SettingsContent,
                fontFamily = FontFamily.Monospace,
            )
            PinEntryStatus.Verifying -> Text("Verifying…", style = InfusionTvScaleCalibration.Type.SettingsContent)
            is PinEntryStatus.WrongPin -> Text(
                "Wrong PIN — ${status.remainingAttempts} attempts remaining",
                color = MaterialTheme.colorScheme.error,
                style = InfusionTvScaleCalibration.Type.SettingsContent,
            )
            is PinEntryStatus.Locked -> Text(
                "Locked — try again in ${(status.remainingMs + 999) / 1000}s",
                color = MaterialTheme.colorScheme.error,
                style = InfusionTvScaleCalibration.Type.SettingsContent,
            )
            PinEntryStatus.Unlocked -> Text(
                "Unlocked — enter your PIN",
                style = InfusionTvScaleCalibration.Type.SettingsContent,
            )
            PinEntryStatus.Verified -> Text("Verified", style = InfusionTvScaleCalibration.Type.SettingsContent)
        }

        val rows: List<List<PinKey>> = PinKeypad
        rows.forEach { rowKeys ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowKeys.forEach { key ->
                    val keyTag = key.tag
                    val canType = status == PinEntryStatus.Entering || status is PinEntryStatus.WrongPin
                    InfusionTvButton(
                        onClick = {
                            when (key) {
                                is PinKey.Digit -> holder.onDigit(key.digit)
                                PinKey.Backspace -> holder.onBackspace()
                                PinKey.Submit -> holder.submit()
                            }
                        },
                        enabled = when (key) {
                            PinKey.Backspace -> canType && pinState.digits.isNotEmpty()
                            is PinKey.Digit -> canType
                            PinKey.Submit -> canType && pinState.digits.length >= MinPinDigits
                        },
                        modifier = stableFocusModifier(keyTag, requesters, {}),
                    ) {
                        Text(key.label, style = InfusionTvScaleCalibration.Type.SettingsContent)
                    }
                }
            }
        }
        InfusionTvButton(
            onClick = onDismiss,
            modifier = stableFocusModifier(KeyPinCancel, requesters, {}),
        ) {
            Text("Cancel")
        }
    }
}

@Composable
private fun TvDialog(
    title: String,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .width(560.dp)
                .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium),
            shape = MaterialTheme.shapes.medium,
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(title, style = InfusionTvScaleCalibration.Type.Headline)
                content()
            }
        }
    }
}

@Composable
private fun TvTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    key: String,
    requesters: MutableMap<String, FocusRequester>,
    secret: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {

        InfusionTvFieldLabel(label, style = InfusionTvScaleCalibration.Type.SettingsContent)
        InfusionTvTextField(
            value = value,
            onValueChange = onChange,
            secret = secret,
            textStyle = InfusionTvScaleCalibration.Type.SettingsContent,
            modifier = Modifier
                .fillMaxWidth()
                .then(stableFocusModifier(key, requesters, {})),
        )
    }
}

private const val MinPinDigits = 4

private sealed interface PinKey {
    val label: String
    val tag: String

    data class Digit(val digit: Char) : PinKey {
        override val label: String get() = digit.toString()
        override val tag: String get() = "pin:$digit"
    }

    data object Backspace : PinKey {
        override val label: String get() = "⌫"
        override val tag: String get() = "pin:backspace"
    }

    data object Submit : PinKey {
        override val label: String get() = "OK"
        override val tag: String get() = "pin:ok"
    }
}

private val PinKeypad: List<List<PinKey>> = listOf(
    listOf(PinKey.Digit('1'), PinKey.Digit('2'), PinKey.Digit('3')),
    listOf(PinKey.Digit('4'), PinKey.Digit('5'), PinKey.Digit('6')),
    listOf(PinKey.Digit('7'), PinKey.Digit('8'), PinKey.Digit('9')),
    listOf(PinKey.Backspace, PinKey.Digit('0'), PinKey.Submit),
)
