package com.example.rockey.ws

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.rockey.huoyan.HuoyanManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import kotlin.math.min

class OkHttpSocketClient(
    wsUrl: String,
    fallbackWsUrls: List<String> = emptyList(),
) : HuoyanManager.SocketClient {
    companion object {
        private const val TAG = "OkHttpSocketClient"
        private const val MAX_RECONNECT_DELAY_MS = 5000L
    }

    private val client = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wsUrls = linkedSetOf(wsUrl.trim()).apply {
        fallbackWsUrls.forEach { add(it.trim()) }
    }.filter { it.isNotEmpty() }

    private var webSocket: WebSocket? = null
    private var listener: HuoyanManager.SocketListener? = null
    private var connected = false
    private var closedByClient = false
    private var reconnectAttempt = 0
    private var urlAttemptCursor = 0
    private var reconnectRunnable: Runnable? = null

    override fun connect() {
        if (wsUrls.isEmpty()) {
            listener?.onError("No websocket url configured")
            return
        }
        closedByClient = false
        reconnectAttempt = 0
        urlAttemptCursor = 0
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        webSocket?.cancel()
        connected = false
        openSocket()
    }

    private fun openSocket() {
        val targetUrl = wsUrls[urlAttemptCursor % wsUrls.size]
        val request = Request.Builder().url(targetUrl).build()
        Log.i(TAG, "Connecting to $targetUrl (attempt=${reconnectAttempt + 1})")
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                reconnectAttempt = 0
                listener?.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener?.onTextMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                listener?.onError(t.message ?: "unknown websocket error")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                listener?.onClose()
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (closedByClient) {
            return
        }
        reconnectAttempt += 1
        urlAttemptCursor = (urlAttemptCursor + 1) % wsUrls.size
        val delay = min((1000L shl min(reconnectAttempt, 3)), MAX_RECONNECT_DELAY_MS)
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = Runnable {
            if (!closedByClient && !connected) {
                webSocket?.cancel()
                openSocket()
            }
        }
        mainHandler.postDelayed(reconnectRunnable!!, delay)
    }

    override fun sendText(text: String) {
        webSocket?.send(text)
    }

    override fun sendBinary(data: ByteArray) {
        webSocket?.send(ByteString.of(*data))
    }

    override fun isConnected(): Boolean = connected

    override fun close() {
        closedByClient = true
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        webSocket?.close(1000, "client close")
        connected = false
    }

    override fun setListener(listener: HuoyanManager.SocketListener) {
        this.listener = listener
    }
}
