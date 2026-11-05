package dev.infusion.core.playback.media3

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.ForwardingExtractorsFactory
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.TrackOutput

@UnstableApi
internal class DolbyVisionProfile7ExtractorsFactory(
    delegate: ExtractorsFactory,
    private val rewriter: DolbyVisionProfile7FormatRewriter,
) : ForwardingExtractorsFactory(delegate) {

    override fun createExtractors(): Array<Extractor> = wrap(super.createExtractors())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = wrap(super.createExtractors(uri, responseHeaders))

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        Array(extractors.size) { index -> DolbyVisionBaseLayerExtractor(extractors[index], rewriter) }
}

@UnstableApi
internal class DolbyVisionBaseLayerExtractor(
    delegate: Extractor,
    private val rewriter: DolbyVisionProfile7FormatRewriter,
) : ForwardingExtractor(delegate) {

    override fun init(output: ExtractorOutput) {
        super.init(DolbyVisionBaseLayerExtractorOutput(output, rewriter))
    }
}

@UnstableApi
internal class DolbyVisionBaseLayerExtractorOutput(
    delegate: ExtractorOutput,
    private val rewriter: DolbyVisionProfile7FormatRewriter,
) : ForwardingExtractorOutput(delegate) {

    override fun track(id: Int, type: Int): TrackOutput =
        DolbyVisionBaseLayerTrackOutput(super.track(id, type), rewriter, type)
}

@UnstableApi
internal class DolbyVisionBaseLayerTrackOutput(
    delegate: TrackOutput,
    private val rewriter: DolbyVisionProfile7FormatRewriter,
    private val trackType: Int,
) : ForwardingTrackOutput(delegate) {

    private var baseLayerSamples: Boolean = false

    private var nalLengthFieldBytes: Int = DolbyVisionBaseLayerSampleFilter
        .nalLengthFieldBytes(emptyList())

    private val pendingParts = mutableListOf<Part>()

    private var samplesRewritten = 0
    private var nalUnitsDropped = 0
    private var bytesBefore = 0L
    private var bytesAfter = 0L

    private var firstWriteRecorded = false
    private var firstSampleRecorded = false

    override fun format(format: Format) {
        val rewritten = if (trackType == C.TRACK_TYPE_VIDEO) rewriter.rewrite(format) else null

        pendingParts.clear()
        baseLayerSamples = rewritten != null
        if (rewritten != null) {
            nalLengthFieldBytes = DolbyVisionBaseLayerSampleFilter.nalLengthFieldBytes(rewritten.initializationData)
        }
        super.format(rewritten ?: format)
    }

    override fun sampleData(data: ParsableByteArray, length: Int) =
        sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (!baseLayerSamples || length <= 0) {
            super.sampleData(data, length, sampleDataPart)
            return
        }
        val bytes = ByteArray(length)
        data.readBytes(bytes, 0, length)
        append(sampleDataPart, bytes)
    }

    override fun sampleData(data: DataReader, length: Int, allowEndOfInput: Boolean): Int =
        sampleData(data, length, allowEndOfInput, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleData(
        data: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        if (!baseLayerSamples || length <= 0) {
            return super.sampleData(data, length, allowEndOfInput, sampleDataPart)
        }
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = data.read(bytes, read, length - read)
            if (count == C.RESULT_END_OF_INPUT) {
                if (!allowEndOfInput) throw java.io.EOFException()
                break
            }
            read += count
        }
        if (read > 0) append(sampleDataPart, if (read == length) bytes else bytes.copyOf(read))
        return read
    }

    private fun append(part: Int, bytes: ByteArray) {
        if (!firstWriteRecorded) {
            firstWriteRecorded = true
            Media3ForensicsLog.event("dv-profile7-base-layer-write", "part=$part length=${bytes.size}")
        }
        val last = pendingParts.lastOrNull()
        if (last != null && last.part == part) {
            pendingParts[pendingParts.lastIndex] = Part(part, last.bytes + bytes)
        } else {
            pendingParts += Part(part, bytes)
        }
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        if (!baseLayerSamples || pendingParts.isEmpty()) {
            super.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }
        val parts = pendingParts.toList()
        pendingParts.clear()

        val mainIndex = parts.indexOfFirst { it.part == TrackOutput.SAMPLE_DATA_PART_MAIN }
        val main = parts.getOrNull(mainIndex)?.bytes
        val prefix = parts.getOrNull(mainIndex - 1)?.takeIf { mainIndex > 0 }?.let { previous ->
            previous.takeIf { it.part == TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL && it.bytes.size == 4 && main != null }
        }
        val prefixSize = prefix?.let { readBigEndianInt(it.bytes) }
        if (!firstSampleRecorded) {

            firstSampleRecorded = true
            Media3ForensicsLog.event(
                "dv-profile7-base-layer-probe",
                "parts=${parts.joinToString(",") { part -> "${part.part}:${part.bytes.size}" }} " +
                    "mainIndex=$mainIndex size=$size offset=$offset crypto=${cryptoData != null} " +
                    "nalLengthFieldBytes=$nalLengthFieldBytes " +
                    "framing=${DolbyVisionBaseLayerSampleFilter.describe(main ?: ByteArray(0), nalLengthFieldBytes)}",
            )
        }
        val filtered = main
            ?.takeIf { cryptoData == null && offset == 0 }
            ?.takeIf { mainIndex == 0 || (prefixSize != null && prefixSize == main.size) }
            ?.takeIf { size == main.size + parts.filter { it !== parts[mainIndex] }.sumOf { part -> part.bytes.size } }
            ?.let { DolbyVisionBaseLayerSampleFilter.filter(it, nalLengthFieldBytes) }
        if (filtered == null) {
            emit(parts)
            super.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }

        parts.forEachIndexed { index, part ->
            when {
                index == mainIndex -> emitMain(filtered.sample)
                part === prefix -> emitPart(Part(part.part, bigEndianInt(filtered.sample.size)))
                else -> emitPart(part)
            }
        }
        samplesRewritten++
        nalUnitsDropped += filtered.droppedNalUnits
        bytesBefore += main.size
        bytesAfter += filtered.sample.size
        if (samplesRewritten == FIRST_SAMPLE || samplesRewritten % LOG_EVERY_SAMPLES == 0) {
            Media3ForensicsLog.event(
                "dv-profile7-base-layer-samples",
                "samples=$samplesRewritten droppedNalUnits=$nalUnitsDropped bytesBefore=$bytesBefore bytesAfter=$bytesAfter " +
                    "nalLengthFieldBytes=$nalLengthFieldBytes sampleSize=${main.size}${if (parts.size == 1) "" else " parts=${parts.size}"}",
            )
        }
        super.sampleMetadata(
            timeUs,
            flags,

            filtered.sample.size + parts.filter { it !== parts[mainIndex] }.sumOf { part -> part.bytes.size },
            offset,
            cryptoData,
        )
    }

    private fun readBigEndianInt(bytes: ByteArray): Int {
        var value = 0
        for (byte in bytes) value = (value shl 8) or (byte.toInt() and 0xFF)
        return value
    }

    private fun bigEndianInt(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun emit(parts: List<Part>) {
        parts.forEach { part -> emitPart(part) }
    }

    private fun emitMain(bytes: ByteArray) {
        val buffer = ParsableByteArray(bytes)
        super.sampleData(buffer, bytes.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
    }

    private fun emitPart(part: Part) {
        val buffer = ParsableByteArray(part.bytes)
        super.sampleData(buffer, part.bytes.size, part.part)
    }

    private class Part(val part: Int, val bytes: ByteArray)

    private companion object {
        const val FIRST_SAMPLE = 1
        const val LOG_EVERY_SAMPLES = 1200
    }
}
