package dev.infusion.core.network

import java.io.Closeable
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

interface HardenedWebSocketHandle : Closeable {

    fun send(text: String): Boolean

    fun closeNormally()

    fun cancelNow()

    override fun close() = cancelNow()
}

interface HardenedWebSocketListener {

    fun onOpen()

    fun onTextMessage(text: String)

    fun onClosed(failure: Throwable?)
}

interface HardenedWebSocketFactory {
    fun openWebSocket(request: HardenedRequest, listener: HardenedWebSocketListener): HardenedWebSocketHandle
}

internal class OkHttpWebSocketHandle(private val socket: WebSocket) : HardenedWebSocketHandle {
    override fun send(text: String): Boolean = runCatching { socket.send(text) }.getOrDefault(false)

    override fun closeNormally() {
        runCatching { socket.close(NORMAL_CLOSURE, "closed") }
    }

    override fun cancelNow() {
        runCatching { socket.cancel() }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}

internal class OkHttpWebSocketBridge(
    private val delegate: HardenedWebSocketListener,
) : WebSocketListener() {

    override fun onOpen(webSocket: WebSocket, response: Response) {
        delegate.onOpen()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        delegate.onTextMessage(text)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        delegate.onClosed(null)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {

        runCatching { webSocket.close(NORMAL_CLOSURE, null) }
        delegate.onClosed(null)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        delegate.onClosed(t)
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
