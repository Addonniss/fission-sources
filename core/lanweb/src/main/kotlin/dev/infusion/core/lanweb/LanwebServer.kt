package dev.infusion.core.lanweb

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

class LanwebServer(
    private val clock: Clock = SystemClock,
    private val logger: (String) -> Unit = {},
) {

    fun start(
        router: LanwebRouter,
        config: LanwebConfig = LanwebConfig(),
        validation: LanwebValidation = LanwebValidation.NONE,
    ): BoundLanwebServer {
        val serverSocket = ServerSocket()
        serverSocket.reuseAddress = true
        serverSocket.bind(
            InetSocketAddress(InetAddress.getByName(config.bindAddress), config.port),
            config.backlog,
        )
        val localAddress = serverSocket.inetAddress.hostAddress ?: config.bindAddress
        val authority = authorityOf(localAddress, serverSocket.localPort)
        val validator = LanwebRequestValidator(authority = authority, rules = validation)
        val permits = Semaphore(config.maxConnections)
        val connectionRate = RateLimiter(config.maxConnectionsPerWindow, config.connectionWindowMs, clock)
        val running = AtomicBoolean(true)
        val acceptThread = Thread({
            while (running.get()) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: SocketException) {
                    break
                } catch (e: IOException) {
                    if (!running.get()) break
                    continue
                }
                if (!connectionRate.tryAcquire() || !permits.tryAcquire()) {

                    runCatching { socket.close() }
                    continue
                }
                val handler = Thread({
                    try {
                        handleConnection(socket, router, config, validator)
                    } finally {
                        permits.release()
                    }
                }, "lanweb-conn")
                handler.isDaemon = true
                handler.start()
            }
        }, "lanweb-accept")
        acceptThread.isDaemon = true
        acceptThread.start()
        logger(LanwebLog.bindLine(authority))
        return BoundLanwebServer(
            listener = serverSocket,
            onStop = { running.set(false) },
            localAddress = localAddress,
            localPort = serverSocket.localPort,
            authority = authority,
        )
    }

    private fun handleConnection(
        socket: Socket,
        router: LanwebRouter,
        config: LanwebConfig,
        validator: LanwebRequestValidator,
    ) {
        try {
            socket.tcpNoDelay = true

            val deadlineMs = clock.nowEpochMs() + config.connectionDeadlineMs
            val remainingMs = (deadlineMs - clock.nowEpochMs()).coerceAtLeast(1)
            socket.soTimeout = minOf(config.readTimeoutMs, remainingMs).toInt()
            var routeLog: String? = null
            val response: LanwebResponse = try {
                val input = DeadlineInputStream(socket.getInputStream(), clock, deadlineMs)
                val request = readRequest(input, config)
                routeLog = LanwebLog.routeLog(request.method, request.path)
                dispatch(request, router, validator)
            } catch (e: LanwebHttpException) {
                LanwebResponse.error(e.status)
            } catch (e: SocketTimeoutException) {
                throw e
            } catch (e: IOException) {
                throw e
            } catch (e: RuntimeException) {
                LanwebResponse.error(500)
            }
            val out = DeadlineOutputStream(socket.getOutputStream(), clock, config.writeTimeoutMs, deadlineMs)
            out.begin()
            writeResponse(out, response)
            logger(LanwebLog.requestLine(routeLog, response.status))
        } catch (e: SocketTimeoutException) {

        } catch (e: IOException) {

        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readRequest(input: InputStream, config: LanwebConfig): LanwebRequest {
        val requestLine = readLine(input, config.maxRequestLineBytes, 414)
            ?: throw LanwebHttpException(400)
        val parts = requestLine.split(' ')
        if (parts.size != 3) throw LanwebHttpException(400)
        val (method, target, version) = parts
        if (!METHOD.matches(method)) throw LanwebHttpException(400)
        if (!target.startsWith("/")) throw LanwebHttpException(400)
        if (target.any { it.code < 0x20 }) throw LanwebHttpException(400)
        if (version != "HTTP/1.1" && version != "HTTP/1.0") throw LanwebHttpException(400)

        val path = target.substringBefore('?')
        val query = if (target.contains('?')) target.substringAfter('?') else null

        val headers = LinkedHashMap<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine(input, config.maxHeaderBytes - headerBytes, 431) ?: throw LanwebHttpException(400)
            headerBytes += line.length + 2
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) throw LanwebHttpException(400)
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (!HEADER_NAME.matches(name)) throw LanwebHttpException(400)
            if (value.any { it.code < 0x20 && it != '\t' }) throw LanwebHttpException(400)
            if (name == "content-length" && headers.containsKey(name)) throw LanwebHttpException(400)
            headers[name] = value
        }
        if (headers.containsKey("transfer-encoding")) throw LanwebHttpException(400)

        val body: ByteArray = if (method == "POST") {
            val lengthHeader = headers["content-length"] ?: throw LanwebHttpException(411)
            val length = lengthHeader.toLongOrNull() ?: throw LanwebHttpException(400)
            if (length < 0 || length > config.maxBodyBytes) throw LanwebHttpException(413)
            val buffer = ByteArray(length.toInt())
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) throw LanwebHttpException(400)
                read += n
            }
            buffer
        } else {
            ByteArray(0)
        }
        return LanwebRequest(
            method = method,
            path = path,
            query = query,
            headers = headers,
            body = body,
        )
    }

    private fun readLine(input: InputStream, maxBytes: Int, tooLargeCode: Int): String? {
        val line = StringBuilder()
        var byteCount = 0
        var sawCr = false
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) {
                if (!sawCr) throw LanwebHttpException(400)
                return line.toString()
            }
            if (b == '\r'.code) {
                if (sawCr) throw LanwebHttpException(400)
                sawCr = true
                continue
            }
            if (sawCr) throw LanwebHttpException(400)
            line.append(b.toChar())
            byteCount++
            if (byteCount > maxBytes) throw LanwebHttpException(tooLargeCode)
        }
    }

    private fun dispatch(
        request: LanwebRequest,
        router: LanwebRouter,
        validator: LanwebRequestValidator,
    ): LanwebResponse {

        validator.rejection(request.method, request.headers)?.let { return LanwebResponse.error(it) }
        return when (val resolved = router.resolve(request.method, request.path)) {
            is LanwebRouteResolution.Matched ->
                resolved.handler.handle(request.withPathParams(resolved.pathParams))
            LanwebRouteResolution.NotFound -> throw LanwebHttpException(404)
            LanwebRouteResolution.MethodNotAllowed -> throw LanwebHttpException(405)
        }
    }

    private fun writeResponse(out: OutputStream, response: LanwebResponse) {
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(response.body.size).append("\r\n")
            response.headers.forEach { (name, value) ->
                append(name).append(": ").append(value).append("\r\n")
            }
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(StandardCharsets.US_ASCII))
        out.write(response.body)
        out.flush()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        410 -> "Gone"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        414 -> "URI Too Long"
        415 -> "Unsupported Media Type"
        421 -> "Misdirected Request"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        else -> "Error"
    }

    private class DeadlineInputStream(
        private val delegate: InputStream,
        private val clock: Clock,
        private val deadlineMs: Long,
    ) : InputStream() {
        private fun check() {
            if (clock.nowEpochMs() > deadlineMs) throw SocketTimeoutException("connection deadline exceeded")
        }

        override fun read(): Int {
            check()
            val b = delegate.read()
            check()
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            check()
            val n = delegate.read(b, off, len)
            check()
            return n
        }

        override fun available(): Int = delegate.available()

        override fun close() = delegate.close()
    }

    private class DeadlineOutputStream(
        private val delegate: OutputStream,
        private val clock: Clock,
        private val writeTimeoutMs: Long,
        private val connectionDeadlineMs: Long,
    ) : OutputStream() {
        private var deadlineMs = Long.MAX_VALUE

        fun begin() {
            deadlineMs = minOf(clock.nowEpochMs() + writeTimeoutMs, connectionDeadlineMs)
        }

        private fun check() {
            if (clock.nowEpochMs() > deadlineMs) throw SocketTimeoutException("write deadline exceeded")
        }

        override fun write(b: Int) {
            check()
            delegate.write(b)
            check()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check()
            delegate.write(b, off, len)
            check()
        }

        override fun flush() {
            check()
            delegate.flush()
        }
    }

    private companion object {
        val METHOD = Regex("[A-Za-z]{1,16}")
        val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

        fun authorityOf(address: String, port: Int): String =
            if (address.contains(':')) "[$address]:$port" else "$address:$port"
    }
}

class BoundLanwebServer internal constructor(
    private val listener: AutoCloseable,
    private val onStop: () -> Unit,
    val localAddress: String,
    val localPort: Int,

    val authority: String,
) {

    val baseUrl: String get() = "http://$authority"

    fun stop() {
        runCatching { listener.close() }
        onStop()
    }
}
