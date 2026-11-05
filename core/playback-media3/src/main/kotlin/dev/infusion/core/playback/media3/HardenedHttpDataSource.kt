package dev.infusion.core.playback.media3

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import dev.infusion.core.network.HardenedHttpClient
import dev.infusion.core.network.HardenedRequest
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.network.NetworkPolicyException
import dev.infusion.core.network.UrlParser
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicLong

class HardenedHttpDataSource(
    private val transport: HardenedHttpClient,
    private val limits: NetworkLimits = NetworkLimits.media(),
    private val sensitiveHeaders: Set<String> = emptySet(),
) : HttpDataSource {

    private val requestProperties = mutableMapOf<String, String>()

    private var currentResponse: dev.infusion.core.network.HardenedResponse? = null
    private var openedUri: Uri? = null
    private var responseCode = -1
    private var contentTypeValue: String? = null
    private var bytesRead = 0L

    fun contentType(): String? = contentTypeValue

    override fun open(dataSpec: DataSpec): Long {
        val sequence = REQUEST_SEQUENCE.incrementAndGet()
        val headers = HashMap(requestProperties)
        dataSpec.httpRequestHeaders.forEach { (k, v) -> headers[k] = v }

        val callerRange = headers.keys.firstOrNull { it.equals("Range", ignoreCase = true) }
        if (callerRange == null) {
            if (dataSpec.position > 0L) headers["Range"] = "bytes=${dataSpec.position}-"
        } else {

            val callerStart = parseRangeStart(headers.getValue(callerRange))
            if (callerStart == null || callerStart != dataSpec.position) {
                close()
                throw HttpDataSource.HttpDataSourceException(
                    "caller-supplied Range inconsistent with DataSpec.position " +
                        "(range start=$callerStart, position=${dataSpec.position})",
                    dataSpec,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                )
            }
        }
        val url = try {
            UrlParser.parse(dataSpec.uri.toString())
        } catch (e: NetworkPolicyException) {
            throw HttpDataSource.HttpDataSourceException(
                "invalid media url",
                e,
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }
        val response = try {
            transport.execute(
                HardenedRequest(
                    url = url,
                    method = dataSpec.getHttpMethodString(),
                    headers = headers,
                    limits = limits,
                    body = dataSpec.httpBody,
                    sensitiveHeaders = sensitiveHeaders,
                ),
            )
        } catch (e: NetworkPolicyException) {
            throw HttpDataSource.HttpDataSourceException(
                "media request rejected by policy: ${e.reason.name}",
                e,
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }
        currentResponse = response
        openedUri = dataSpec.uri
        responseCode = response.statusCode
        contentTypeValue = response.headers["content-type"]
        Media3ForensicsLog.event(
            "range-response",
            "request=$sequence bytePosition=${dataSpec.position} range=${headerValue(headers, "Range") ?: "none"} " +
                "status=$responseCode contentLength=${headerValue(response.headers, "Content-Length") ?: "none"} " +
                "contentRange=${headerValue(response.headers, "Content-Range")?.let(::redactRange) ?: "none"}",
        )
        if (responseCode !in 200..299) {
            close()
            throw HttpDataSource.HttpDataSourceException(
                "non-2xx media response: $responseCode",
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                responseCode,
            )
        }

        if (responseCode == 206) {
            val contentRange = response.headers.entries.firstOrNull { (k, _) -> k.equals("Content-Range", ignoreCase = true) }?.value
            val contentStart = contentRange?.let { parseContentRangeStart(it) }
            if (contentRange == null || contentStart == null || contentStart != dataSpec.position) {
                close()
                throw HttpDataSource.HttpDataSourceException(
                    "206 response Content-Range start (${contentStart ?: "missing"}) does not match " +
                        "requested position ${dataSpec.position}",
                    dataSpec,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                )
            }
        } else if (dataSpec.position > 0) {
            skipFully(dataSpec.position, dataSpec)
        }
        if (dataSpec.length != C_LENGTH_UNSET) {
            Media3ForensicsLog.event("range-open", "request=$sequence returnedLength=${dataSpec.length} followsSeek=${dataSpec.position > 0L}")
            return dataSpec.length
        }
        val contentLength = headerValue(response.headers, "Content-Length")?.toLongOrNull()
        if (responseCode == 206) {

            val length = contentLength ?: contentRangeLength(response.headers) ?: C_LENGTH_UNSET
            Media3ForensicsLog.event("range-open", "request=$sequence returnedLength=$length followsSeek=${dataSpec.position > 0L}")
            return length
        }
        val length = contentLength?.let { total ->
            (total - dataSpec.position).takeIf { it >= 0L }
        } ?: C_LENGTH_UNSET
        Media3ForensicsLog.event("range-open", "request=$sequence returnedLength=$length followsSeek=${dataSpec.position > 0L}")
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val response = currentResponse
            ?: throw HttpDataSource.HttpDataSourceException(
                "read before open",
                DataSpec(Uri.EMPTY),
                HttpDataSource.HttpDataSourceException.TYPE_READ,
            )
        val n = try {
            response.body.read(buffer, offset, length)
        } catch (e: InterruptedIOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                DataSpec(Uri.EMPTY),
                HttpDataSource.HttpDataSourceException.TYPE_READ,
            )
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                DataSpec(Uri.EMPTY),
                HttpDataSource.HttpDataSourceException.TYPE_READ,
            )
        }
        if (n > 0) bytesRead += n
        return n
    }

    override fun getUri(): Uri? = openedUri

    override fun getResponseCode(): Int = responseCode

    override fun getResponseHeaders(): Map<String, List<String>> =
        currentResponse?.headers?.mapValues { (_, v) -> listOf(v) } ?: emptyMap()

    override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) {

    }

    override fun setRequestProperty(name: String, value: String) {
        requestProperties[name] = value
    }

    override fun clearRequestProperty(name: String) {
        requestProperties.remove(name)
    }

    override fun clearAllRequestProperties() {
        requestProperties.clear()
    }

    override fun close() {
        currentResponse?.close()
        currentResponse = null
        openedUri = null

    }

    private fun skipFully(n: Long, dataSpec: DataSpec) {
        val response = currentResponse ?: return
        val buf = ByteArray(8192)
        var remaining = n
        while (remaining > 0) {
            val read = response.body.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (read < 0) {
                throw HttpDataSource.HttpDataSourceException(
                    "stream ended before requested position",
                    dataSpec,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                )
            }
            remaining -= read
        }
    }

    private companion object {
        const val C_LENGTH_UNSET: Long = -1L
        val REQUEST_SEQUENCE = AtomicLong(0L)

        fun redactRange(value: String): String {
            val range = value.substringAfter(' ', "").substringBefore('/').trim()
            val total = value.substringAfter('/', "").trim()
            return "bytes $range/$total"
        }

        fun parseRangeStart(header: String): Long? {
            val value = header.substringAfter('=', "").trim()
            val dash = value.indexOf('-')
            if (dash <= 0) return null
            return value.substring(0, dash).trim().toLongOrNull()
        }

        fun parseContentRangeStart(header: String): Long? {
            val value = header.trim()
            val space = value.indexOf(' ')
            if (space <= 0) return null
            val unit = value.substring(0, space).trim()
            if (!unit.equals("bytes", ignoreCase = true)) return null
            val range = value.substring(space + 1).substringBefore('/').trim()
            val dash = range.indexOf('-')
            if (dash <= 0) return null
            return range.substring(0, dash).trim().toLongOrNull()
        }

        fun headerValue(headers: Map<String, String>, name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        fun contentRangeLength(headers: Map<String, String>): Long? {
            val value = headerValue(headers, "Content-Range") ?: return null
            val range = value.substringAfter(' ', "").substringBefore('/').trim()
            val dash = range.indexOf('-')
            if (dash <= 0) return null
            val start = range.substringBefore('-').trim().toLongOrNull() ?: return null
            val end = range.substringAfter('-').trim().toLongOrNull() ?: return null
            return (end - start + 1L).takeIf { it >= 0L }
        }
    }
}

class HardenedHttpDataSourceFactory(
    private val transport: () -> dev.infusion.core.network.HardenedHttpClient,
    private val limits: NetworkLimits = NetworkLimits.media(),
    private val sensitiveHeaders: Set<String> = emptySet(),
) : HttpDataSource.Factory {

    private val requestProperties = mutableMapOf<String, String>()

    override fun createDataSource(): HttpDataSource {
        val source = HardenedHttpDataSource(transport(), limits, sensitiveHeaders)
        requestProperties.forEach { (name, value) -> source.setRequestProperty(name, value) }
        return source
    }

    override fun setDefaultRequestProperties(properties: Map<String, String>): HttpDataSource.Factory {
        requestProperties.clear()
        requestProperties.putAll(properties)
        return this
    }
}
