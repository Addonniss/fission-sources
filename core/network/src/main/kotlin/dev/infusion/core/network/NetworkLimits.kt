package dev.infusion.core.network

import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

data class NetworkLimits(

    val maxResponseBytes: Long = 512L * 1024,

    val maxBufferedResponseBytes: Long? = null,

    val maxDecompressedBytes: Long = 2L * 1024 * 1024,

    val maxDecompressionRatio: Int = 20,

    val connectTimeoutMs: Long = 10_000,

    val readTimeoutMs: Long = 20_000,

    val totalTimeoutMs: Long = 30_000,

    val maxRedirects: Int = 3,

    val retryOnConnectionFailure: Boolean = false,

    val allowHttpToHttpsRedirect: Boolean = false,

    val maxRequestBytes: Long = 256L * 1024,
) {
    companion object {
        val DEFAULT = NetworkLimits()

        fun manifest() = DEFAULT.copy(maxResponseBytes = 256L * 1024)

        fun resource() = DEFAULT.copy(maxResponseBytes = 512L * 1024)

        fun media() = DEFAULT.copy(
            maxResponseBytes = Long.MAX_VALUE,
            maxDecompressedBytes = Long.MAX_VALUE,
            maxDecompressionRatio = DEFAULT.maxDecompressionRatio,
            totalTimeoutMs = 0L,
            maxRedirects = 10,
            retryOnConnectionFailure = true,
            allowHttpToHttpsRedirect = true,
        )
    }
}

class LimitedInputStream(
    private val delegate: InputStream,
    private val maxBytes: Long,
    private val onLimit: (Long) -> Nothing,
) : InputStream() {

    private var readBytes = 0L

    val bytesRead: Long get() = readBytes

    private fun bump(delta: Int): Int {
        if (delta > 0) {
            readBytes += delta
            if (readBytes > maxBytes) onLimit(readBytes)
        }
        return delta
    }

    override fun read(): Int {
        val b = delegate.read()
        if (b >= 0) {
            readBytes++
            if (readBytes > maxBytes) onLimit(readBytes)
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = bump(delegate.read(b, off, len))

    override fun skip(n: Long): Long {
        val skipped = delegate.skip(n)
        if (skipped > 0) {
            readBytes += skipped
            if (readBytes > maxBytes) onLimit(readBytes)
        }
        return skipped
    }

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()

    override fun markSupported(): Boolean = delegate.markSupported()

    override fun mark(readlimit: Int) = delegate.mark(readlimit)

    override fun reset() = delegate.reset()
}

object DecompressionGuard {

    fun wrap(raw: InputStream, contentEncoding: String?, limits: NetworkLimits): InputStream {
        val encoding = contentEncoding?.lowercase()?.trim()
        return when (encoding) {
            null, "", "identity" -> LimitedInputStream(raw, limits.maxResponseBytes) {
                throw NetworkPolicyException(DiagnosticReason.LIMIT_EXCEEDED, "response body exceeds limit")
            }
            "gzip", "x-gzip" -> gzipWrapped(raw, limits)
            "deflate" -> deflateWrapped(raw, limits)
            else -> throw NetworkPolicyException(
                DiagnosticReason.UNSUPPORTED_ENCODING,
                "unsupported content-encoding: $encoding",
            )
        }
    }

    private fun gzipWrapped(raw: InputStream, limits: NetworkLimits): InputStream {
        val rawLimited = rawLimited(raw, limits)
        return decompressedWrapped(GZIPInputStream(rawLimited), rawLimited, limits)
    }

    private fun deflateWrapped(raw: InputStream, limits: NetworkLimits): InputStream {
        val rawLimited = rawLimited(raw, limits)
        return decompressedWrapped(InflaterInputStream(rawLimited, Inflater(true)), rawLimited, limits)
    }

    private fun rawLimited(raw: InputStream, limits: NetworkLimits): LimitedInputStream =
        LimitedInputStream(raw, limits.maxResponseBytes) {
            throw NetworkPolicyException(DiagnosticReason.LIMIT_EXCEEDED, "response body exceeds limit")
        }

    private fun decompressedWrapped(
        decompressor: InputStream,
        compressedCounter: LimitedInputStream,
        limits: NetworkLimits,
    ): InputStream = RatioLimitedInputStream(
        delegate = decompressor,
        maxDecompressedBytes = limits.maxDecompressedBytes,
        maxRatio = limits.maxDecompressionRatio,
        compressedProvider = { compressedCounter.bytesRead },
    ) { detail ->
        throw NetworkPolicyException(DiagnosticReason.LIMIT_EXCEEDED, "decompression limit exceeded: $detail")
    }
}

class RatioLimitedInputStream(
    private val delegate: InputStream,
    private val maxDecompressedBytes: Long,
    private val maxRatio: Int,
    private val compressedProvider: () -> Long,
    private val onViolation: (String) -> Nothing,
) : InputStream() {

    private var decompressed = 0L

    private fun check(delta: Int) {
        if (delta <= 0) return
        decompressed += delta
        if (decompressed > maxDecompressedBytes) onViolation("decompressed size")
        val compressed = compressedProvider()
        if (compressed > 0 && decompressed > compressed * maxRatio) onViolation("decompression ratio")
    }

    override fun read(): Int {
        val b = delegate.read()
        check(if (b >= 0) 1 else 0)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        check(if (n > 0) n else 0)
        return n
    }

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()
}
