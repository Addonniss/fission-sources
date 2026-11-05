package dev.infusion.core.playback.media3

import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.HevcConfig

@UnstableApi
internal object DolbyVisionBaseLayerSampleFilter {

    internal class Filtered(val sample: ByteArray, val droppedNalUnits: Int)

    private const val NAL_UNIT_HEADER_BYTES = 2
    private const val NAL_UNIT_TYPE_MASK = 0x3F
    private const val LAYER_ID_HIGH_BIT_MASK = 0x01
    private const val LAYER_ID_LOW_BITS_MASK = 0xF8
    private const val LAYER_ID_LOW_BITS_SHIFT = 3

    private const val NAL_UNIT_TYPE_UNSPECIFIED_FIRST = 62
    private const val NAL_UNIT_TYPE_UNSPECIFIED_LAST = 63

    private const val NAL_UNIT_TYPE_VCL_LAST = 31

    private const val DEFAULT_NAL_LENGTH_FIELD_BYTES = 4
    private const val MIN_NAL_LENGTH_FIELD_BYTES = 1
    private const val MAX_NAL_LENGTH_FIELD_BYTES = 4

    private const val MAX_REWRITTEN_ACCESS_UNIT_BYTES = 64 * 1024 * 1024

    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    fun nalLengthFieldBytes(initializationData: List<ByteArray>): Int {
        val config = initializationData.firstOrNull { it.size > 22 && it[0] == 1.toByte() }
            ?: return DEFAULT_NAL_LENGTH_FIELD_BYTES
        return runCatching { HevcConfig.parse(ParsableByteArray(config)).nalUnitLengthFieldLength }
            .getOrDefault(DEFAULT_NAL_LENGTH_FIELD_BYTES)
            .takeIf { it in MIN_NAL_LENGTH_FIELD_BYTES..MAX_NAL_LENGTH_FIELD_BYTES }
            ?: DEFAULT_NAL_LENGTH_FIELD_BYTES
    }

    fun describe(sample: ByteArray, nalLengthFieldBytes: Int): String {
        val startCodeFirst = startCodeLength(sample, 0) != null
        val candidates = if (startCodeFirst) {
            arrayOf("start-code" to parseStartCodeFramed(sample), "length-prefixed" to parseLengthPrefixed(sample, nalLengthFieldBytes))
        } else {
            arrayOf("length-prefixed" to parseLengthPrefixed(sample, nalLengthFieldBytes), "start-code" to parseStartCodeFramed(sample))
        }
        for ((framing, accessUnit) in candidates) {
            if (accessUnit == null) continue
            val types = accessUnit.units.joinToString(",") { unit -> describeUnit(sample, unit) }
            val dropped = accessUnit.units.count { !it.baseLayer }
            return "$framing units=${accessUnit.units.size} base=${accessUnit.units.size - dropped} dropped=$dropped types=[$types]"
        }
        return "unparsed bytes=${sample.size}"
    }

    private fun describeUnit(sample: ByteArray, unit: NalUnit): String {
        val first = sample[unit.start].toInt()
        val second = sample[unit.start + 1].toInt()
        val layerId = ((first and LAYER_ID_HIGH_BIT_MASK) shl 5) or
            ((second and LAYER_ID_LOW_BITS_MASK) shr LAYER_ID_LOW_BITS_SHIFT)
        return if (layerId == 0) "${unit.nalUnitType}" else "${unit.nalUnitType}l$layerId"
    }

    fun filter(sample: ByteArray, nalLengthFieldBytes: Int): Filtered? {
        if (nalLengthFieldBytes !in MIN_NAL_LENGTH_FIELD_BYTES..MAX_NAL_LENGTH_FIELD_BYTES) return null
        if (sample.size <= NAL_UNIT_HEADER_BYTES) return null

        val startCodeFirst = startCodeLength(sample, 0) != null
        val parsers = if (startCodeFirst) {
            arrayOf<AccessUnit?>(
                parseStartCodeFramed(sample),
                parseLengthPrefixed(sample, nalLengthFieldBytes),
            )
        } else {
            arrayOf<AccessUnit?>(
                parseLengthPrefixed(sample, nalLengthFieldBytes),
                parseStartCodeFramed(sample),
            )
        }
        for (unit in parsers) {
            if (unit == null) continue
            rewrite(sample, unit)?.let { return it }
        }
        return null
    }

    private fun parseLengthPrefixed(sample: ByteArray, nalLengthFieldBytes: Int): AccessUnit? {
        if (sample.size <= nalLengthFieldBytes) return null
        val units = mutableListOf<NalUnit>()
        var offset = 0
        while (offset < sample.size) {
            if (sample.size - offset < nalLengthFieldBytes) return null
            var length = 0
            for (byteIndex in 0 until nalLengthFieldBytes) {
                length = (length shl 8) or (sample[offset + byteIndex].toInt() and 0xFF)
            }
            val start = offset + nalLengthFieldBytes
            if (length < NAL_UNIT_HEADER_BYTES || length > sample.size - start) return null
            units += NalUnit(nalUnitType(sample, start), isBaseLayerUnit(sample, start), start, length)
            offset = start + length
        }
        return units.takeIf { it.isNotEmpty() }?.let { AccessUnit(it, nalLengthFieldBytes, startCodeFramed = false) }
    }

    private fun parseStartCodeFramed(sample: ByteArray): AccessUnit? {
        if (startCodeLength(sample, 0) == null) return null
        val units = mutableListOf<NalUnit>()
        var offset = startCodeLength(sample, 0)!!
        while (offset < sample.size) {
            val next = nextStartCode(sample, offset)
            val end = next ?: sample.size
            if (end - offset < NAL_UNIT_HEADER_BYTES) return null
            units += NalUnit(nalUnitType(sample, offset), isBaseLayerUnit(sample, offset), offset, end - offset)
            offset = if (next == null) sample.size else next + startCodeLength(sample, next)!!
        }
        return units.takeIf { it.isNotEmpty() }?.let { AccessUnit(it, START_CODE.size, startCodeFramed = true) }
    }

    private fun rewrite(sample: ByteArray, accessUnit: AccessUnit): Filtered? {
        val kept = accessUnit.units.filter { it.baseLayer }
        val dropped = accessUnit.units.size - kept.size
        if (dropped == 0) return null
        if (kept.isEmpty()) return null

        if (kept.none { it.nalUnitType <= NAL_UNIT_TYPE_VCL_LAST }) return null

        val prefixBytes = accessUnit.prefixBytes
        val size = kept.sumOf { prefixBytes + it.length }

        if (size <= 0 || size > MAX_REWRITTEN_ACCESS_UNIT_BYTES) return null
        val rebuilt = ByteArray(size)
        var writeOffset = 0
        for (unit in kept) {
            if (accessUnit.startCodeFramed) {
                System.arraycopy(START_CODE, 0, rebuilt, writeOffset, START_CODE.size)
            } else {
                var length = unit.length
                for (byteIndex in prefixBytes - 1 downTo 0) {
                    rebuilt[writeOffset + byteIndex] = (length and 0xFF).toByte()
                    length = length ushr 8
                }
            }
            writeOffset += prefixBytes
            System.arraycopy(sample, unit.start, rebuilt, writeOffset, unit.length)
            writeOffset += unit.length
        }
        return Filtered(rebuilt, dropped)
    }

    private fun isBaseLayerUnit(sample: ByteArray, start: Int): Boolean {
        if (start + NAL_UNIT_HEADER_BYTES > sample.size) return false
        val type = nalUnitType(sample, start)
        if (type in NAL_UNIT_TYPE_UNSPECIFIED_FIRST..NAL_UNIT_TYPE_UNSPECIFIED_LAST) return false
        val first = sample[start].toInt()
        val second = sample[start + 1].toInt()
        val layerId = ((first and LAYER_ID_HIGH_BIT_MASK) shl 5) or
            ((second and LAYER_ID_LOW_BITS_MASK) shr LAYER_ID_LOW_BITS_SHIFT)
        return layerId == 0
    }

    private fun nalUnitType(sample: ByteArray, start: Int): Int =
        (sample[start].toInt() ushr 1) and NAL_UNIT_TYPE_MASK

    private fun startCodeLength(sample: ByteArray, index: Int): Int? {
        if (index < 0 || index + 3 > sample.size) return null
        if (sample[index].toInt() != 0 || sample[index + 1].toInt() != 0) return null
        if (sample[index + 2].toInt() == 1) return 3
        if (index + 4 <= sample.size && sample[index + 2].toInt() == 0 && sample[index + 3].toInt() == 1) return 4
        return null
    }

    private fun nextStartCode(sample: ByteArray, from: Int): Int? {
        var index = from
        while (index + 3 <= sample.size) {
            if (sample[index].toInt() == 0 && sample[index + 1].toInt() == 0 && startCodeLength(sample, index) != null) {
                return index
            }
            index++
        }
        return null
    }

    private class NalUnit(
        val nalUnitType: Int,
        val baseLayer: Boolean,
        val start: Int,
        val length: Int,
    )

    private class AccessUnit(
        val units: List<NalUnit>,
        val prefixBytes: Int,
        val startCodeFramed: Boolean,
    )
}
