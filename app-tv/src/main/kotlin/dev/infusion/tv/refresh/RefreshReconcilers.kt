package dev.infusion.tv.refresh

import dev.infusion.core.domain.acquisition.AcquisitionService
import dev.infusion.core.domain.refresh.LibraryChangeHint
import dev.infusion.core.domain.refresh.LibraryRefreshReconciler
import dev.infusion.core.domain.refresh.ProvenImportKind
import dev.infusion.core.domain.refresh.ProvenImportTarget
import dev.infusion.source.jellyfin.events.JellyfinLibraryChangeFeed
import dev.infusion.tv.acquisition.AcquisitionCoordinator

class JellyfinRefreshReconciler(

    private val configured: () -> Boolean,

    private val refreshNativeItems: suspend (List<String>) -> Int,

    private val removeNativeItems: suspend (List<String>) -> Int,

    private val mirrorRecentItems: suspend () -> Int,

    private val refreshHome: suspend () -> Unit,

    private val refreshMappedNativeId: suspend (String) -> Int = { 0 },

    private val isTargetAvailable: (ProvenImportTarget) -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,

    private val fullScanMinIntervalMs: Long = 60_000L,

    private val onDiagnostic: (String) -> Unit = {},
) : LibraryRefreshReconciler {

    override fun serviceIds(): Set<String> =

        if (configured()) setOf(JellyfinLibraryChangeFeed.SERVICE_ID) else emptySet()

    override val catchUpIntervalMs: Long = 120_000L

    @Volatile
    private var lastFullScanAtMs: Long = Long.MIN_VALUE / 2

    override suspend fun reconcile(hint: LibraryChangeHint) {
        if (hint.removedNativeIds.isNotEmpty()) {
            runCatching { removeNativeItems(hint.removedNativeIds.toList()) }
        }
        var mirrored = 0
        if (hint.changedNativeIds.isNotEmpty()) {
            mirrored = runCatching { refreshNativeItems(hint.changedNativeIds.toList()) }.getOrDefault(0)
        }
        if (hint.scopeOnly) {
            val now = clock()
            val due = now - lastFullScanAtMs >= fullScanMinIntervalMs

            runCatching { mirrorRecentItems() }
            onDiagnostic("jellyfin-reconcile origin=push scope=recent fullDue=$due")
            if (due) {
                lastFullScanAtMs = now
                runCatching { refreshHome() }
                onDiagnostic("jellyfin-reconcile origin=push scope=full")
            }
        } else if (hint.changedNativeIds.isNotEmpty() && mirrored == 0) {

            runCatching { mirrorRecentItems() }
            onDiagnostic(
                "jellyfin-reconcile origin=push changed=${hint.changedNativeIds.size} mirrored=0 fallback=recent",
            )
        } else {
            onDiagnostic(
                "jellyfin-reconcile origin=push changed=${hint.changedNativeIds.size} mirrored=$mirrored",
            )
        }
    }

    override suspend fun catchUp(serviceId: String) {
        lastFullScanAtMs = clock()
        runCatching { refreshHome() }
        onDiagnostic("jellyfin-reconcile origin=fallback scope=full")
    }

    override suspend fun reconcileProvenImport(target: ProvenImportTarget): Boolean {
        if (!configured()) return false
        var targeted = 0
        if (target.kind == ProvenImportKind.MOVIE) {
            targeted = runCatching { refreshMappedNativeId(target.titleId) }.getOrDefault(0)
        }
        if (targeted == 0) {
            runCatching { mirrorRecentItems() }
        }
        onDiagnostic("jellyfin-probe kind=${target.kind.name} targeted=$targeted")
        return isTargetAvailable(target)
    }
}

class AcquisitionRefreshReconciler(
    private val acquisition: () -> AcquisitionCoordinator,

    private val configuredServiceIds: () -> Set<String> = {
        setOf(AcquisitionService.RADARR.id, AcquisitionService.SONARR.id)
    },

    private val hasNonTerminalWork: (String) -> Boolean = { false },

    private val onDiagnostic: (String) -> Unit = {},
) : LibraryRefreshReconciler {

    override fun serviceIds(): Set<String> = configuredServiceIds()

    override val catchUpIntervalMs: Long = 30_000L

    override fun catchUpWhileConnected(serviceId: String): Boolean = hasNonTerminalWork(serviceId)

    override suspend fun reconcile(hint: LibraryChangeHint) {
        onDiagnostic("acquisition-reconcile origin=push changed=${hint.changedNativeIds.size} removed=${hint.removedNativeIds.size}")
        acquisition().observeNow()
    }

    override suspend fun catchUp(serviceId: String) {
        onDiagnostic("acquisition-reconcile origin=fallback service=$serviceId")
        acquisition().observeNow()
    }
}
