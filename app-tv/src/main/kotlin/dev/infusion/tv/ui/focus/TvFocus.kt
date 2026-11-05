package dev.infusion.tv.ui.focus

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.flow.first

val LocalTopLevelContentUpRequester =
    androidx.compose.runtime.compositionLocalOf<FocusRequester?> { null }

@Composable
fun rememberFocusRequesters(): FocusKeyRegistry = remember { FocusKeyRegistry() }

@Composable
fun stableFocusModifier(
    key: String,
    requesters: MutableMap<String, FocusRequester>,
    onFocusedKeyChanged: (String) -> Unit,
): Modifier = stableFocusModifier(
    key = key,
    requesters = requesters,
    topLevelContentBoundary = false,
    onFocusedKeyChanged = onFocusedKeyChanged,
)

@Composable
fun stableFocusModifier(
    key: String,
    requesters: MutableMap<String, FocusRequester>,
    onFocusedKeyChanged: (String) -> Unit,
    topLevelContentBoundary: Boolean,

    requester: FocusRequester? = null,
): Modifier {
    val rememberedRequester = remember(key) { FocusRequester() }
    val nodeRequester = requester ?: rememberedRequester
    val zone = LocalTvZoneEntry.current
    val registry = requesters as? FocusKeyRegistry

    val adopting = if (registry == null) {
        null
    } else {
        zone?.takeIf { it.adoptsPageRegistrations }?.registry?.takeIf { it !== registry }
    }
    if (registry != null) {

        registry.registerKey(key, nodeRequester)
        DisposableEffect(key, nodeRequester, registry) {
            onDispose { registry.unregisterKey(key, nodeRequester) }
        }
    } else {
        requesters[key] = nodeRequester
    }
    if (adopting != null) {
        adopting.registerKey(key, nodeRequester)
        DisposableEffect(key, nodeRequester, adopting) {
            onDispose { adopting.unregisterKey(key, nodeRequester) }
        }
    }

    val armed = registry?.pendingKey() ?: adopting?.pendingKey() ?: zone?.armedKey
    if (armed == key) {
        LaunchedEffect(key, armed) {
            registry?.claimArmed(key)
            adopting?.claimArmed(key)
            if (registry == null && adopting == null) zone?.onNodeAttached(key)
        }
    }
    val topLevelTabRequester = LocalTopLevelContentUpRequester.current
    return Modifier
        .testTag(key)
        .focusProperties {
            if (topLevelContentBoundary && topLevelTabRequester != null) {
                up = topLevelTabRequester
            }
        }
        .focusRequester(nodeRequester)
        .onFocusChanged { state ->
            if (state.isFocused) {

                if (registry != null) {
                    registry.onFocusReport(key)
                    adopting?.onFocusReport(key)
                } else {
                    zone?.onFocusReport(key)
                }
                onFocusedKeyChanged(key)
            } else if (registry != null) {

                registry.onFocusLost(key)
                adopting?.onFocusLost(key)
            }
        }
}

@Composable
fun RequestFocusOnKey(
    key: String?,
    requesters: MutableMap<String, FocusRequester>,

    before: suspend () -> Unit = {},

    restoration: Boolean = false,
) {
    val current = key
    val zone = LocalTvZoneEntry.current

    val registryNow = requesters as? FocusKeyRegistry
    val landed = remember(current) { mutableStateOf(false) }
    if (restoration && registryNow != null) {
        LaunchedEffect(current, registryNow) {
            if (current == null) return@LaunchedEffect
            snapshotFlow { registryNow.focusedKey }
                .first { it == current }
            landed.value = true
        }
    }
    LaunchedEffect(current, restoration, landed.value) {
        if (current == null) return@LaunchedEffect
        if (restoration && landed.value) return@LaunchedEffect
        before()
        val registry = requesters as? FocusKeyRegistry
        if (registry != null) {

            if (registry.request(current)) {
                registry.disarm()
            } else {

                if (restoration) registry.restore(current) else registry.arm(current)
            }
        } else if (requesters[current]?.requestFocus() != true) {
            zone?.arm(current)
        }
    }
}

@Composable
fun Modifier.artworkFocusAnchor(
    key: String,
    requesters: MutableMap<String, FocusRequester>,
): Modifier = this
    .then(
        stableFocusModifier(
            key = key,
            requesters = requesters,
            onFocusedKeyChanged = {},
        ),
    )
    .focusable()

@Composable
fun RequestInitialFocusOnce(
    key: String?,
    requesters: MutableMap<String, FocusRequester>,
) {
    val requested = remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(key) {
        val target = key
        if (target != null && !requested.value) {
            requesters[target]?.requestFocus()
            requested.value = true
        }
    }
}
