package dev.infusion.tv.playback

import dev.infusion.core.playback.api.ContentDisplayTarget
import dev.infusion.core.playback.api.DisplayMatchingPolicy
import dev.infusion.core.playback.api.DisplayModeOption
import dev.infusion.core.playback.api.DisplayModeSelector

class DisplayMatchingCoordinator(
    private val diagnostics: DisplayMatchingDiagnostics = DisplayMatchingDiagnostics.NONE,
) {

    private var host: DisplayModeHost? = null

    private var ownedModeId: Int? = null

    private var pendingModeId: Int? = null

    fun attachHost(host: DisplayModeHost) {
        this.host = host
    }

    fun detachHost(host: DisplayModeHost) {
        if (this.host !== host) return
        this.host = null
        ownedModeId = null
        pendingModeId = null
    }

    val requestInForce: Boolean get() = pendingModeId == null

    fun confirmApplication(): Boolean {
        val pending = pendingModeId ?: return true
        val host = this.host
        if (host == null) {
            pendingModeId = null
            return true
        }
        if (host.currentMode()?.modeId == pending) {
            pendingModeId = null
            diagnostics.log("request modeId=$pending is the active mode (application confirmed)")
        }
        return pendingModeId == null
    }

    fun apply(policy: DisplayMatchingPolicy, target: ContentDisplayTarget?) {

        confirmApplication()
        if (policy == DisplayMatchingPolicy.OFF) {

            val owned = ownedModeId
            release()
            diagnostics.log("apply policy=OFF target=${target?.describe() ?: "none"} released=${owned ?: "none"}")
            return
        }
        val host = this.host ?: run {
            diagnostics.log("apply policy=$policy target=${target?.describe() ?: "none"} host=none (no request)")
            return
        }
        val advertised = host.advertisedModes()

        if (advertised.isEmpty()) {
            diagnostics.log("apply policy=$policy advertised=0 (no request)")
            return
        }
        if (target == null) {
            diagnostics.log("apply policy=$policy target=none advertised=${advertised.size} (no request)")
            return
        }

        val current = host.currentMode()
        val selected = DisplayModeSelector.select(
            policy = policy,
            target = target,
            currentMode = current,
            modes = advertised,
        )
        if (selected == null) {
            diagnostics.log(
                "apply policy=$policy target=${target.describe()} current=${current?.describe() ?: "unknown"} " +
                    "advertised=${advertised.size} selected=none (retain current mode)",
            )
            return
        }
        diagnostics.log(
            "apply policy=$policy target=${target.describe()} current=${current?.describe() ?: "unknown"} " +
                "advertised=${advertised.size} selected=${selected.describe()} owned=${ownedModeId ?: "none"}",
        )
        request(host, selected.modeId)
    }

    fun release() {
        val owned = ownedModeId
        ownedModeId = null

        pendingModeId = null
        if (owned == null) return
        diagnostics.log("release owned=${owned} -> preferredDisplayModeId=$NoPreferredDisplayMode")
        host?.requestMode(NoPreferredDisplayMode)
    }

    val ownsDisplayModeForTest: Boolean get() = ownedModeId != null

    val pendingModeIdForTest: Int? get() = pendingModeId

    private fun request(host: DisplayModeHost, modeId: Int) {

        if (ownedModeId == modeId) {
            diagnostics.log("request modeId=$modeId already owned (no write)")
            return
        }

        if (ownedModeId == null && host.currentMode()?.modeId == modeId) {
            ownedModeId = modeId
            pendingModeId = null
            diagnostics.log("request modeId=$modeId already the active mode (adopted, no write)")
            return
        }
        diagnostics.log("request writing modeId=$modeId")
        host.requestMode(modeId)
        ownedModeId = modeId

        pendingModeId = modeId
    }
}

internal fun DisplayModeOption.describe(): String =
    "id=$modeId ${widthPx}x$heightPx@${refreshRateMilliHz}mHz(${resolutionFamily ?: "nonstandard"})"

internal fun ContentDisplayTarget.describe(): String {
    val size = if (widthPx != null && heightPx != null) "$widthPx x $heightPx" else "unknown-size"
    return "${frameRateMilliHz}mHz $size(${resolutionFamily ?: "unknown-family"})"
}
