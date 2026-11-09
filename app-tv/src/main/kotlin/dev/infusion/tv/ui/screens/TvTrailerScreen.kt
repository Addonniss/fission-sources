package dev.infusion.tv.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import dev.infusion.tv.navigation.TrailerEmbedProvider
import dev.infusion.tv.navigation.TrailerTarget
import dev.infusion.tv.navigation.trailerEmbedHosts
import dev.infusion.tv.navigation.trailerEmbedRequestHeaders
import dev.infusion.tv.navigation.trailerEmbedUrl
import dev.infusion.tv.ui.focus.RequestFocusOnKey
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.theme.InfusionTvAppearance
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration

internal const val TrailerScreenRootTag = "trailer-root"

internal const val TrailerVideoAreaTag = "trailer-video-area"

internal const val TrailerBackKey = "trailer-back"

internal const val TrailerLoadingCopy = "Loading trailer…"
internal const val TrailerUnsupportedCopy = "This trailer can't play inside fission."
internal const val TrailerFailureCopy = "Couldn't play the trailer."
internal const val TrailerUnavailableCopy = "The trailer is unavailable right now."

sealed interface TrailerPlaybackState {
    data object Loading : TrailerPlaybackState
    data object Playing : TrailerPlaybackState

    data class Failed(val reason: String) : TrailerPlaybackState
    data object Unsupported : TrailerPlaybackState
}

internal class TrailerPlaybackEndLatch {

    private var closed = false

    fun claimEnd(): Boolean {
        if (closed) return false
        closed = true
        return true
    }

    fun markClosed() {
        closed = true
    }

    fun isClosed(): Boolean = closed
}

@Composable
fun TvTrailerScreen(
    target: TrailerTarget,
    onBack: () -> Unit,
) {
    val embedUrl = trailerEmbedUrl(target)

    var state by remember(target) {
        mutableStateOf<TrailerPlaybackState>(
            if (embedUrl == null) TrailerPlaybackState.Unsupported else TrailerPlaybackState.Loading,
        )
    }

    val endLatch = remember(target) { TrailerPlaybackEndLatch() }

    val dismiss: () -> Unit = {
        endLatch.markClosed()
        onBack()
    }

    val onPlaybackEnded: () -> Unit = {
        if (endLatch.claimEnd()) {
            android.util.Log.i(TrailerLogTag, "trailer playback ended; closing the destination")
            onBack()
        }
    }

    val embedLive = embedUrl != null &&
        (state == TrailerPlaybackState.Loading || state == TrailerPlaybackState.Playing)
    TvTrailerSurface(
        state = state,
        onBack = dismiss,
        embed = if (embedLive) {
            {
                TrailerEmbedWebView(
                    embedUrl = embedUrl,
                    provider = (target as? TrailerTarget.Embed)?.provider,
                    onStateChange = { state = it },
                    onPlaybackEnded = onPlaybackEnded,
                    onDispose = { endLatch.markClosed() },
                )
            }
        } else {
            null
        },
    )
}

@Composable
internal fun TvTrailerSurface(
    state: TrailerPlaybackState,
    onBack: () -> Unit,
    embed: (@Composable () -> Unit)? = null,
) {
    val requesters = rememberFocusRequesters()

    val embedShowing = embed != null
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(embedShowing) {
        if (embedShowing) runCatching { rootFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(InfusionTvAppearance.OledBlack)
            .testTag(TrailerScreenRootTag)
            .then(
                if (embedShowing) {
                    Modifier.focusRequester(rootFocus).focusTarget()
                } else {
                    Modifier
                },
            )

            .onPreviewKeyEvent { event ->
                if (event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyDown) onBack()
                    true
                } else {
                    false
                }
            },
    ) {

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .testTag(TrailerVideoAreaTag),
        ) {
            embed?.invoke()
            when (state) {
                TrailerPlaybackState.Loading -> TrailerStatusLine(
                    text = TrailerLoadingCopy,
                    modifier = Modifier.align(Alignment.Center),
                )
                TrailerPlaybackState.Playing -> Unit
                is TrailerPlaybackState.Failed -> TrailerBoundedMessage(
                    text = state.reason,
                    requesters = requesters,
                    onBack = onBack,
                )
                TrailerPlaybackState.Unsupported -> TrailerBoundedMessage(
                    text = TrailerUnsupportedCopy,
                    requesters = requesters,
                    onBack = onBack,
                )
            }
        }
    }
}

@Composable
private fun TrailerStatusLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = InfusionTvScaleCalibration.Type.Body,
        modifier = modifier.padding(16.dp),
    )
}

@Composable
private fun BoxScope.TrailerBoundedMessage(
    text: String,
    requesters: MutableMap<String, FocusRequester>,
    onBack: () -> Unit,
) {

    RequestFocusOnKey(key = TrailerBackKey, requesters = requesters)
    Column(
        modifier = Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TrailerStatusLine(text)
        InfusionTvButton(
            onClick = onBack,
            modifier = stableFocusModifier(
                key = TrailerBackKey,
                requesters = requesters,
                onFocusedKeyChanged = {},
            ),
        ) { Text("Back") }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TrailerEmbedWebView(
    embedUrl: String,
    provider: TrailerEmbedProvider?,
    onStateChange: (TrailerPlaybackState) -> Unit,
    onPlaybackEnded: () -> Unit,
    onDispose: () -> Unit,
) {
    val context = LocalContext.current

    val stateCallback = remember { StateCallback() }
    stateCallback.onStateChange = onStateChange

    val endBridge = remember { TrailerEndBridge() }
    endBridge.onEnded = onPlaybackEnded
    val onDisposeHolder = remember { StateCallback() }
    onDisposeHolder.onDispose = onDispose
    val webView = remember(embedUrl) {
        WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false

            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            isFocusable = false
            isFocusableInTouchMode = false

            addJavascriptInterface(endBridge, TrailerEndBridgeName)
            webViewClient = TrailerEmbedWebViewClient(
                hosts = provider?.let(::trailerEmbedHosts).orEmpty(),
                onStateChange = { stateCallback.onStateChange?.invoke(it) },
            )

            loadUrl(embedUrl, provider?.let(::trailerEmbedRequestHeaders).orEmpty())
        }
    }
    DisposableEffect(webView) {
        onDispose {

            onDisposeHolder.onDispose?.invoke()
            webView.stopLoading()
            webView.removeJavascriptInterface(TrailerEndBridgeName)
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }
    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
}

private class StateCallback {
    var onStateChange: ((TrailerPlaybackState) -> Unit)? = null
    var onDispose: (() -> Unit)? = null
}

private const val TrailerLogTag = "TvTrailer"

private const val TrailerEndBridgeName = "InfusionTrailer"

private class TrailerEndBridge {
    var onEnded: (() -> Unit)? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @android.webkit.JavascriptInterface
    fun playbackEnded() {
        val callback = onEnded ?: return
        mainHandler.post { callback() }
    }
}

private const val TrailerEndHook = """
(function () {
  try {
    if (window.__infusionTrailerEndHooked) { return 'already'; }
    window.__infusionTrailerEndHooked = true;
    document.addEventListener('ended', function (event) {
      var target = event && event.target;
      if (!target || !target.tagName || String(target.tagName).toLowerCase() !== 'video') { return; }
      try { $TrailerEndBridgeName.playbackEnded(); } catch (e) { }
    }, true);
    return 'hooked';
  } catch (e) {
    return 'error';
  }
})()
"""

private class TrailerEmbedWebViewClient(
    private val hosts: Set<String>,
    private val onStateChange: (TrailerPlaybackState) -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (!url.scheme.equals("https", ignoreCase = true)) return true
        val host = url.host?.lowercase()
        return host == null || hosts.none { host == it || host.endsWith(".$it") }
    }

    override fun onPageFinished(view: WebView, url: String) {
        onStateChange(TrailerPlaybackState.Playing)

        view.evaluateJavascript(TrailerEndHook, null)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {

        if (request.isForMainFrame) onStateChange(TrailerPlaybackState.Failed(TrailerFailureCopy))
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: android.webkit.WebResourceResponse,
    ) {
        if (request.isForMainFrame) {
            onStateChange(TrailerPlaybackState.Failed(TrailerUnavailableCopy))
        }
    }
}
