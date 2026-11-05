package dev.infusion.core.update

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

object Sha256 {

    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    fun normalize(declared: String?): String? {
        val trimmed = declared?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val withoutPrefix = when {
            trimmed.startsWith("sha256:", ignoreCase = true) -> trimmed.substring(7)
            trimmed.contains(':') -> return null
            else -> trimmed
        }
        val hex = withoutPrefix.lowercase()
        if (hex.length != 64 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return hex
    }

    fun digestFromSums(sums: String?, assetName: String): String? {
        val document = sums?.takeIf { it.isNotBlank() } ?: return null
        for (line in document.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val separator = trimmed.indexOf("  ")
            if (separator != 64) continue
            val digest = normalize(trimmed.substring(0, separator)) ?: continue
            val name = trimmed.substring(separator + 2).trim()
            if (name == assetName) return digest
        }
        return null
    }

    fun hasher(): Hasher = Hasher()

    class Hasher internal constructor() {

        private val digest = MessageDigest.getInstance("SHA-256")

        var bytesHashed: Long = 0L
            private set

        fun update(buffer: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            digest.update(buffer, offset, length)
            bytesHashed += length
        }

        fun finish(): String = hex(digest.digest())
    }

    fun copyAndHash(
        source: InputStream,
        sink: OutputStream,
        maxBytes: Long = Long.MAX_VALUE,
        onProgress: (bytesWritten: Long) -> Unit = {},
    ): String {
        val hasher = hasher()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (hasher.bytesHashed + read > maxBytes) {
                throw SizeMismatchException(declared = maxBytes, observedAtLeast = hasher.bytesHashed + read)
            }
            sink.write(buffer, 0, read)
            hasher.update(buffer, 0, read)
            onProgress(hasher.bytesHashed)
        }
        sink.flush()
        return hasher.finish()
    }

    fun ofFile(file: File): String = file.inputStream().use { stream ->
        val hasher = hasher()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            hasher.update(buffer, 0, read)
        }
        hasher.finish()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

class SizeMismatchException(val declared: Long, val observedAtLeast: Long) :
    IllegalStateException("body exceeds the declared $declared bytes")
