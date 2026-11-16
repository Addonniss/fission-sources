package dev.infusion.tv.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import dev.infusion.core.update.UpdateCheckResult
import dev.infusion.core.update.UpdateState
import dev.infusion.core.update.toCheckNotice
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.LocalInfusionTheme
import dev.infusion.tv.ui.theme.infusionAccentContentColor
import dev.infusion.tv.ui.theme.infusionTvButtonColors
import dev.infusion.tv.ui.theme.themeInfoPanelContour
import dev.infusion.tv.ui.theme.themeInfoPanelFill
import dev.infusion.tv.updater.UpdateController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

const val SettingsAboutIndependenceNoticeTag = "settings:about:independence"

const val SettingsAboutPane = "settings:about:pane"

const val SettingsAboutLicencePrefix = "settings:about:licence"

internal fun aboutLicenceTag(key: String): String = "$SettingsAboutLicencePrefix:$key"

internal fun aboutLicenceRowTag(key: String): String = "settings:about:licence-row:$key"

internal fun aboutLicenceReadTag(key: String): String = "settings:about:licence-read:$key"

const val SettingsAboutSoftwareRow = "settings:about:software-row"

const val SettingsAboutSoftwareRead = "settings:about:software-read"

const val SettingsAboutServicesRow = "settings:about:services-row"

const val SettingsAboutServicesRead = "settings:about:services-read"

const val SettingsAboutReleaseRowPrefix = "settings:about:release-row"

const val SettingsAboutReleaseReadPrefix = "settings:about:release-read"

internal fun aboutReleaseNoteRowTag(versionName: String): String =
    "$SettingsAboutReleaseRowPrefix:$versionName"

internal fun aboutReleaseNoteReadTag(versionName: String): String =
    "$SettingsAboutReleaseReadPrefix:$versionName"

internal fun aboutReleaseNoteDisclosure(versionName: String): String = "release:$versionName"
internal const val AboutSoftwareDisclosure = "software"
internal const val AboutServicesDisclosure = "metadata-services"

const val SettingsAboutCheckUpdates = "settings:about:updates"

const val SettingsAboutUpdateStatusTag = "settings:about:updates:status"

const val SettingsAboutInstall = "settings:about:updates:install"

internal data class AboutCreditLine(val name: String, val detail: String)

internal data class AboutCreditGroup(val title: String, val lines: List<AboutCreditLine>)

internal data class AboutLicenceNotice(
    val key: String,
    val title: String,
    val covers: String,
    val body: String,
)

internal val AboutSoftwareCredits: List<AboutCreditGroup> = listOf(
    AboutCreditGroup(
        title = "Software",
        lines = listOf(
            AboutCreditLine(
                name = "AndroidX Media3",
                detail = "playback of your own media (Apache License 2.0)",
            ),
            AboutCreditLine(
                name = "AndroidX and Jetpack Compose",
                detail = "the Android foundations and the interface toolkit (Apache License 2.0)",
            ),
            AboutCreditLine(
                name = "Kotlin and Kotlin Coroutines",
                detail = "the language this app is written in and its concurrency library (Apache License 2.0)",
            ),
            AboutCreditLine(
                name = "OkHttp",
                detail = "the HTTP client that talks to your servers and services (Apache License 2.0)",
            ),
            AboutCreditLine(
                name = "ZXing",
                detail = "the QR code the pairing screen shows (Apache License 2.0)",
            ),
            AboutCreditLine(
                name = "org.json",
                detail = "the local encoding of small records (The JSON License)",
            ),
        ),
    ),
)

internal val AboutServiceCredits: List<AboutCreditGroup> = listOf(
    AboutCreditGroup(
        title = "Metadata and services",
        lines = listOf(
            AboutCreditLine(
                name = "TMDB",
                detail = "movie and TV metadata and artwork. This product uses TMDB and the TMDB " +
                    "APIs but is not endorsed, certified, or otherwise approved by TMDB.",
            ),
            AboutCreditLine(
                name = "OMDb",
                detail = "optional community ratings, used under CC BY-NC 4.0. OMDb is not " +
                    "affiliated with IMDb.",
            ),
            AboutCreditLine(
                name = "Jellyfin",
                detail = "browsing and playback from your own Jellyfin server. fission is not " +
                    "affiliated with, or endorsed by, the Jellyfin project.",
            ),
            AboutCreditLine(
                name = "Simkl, Trakt and MDBList",
                detail = "optional watch-tracking accounts you connect yourself and can disconnect " +
                    "at any time. fission is not affiliated with, or endorsed by, any of them.",
            ),
            AboutCreditLine(
                name = "TheIntroDB and IntroDB.app",
                detail = "intro and segment markers for the episodes you play.",
            ),
            AboutCreditLine(
                name = "Add-ons",
                detail = "the add-ons you install use the Stremio-compatible add-on protocol. Their " +
                    "catalogs, metadata and streams belong to their authors; fission is not " +
                    "affiliated with Stremio or with any add-on provider.",
            ),
        ),
    ),
)

internal const val AboutIndependenceNotice: String =
    "fission is an independent personal project. Third-party names and marks belong to their " +
        "owners, and no endorsement by any project or service credited on this page is claimed or " +
        "implied."

internal const val AboutProductSummary: String =
    "fission plays the media you own: files on your network, your own Jellyfin server, and the " +
        "catalogs and streams the add-ons you install provide."

internal const val AboutCheckUpdatesHelp: String =
    "Checks the published releases for a newer version of fission."

internal const val AboutInstallHelp: String =
    "Downloads the release, checks it, then lets Android ask you to install it."

internal fun aboutLicenceRowHelp(notice: AboutLicenceNotice): String =
    "Covers ${notice.covers}. Press to read the full licence."

internal fun aboutReleaseNoteRowHelp(note: AboutReleaseNote): String = note.title

internal const val AboutSoftwareHelp: String =
    "The software fission is built on. Press to read the credits."

internal const val AboutServicesHelp: String =
    "The services and add-ons fission talks to. Press to read the credits."

const val SettingsAboutVersionTag = "settings:about:version"

internal fun aboutVersionTitle(installedVersionName: String?): String =
    installedVersionName?.trim()?.takeIf { it.isNotEmpty() }?.let { "fission v$it" } ?: "fission"

internal fun aboutUpdateStatus(
    state: UpdateState,
    check: UpdateCheckResult?,
    checking: Boolean,
    installedVersionName: String?,
): String? = when (state) {
    is UpdateState.Downloading -> "Downloading the update…"
    is UpdateState.Verifying -> "Verifying the downloaded update…"
    is UpdateState.NeedsPermission -> "Allow fission to install apps, then try again."
    is UpdateState.ReadyToInstall -> "Confirm the installation on the system screen."
    is UpdateState.Failed -> state.kind.message
    else -> when {
        checking -> "Checking for updates…"
        check is UpdateCheckResult.Found -> {
            val installed = installedVersionName?.trim()?.takeIf { it.isNotEmpty() }
            val available = "fission v${check.candidate.versionName} is available."
            if (installed == null) available else "$available You have v$installed."
        }
        check is UpdateCheckResult.NoUpdate -> check.reason.toCheckNotice().message
        else -> null
    }
}

private class AboutLayoutMetrics {
    var pane: LayoutCoordinates? = null
    val blockContentTopPx: MutableMap<String, Float> = mutableMapOf()
    val blockHeightPx: MutableMap<String, Float> = mutableMapOf()
}

internal val AboutLicenceNotices: List<AboutLicenceNotice> = listOf(
    AboutLicenceNotice(
        key = "apache-2.0",
        title = "Apache License 2.0",
        covers = "AndroidX, Jetpack Compose, AndroidX Media3, Kotlin, Kotlin Coroutines, OkHttp " +
            "and ZXing",
        body = AboutApacheLicenceText,
    ),
    AboutLicenceNotice(
        key = "json",
        title = "The JSON License",
        covers = "org.json",
        body = AboutJsonLicenceText,
    ),
)

private const val AboutScrollLines = 3

@Composable
internal fun AboutSectionContent(
    requesters: MutableMap<String, FocusRequester>,
    entryToken: Int,
    rememberedKey: String?,
    onFocusRequested: (String) -> Unit,
    installedVersionName: String? = null,
    updateController: UpdateController? = null,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val lineStepPx = with(LocalDensity.current) {
        InfusionTvScaleCalibration.Type.SettingsContent.lineHeight.toPx()
    } * AboutScrollLines

    val idleState = remember { MutableStateFlow<UpdateState>(UpdateState.Idle) }
    val updateState by (updateController?.state ?: idleState).collectAsStateWithLifecycle()

    var expandedDisclosures by remember(entryToken) { mutableStateOf(emptySet<String>()) }
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<UpdateCheckResult?>(null) }

    var focusedKey by remember { mutableStateOf(SettingsAboutCheckUpdates) }

    val layout = remember { AboutLayoutMetrics() }

    val offeredRelease = checkResult as? UpdateCheckResult.Found
    val controlKeys = buildList {
        if (updateController != null) add(SettingsAboutCheckUpdates)
        if (offeredRelease != null && updateController != null) add(SettingsAboutInstall)
        AboutReleaseNotes.forEach { note ->
            add(aboutReleaseNoteRowTag(note.versionName))
            if (aboutReleaseNoteDisclosure(note.versionName) in expandedDisclosures) {
                add(aboutReleaseNoteReadTag(note.versionName))
            }
        }
        add(SettingsAboutSoftwareRow)
        if (AboutSoftwareDisclosure in expandedDisclosures) add(SettingsAboutSoftwareRead)
        add(SettingsAboutServicesRow)
        if (AboutServicesDisclosure in expandedDisclosures) add(SettingsAboutServicesRead)
        AboutLicenceNotices.forEach { notice ->
            add(aboutLicenceRowTag(notice.key))
            if (notice.key in expandedDisclosures) add(aboutLicenceReadTag(notice.key))
        }
    }
    val firstControl = controlKeys.first()

    RestoreSectionFocus(entryToken, rememberedKey = null, firstControl, requesters)

    val onFocused: (String) -> Unit = { key ->
        focusedKey = key
        onFocusRequested(key)
    }

    fun alignReadBlockTop(key: String) {
        val contentTop = layout.blockContentTopPx[key] ?: return
        val target = contentTop.toInt().coerceIn(0, scroll.maxValue)
        if (target != scroll.value) scope.launch { scroll.scrollTo(target) }
    }

    fun readBlockScrolls(key: String, delta: Int): Boolean {
        val contentTop = layout.blockContentTopPx[key] ?: return false
        val blockHeight = layout.blockHeightPx[key] ?: return false
        val viewport = scroll.viewportSize
        if (viewport <= 0) return false
        val blockTop = contentTop - scroll.value
        return if (delta > 0) {
            blockTop + blockHeight > viewport + 1f
        } else {
            blockTop < -1f
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(24.dp)
            .testTag(SettingsAboutPane)
            .onGloballyPositioned { layout.pane = it }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val delta = when (event.key) {
                    Key.DirectionUp -> -1
                    Key.DirectionDown -> 1
                    else -> return@onPreviewKeyEvent false
                }

                if (focusedKey in controlKeys && readBlockScrolls(focusedKey, delta)) {
                    scope.launch { scroll.scrollBy(delta * lineStepPx) }
                    return@onPreviewKeyEvent true
                }

                val index = controlKeys.indexOf(focusedKey).coerceAtLeast(0)
                val target = controlKeys[(index + delta).coerceIn(0, controlKeys.lastIndex)]
                if (target != focusedKey) requesters[target]?.requestFocus()
                true
            },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("About", style = InfusionTvScaleCalibration.Type.Headline)
        AboutPanel {

            Text(
                aboutVersionTitle(installedVersionName),
                style = InfusionTvScaleCalibration.Type.Headline,
                modifier = Modifier.testTag(SettingsAboutVersionTag),
            )
            Text(
                AboutProductSummary,
                style = InfusionTvScaleCalibration.Type.SettingsContent,
            )
        }

        AboutActionRow(
            tag = SettingsAboutCheckUpdates,
            label = "Check for updates",
            help = AboutCheckUpdatesHelp,
            requesters = requesters,
            enabled = updateController != null,
            onFocused = onFocused,
            onActivate = {
                val controller = updateController
                if (controller != null && !checking) {
                    checking = true
                    scope.launch {
                        try {

                            checkResult = controller.checkNow()
                        } finally {

                            checking = false
                        }
                    }
                }
            },
        )
        aboutUpdateStatus(updateState, checkResult, checking, installedVersionName)?.let { status ->

            Text(
                status,
                style = InfusionTvScaleCalibration.Type.SettingsContent,
                modifier = Modifier.testTag(SettingsAboutUpdateStatusTag),
            )
        }
        if (offeredRelease != null && updateController != null) {
            AboutActionRow(
                tag = SettingsAboutInstall,

                label = "Download & install fission v${offeredRelease.candidate.versionName}",
                help = AboutInstallHelp,
                requesters = requesters,
                onFocused = onFocused,
                onActivate = { updateController.onUpdateConfirmed() },
            )
        }

        Text("Release notes", style = InfusionTvScaleCalibration.Type.Headline)
        if (AboutReleaseNotes.isEmpty()) {
            Text(
                AboutReleaseNotesUnavailable,
                style = InfusionTvScaleCalibration.Type.SettingsContent,
            )
        } else {
            AboutReleaseNotes.forEach { note ->
                AboutDisclosure(
                    disclosureKey = aboutReleaseNoteDisclosure(note.versionName),
                    rowTag = aboutReleaseNoteRowTag(note.versionName),
                    readTag = aboutReleaseNoteReadTag(note.versionName),

                    title = "v${note.versionName}",
                    help = aboutReleaseNoteRowHelp(note),
                    expandedDisclosures = expandedDisclosures,
                    onToggle = { key -> expandedDisclosures = toggleDisclosure(expandedDisclosures, key) },
                    requesters = requesters,
                    onFocused = onFocused,
                    layout = layout,
                    onFocusedAlign = { alignReadBlockTop(aboutReleaseNoteReadTag(note.versionName)) },
                ) {
                    AboutReleaseNotePanel(note)
                }
            }
        }

        AboutDisclosure(
            disclosureKey = AboutSoftwareDisclosure,
            rowTag = SettingsAboutSoftwareRow,
            readTag = SettingsAboutSoftwareRead,
            title = AboutSoftwareCredits.firstOrNull()?.title ?: "Software",
            help = AboutSoftwareHelp,
            expandedDisclosures = expandedDisclosures,
            onToggle = { key -> expandedDisclosures = toggleDisclosure(expandedDisclosures, key) },
            requesters = requesters,
            onFocused = onFocused,
            layout = layout,
            onFocusedAlign = { alignReadBlockTop(SettingsAboutSoftwareRead) },
        ) {
            AboutSoftwareCredits.forEach { group -> AboutCreditGroupPanel(group) }
        }

        AboutDisclosure(
            disclosureKey = AboutServicesDisclosure,
            rowTag = SettingsAboutServicesRow,
            readTag = SettingsAboutServicesRead,

            title = AboutServiceCredits.firstOrNull()?.title ?: "Metadata and services",
            help = AboutServicesHelp,
            expandedDisclosures = expandedDisclosures,
            onToggle = { key -> expandedDisclosures = toggleDisclosure(expandedDisclosures, key) },
            requesters = requesters,
            onFocused = onFocused,
            layout = layout,
            onFocusedAlign = { alignReadBlockTop(SettingsAboutServicesRead) },
        ) {

            AboutServiceCredits.forEach { group -> AboutCreditGroupPanel(group) }
        }

        Text("Licences", style = InfusionTvScaleCalibration.Type.Headline)
        AboutLicenceNotices.forEach { notice ->
            AboutDisclosure(
                disclosureKey = notice.key,
                rowTag = aboutLicenceRowTag(notice.key),
                readTag = aboutLicenceReadTag(notice.key),
                title = notice.title,
                help = aboutLicenceRowHelp(notice),
                expandedDisclosures = expandedDisclosures,
                onToggle = { key -> expandedDisclosures = toggleDisclosure(expandedDisclosures, key) },
                requesters = requesters,
                onFocused = onFocused,
                layout = layout,
                onFocusedAlign = { alignReadBlockTop(aboutLicenceReadTag(notice.key)) },
            ) {
                AboutPanel {
                    Text(
                        "Covers ${notice.covers}.",
                        style = InfusionTvScaleCalibration.Type.SettingsContent,
                    )

                    Text(
                        notice.body,
                        style = InfusionTvScaleCalibration.Type.SettingsContent,
                        modifier = Modifier.testTag(aboutLicenceTag(notice.key)),
                    )
                }
            }
        }
        Text(
            AboutIndependenceNotice,
            style = InfusionTvScaleCalibration.Type.SettingsContent,
            modifier = Modifier.testTag(SettingsAboutIndependenceNoticeTag),
        )
    }
}

@Composable
private fun AboutActionRow(
    tag: String,
    label: String,
    help: String,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onActivate: () -> Unit,
    enabled: Boolean = true,
    value: String = "",
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    InfusionTvButton(
        onClick = onActivate,
        enabled = enabled,
        interactionSource = interactionSource,
        colors = infusionTvButtonColors(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = GeneralRowHeight)
            .testTag(tag)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                if (event.key == Key.DirectionRight && enabled) {
                    onActivate()
                    true
                } else {
                    false
                }
            }
            .then(
                if (enabled) {
                    stableFocusModifier(
                        key = tag,
                        requesters = requesters,
                        onFocusedKeyChanged = onFocused,
                    )
                } else {
                    Modifier
                },
            )
            .then(if (enabled) settingsRowBringIntoView(focused) else Modifier),
    ) {
        SettingsRowBody(
            presentation = SettingsRowPresentation.EXPANDED,
            label = label,
            help = help,
            value = value,
            focused = focused,
            valueColor = infusionAccentContentColor(focused),
        )
    }
}

@Composable
private fun AboutDisclosure(
    disclosureKey: String,
    rowTag: String,
    readTag: String,
    title: String,
    help: String,
    expandedDisclosures: Set<String>,
    onToggle: (String) -> Unit,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    layout: AboutLayoutMetrics,
    onFocusedAlign: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val expanded = disclosureKey in expandedDisclosures
    AboutActionRow(
        tag = rowTag,
        label = title,
        help = help,
        requesters = requesters,
        onFocused = onFocused,
        value = if (expanded) "Hide" else "Read",
        onActivate = { onToggle(disclosureKey) },
    )
    if (expanded) {
        AboutReadBlock(
            tag = readTag,
            requesters = requesters,
            onFocused = onFocused,
            layout = layout,
            onFocusedAlign = onFocusedAlign,
            content = content,
        )
    }
}

private fun toggleDisclosure(open: Set<String>, key: String): Set<String> =
    if (key in open) open - key else open + key

@Composable
private fun AboutReadBlock(
    tag: String,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    layout: AboutLayoutMetrics,
    onFocusedAlign: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->

                val pane = layout.pane
                val contentTop = if (pane != null && pane.isAttached) {
                    runCatching { pane.localPositionOf(coordinates, Offset.Zero).y }.getOrNull()
                } else {
                    null
                }
                if (contentTop != null) {
                    layout.blockContentTopPx[tag] = contentTop
                    layout.blockHeightPx[tag] = coordinates.size.height.toFloat()
                }
            }
            .then(
                stableFocusModifier(
                    key = tag,
                    requesters = requesters,
                    onFocusedKeyChanged = onFocused,
                ),
            )

            .onFocusChanged { state -> if (state.isFocused) onFocusedAlign() }
            .focusable(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun AboutReleaseNotePanel(note: AboutReleaseNote) {
    AboutPanel {

        Text(
            note.title,
            style = InfusionTvScaleCalibration.Type.Headline,
            modifier = Modifier.testTag(aboutReleaseNoteTitleTag(note.versionName)),
        )
        if (note.whatsNew.isNotEmpty()) {

            Text("What's new", style = InfusionTvScaleCalibration.Type.Emphasis)
            note.whatsNew.forEach { line ->
                Text("• $line", style = InfusionTvScaleCalibration.Type.SettingsContent)
            }
        }
        if (note.fixes.isNotEmpty()) {
            Text("Fixes", style = InfusionTvScaleCalibration.Type.Emphasis)
            note.fixes.forEach { line ->
                Text("• $line", style = InfusionTvScaleCalibration.Type.SettingsContent)
            }
        }
    }
}

internal fun aboutReleaseNoteTitleTag(versionName: String): String =
    "settings:about:release:$versionName"

@Composable
private fun AboutCreditGroupPanel(group: AboutCreditGroup) {
    AboutPanel {
        group.lines.forEach { line ->
            Text(
                "${line.name} — ${line.detail}",
                style = InfusionTvScaleCalibration.Type.SettingsContent,
            )
        }
    }
}

@Composable
private fun AboutPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = InfusionTvContour.Shape,
        colors = SurfaceDefaults.colors(
            containerColor = themeInfoPanelFill(LocalInfusionTheme.current),
        ),
        modifier = Modifier.themeInfoPanelContour(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}
