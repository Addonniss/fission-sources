package dev.infusion.tv.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
internal fun DiagnosticsRestoreTombstone(onRestore: () -> Unit) {
    LaunchedEffect(Unit) { onRestore() }
}
