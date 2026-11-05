package dev.infusion.tv.updater

import dev.infusion.core.update.UpdateCandidate
import dev.infusion.core.update.UpdateFailureKind

sealed interface UpdateInstallEvent {

    data class Downloading(val bytesRead: Long, val totalBytes: Long) : UpdateInstallEvent

    data object Verifying : UpdateInstallEvent
}

sealed interface InstallOutcome {

    data object AwaitingUserConfirmation : InstallOutcome

    data object PermissionMissing : InstallOutcome

    data object UserCancelled : InstallOutcome

    data object Installed : InstallOutcome

    data class Failed(val kind: UpdateFailureKind) : InstallOutcome
}

interface UpdateInstaller {

    suspend fun install(
        candidate: UpdateCandidate,
        onEvent: (UpdateInstallEvent) -> Unit,
    ): InstallOutcome

    fun observeResults(listener: (InstallOutcome) -> Unit)
}
