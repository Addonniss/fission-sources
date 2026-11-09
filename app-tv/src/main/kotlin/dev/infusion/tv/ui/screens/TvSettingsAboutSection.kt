package dev.infusion.tv.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.theme.InfusionTvAppearance
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import kotlinx.coroutines.launch

const val SettingsAboutRead = "settings:about:read"

const val SettingsAboutIndependenceNoticeTag = "settings:about:independence"

const val SettingsAboutLicencePrefix = "settings:about:licence"

internal fun aboutLicenceTag(key: String): String = "$SettingsAboutLicencePrefix:$key"

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

const val SettingsAboutVersionTag = "settings:about:version"

internal fun aboutVersionTitle(installedVersionName: String?): String =
    installedVersionName?.trim()?.takeIf { it.isNotEmpty() }?.let { "fission v$it" } ?: "fission"

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
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val lineStepPx = with(LocalDensity.current) {
        InfusionTvScaleCalibration.Type.SettingsContent.lineHeight.toPx()
    } * AboutScrollLines

    RestoreSectionFocus(entryToken, rememberedKey, SettingsAboutRead, requesters)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(24.dp)
            .then(
                stableFocusModifier(
                    key = SettingsAboutRead,
                    requesters = requesters,
                    onFocusedKeyChanged = onFocusRequested,
                ),
            )
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                when (event.key) {
                    Key.DirectionUp -> {
                        scope.launch { scroll.scrollBy(-lineStepPx) }
                        true
                    }
                    Key.DirectionDown -> {
                        scope.launch { scroll.scrollBy(lineStepPx) }
                        true
                    }
                    else -> false
                }
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
        AboutCreditsPanel(AboutSoftwareCredits)
        AboutCreditsPanel(AboutServiceCredits)
        Text("Licences", style = InfusionTvScaleCalibration.Type.Headline)
        AboutLicenceNotices.forEach { notice ->
            AboutPanel {
                Text(notice.title, style = InfusionTvScaleCalibration.Type.Headline)
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
        Text(
            AboutIndependenceNotice,
            style = InfusionTvScaleCalibration.Type.SettingsContent,
            modifier = Modifier.testTag(SettingsAboutIndependenceNoticeTag),
        )
    }
}

@Composable
private fun AboutCreditsPanel(groups: List<AboutCreditGroup>) {
    groups.forEach { group ->
        AboutPanel {
            Text(group.title, style = InfusionTvScaleCalibration.Type.Headline)
            group.lines.forEach { line ->
                Text(
                    "${line.name} — ${line.detail}",
                    style = InfusionTvScaleCalibration.Type.SettingsContent,
                )
            }
        }
    }
}

@Composable
private fun AboutPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = InfusionTvContour.Shape,
        colors = SurfaceDefaults.colors(containerColor = InfusionTvAppearance.Panel),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}
