package dev.infusion.tv.ui.focus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.infusion.tv.ui.theme.InfusionTvFilterChip
import androidx.tv.material3.Text
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import kotlinx.coroutines.launch

@Composable
fun <T> TvFilterStrip(
    items: List<T>,
    key: (T) -> String,
    label: (T) -> String,
    selectedKey: String?,
    registry: FocusKeyRegistry,
    onActivate: (T) -> Unit,
    onEnter: (T) -> Unit,
    onFocusedChipChanged: (String) -> Unit = {},
    onExitUp: () -> Boolean = { false },
    enabled: (T) -> Boolean = { true },
    activateOnTraverse: Boolean = false,
    modifier: Modifier = Modifier,
    listTag: String? = null,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var cursorKey by remember { mutableStateOf<String?>(null) }

    fun enabledItems(): List<T> = items.filter { enabled(it) }

    fun focusChip(target: T) {
        val targetKey = key(target)
        cursorKey = targetKey
        val index = items.indexOfFirst { key(it) == targetKey }
        if (index >= 0 && !registry.isLive(targetKey)) {

            scope.launch { listState.scrollToItem(index) }
        }
        registry.arm(targetKey)
        if (registry.request(targetKey)) registry.disarm()
        onFocusedChipChanged(targetKey)
    }

    fun traverse(delta: Int) {
        val available = enabledItems()
        if (available.isEmpty()) return
        val current = cursorKey ?: selectedKey
        val index = available.indexOfFirst { key(it) == current }
        val next = if (index < 0) 0 else (index + delta).coerceIn(0, available.size - 1)
        val target = available[next]
        focusChip(target)
        if (activateOnTraverse) onActivate(target)
    }

    fun enter(item: T) {
        if (key(item) != selectedKey) onActivate(item)
        onEnter(item)
    }

    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.then(
            if (listTag != null) Modifier.testTag(listTag) else Modifier,
        ),
    ) {
        items(items, key = { key(it) }) { item ->
            val chipKey = key(item)
            val chipEnabled = enabled(item)

            var sawEntryDown by remember { mutableStateOf(false) }
            InfusionTvFilterChip(
                selected = chipKey == selectedKey,
                enabled = chipEnabled,
                onClick = {
                    if (chipKey == selectedKey) {

                        onEnter(item)
                    } else {
                        onActivate(item)
                    }
                },
                modifier = stableFocusModifier(
                    key = chipKey,
                    requesters = registry,
                    onFocusedKeyChanged = { focused ->
                        cursorKey = focused
                        onFocusedChipChanged(focused)
                    },
                )
                    .then(
                        if (chipEnabled) {
                            Modifier
                        } else {

                            Modifier.focusProperties { canFocus = false }
                        },
                    )
                    .onPreviewKeyEvent { event ->
                        when (event.key) {
                            Key.DirectionLeft, Key.DirectionRight -> {
                                if (event.type != KeyEventType.KeyDown) {
                                    return@onPreviewKeyEvent true
                                }
                                traverse(if (event.key == Key.DirectionLeft) -1 else 1)
                                true
                            }
                            Key.DirectionDown, Key.DirectionCenter -> {

                                if (event.type == KeyEventType.KeyDown) {
                                    sawEntryDown = true
                                } else {
                                    val genuine = sawEntryDown
                                    sawEntryDown = false
                                    if (genuine && chipEnabled) enter(item)
                                }
                                true
                            }
                            Key.DirectionUp -> {
                                if (event.type != KeyEventType.KeyDown) {
                                    return@onPreviewKeyEvent true
                                }
                                onExitUp()
                            }
                            else -> false
                        }
                    },
            ) {
                Text(label(item), style = InfusionTvScaleCalibration.Type.Emphasis, maxLines = 1)
            }
        }
    }
}
