package dev.infusion.core.playback.api

import kotlin.math.abs
import kotlin.math.roundToInt

enum class DisplayMatchingPolicy(
    val storageKey: String,
) {

    OFF("off"),

    MATCH_FRAME_RATE("match-frame-rate"),

    MATCH_FRAME_RATE_AND_RESOLUTION("match-frame-rate-and-resolution"),
    ;

    companion object {

        val DEFAULT: DisplayMatchingPolicy = OFF

        fun orDefault(storageKey: String?): DisplayMatchingPolicy =
            entries.firstOrNull { it.storageKey == storageKey } ?: DEFAULT
    }
}

enum class DisplayResolutionFamily(

    val nominalWidthPx: Int,

    val nominalHeightPx: Int,
) {
    SD_480(720, 480),
    SD_576(720, 576),
    HD_720(1280, 720),
    FHD_1080(1920, 1080),
    QHD_1440(2560, 1440),
    UHD_2160(3840, 2160),
    UHD_4320(7680, 4320),
    ;

    val rank: Int get() = ordinal

    companion object {

        fun forModeHeight(heightPx: Int?): DisplayResolutionFamily? {
            if (heightPx == null || heightPx <= 0) return null
            return entries.firstOrNull { it.nominalHeightPx >= heightPx }
        }

        fun forContent(widthPx: Int?, heightPx: Int?): DisplayResolutionFamily? {
            if (heightPx == null || heightPx <= 0) return null
            var index = entries.indexOfFirst { it.nominalHeightPx >= heightPx }
            if (index < 0) return null
            val width = widthPx
            if (width != null && width > 0) {
                while (index < entries.size && entries[index].nominalWidthPx < width) {
                    index++
                }
                if (index >= entries.size) return null
            }
            return entries[index]
        }
    }
}

data class DisplayModeOption(
    val modeId: Int,
    val widthPx: Int,
    val heightPx: Int,
    val refreshRateMilliHz: Int,
) {
    init {
        require(widthPx > 0) { "widthPx must be > 0" }
        require(heightPx > 0) { "heightPx must be > 0" }
        require(refreshRateMilliHz > 0) { "refreshRateMilliHz must be > 0" }
    }

    val resolutionFamily: DisplayResolutionFamily?
        get() = DisplayResolutionFamily.forModeHeight(heightPx)

    companion object {

        fun of(
            modeId: Int,
            widthPx: Int,
            heightPx: Int,
            refreshRateHz: Double,
        ): DisplayModeOption? {
            if (!refreshRateHz.isFinite() || refreshRateHz <= 0.0) return null
            val milliHz = (refreshRateHz * 1000.0).roundToInt()
            if (milliHz <= 0) return null
            return DisplayModeOption(modeId, widthPx, heightPx, milliHz)
        }
    }
}

data class DeclaredVideoMetadata(

    val frameRateHz: Double? = null,

    val widthPx: Int? = null,

    val heightPx: Int? = null,
) {

    val isEmpty: Boolean get() = frameRateHz == null && widthPx == null && heightPx == null

    companion object {

        val NONE = DeclaredVideoMetadata()

        fun of(frameRateHz: Double?, resolution: String?): DeclaredVideoMetadata {
            val size = parseResolution(resolution) ?: return DeclaredVideoMetadata(
                frameRateHz = frameRateHz?.takeIf { it.isFinite() && it > 0.0 },
            )
            return DeclaredVideoMetadata(
                frameRateHz = frameRateHz?.takeIf { it.isFinite() && it > 0.0 },
                widthPx = size.first,
                heightPx = size.second,
            )
        }

        private fun parseResolution(resolution: String?): Pair<Int, Int>? {
            val raw = resolution?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val parts = raw.lowercase().split('x')
            if (parts.size != 2) return null
            val width = parts[0].trim().toIntOrNull() ?: return null
            val height = parts[1].trim().toIntOrNull() ?: return null
            if (width <= 0 || height <= 0) return null
            return width to height
        }
    }
}

data class ContentDisplayTarget(
    val frameRateMilliHz: Int,

    val widthPx: Int? = null,

    val heightPx: Int? = null,
) {
    init {
        require(frameRateMilliHz > 0) { "frameRateMilliHz must be > 0" }
        require(widthPx == null || widthPx > 0) { "widthPx must be > 0 when known" }
        require(heightPx == null || heightPx > 0) { "heightPx must be > 0 when known" }
    }

    val resolutionFamily: DisplayResolutionFamily?
        get() = DisplayResolutionFamily.forContent(widthPx, heightPx)

    companion object {
        fun from(
            inventory: TrackInventory,
            selectedVideoTrackId: TrackId? = null,
            declared: DeclaredVideoMetadata? = null,
            measured: MeasuredVideoEvidence? = null,
        ): ContentDisplayTarget? {
            val videos = inventory.videoTracks
            if (videos.isEmpty()) return null

            val selectedGroup = selectedVideoTrackId?.let { groupIndexOf(it) }
            val primaryGroup = selectedGroup ?: videos.mapNotNull { groupIndexOf(it.id) }.minOrNull()
            val groupTracks = if (primaryGroup == null) {
                videos
            } else {
                videos.filter { groupIndexOf(it.id) == primaryGroup }
            }

            val largest = groupTracks
                .filter { it.width != null && it.height != null }
                .maxByOrNull { (it.width ?: 0).toLong() * (it.height ?: 0).toLong() }
            val containerRate = largest?.frameRate
                ?: groupTracks.firstNotNullOfOrNull { it.frameRate }
            val milliHz = milliHzOf(containerRate?.toDouble())
                ?: milliHzOf(declared?.frameRateHz)
                ?: measured?.frameRateMilliHz
                ?: return null

            val containerWidth = largest?.width
            val containerHeight = largest?.height
            val effective = if (containerWidth != null && containerHeight != null) {
                val rotation = largest.rotationDegrees ?: 0
                val pixelAspect = largest.pixelWidthHeightRatio
                    ?.takeIf { it.isFinite() && it > 0f }
                    ?: 1f
                val rotatedWidth = if (abs(rotation) % 180 == 90) containerHeight else containerWidth
                val rotatedHeight = if (abs(rotation) % 180 == 90) containerWidth else containerHeight
                (rotatedWidth * pixelAspect).roundToInt() to rotatedHeight
            } else if (declared?.widthPx != null && declared.heightPx != null) {
                declared.widthPx to declared.heightPx
            } else {
                measured?.widthPx to measured?.heightPx
            }
            val effectiveWidth = effective.first
            val effectiveHeight = effective.second
            if (effectiveWidth == null || effectiveHeight == null ||
                effectiveWidth <= 0 || effectiveHeight <= 0
            ) {
                return ContentDisplayTarget(frameRateMilliHz = milliHz)
            }
            return ContentDisplayTarget(
                frameRateMilliHz = milliHz,
                widthPx = effectiveWidth,
                heightPx = effectiveHeight,
            )
        }

        private fun milliHzOf(rateHz: Double?): Int? {
            if (rateHz == null || !rateHz.isFinite() || rateHz <= 0.0) return null
            return (rateHz * 1000.0).roundToInt().takeIf { it > 0 }
        }

        private fun groupIndexOf(id: TrackId): Int? =
            id.value.split(':').getOrNull(1)?.toIntOrNull()
    }
}

enum class CadenceClass {
    EXACT,
    INTEGER_MULTIPLE,
    FRACTIONAL_2_5,
    NONE,
}

object DisplayModeSelector {

    fun select(
        policy: DisplayMatchingPolicy,
        target: ContentDisplayTarget?,
        currentMode: DisplayModeOption?,
        modes: List<DisplayModeOption>,
    ): DisplayModeOption? {
        if (policy == DisplayMatchingPolicy.OFF) return null
        if (target == null) return null
        if (target.frameRateMilliHz <= 0) return null

        val advertised = modes.distinctBy { it.modeId }
        if (advertised.isEmpty()) return null

        val selected = when (policy) {
            DisplayMatchingPolicy.OFF -> null
            DisplayMatchingPolicy.MATCH_FRAME_RATE ->
                frameRateLadder(advertised, currentMode, target.frameRateMilliHz)

            DisplayMatchingPolicy.MATCH_FRAME_RATE_AND_RESOLUTION ->
                resolutionLadder(advertised, target)
                    ?: frameRateLadder(advertised, currentMode, target.frameRateMilliHz)
        } ?: return null

        if (selected.modeId == currentMode?.modeId) return null
        return if (materiallyImproves(policy, selected, currentMode, target)) selected else null
    }

    private fun frameRateLadder(
        modes: List<DisplayModeOption>,
        currentMode: DisplayModeOption?,
        contentRateMilliHz: Int,
    ): DisplayModeOption? {
        val beneficial = modes.filter { cadenceOf(it, contentRateMilliHz) != CadenceClass.NONE }
        if (beneficial.isEmpty()) return null
        val currentFamily = currentMode?.resolutionFamily
        val sameFamily = if (currentFamily == null) {
            emptyList()
        } else {
            beneficial.filter { it.resolutionFamily == currentFamily }
        }
        val pool = sameFamily.ifEmpty { beneficial }
        return pool.sortedWith(sameFamilyComparator(contentRateMilliHz)).first()
    }

    private fun resolutionLadder(
        modes: List<DisplayModeOption>,
        target: ContentDisplayTarget,
    ): DisplayModeOption? {
        val contentFamily = target.resolutionFamily ?: return null
        val contentRank = contentFamily.rank
        val atOrAbove = modes.filter { mode ->
            val family = mode.resolutionFamily ?: return@filter false
            family.rank >= contentRank
        }
        if (atOrAbove.isEmpty()) return null

        val cadence = atOrAbove.associateWith { cadenceOf(it, target.frameRateMilliHz) }
        val sameFamily = atOrAbove.filter { it.resolutionFamily?.rank == contentRank }
        val higherFamily = atOrAbove.filter { (it.resolutionFamily?.rank ?: -1) > contentRank }

        return sameFamily.filter { cadence[it] == CadenceClass.EXACT }
            .minWithOrNull(cadenceComparator(target.frameRateMilliHz))
            ?: higherFamily.filter { cadence[it] == CadenceClass.EXACT }
                .minWithOrNull(higherFamilyComparator(target.frameRateMilliHz))
            ?: sameFamily.filter { cadence[it] != CadenceClass.NONE }
                .minWithOrNull(cadenceComparator(target.frameRateMilliHz))
            ?: higherFamily.filter { cadence[it] != CadenceClass.NONE }
                .minWithOrNull(higherFamilyComparator(target.frameRateMilliHz))
    }

    private fun materiallyImproves(
        policy: DisplayMatchingPolicy,
        selected: DisplayModeOption,
        currentMode: DisplayModeOption?,
        target: ContentDisplayTarget,
    ): Boolean {

        if (currentMode == null) return true

        val selectedClass = cadenceOf(selected, target.frameRateMilliHz)
        val currentClass = cadenceOf(currentMode, target.frameRateMilliHz)
        if (currentClass == CadenceClass.NONE && selectedClass != CadenceClass.NONE) return true
        if (currentClass == CadenceClass.FRACTIONAL_2_5 &&
            selectedClass != CadenceClass.NONE &&
            selectedClass != CadenceClass.FRACTIONAL_2_5
        ) {
            return true
        }
        if (policy == DisplayMatchingPolicy.MATCH_FRAME_RATE_AND_RESOLUTION) {
            val contentRank = target.resolutionFamily?.rank ?: return false
            val selectedDistance = familyDistance(selected.resolutionFamily, contentRank)
            val currentDistance = familyDistance(currentMode.resolutionFamily, contentRank)
            return selectedDistance < currentDistance
        }
        return false
    }

    private fun familyDistance(family: DisplayResolutionFamily?, contentRank: Int): Int =
        if (family == null) Int.MAX_VALUE else abs(family.rank - contentRank)

    fun cadenceOf(mode: DisplayModeOption, contentRateMilliHz: Int): CadenceClass {
        if (contentRateMilliHz <= 0) return CadenceClass.NONE
        val refresh = mode.refreshRateMilliHz
        if (refresh == contentRateMilliHz) return CadenceClass.EXACT
        if (refresh % contentRateMilliHz == 0 && refresh > contentRateMilliHz) {
            return CadenceClass.INTEGER_MULTIPLE
        }
        val twoAndAHalf = (contentRateMilliHz * 2.5).roundToInt()
        if (refresh == twoAndAHalf) return CadenceClass.FRACTIONAL_2_5
        return CadenceClass.NONE
    }

    private fun sameFamilyComparator(contentRateMilliHz: Int): Comparator<DisplayModeOption> =
        compareBy<DisplayModeOption> { it.widthPx.toLong() * it.heightPx.toLong() }
            .thenBy { cadenceOf(it, contentRateMilliHz).ordinal }
            .thenBy { it.refreshRateMilliHz }
            .thenBy { it.modeId }

    private fun higherFamilyComparator(contentRateMilliHz: Int): Comparator<DisplayModeOption> =
        compareBy<DisplayModeOption> { it.resolutionFamily?.rank ?: Int.MAX_VALUE }
            .thenBy { it.widthPx.toLong() * it.heightPx.toLong() }
            .thenBy { cadenceOf(it, contentRateMilliHz).ordinal }
            .thenBy { it.refreshRateMilliHz }
            .thenBy { it.modeId }

    private fun cadenceComparator(contentRateMilliHz: Int): Comparator<DisplayModeOption> =
        compareBy<DisplayModeOption> { cadenceOf(it, contentRateMilliHz).ordinal }
            .thenBy { it.widthPx.toLong() * it.heightPx.toLong() }
            .thenBy { it.refreshRateMilliHz }
            .thenBy { it.modeId }
}
