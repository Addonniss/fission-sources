package dev.infusion.tv.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.tv.material3.Text
import dev.infusion.core.domain.appearance.SplashScreenMode
import dev.infusion.tv.perf.LogcatColdStartTrace
import dev.infusion.tv.ui.theme.InfusionTvAppearance
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration

const val ColdStartSplashTag: String = "cold-start-splash"

const val ColdStartSplashWordTag: String = "cold-start-splash-word"

const val ColdStartSplashWord: String = "fission"

const val ColdStartSplashAccentLetter: String = "s"

const val ColdStartSplashWordViewportShare: Float = 0.30f

val ColdStartSplashWordWeight: FontWeight = FontWeight.Normal

val ColdStartSplashWordBase: TextStyle = InfusionTvScaleCalibration.Type.HeadlineNormal

enum class InitialHeroImageState {

    Pending,

    Ready,

    Unavailable,
}

class ColdStartSplashController(

    private val trace: (stage: String, detail: String) -> Unit = { _, _ -> },
) {

    var startupReady: Boolean by mutableStateOf(false)
        private set

    var startupTerminal: Boolean by mutableStateOf(false)
        private set

    var startupSurfaceReady: Boolean by mutableStateOf(false)
        private set

    var revealed: Boolean by mutableStateOf(false)
        private set

    var initialHeroKey: String? by mutableStateOf(null)
        private set

    var initialHeroImage: InitialHeroImageState by mutableStateOf(InitialHeroImageState.Pending)
        private set

    var mode: SplashScreenMode by mutableStateOf(SplashScreenMode.DEFAULT)
        private set

    val visible: Boolean get() = !revealed && !startupTerminal

    fun onStartupReady() {
        startupReady = true
        trace("startup-ready", "")
        revealIfReady()
    }

    fun onStartupTerminal() {
        startupTerminal = true
        trace("startup-terminal", "")
    }

    fun onStartupSurfaceReady() {
        startupSurfaceReady = true
        trace("startup-surface-ready", "")
        revealIfReady()
    }

    fun onInitialHeroPresented(key: String) {
        if (revealed || startupTerminal) return
        if (initialHeroKey == key) return
        initialHeroKey = key
        initialHeroImage = InitialHeroImageState.Pending
        trace("hero-presented", "key=${LogcatColdStartTrace.digest(key)}")
        revealIfReady()
    }

    fun onInitialHeroAbsent() {
        if (revealed || startupTerminal) return
        if (initialHeroKey != null) return
        initialHeroImage = InitialHeroImageState.Unavailable
        trace("hero-absent", "")
        revealIfReady()
    }

    fun onInitialHeroImageOutcome(key: String, loaded: Boolean) {
        if (revealed || startupTerminal) return
        if (key != initialHeroKey) return
        if (initialHeroImage != InitialHeroImageState.Pending) return
        initialHeroImage = if (loaded) InitialHeroImageState.Ready else InitialHeroImageState.Unavailable
        trace("hero-image", "loaded=$loaded key=${LogcatColdStartTrace.digest(key)}")

        revealIfReady()
    }

    fun surfaceReady(): Boolean = startupReady && startupSurfaceReady

    fun onModeObserved(effective: SplashScreenMode) {
        if (mode == effective) return
        mode = effective
        trace("mode", "mode=$effective")
        revealIfReady()
    }

    fun revealIfReady() {
        if (revealed || startupTerminal) return
        if (!revealReady(mode)) return
        reveal()
    }

    fun revealReady(mode: SplashScreenMode): Boolean =
        surfaceReady() &&
            (mode == SplashScreenMode.FAST || initialHeroImage != InitialHeroImageState.Pending)

    fun reveal() {
        if (!startupTerminal) {
            revealed = true
            trace("reveal", "mode=$mode")
        }
    }

    fun note(stage: String, detail: String = "") {
        trace(stage, detail)
    }
}

val LocalColdStartSplashController = compositionLocalOf<ColdStartSplashController?> { null }

val LocalSplashScreenMode = compositionLocalOf { SplashScreenMode.DEFAULT }

fun ColdStartSplashController.blocksStartupInput(): Boolean = visible

internal fun coldStartSplashWord(
    orange: Color = InfusionTvAppearance.SelectionOrange,
    light: Color = InfusionTvAppearance.TextPrimary,
    weight: FontWeight = ColdStartSplashWordWeight,
): AnnotatedString {

    val accentIndex = ColdStartSplashWord.lastIndexOf(ColdStartSplashAccentLetter)
    return buildAnnotatedString {
        withStyle(SpanStyle(color = light, fontWeight = weight)) {
            append(ColdStartSplashWord.take(accentIndex))
        }
        withStyle(SpanStyle(color = orange, fontWeight = weight)) {
            append(ColdStartSplashWord.substring(accentIndex, accentIndex + 1))
        }
        withStyle(SpanStyle(color = light, fontWeight = weight)) {
            append(ColdStartSplashWord.drop(accentIndex + 1))
        }
    }
}

@Composable
fun ColdStartSplash(
    controller: ColdStartSplashController,
    modifier: Modifier = Modifier,
    mode: SplashScreenMode = LocalSplashScreenMode.current,
) {

    if (!controller.visible) return

    SideEffect { controller.onModeObserved(mode) }

    val ready = controller.revealReady(mode)
    LaunchedEffect(ready) {
        if (ready) controller.reveal()
    }
    if (ready) return

    BackHandler(enabled = true) { }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(InfusionTvAppearance.OledBlack)
            .testTag(ColdStartSplashTag),
        contentAlignment = Alignment.Center,
    ) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val word = coldStartSplashWord()

        val base = ColdStartSplashWordBase
        val targetWidthPx = with(density) { maxWidth.toPx() } * ColdStartSplashWordViewportShare
        val measuredWidthPx = measurer
            .measure(text = word, style = base, softWrap = false, maxLines = 1)
            .size.width

        val ratio = if (measuredWidthPx > 0) targetWidthPx / measuredWidthPx else 1f
        val wordStyle = coldStartSplashWordStyle(base, ratio)
        Text(
            text = word,
            style = wordStyle,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.testTag(ColdStartSplashWordTag),
        )
    }
}

internal fun coldStartSplashWordStyle(base: TextStyle, ratio: Float): TextStyle =
    base.copy(
        fontWeight = ColdStartSplashWordWeight,
        fontSize = base.fontSize * ratio,
        lineHeight = base.lineHeight * ratio,
    )
