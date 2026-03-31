package com.example.rockey.huoyan

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Timer
import java.util.UUID
import kotlin.concurrent.fixedRateTimer

class HuoyanManager(
    private val cameraProvider: CameraProvider,
    private val socketClient: SocketClient,
    private val resultListener: ResultListener,
) {
    companion object {
        private const val TAG = "HuoyanManager"
    }

    private enum class ProtocolMode {
        UNKNOWN,
        LEGACY_HUOYAN,
        OPENCLAW_BRIDGE,
    }

    interface CameraProvider {
        fun capture(callback: PhotoCallback)
    }

    interface PhotoCallback {
        fun onSuccess(imageBytes: ByteArray)
        fun onError(error: String)
    }

    interface SocketClient {
        fun connect()
        fun sendText(text: String)
        fun sendBinary(data: ByteArray)
        fun isConnected(): Boolean
        fun close()
        fun setListener(listener: SocketListener)
    }

    interface SocketListener {
        fun onOpen()
        fun onTextMessage(text: String)
        fun onError(error: String)
        fun onClose()
    }

    interface ResultListener {
        fun onStatus(message: String)
        fun onAnswer(answer: String, summary: String, raw: JSONObject)
        fun onError(error: String)
        fun onModeChanged(enabled: Boolean)
        fun onConnectionChanged(connected: Boolean)
    }

    private data class ConversationTurn(
        val role: String,
        val text: String,
    )

    private data class BridgeAttachment(
        val type: String = "image",
        val mimeType: String,
        val fileName: String,
        val content: String,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val conversationTurns = mutableListOf<ConversationTurn>()
    private val bridgeResponses = mutableMapOf<String, StringBuilder>()
    private var timer: Timer? = null
    private var continuousMode = false
    private var intervalMs = 5000L
    private var pendingQuestionAfterUpload: String? = null
    private var latestBridgeAttachment: BridgeAttachment? = null
    private var protocolMode = ProtocolMode.UNKNOWN
    private var bridgeSessionKey = UUID.randomUUID().toString()

    init {
        socketClient.setListener(InternalSocketListener())
    }

    fun connect() {
        resultListener.onStatus("正在连接 OpenClaw 慧眼服务...")
        socketClient.connect()
    }

    fun setIntervalMs(value: Long) {
        intervalMs = if (value < 1000) 1000 else value
    }

    fun isContinuousMode(): Boolean = continuousMode

    fun startContinuousMode() {
        if (protocolMode == ProtocolMode.OPENCLAW_BRIDGE) {
            resultListener.onError("当前 OpenClaw Bridge 仅支持对话协议，不支持连续图像模式")
            return
        }
        if (!socketClient.isConnected()) {
            resultListener.onError("WebSocket 未连接，无法开启慧眼模式")
            return
        }
        if (continuousMode) {
            resultListener.onStatus("慧眼模式已经开启")
            return
        }

        continuousMode = true
        socketClient.sendText(jsonCommand("start_continuous"))
        resultListener.onModeChanged(true)
        resultListener.onStatus("慧眼模式已开启，开始每 5 秒自动采集画面")

        timer = fixedRateTimer(initialDelay = 0, period = intervalMs) {
            captureAndUpload()
        }
    }

    fun stopContinuousMode() {
        if (!continuousMode) {
            resultListener.onStatus("慧眼模式未开启")
            return
        }

        continuousMode = false
        timer?.cancel()
        timer = null
        if (socketClient.isConnected()) {
            socketClient.sendText(jsonCommand("stop_continuous"))
        }
        resultListener.onModeChanged(false)
        resultListener.onStatus("慧眼模式已关闭")
    }

    fun captureOnce() {
        if (!socketClient.isConnected()) {
            resultListener.onError("WebSocket 未连接")
            return
        }
        if (protocolMode == ProtocolMode.OPENCLAW_BRIDGE) {
            resultListener.onStatus("正在拍照并缓存给 OpenClaw Bridge...")
        }
        captureAndUpload()
    }

    fun captureAndAsk(question: String) {
        val normalizedQuestion = question.trim()
        if (normalizedQuestion.isEmpty()) {
            resultListener.onError("请输入问题")
            return
        }
        if (!socketClient.isConnected()) {
            resultListener.onError("WebSocket 未连接")
            return
        }

        val outgoingQuestion = buildOutgoingQuestion(normalizedQuestion)
        rememberTurn("user", normalizedQuestion)
        if (protocolMode == ProtocolMode.OPENCLAW_BRIDGE) {
            pendingQuestionAfterUpload = outgoingQuestion
            resultListener.onStatus("正在拍照并把图像和问题发送给 OpenClaw Bridge...")
            captureAndUpload()
        } else {
            pendingQuestionAfterUpload = outgoingQuestion
            resultListener.onStatus("正在拍照并发送给 OpenClaw...")
            captureAndUpload()
        }
    }

    fun askAboutLatest(question: String) {
        val normalizedQuestion = question.trim()
        if (normalizedQuestion.isEmpty()) {
            resultListener.onError("请输入问题")
            return
        }
        if (!socketClient.isConnected()) {
            resultListener.onError("WebSocket 未连接")
            return
        }

        rememberTurn("user", normalizedQuestion)
        val outgoingQuestion = buildOutgoingQuestion(normalizedQuestion)
        if (protocolMode == ProtocolMode.OPENCLAW_BRIDGE) {
            val attachment = latestBridgeAttachment
            if (attachment != null) {
                resultListener.onStatus("正在把最新画面和问题发送给 OpenClaw Bridge...")
                sendBridgeQuestion(outgoingQuestion, listOf(attachment))
            } else {
                resultListener.onStatus("当前没有缓存画面，将仅发送文字问题")
                sendBridgeQuestion(outgoingQuestion)
            }
        } else {
            sendQuestion(outgoingQuestion)
        }
    }

    fun release() {
        stopContinuousMode()
        socketClient.close()
    }

    private fun captureAndUpload() {
        cameraProvider.capture(object : PhotoCallback {
            override fun onSuccess(imageBytes: ByteArray) {
                if (imageBytes.isEmpty()) {
                    postError("拍照返回空数据")
                    return
                }
                if (!socketClient.isConnected()) {
                    postError("连接已断开，无法上传图片")
                    return
                }
                if (protocolMode == ProtocolMode.OPENCLAW_BRIDGE) {
                    val attachment = buildBridgeAttachment(imageBytes)
                    if (attachment == null) {
                        postError("图片编码失败，无法发送给 OpenClaw Bridge")
                        return
                    }
                    latestBridgeAttachment = attachment
                    val question = pendingQuestionAfterUpload
                    pendingQuestionAfterUpload = null
                    if (!question.isNullOrBlank()) {
                        sendBridgeQuestion(question, listOf(attachment))
                        postStatus("已拍照，正在把图像和问题发送给 OpenClaw Bridge")
                    } else {
                        postStatus("已缓存当前画面，可继续提问")
                    }
                    return
                }
                socketClient.sendBinary(imageBytes)
                postStatus("已上传一帧图像（${imageBytes.size} bytes）")
            }

            override fun onError(error: String) {
                postError("拍照失败: $error")
            }
        })
    }

    private fun jsonCommand(command: String): String {
        return try {
            JSONObject().apply {
                put("type", "command")
                put("command", command)
            }.toString()
        } catch (_: JSONException) {
            "{\"type\":\"command\",\"command\":\"$command\"}"
        }
    }

    private fun sendQuestion(question: String) {
        if (protocolMode == ProtocolMode.OPENCLAW_BRIDGE) {
            sendBridgeQuestion(question)
            return
        }
        val payload = try {
            JSONObject().apply {
                put("type", "question")
                put("question", question)
            }.toString()
        } catch (_: JSONException) {
            "{\"type\":\"question\",\"question\":\"${question.replace("\"", "\\\"")}\"}"
        }
        socketClient.sendText(payload)
        postStatus("问题已发送，等待 OpenClaw 回复")
    }

    private fun sendBridgeQuestion(
        question: String,
        attachments: List<BridgeAttachment> = emptyList(),
    ) {
        val requestId = UUID.randomUUID().toString()
        bridgeResponses[requestId] = StringBuilder()
        val history = buildBridgeHistory()
        val payload = try {
            JSONObject().apply {
                put("payload", question)
                put("requestId", requestId)
                put("sessionKey", bridgeSessionKey)
                if (history.length() > 0) {
                    put("history", history)
                }
                if (attachments.isNotEmpty()) {
                    put("attachments", JSONArray().apply {
                        attachments.forEach { attachment ->
                            put(JSONObject().apply {
                                put("type", attachment.type)
                                put("mimeType", attachment.mimeType)
                                put("fileName", attachment.fileName)
                                put("content", attachment.content)
                            })
                        }
                    })
                }
            }.toString()
        } catch (_: JSONException) {
            JSONObject(
                mapOf(
                    "payload" to question,
                    "requestId" to requestId,
                    "sessionKey" to bridgeSessionKey,
                ),
            ).toString()
        }
        socketClient.sendText(payload)
        postStatus(
            if (attachments.isNotEmpty()) {
                "图像和问题已发送，等待 OpenClaw Bridge 回复"
            } else {
                "问题已发送，等待 OpenClaw Bridge 回复"
            },
        )
    }

    private fun buildBridgeAttachment(imageBytes: ByteArray): BridgeAttachment? {
        if (imageBytes.isEmpty()) {
            return null
        }
        return BridgeAttachment(
            mimeType = "image/jpeg",
            fileName = "rokid_${System.currentTimeMillis()}.jpg",
            content = Base64.encodeToString(imageBytes, Base64.NO_WRAP),
        )
    }

    private fun buildBridgeHistory(): JSONArray {
        val turns = conversationTurns.dropLast(1).takeLast(6)
        val history = JSONArray()
        turns.forEach { turn ->
            history.put(
                JSONObject().apply {
                    put("role", turn.role)
                    put("content", turn.text)
                },
            )
        }
        return history
    }

    private fun buildOutgoingQuestion(question: String): String {
        return if (protocolMode == ProtocolMode.OPENCLAW_BRIDGE) {
            question
        } else {
            buildContextualQuestion(question)
        }
    }

    private fun buildContextualQuestion(question: String): String {
        val recentTurns = conversationTurns.takeLast(6)
        if (recentTurns.isEmpty()) {
            return question
        }

        val contextText = recentTurns.joinToString("\n") { turn ->
            val roleName = if (turn.role == "assistant") "OpenClaw" else "用户"
            "$roleName: ${turn.text}"
        }

        return buildString {
            appendLine("以下是最近对话上下文，请延续上下文回答。")
            appendLine(contextText)
            append("用户新的问题: ")
            append(question)
        }
    }

    private fun rememberTurn(role: String, text: String) {
        conversationTurns += ConversationTurn(role = role, text = text)
        if (conversationTurns.size > 12) {
            conversationTurns.removeAt(0)
        }
    }

    private fun postStatus(text: String) {
        mainHandler.post { resultListener.onStatus(text) }
    }

    private fun postError(text: String) {
        mainHandler.post { resultListener.onError(text) }
    }

    private inner class InternalSocketListener : SocketListener {
        override fun onOpen() {
            mainHandler.post { resultListener.onConnectionChanged(true) }
            postStatus("OpenClaw 慧眼服务已连接")
        }

        override fun onTextMessage(text: String) {
            Log.d(TAG, "WS <- $text")
            try {
                val obj = JSONObject(text)
                when {
                    isBridgeStatusFrame(obj) -> handleBridgeStatus(obj)
                    isBridgeErrorFrame(obj) -> handleBridgeError(obj)
                    obj.has("event") -> handleBridgeEvent(obj)
                    else -> handleLegacyMessage(obj)
                }
            } catch (e: JSONException) {
                postError("解析服务消息失败: ${e.message}")
            }
        }

        private fun handleLegacyMessage(obj: JSONObject) {
            markProtocol(ProtocolMode.LEGACY_HUOYAN)
            when (val type = obj.optString("type")) {
                    "welcome", "image_received", "mode_changed", "status" -> {
                        if (type == "image_received") {
                            val question = pendingQuestionAfterUpload
                            pendingQuestionAfterUpload = null
                            if (!question.isNullOrBlank()) {
                                sendQuestion(question)
                            }
                        }
                        postStatus(obj.optString("message", type))
                    }
                    "huoyan_result", "analysis_result" -> {
                        val answer = obj.optString("answer", "")
                        val summary = obj.optString("summary", "")
                        rememberTurn("assistant", answer.ifBlank { summary })
                        mainHandler.post { resultListener.onAnswer(answer, summary, obj) }
                    }
                    "error", "analysis_error", "answer_error" -> {
                        pendingQuestionAfterUpload = null
                        postError(obj.optString("message", "未知错误"))
                    }
                    else -> postStatus("收到消息: $type")
                }
        }

        private fun handleBridgeStatus(obj: JSONObject) {
            markProtocol(ProtocolMode.OPENCLAW_BRIDGE)
            val connected = obj.optBoolean("connected", socketClient.isConnected())
            val gatewayReachable = obj.optBoolean("gatewayReachable", true)
            mainHandler.post { resultListener.onConnectionChanged(connected && gatewayReachable) }
            when {
                !connected -> postError("OpenClaw Bridge 连接已断开")
                !gatewayReachable -> postError("OpenClaw Gateway 当前不可达，请检查桌面 bridge 服务")
                else -> postStatus("OpenClaw Bridge 协议已就绪")
            }
        }

        private fun handleBridgeError(obj: JSONObject) {
            markProtocol(ProtocolMode.OPENCLAW_BRIDGE)
            pendingQuestionAfterUpload = null
            val requestId = obj.optString("requestId")
            if (requestId.isNotBlank()) {
                bridgeResponses.remove(requestId)
            }
            val code = obj.optString("code").ifBlank { "UNKNOWN" }
            val message = obj.optString("message").ifBlank { "未知错误" }
            postError("OpenClaw Bridge 请求失败[$code]: $message")
        }

        private fun handleBridgeEvent(obj: JSONObject) {
            markProtocol(ProtocolMode.OPENCLAW_BRIDGE)
            when (obj.optString("event")) {
                "message" -> handleBridgeMessage(obj.optJSONObject("data"))
                "done" -> handleBridgeDone(obj.optJSONObject("data"), obj)
                else -> postStatus("收到 OpenClaw Bridge 事件: ${obj.optString("event")}")
            }
        }

        override fun onError(error: String) {
            pendingQuestionAfterUpload = null
            bridgeResponses.clear()
            mainHandler.post { resultListener.onConnectionChanged(false) }
            val detail =
                if (error.contains("/127.0.0.1:2478")) {
                    "OpenClaw Bridge 2478 未就绪: $error。请在主机执行 adb reverse tcp:2478 tcp:18789，并确认 OpenClaw gateway 正在监听 18789。"
                } else if (error.contains("connect", ignoreCase = true) || error.contains("failed", ignoreCase = true)) {
                    "OpenClaw Bridge 2478 连接失败: $error"
                } else {
                    "OpenClaw Bridge 异常: $error"
                }
            postError(detail)
        }

        override fun onClose() {
            pendingQuestionAfterUpload = null
            bridgeResponses.clear()
            mainHandler.post { resultListener.onConnectionChanged(false) }
            postStatus("OpenClaw 慧眼连接已关闭")
        }

        private fun handleBridgeMessage(data: JSONObject?) {
            if (data == null) {
                return
            }
            val requestId = data.optString("message_id")
            if (requestId.isBlank()) {
                return
            }
            val chunk = data.optString("answer_stream")
            if (chunk.isBlank()) {
                return
            }
            val response = bridgeResponses.getOrPut(requestId) { StringBuilder() }
            response.append(chunk)
            postStatus("OpenClaw 正在回答...")
        }

        private fun handleBridgeDone(data: JSONObject?, raw: JSONObject) {
            if (data == null) {
                return
            }
            val requestId = data.optString("message_id")
            val answer = bridgeResponses.remove(requestId)?.toString()?.trim().orEmpty()
            if (answer.isNotBlank()) {
                rememberTurn("assistant", answer)
            }
            val summary = answer.take(80)
            mainHandler.post { resultListener.onAnswer(answer, summary, raw) }
        }

        private fun isBridgeStatusFrame(obj: JSONObject): Boolean {
            return obj.optString("type") == "status" &&
                (obj.has("connected") || obj.has("gatewayReachable"))
        }

        private fun isBridgeErrorFrame(obj: JSONObject): Boolean {
            return obj.optString("type") == "error" &&
                obj.has("requestId") &&
                obj.has("code")
        }

        private fun markProtocol(mode: ProtocolMode) {
            if (protocolMode == mode) {
                return
            }
            protocolMode = mode
            Log.i(TAG, "Detected bridge protocol mode: $mode")
        }
    }
}
