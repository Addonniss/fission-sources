package dev.infusion.core.playback.api

data class DeviceRouteSnapshot(
    val device: DeviceBuild,
    val display: DisplaySnapshot? = null,
    val audioRoute: AudioRouteSnapshot? = null,
    val memory: MemorySnapshot? = null,
    val capturedAtEpochMs: Long,
)

data class DeviceBuild(
    val sdkInt: Int,
    val manufacturer: String? = null,
    val model: String? = null,
    val buildId: String? = null,
    val isTv: Boolean? = null,
)

data class DisplaySnapshot(

    val hdrTypes: Set<DisplayHdrType> = emptySet(),
    val refreshRateHz: Float? = null,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
)

enum class DisplayHdrType {
    HDR10,
    HDR10_PLUS,
    HLG,
    DOLBY_VISION,
    UNKNOWN,
}

data class AudioRouteSnapshot(
    val routeType: AudioRouteType,
    val routeName: String? = null,

    val supportsDirectPlayback: Boolean? = null,
)

enum class AudioRouteType {
    SPEAKER,
    HDMI,
    ARC,
    EAR_ARC,
    SPDIF,
    BLUETOOTH,
    USB,
    WIRED_HEADSET,
    UNKNOWN,
}

data class MemorySnapshot(
    val totalMb: Long? = null,
    val availableMb: Long? = null,
    val lowMemory: Boolean? = null,
)
