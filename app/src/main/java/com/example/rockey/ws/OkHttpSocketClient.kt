package com.example.rockey.ws

import com.example.rockey.huoyan.HuoyanManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class OkHttpSocketClient(
    private val wsUrl: String,
) : HuoyanManager.SocketClient {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var listener: HuoyanManager.SocketListener? = null
    private var connected = false

    override fun connect() {
        webSocket?.cancel()
        connected = false
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                listener?.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener?.onTextMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                listener?.onError(t.message ?: "unknown websocket error")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                listener?.onClose()
            }
        })
    }

    override fun sendText(text: String) {
        webSocket?.send(text)
    }

    override fun sendBinary(data: ByteArray) {
        webSocket?.send(ByteString.of(*data))
    }

    override fun isConnected(): Boolean = connected

    override fun close() {
        webSocket?.close(1000, "client close")
        connected = false
    }

    override fun setListener(listener: HuoyanManager.SocketListener) {
        this.listener = listener
    }
}
