package com.example.rockey

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.rockey.camera.RokidCameraIntegration
import com.example.rockey.huoyan.HuoyanManager
import com.example.rockey.sync.PeriodicFrameSyncScheduler
import com.example.rockey.voice.RokidAssistClient
import com.example.rockey.voice.RokidTtsClient
import com.example.rockey.ws.OkHttpSocketClient
import com.google.android.material.button.MaterialButton
import com.rokid.arsdk.connection.DeviceInfo
import com.rokid.cxr.client.utils.ValueUtil
import com.rokid.os.sprite.assist.basic.AssistMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "RockeyMain"
        private const val WS_URL = "ws://127.0.0.1:2478"
        private val WS_FALLBACK_URLS = listOf("ws://127.0.0.1:18789")
        private const val DEFAULT_VOICE_PROMPT = "请直接分析我眼前的画面，并告诉我最重要的信息。"
        private const val AUTO_FRAME_SYNC_INTERVAL_MS = 60_000L
        private const val AUTO_FRAME_SYNC_PROMPT = "系统同步当前画面，请更新视觉上下文并保持静默，无需回复。"
        private const val RETRY_LISTEN_DELAY_MS = 900L
        private const val MAX_TTS_TIMEOUT_MS = 45_000L
        private const val ROKID_ASSIST_PACKAGE = "com.rokid.os.sprite.assistserver"
        private val WAKE_WORDS = listOf("rockey", "洛奇", "洛基", "罗奇")
        private val RECAPTURE_KEYWORDS = listOf("重拍", "再拍", "重新拍", "拍照", "看图", "截图", "拍一张")
    }

    private enum class RecognizerMode {
        NONE,
        ACTIVITY,
        SERVICE,
        ROKID_ASSIST,
    }

    private enum class SpeakerMode {
        NONE,
        ROKID_BINDER,
        SYSTEM_TTS,
    }

    private lateinit var connectionStateText: TextView
    private lateinit var statusText: TextView
    private lateinit var liveTranscriptText: TextView
    private lateinit var conversationText: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var voiceButton: MaterialButton
    private lateinit var reconnectButton: MaterialButton

    private data class PendingSpeech(
        val text: String,
        val utteranceId: String,
    )

    private var rokid: RokidCameraIntegration? = null
    private var huoyanManager: HuoyanManager? = null
    private val scannedDevices = mutableListOf<DeviceInfo>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bootstrapInProgress = false
    private var rokidConnected = false
    private var wsConnected = false
    private var awaitingAnswer = false
    private var isListening = false
    private var isSpeaking = false
    private var aiSceneActive = false
    private var wakeWordArmed = true
    private var lastHeardText = ""

    private var recognizerMode = RecognizerMode.NONE
    private var speakerMode = SpeakerMode.NONE
    private var speechActivityIntent: Intent? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var rokidAssistClient: RokidAssistClient? = null
    private var rokidTtsClient: RokidTtsClient? = null
    private var listeningCueTone: ToneGenerator? = null
    private var speechReady = false
    private var ttsReady = false
    private var assistRecognitionPending = false
    private var assistRecognitionStartedAt = 0L
    private var lastListeningCueAt = 0L
    private var pendingSpeech: PendingSpeech? = null
    private var currentTtsTimeoutMs = MAX_TTS_TIMEOUT_MS
    private var hasSentImageContext = false
    private var backgroundSyncInFlight = false
    private var autoFrameSyncAnnounced = false

    private val frameSyncScheduler by lazy {
        PeriodicFrameSyncScheduler(
            handler = mainHandler,
            intervalMs = AUTO_FRAME_SYNC_INTERVAL_MS,
            shouldRun = ::shouldRunAutoFrameSync,
            onTick = ::runAutoFrameSync,
        )
    }

    private val restartListeningRunnable = Runnable {
        startListening(reason = "auto")
    }

    private val ttsTimeoutRunnable =
        Runnable {
            if (!isSpeaking) {
                return@Runnable
            }
            appendSystemMessage("TTS 播报超时，已自动结束本次播报")
            handleTtsPlaybackStopped()
        }

    private val assistRecognitionTimeoutRunnable =
        Runnable {
            if (!assistRecognitionPending || recognizerMode != RecognizerMode.ROKID_ASSIST) {
                return@Runnable
            }
            assistRecognitionPending = false
            isListening = false
            reportAsrNone()
            liveTranscriptText.text = "Rokid ASR 未返回结果"
            updateStatus(
                state = "待命中",
                detail = "Rokid ASR 超时，等待下一次唤起",
            )
            updateActionState()
            scheduleListening(RETRY_LISTEN_DELAY_MS)
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { granted -> !granted }.keys
            if (denied.isEmpty()) {
                appendSystemMessage("权限已就绪，继续进入语音模式")
                bootstrapSession(forceReconnect = false)
            } else {
                frameSyncScheduler.stop()
                backgroundSyncInFlight = false
                updateStatus(
                    state = "权限不足",
                    detail = "缺少相机、麦克风或设备连接权限",
                )
                appendSystemMessage("被拒绝的权限: ${denied.joinToString()}")
                updateActionState()
            }
        }

    private val speechActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleSpeechActivityResult(result.resultCode, result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectionStateText = findViewById(R.id.connectionStateText)
        statusText = findViewById(R.id.statusText)
        liveTranscriptText = findViewById(R.id.liveTranscriptText)
        conversationText = findViewById(R.id.conversationText)
        chatScroll = findViewById(R.id.chatScroll)
        voiceButton = findViewById(R.id.voiceButton)
        reconnectButton = findViewById(R.id.reconnectButton)

        rokid = RokidCameraIntegration(this)
        voiceButton.visibility = if (rokid?.isLocalMode() == true) View.GONE else View.VISIBLE
        initVoiceStack()

        voiceButton.setOnClickListener {
            if (isListening) {
                stopListening(manual = true)
            } else {
                if (rokid?.supportsAiScene() == true && !aiSceneActive) {
                    appendSystemMessage("请按眼镜上的 AI 键唤起对话")
                    updateStatus(
                        state = "待唤起",
                        detail = "等待眼镜 AI 场景唤起",
                    )
                } else {
                    scheduleListening(delayMs = 0L)
                }
            }
        }
        reconnectButton.setOnClickListener { bootstrapSession(forceReconnect = true) }

        appendSystemMessage(
            if (rokid?.isLocalMode() == true) {
                "当前已切回 Rokid 本机模式，眼镜本机负责拍照、语音和 OpenClaw 会话。"
            } else {
                "当前不在眼镜本机环境，会回退到 companion 模式。"
            },
        )
        updateStatus(
            state = "准备中",
            detail = if (rokid?.isLocalMode() == true) {
                "正在连接本机相机与 OpenClaw Bridge 2478..."
            } else {
                "正在连接 Rokid 眼镜与 OpenClaw Bridge 2478..."
            },
        )
        liveTranscriptText.text = if (rokid?.isLocalMode() == true) "直接说话即可" else "等待眼镜 AI 键"
        updateActionState()
        bootstrapSession(forceReconnect = false)
    }

    private fun initVoiceStack() {
        val preferSilentRecognizer = rokid?.isLocalMode() == true
        if (preferSilentRecognizer && SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizerMode = RecognizerMode.SERVICE
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(VoiceRecognitionListener())
            }
            speechReady = true
            appendSystemMessage("本机模式已启用静默语音唤醒")
        } else {
            speechActivityIntent = resolveRecognitionActivityIntent()
        }

        if (!speechReady && speechActivityIntent != null) {
            recognizerMode = RecognizerMode.ACTIVITY
            speechReady = true
            appendSystemMessage("已启用 Rokid/系统语音入口")
        } else if (!speechReady && SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizerMode = RecognizerMode.SERVICE
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(VoiceRecognitionListener())
            }
            speechReady = true
            appendSystemMessage("已启用 Android 语音识别服务")
        } else {
            recognizerMode = RecognizerMode.NONE
            appendSystemMessage("当前设备没有可用的语音识别入口")
        }

        if (isPackageInstalled(ROKID_ASSIST_PACKAGE)) {
            initRokidAssist(allowFallback = true)
        }

        if (isPackageInstalled(ROKID_ASSIST_PACKAGE)) {
            initRokidTts(allowFallback = true)
        } else {
            initSystemTts(preferredEngine = null, allowFallback = false)
        }
    }

    private fun initRokidAssist(allowFallback: Boolean) {
        rokidAssistClient?.release()
        rokidAssistClient =
            RokidAssistClient(
                context = this,
                listener = object : RokidAssistClient.Listener {
                    override fun onBound() {
                        appendSystemMessage("已连接 Rokid MasterAssistService")
                    }

                    override fun onRegistered(result: com.rokid.os.sprite.assist.basic.RegisterResult?) {
                        recognizerMode = RecognizerMode.ROKID_ASSIST
                        speechReady = true
                        appendSystemMessage("已注册 Rokid Assist Client，可直接请求内部 ASR")
                    }

                    override fun onBindingFailed(reason: String) {
                        appendSystemMessage("Rokid Assist 绑定失败: $reason")
                        if (allowFallback) {
                            refreshRecognizerFallbackState()
                        }
                    }

                    override fun onDisconnected(reason: String) {
                        cancelAssistRecognition()
                        appendSystemMessage("Rokid Assist 已断开: $reason")
                        if (allowFallback) {
                            refreshRecognizerFallbackState()
                        }
                    }

                    override fun onMessageReceived(message: AssistMessage) {
                        handleAssistMessage(message)
                    }

                    override fun onDataReceived(channel: String?, extra: String?, data: ByteArray?) {
                        handleAssistData(channel, extra, data)
                    }
                },
            )
        appendSystemMessage("正在绑定 Rokid MasterAssistService")
        rokidAssistClient?.connect()
    }

    private fun refreshRecognizerFallbackState() {
        cancelAssistRecognition()
        recognizerMode =
            when {
                speechActivityIntent != null -> RecognizerMode.ACTIVITY
                speechRecognizer != null -> RecognizerMode.SERVICE
                else -> RecognizerMode.NONE
            }
        speechReady = recognizerMode != RecognizerMode.NONE
        if (!speechReady) {
            appendSystemMessage("当前设备没有可用的语音识别入口")
        }
    }

    private fun resolveRecognitionActivityIntent(): Intent? {
        val candidates =
            listOf(
                buildRecognitionIntent(packageName = ROKID_ASSIST_PACKAGE),
                buildRecognitionIntent(packageName = null),
            )

        return candidates.firstOrNull { intent ->
            intent.resolveActivity(packageManager) != null
        }
    }

    private fun buildRecognitionIntent(packageName: String?): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            packageName?.let(::setPackage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.SIMPLIFIED_CHINESE.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "直接说话即可")
        }
    }

    private fun initRokidTts(allowFallback: Boolean) {
        tts?.shutdown()
        tts = null
        speakerMode = SpeakerMode.NONE
        ttsReady = false
        rokidTtsClient?.release()
        rokidTtsClient =
            RokidTtsClient(
                context = this,
                listener = object : RokidTtsClient.Listener {
                    override fun onBound() {
                        speakerMode = SpeakerMode.ROKID_BINDER
                        ttsReady = true
                        appendSystemMessage("已直连 Rokid TtsService")
                    }

                    override fun onBindingFailed(reason: String) {
                        if (allowFallback) {
                            fallbackToSystemTts("Rokid TtsService 不可用: $reason")
                        } else {
                            speakerMode = SpeakerMode.NONE
                            ttsReady = false
                            appendSystemMessage("Rokid TtsService 绑定失败: $reason")
                        }
                    }

                    override fun onDisconnected(reason: String) {
                        if (isFinishing || isDestroyed) {
                            return
                        }
                        if (allowFallback) {
                            fallbackToSystemTts("Rokid TtsService 已断开: $reason")
                        } else {
                            speakerMode = SpeakerMode.NONE
                            ttsReady = false
                            appendSystemMessage("Rokid TtsService 已断开: $reason")
                        }
                    }

                    override fun onPlaybackStarted(utteranceId: String) {
                        handleTtsPlaybackStarted("Rokid TtsService")
                    }

                    override fun onPlaybackStopped(utteranceId: String) {
                        handleTtsPlaybackStopped()
                    }
                },
            )
        appendSystemMessage("正在绑定 Rokid TtsService")
        Log.i(TAG, "initRokidTts: binding Rokid TtsService")
        rokidTtsClient?.connect()
    }

    private fun fallbackToSystemTts(reason: String) {
        rokidTtsClient?.release()
        rokidTtsClient = null
        Log.w(TAG, "fallbackToSystemTts: $reason")
        if (speakerMode == SpeakerMode.SYSTEM_TTS && ttsReady) {
            return
        }
        appendSystemMessage("$reason，回退到系统默认 TTS")
        initSystemTts(preferredEngine = null, allowFallback = false)
    }

    private fun initSystemTts(preferredEngine: String?, allowFallback: Boolean) {
        val engineLabel =
            if (preferredEngine == ROKID_ASSIST_PACKAGE) {
                "Rokid assistserver TTS"
            } else {
                "系统默认 TTS"
            }

        val previous = tts
        var candidate: TextToSpeech? = null
        fun handleInitStatus(status: Int) {
            val current = candidate ?: tts
            if (current == null) {
                appendSystemMessage("$engineLabel 初始化回调过早，忽略本次结果")
                return
            }
            if (status == TextToSpeech.SUCCESS) {
                speakerMode = SpeakerMode.SYSTEM_TTS
                tts = current
                previous?.shutdown()
                configureSystemTtsEngine(engineLabel)
                appendSystemMessage("已启用 $engineLabel")
            } else if (allowFallback && preferredEngine != null) {
                current.shutdown()
                appendSystemMessage("$engineLabel 不可用，回退到系统默认 TTS")
                initSystemTts(preferredEngine = null, allowFallback = false)
            } else {
                current.shutdown()
                tts = null
                speakerMode = SpeakerMode.NONE
                ttsReady = false
                appendSystemMessage("TTS 初始化失败，将仅显示文字回答")
            }
        }
        val listener = listener@{ status: Int ->
            val current = candidate
            if (current == null) {
                mainHandler.post { handleInitStatus(status) }
                return@listener
            }
            handleInitStatus(status)
        }

        candidate =
            if (preferredEngine != null) {
                TextToSpeech(this, listener, preferredEngine)
            } else {
                TextToSpeech(this, listener)
            }
        tts = candidate
    }

    private fun configureSystemTtsEngine(engineLabel: String) {
        val speaker = tts ?: return
        speaker.language = Locale.SIMPLIFIED_CHINESE
        speaker.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                handleTtsPlaybackStarted(engineLabel)
            }

            override fun onDone(utteranceId: String?) {
                handleTtsPlaybackStopped()
            }

            override fun onError(utteranceId: String?) {
                handleTtsPlaybackStopped()
            }
        })
        ttsReady = true
        Log.i(TAG, "configureSystemTtsEngine: ready with $engineLabel")
        val queued = pendingSpeech
        if (queued != null) {
            pendingSpeech = null
            Log.i(TAG, "configureSystemTtsEngine: replay pending speech ${queued.utteranceId}")
            speakAnswer(queued.text, queued.utteranceId)
        }
    }

    private fun handleTtsPlaybackStarted(engineLabel: String) {
        Log.i(TAG, "handleTtsPlaybackStarted: engine=$engineLabel timeoutMs=$currentTtsTimeoutMs")
        isSpeaking = true
        mainHandler.removeCallbacks(ttsTimeoutRunnable)
        mainHandler.postDelayed(ttsTimeoutRunnable, currentTtsTimeoutMs)
        updateStatus(
            state = "回答中",
            detail = "$engineLabel 正在播报回答",
        )
        updateActionState()
    }

    private fun handleTtsPlaybackStopped() {
        Log.i(TAG, "handleTtsPlaybackStopped")
        mainHandler.removeCallbacks(ttsTimeoutRunnable)
        isSpeaking = false
        reportTtsFinished()
        if (rokid?.isLocalMode() == true) {
            wakeWordArmed = true
            liveTranscriptText.text = "说“Rockey”唤醒"
            updateStatus(
                state = "待唤醒",
                detail = "等待唤醒词 Rockey",
            )
        } else {
            updateStatus(
                state = "待命中",
                detail = "继续说话即可",
            )
        }
        updateActionState()
        scheduleListening(RETRY_LISTEN_DELAY_MS)
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun handleSpeechActivityResult(resultCode: Int, data: Intent?) {
        isListening = false
        val transcript =
            data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()

        if (resultCode != Activity.RESULT_OK || transcript.isBlank()) {
            reportAsrNone()
            liveTranscriptText.text = "没有听清，再说一次"
            if (!awaitingAnswer && wsConnected && rokidConnected) {
                scheduleListening(RETRY_LISTEN_DELAY_MS)
            }
            updateActionState()
            return
        }

        lastHeardText = transcript
        handleRecognizedText(transcript)
    }

    private fun registerAiSceneCallbacks() {
        rokid?.setAiEventListener(
            set = true,
            listener = object : RokidCameraIntegration.AiSceneListener {
                override fun onAiKeyDown() {
                    Log.i(TAG, "AiEventListener.onAiKeyDown")
                    runOnUiThread {
                        aiSceneActive = true
                        appendSystemMessage("收到眼镜 AI 键事件")
                        beginAiSession(source = "glasses")
                    }
                }

                override fun onAiKeyUp() {
                    Log.i(TAG, "AiEventListener.onAiKeyUp")
                    runOnUiThread {
                        appendSystemMessage("AI 键已释放")
                    }
                }

                override fun onAiExit() {
                    Log.i(TAG, "AiEventListener.onAiExit")
                    runOnUiThread {
                        aiSceneActive = false
                        stopListening(manual = false)
                        stopSpeaking()
                        isSpeaking = false
                        awaitingAnswer = false
                        liveTranscriptText.text = "AI 场景已退出"
                        updateStatus(
                            state = "待唤起",
                            detail = "等待眼镜再次唤起 AI 场景",
                        )
                        updateActionState()
                    }
                }
            },
        )
    }

    private fun beginAiSession(source: String) {
        Log.i(TAG, "beginAiSession source=$source aiSceneActive=$aiSceneActive")
        rokid?.notifyAiStart().logIfUnexpected("notifyAiStart")
        wakeWordArmed = false
        updateStatus(
            state = "聆听中",
            detail = if (source == "glasses") "AI 场景已唤起，请直接说话" else "开始新的 AI 对话",
        )
        scheduleListening(delayMs = 0L)
    }

    private fun reportAsrPartial(content: String) {
        Log.i(TAG, "reportAsrPartial length=${content.length}")
        rokid?.sendAsrContent(content).logIfUnexpected("sendAsrContent(partial)")
    }

    private fun reportAsrFinal(content: String) {
        Log.i(TAG, "reportAsrFinal length=${content.length}")
        rokid?.sendAsrContent(content).logIfUnexpected("sendAsrContent")
        rokid?.notifyAsrEnd().logIfUnexpected("notifyAsrEnd")
    }

    private fun reportAsrNone() {
        Log.i(TAG, "reportAsrNone")
        rokid?.notifyAsrNone().logIfUnexpected("notifyAsrNone")
        rokid?.notifyAsrEnd().logIfUnexpected("notifyAsrEnd")
    }

    private fun reportAsrError() {
        Log.i(TAG, "reportAsrError")
        rokid?.notifyAsrError().logIfUnexpected("notifyAsrError")
        rokid?.notifyAsrEnd().logIfUnexpected("notifyAsrEnd")
    }

    private fun reportAiResponse(content: String) {
        Log.i(TAG, "reportAiResponse length=${content.length}")
        rokid?.sendTtsContent(content).logIfUnexpected("sendTtsContent")
    }

    private fun reportTtsFinished() {
        Log.i(TAG, "reportTtsFinished")
        rokid?.notifyTtsAudioFinished().logIfUnexpected("notifyTtsAudioFinished")
    }

    private fun reportAiFailure(error: String) {
        Log.w(TAG, "reportAiFailure error=$error")
        when {
            error.contains("上传") || error.contains("图片") || error.contains("拍照") -> {
                rokid?.notifyPicUploadError().logIfUnexpected("notifyPicUploadError")
            }
            !wsConnected || error.contains("WebSocket", ignoreCase = true) || error.contains("网络") -> {
                rokid?.notifyNoNetwork().logIfUnexpected("notifyNoNetwork")
            }
            else -> {
                rokid?.notifyAiError().logIfUnexpected("notifyAiError")
            }
        }
    }

    private fun ValueUtil.CxrStatus?.logIfUnexpected(action: String) {
        if (this == null || this == ValueUtil.CxrStatus.REQUEST_SUCCEED) {
            return
        }
        Log.w(TAG, "$action returned $name")
        appendSystemMessage("$action 返回 ${name}")
    }

    private fun bootstrapSession(forceReconnect: Boolean) {
        if (bootstrapInProgress) {
            return
        }

        if (!hasAllNeededPermissions()) {
            requestNeededPermissions()
            return
        }

        bootstrapInProgress = true
        awaitingAnswer = false
        backgroundSyncInFlight = false
        refreshFrameSyncScheduling()
        cancelScheduledListening()
        stopListening(manual = false)

        if (forceReconnect) {
            huoyanManager?.release()
            huoyanManager = null
            rokid?.disconnect()
            rokidConnected = false
            wsConnected = false
            appendSystemMessage("正在重新建立语音链路...")
        }

        updateStatus(
            state = "启动中",
            detail = if (rokid?.isLocalMode() == true) {
                "正在准备本机相机、语音识别和 OpenClaw 会话..."
            } else {
                "正在准备 Rokid companion 会话、语音识别和 OpenClaw 会话..."
            },
        )
        liveTranscriptText.text = if (rokid?.isLocalMode() == true) "准备聆听..." else "正在连接眼镜..."
        updateActionState()

        val initOk = rokid?.init() == true
        if (!initOk) {
            bootstrapInProgress = false
            updateStatus(
                state = "初始化失败",
                detail = "Rokid 模块初始化失败",
            )
            appendSystemMessage("Rokid 初始化失败")
            updateActionState()
            return
        }

        startScanAndConnect()
    }

    private fun startScanAndConnect() {
        scannedDevices.clear()
        rokid?.startScan(
            onScanResult = { devices ->
                scannedDevices.clear()
                scannedDevices.addAll(devices)
                if (devices.isEmpty()) {
                    onBootstrapFailed(if (rokid?.isLocalMode() == true) "没有发现可用相机或设备" else "没有发现已配对的 Rokid 眼镜")
                    return@startScan
                }

                val device = devices.first()
                appendSystemMessage("已识别设备: ${device.name}")
                rokid?.connect(
                    device = device,
                    onConnected = {
                        rokidConnected = true
                        aiSceneActive = rokid?.isLocalMode() == true
                        wakeWordArmed = rokid?.isLocalMode() == true
                        registerAiSceneCallbacks()
                        if (rokid?.supportsAiScene() == true) {
                            rokid?.setPhotoParams()?.logIfUnexpected("setPhotoParams")
                        }
                        updateStatus(
                            state = if (rokid?.supportsAiScene() == true) "等待 AI 唤起" else "待唤醒",
                            detail = if (rokid?.supportsAiScene() == true) {
                                "已接入 ${device.name}，等待眼镜 AI 键唤起后进入 OpenClaw 对话..."
                            } else {
                                "已接入 ${device.name}，说“Rockey”即可唤醒"
                            },
                        )
                        appendSystemMessage(
                            if (rokid?.supportsAiScene() == true) {
                                "${device.name} 已连接，等待眼镜 AI 键"
                            } else {
                                "${device.name} 已连接，等待唤醒词 Rockey"
                            },
                        )
                        connectHuoyan()
                    },
                    onDisconnected = {
                        rokidConnected = false
                        aiSceneActive = false
                        wsConnected = false
                        awaitingAnswer = false
                        backgroundSyncInFlight = false
                        stopListening(manual = false)
                        refreshFrameSyncScheduling()
                        updateStatus(
                            state = "已断开",
                            detail = "Rokid 眼镜连接已断开",
                        )
                        appendSystemMessage("Rokid 眼镜已断开")
                        updateActionState()
                    },
                    onConnectFailed = { code, msg ->
                        backgroundSyncInFlight = false
                        refreshFrameSyncScheduling()
                        onBootstrapFailed("连接设备失败: $msg ($code)")
                    },
                )
            },
            onScanError = { code, msg ->
                backgroundSyncInFlight = false
                refreshFrameSyncScheduling()
                onBootstrapFailed("识别设备失败: $msg ($code)")
            },
        )
    }

    private fun connectHuoyan() {
        val existing = huoyanManager
        if (existing != null) {
            existing.connect()
            return
        }

        val socketClient = OkHttpSocketClient(
            wsUrl = WS_URL,
            fallbackWsUrls = WS_FALLBACK_URLS,
        )
        val cameraProvider = object : HuoyanManager.CameraProvider {
            override fun capture(callback: HuoyanManager.PhotoCallback) {
                val rokidInstance = rokid
                if (rokidInstance == null || !rokidInstance.isConnected()) {
                    callback.onError("Rokid 相机未连接")
                    return
                }

                val out = File(
                    getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                    "huoyan_${System.currentTimeMillis()}.jpg",
                )
                rokidInstance.takePhoto(
                    savePath = out.absolutePath,
                    onCaptured = { _, imageData -> callback.onSuccess(imageData) },
                    onError = { error -> callback.onError(error) },
                )
            }
        }

        huoyanManager = HuoyanManager(
            cameraProvider = cameraProvider,
            socketClient = socketClient,
            resultListener = object : HuoyanManager.ResultListener {
                override fun onStatus(message: String) {
                    runOnUiThread {
                        statusText.text = message
                        if (shouldMirrorStatusToChat(message)) {
                            appendSystemMessage(message)
                        }
                    }
                }

                override fun onAnswer(answer: String, summary: String, raw: JSONObject) {
                    awaitingAnswer = false
                    val content = answer.ifBlank { summary.ifBlank { "OpenClaw 已返回结果，但内容为空。" } }
                    appendAssistantMessage(content)
                    reportAiResponse(content)
                    liveTranscriptText.text = if (rokid?.isLocalMode() == true) "等待回答播报完成" else "继续说话即可"
                    if (ttsReady) {
                        speakAnswer(content)
                    } else {
                        reportTtsFinished()
                        if (rokid?.isLocalMode() == true) {
                            wakeWordArmed = true
                        }
                        updateStatus(
                            state = if (rokid?.isLocalMode() == true) "待唤醒" else "待命中",
                            detail = if (rokid?.isLocalMode() == true) "OpenClaw 已回答，等待唤醒词 Rockey" else "OpenClaw 已回答，继续说话即可",
                        )
                        scheduleListening(RETRY_LISTEN_DELAY_MS)
                    }
                    updateActionState()
                }

                override fun onError(error: String) {
                    awaitingAnswer = false
                    if (error.contains("拍照", ignoreCase = true) || error.contains("图像", ignoreCase = true)) {
                        hasSentImageContext = false
                    }
                    appendSystemMessage(error)
                    reportAiFailure(error)
                    liveTranscriptText.text = "说话失败，稍后重试"
                    updateStatus(
                        state = if (wsConnected) "处理中断" else "连接异常",
                        detail = error,
                    )
                    updateActionState()
                    scheduleListening(RETRY_LISTEN_DELAY_MS)
                }

                override fun onBackgroundSync(success: Boolean, message: String) {
                    backgroundSyncInFlight = false
                    runOnUiThread {
                        if (success) {
                            statusText.text = message
                        } else {
                            appendSystemMessage(message)
                            if (!awaitingAnswer && !isSpeaking) {
                                updateStatus(
                                    state = if (wsConnected) "后台同步异常" else "连接异常",
                                    detail = message,
                                )
                            }
                        }
                    }
                    refreshFrameSyncScheduling()
                }

                override fun onModeChanged(enabled: Boolean) {
                    appendSystemMessage(if (enabled) "连续模式已开启" else "连续模式已关闭")
                }

                override fun onConnectionChanged(connected: Boolean) {
                    wsConnected = connected
                    bootstrapInProgress = false
                    backgroundSyncInFlight = false
                    if (connected) {
                        hasSentImageContext = false
                        updateStatus(
                            state = if (rokid?.supportsAiScene() == true && !aiSceneActive) "待唤起" else "待命中",
                            detail = if (rokid?.supportsAiScene() == true && !aiSceneActive) {
                                "OpenClaw Bridge 已连接，等待眼镜 AI 键唤起"
                            } else {
                                if (rokid?.isLocalMode() == true) "OpenClaw Bridge 已连接，说“Rockey”即可唤醒" else "OpenClaw Bridge 已连接，请直接说话"
                            },
                        )
                        liveTranscriptText.text =
                            if (rokid?.supportsAiScene() == true && !aiSceneActive) {
                                "等待眼镜 AI 键"
                            } else {
                                if (rokid?.isLocalMode() == true) "说“Rockey”唤醒" else "直接说话即可"
                            }
                        if (rokid?.supportsAiScene() != true || aiSceneActive) {
                            scheduleListening(RETRY_LISTEN_DELAY_MS)
                        }
                    } else {
                        hasSentImageContext = false
                        stopListening(manual = false)
                        updateStatus(
                            state = "连接中断",
                            detail = "OpenClaw Bridge 2478 已断开",
                        )
                    }
                    refreshFrameSyncScheduling()
                    updateActionState()
                }
            },
        )
        huoyanManager?.connect()
    }

    private fun startListening(reason: String) {
        if (!speechReady || !hasRecordAudioPermission()) {
            liveTranscriptText.text = "麦克风未就绪"
            return
        }
        if (!canStartVoiceCapture()) {
            liveTranscriptText.text =
                if (rokid?.supportsAiScene() == true) "等待眼镜 AI 键" else "语音模式尚未就绪"
            return
        }
        if (!rokidConnected || !wsConnected || bootstrapInProgress || awaitingAnswer || isSpeaking || isListening) {
            return
        }

        cancelScheduledListening()
        lastHeardText = ""
        liveTranscriptText.text =
            if (rokid?.isLocalMode() == true && wakeWordArmed) {
                "说“Rockey”唤醒"
            } else if (reason == "auto") {
                "正在聆听..."
            } else {
                "请开始说话"
            }

        when (recognizerMode) {
            RecognizerMode.ACTIVITY -> {
                val intent = speechActivityIntent ?: return
                isListening = true
                updateStatus(
                    state = "聆听中",
                    detail = "请直接说话",
                )
                playListeningCue()
                updateActionState()
                try {
                    speechActivityLauncher.launch(Intent(intent))
                } catch (_: ActivityNotFoundException) {
                    isListening = false
                    liveTranscriptText.text = "系统语音入口暂不可用"
                    updateActionState()
                }
            }

            RecognizerMode.SERVICE -> {
                val recognizer = speechRecognizer ?: return
                recognizer.cancel()
                recognizer.startListening(buildRecognitionIntent(packageName = null))
            }

            RecognizerMode.ROKID_ASSIST -> {
                val client = rokidAssistClient
                if (client == null || !client.isReady()) {
                    liveTranscriptText.text = "Rokid Assist 尚未就绪"
                    return
                }
                if (client.startVoiceRecognition()) {
                    assistRecognitionPending = true
                    assistRecognitionStartedAt = SystemClock.elapsedRealtime()
                    isListening = true
                    mainHandler.removeCallbacks(assistRecognitionTimeoutRunnable)
                    mainHandler.postDelayed(assistRecognitionTimeoutRunnable, 12_000L)
                    updateStatus(
                        state = "聆听中",
                        detail = "已请求 Rokid 内部 ASR，请直接说话",
                    )
                    playListeningCue()
                    updateActionState()
                } else {
                    liveTranscriptText.text = "Rokid ASR 启动失败"
                }
            }

            RecognizerMode.NONE -> {
                liveTranscriptText.text = "当前设备没有语音识别入口"
            }
        }
    }

    private fun stopListening(manual: Boolean) {
        cancelScheduledListening()
        cancelAssistRecognition()
        if (!isListening) {
            return
        }
        if (recognizerMode == RecognizerMode.ROKID_ASSIST) {
            rokidAssistClient?.stopVoiceRecognition()
        }
        speechRecognizer?.stopListening()
        speechRecognizer?.cancel()
        isListening = false
        if (manual) {
            updateStatus(
                state = "已暂停",
                detail = "已停止聆听，点击按钮可重新开始",
            )
        }
        updateActionState()
    }

    private fun scheduleListening(delayMs: Long) {
        cancelScheduledListening()
        if (!speechReady || !rokidConnected || !wsConnected || awaitingAnswer || isSpeaking || !canStartVoiceCapture()) {
            return
        }
        mainHandler.postDelayed(restartListeningRunnable, delayMs)
    }

    private fun canStartVoiceCapture(): Boolean {
        val rokidInstance = rokid ?: return false
        return rokidConnected && wsConnected && (rokidInstance.isLocalMode() || aiSceneActive || !rokidInstance.supportsAiScene())
    }

    private fun handleRecognizedText(transcript: String) {
        if (rokid?.isLocalMode() == true) {
            handleLocalWakeWord(transcript.trim())
            return
        }
        submitVoiceQuestion(transcript)
    }

    private fun handleLocalWakeWord(transcript: String) {
        if (transcript.isBlank()) {
            scheduleListening(RETRY_LISTEN_DELAY_MS)
            return
        }

        if (wakeWordArmed) {
            if (!containsWakeWord(transcript)) {
                liveTranscriptText.text = "等待“Rockey”唤醒"
                scheduleListening(RETRY_LISTEN_DELAY_MS)
                return
            }

            wakeWordArmed = false
            appendSystemMessage("已通过唤醒词唤醒")
            updateStatus(
                state = "已唤醒",
                detail = "请继续说出你的问题",
            )

            val strippedQuestion = stripWakeWord(transcript)
            if (strippedQuestion.isBlank()) {
                liveTranscriptText.text = "已唤醒，请继续说问题"
                scheduleListening(200L)
                return
            }

            submitVoiceQuestion(strippedQuestion)
            return
        }

        submitVoiceQuestion(transcript)
    }

    private fun containsWakeWord(transcript: String): Boolean {
        val normalized = transcript.lowercase(Locale.ROOT)
        return WAKE_WORDS.any { wakeWord ->
            normalized.contains(wakeWord.lowercase(Locale.ROOT))
        }
    }

    private fun stripWakeWord(transcript: String): String {
        var result = transcript.trim()
        WAKE_WORDS.forEach { wakeWord ->
            result = result.replace(wakeWord, "", ignoreCase = true)
        }
        return result.trim(' ', '，', ',', '。', '.', '！', '!', '？', '?')
    }

    private fun cancelScheduledListening() {
        mainHandler.removeCallbacks(restartListeningRunnable)
    }

    private fun cancelAssistRecognition() {
        assistRecognitionPending = false
        assistRecognitionStartedAt = 0L
        mainHandler.removeCallbacks(assistRecognitionTimeoutRunnable)
    }

    private fun speakAnswer(answer: String, utteranceId: String = "openclaw-${System.currentTimeMillis()}") {
        Log.i(TAG, "speakAnswer: mode=$speakerMode ttsReady=$ttsReady utteranceId=$utteranceId")
        ensureAudioOutputReady()
        currentTtsTimeoutMs = estimateTtsTimeout(answer)
        when (speakerMode) {
            SpeakerMode.ROKID_BINDER -> {
                val started = rokidTtsClient?.speak(answer, utteranceId) == true
                if (!started) {
                    fallbackToSystemTts("Rokid TtsService 调用失败")
                    if (!speakWithSystemTts(answer, utteranceId)) {
                        pendingSpeech = PendingSpeech(answer, utteranceId)
                        Log.w(TAG, "speakAnswer: system TTS not ready, queued $utteranceId")
                        if (!ttsReady) {
                            appendSystemMessage("系统 TTS 初始化中，稍后自动播报")
                            return
                        }
                        handleTtsPlaybackStopped()
                    }
                }
            }

            SpeakerMode.SYSTEM_TTS -> {
                if (!speakWithSystemTts(answer, utteranceId)) {
                    pendingSpeech = PendingSpeech(answer, utteranceId)
                    Log.w(TAG, "speakAnswer: system TTS speak failed, queued $utteranceId")
                    handleTtsPlaybackStopped()
                }
            }

            SpeakerMode.NONE -> handleTtsPlaybackStopped()
        }
    }

    private fun speakWithSystemTts(answer: String, utteranceId: String): Boolean {
        val speaker = tts ?: return false
        speaker.stop()
        val result = speaker.speak(answer, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "speakWithSystemTts failed for $utteranceId")
        }
        return result != TextToSpeech.ERROR
    }

    private fun estimateTtsTimeout(text: String): Long {
        val estimated = 8000L + (text.length.coerceAtMost(400) * 90L)
        return estimated.coerceIn(10_000L, MAX_TTS_TIMEOUT_MS)
    }

    private fun ensureAudioOutputReady() {
        runCatching {
            val audioManager = getSystemService(AudioManager::class.java) ?: return
            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (maxMusic <= 0) {
                return
            }
            val minTarget = (maxMusic * 0.45f).toInt().coerceAtLeast(3)
            val currentMusic = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (currentMusic < minTarget) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, minTarget, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
        }.onFailure { error ->
            Log.w(TAG, "ensureAudioOutputReady failed", error)
        }
    }

    private fun stopSpeaking() {
        when (speakerMode) {
            SpeakerMode.ROKID_BINDER -> rokidTtsClient?.stop()
            SpeakerMode.SYSTEM_TTS -> tts?.stop()
            SpeakerMode.NONE -> Unit
        }
    }

    private fun submitVoiceQuestion(question: String) {
        val manager = huoyanManager
        if (manager == null || !rokidConnected || !wsConnected) {
            appendSystemMessage("当前还没准备好，请稍后再试")
            return
        }

        val normalizedQuestion = question.trim()
        if (normalizedQuestion.isBlank()) {
            reportAsrNone()
            return
        }

        awaitingAnswer = true
        reportAsrFinal(normalizedQuestion)
        appendUserMessage(normalizedQuestion)
        liveTranscriptText.text = "你: $normalizedQuestion"
        val forceRecapture = RECAPTURE_KEYWORDS.any { normalizedQuestion.contains(it, ignoreCase = true) }
        val shouldCapture = forceRecapture || !hasSentImageContext
        updateStatus(
            state = "思考中",
            detail = if (shouldCapture) {
                "正在拍照并把当前画面和问题发送给 OpenClaw Bridge..."
            } else {
                "正在复用上一帧画面并发送问题给 OpenClaw Bridge..."
            },
        )
        updateActionState()
        if (shouldCapture) {
            hasSentImageContext = true
            manager.captureAndAsk(normalizedQuestion.ifBlank { DEFAULT_VOICE_PROMPT })
        } else {
            manager.askAboutLatest(normalizedQuestion.ifBlank { DEFAULT_VOICE_PROMPT })
        }
    }

    private fun updateStatus(state: String, detail: String) {
        runOnUiThread {
            connectionStateText.text = state
            statusText.text = detail
        }
    }

    private fun updateActionState() {
        runOnUiThread {
            val ready = rokidConnected && wsConnected && !bootstrapInProgress && !awaitingAnswer && !isSpeaking
            reconnectButton.isEnabled = !bootstrapInProgress
            voiceButton.isEnabled = rokidConnected && wsConnected && !bootstrapInProgress
            voiceButton.text = when {
                bootstrapInProgress -> "连接中"
                isSpeaking -> "正在回答"
                awaitingAnswer -> "正在思考"
                isListening -> "正在聆听"
                rokid?.supportsAiScene() == true && !aiSceneActive -> "等待AI键"
                ready -> "点击重听"
                else -> "等待连接"
            }
            setViewAlpha(voiceButton, if (voiceButton.isEnabled) 1f else 0.55f)
            setViewAlpha(reconnectButton, if (reconnectButton.isEnabled) 1f else 0.7f)
        }
    }

    private fun appendUserMessage(text: String) {
        appendConversationLine("你", text)
    }

    private fun appendAssistantMessage(text: String) {
        appendConversationLine("OpenClaw", text)
    }

    private fun appendSystemMessage(text: String) {
        appendConversationLine("系统", text)
    }

    private fun appendConversationLine(role: String, text: String) {
        runOnUiThread {
            val prefix = if (conversationText.text.isNullOrEmpty()) "" else "\n\n"
            conversationText.append(prefix + role + "\n" + text.trim())
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun shouldMirrorStatusToChat(message: String): Boolean {
        return listOf("连接", "收到", "失败", "关闭", "上传").any { keyword ->
            message.contains(keyword)
        }
    }

    private fun onBootstrapFailed(message: String) {
        bootstrapInProgress = false
        rokidConnected = false
        wsConnected = false
        awaitingAnswer = false
        backgroundSyncInFlight = false
        refreshFrameSyncScheduling()
        stopListening(manual = false)
        updateStatus(
            state = "启动失败",
            detail = message,
        )
        liveTranscriptText.text = "语音模式尚未就绪"
        appendSystemMessage(message)
        updateActionState()
    }

    private fun hasAllNeededPermissions(): Boolean {
        return neededPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNeededPermissions() {
        val needRequest = neededPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (needRequest.isEmpty()) {
            bootstrapSession(forceReconnect = false)
            return
        }

        updateStatus(
            state = "等待权限",
            detail = "请先授予相机、麦克风和设备连接权限",
        )
        permissionLauncher.launch(needRequest.toTypedArray())
    }

    private fun neededPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions += Manifest.permission.BLUETOOTH
            permissions += Manifest.permission.BLUETOOTH_ADMIN
        }

        return permissions
    }

    private fun shouldRunAutoFrameSync(): Boolean {
        return rokidConnected &&
            wsConnected &&
            !bootstrapInProgress &&
            !awaitingAnswer &&
            !isListening &&
            !isSpeaking &&
            !backgroundSyncInFlight &&
            hasCameraPermission() &&
            rokid?.isConnected() == true
    }

    private fun runAutoFrameSync() {
        val manager = huoyanManager ?: return
        if (!shouldRunAutoFrameSync()) {
            return
        }
        backgroundSyncInFlight = true
        val started = manager.syncLatestFrame(AUTO_FRAME_SYNC_PROMPT)
        if (!started) {
            backgroundSyncInFlight = false
            refreshFrameSyncScheduling()
        }
    }

    private fun refreshFrameSyncScheduling() {
        if (rokidConnected && wsConnected && hasCameraPermission()) {
            frameSyncScheduler.start()
            frameSyncScheduler.reset()
            if (!autoFrameSyncAnnounced) {
                appendSystemMessage("后台画面同步已开启：每 1 分钟抓拍一张并同步到 OpenClaw")
                autoFrameSyncAnnounced = true
            }
        } else {
            frameSyncScheduler.stop()
            autoFrameSyncAnnounced = false
        }
    }

    private fun setViewAlpha(view: View, alpha: Float) {
        view.alpha = alpha
    }

    override fun onDestroy() {
        frameSyncScheduler.stop()
        backgroundSyncInFlight = false
        cancelScheduledListening()
        cancelAssistRecognition()
        stopListening(manual = false)
        if (aiSceneActive) {
            rokid?.sendExitEvent().logIfUnexpected("sendExitEvent")
        }
        speechRecognizer?.destroy()
        stopSpeaking()
        mainHandler.removeCallbacks(ttsTimeoutRunnable)
        tts?.shutdown()
        rokidAssistClient?.release()
        rokidTtsClient?.release()
        listeningCueTone?.release()
        listeningCueTone = null
        huoyanManager?.release()
        rokid?.destroy()
        super.onDestroy()
    }

    private fun handleAssistMessage(message: AssistMessage) {
        val infoType = message.infoType.orEmpty()
        val payload = message.message?.trim().orEmpty()
        Log.i(TAG, "Assist message: $message")
        if (payload.isNotBlank()) {
            appendSystemMessage("Assist[$infoType]: ${payload.take(120)}")
        } else {
            appendSystemMessage("Assist[$infoType] 已回调")
        }
        maybeConsumeAssistTranscript(
            source = infoType,
            payload = payload,
        )
    }

    private fun handleAssistData(channel: String?, extra: String?, data: ByteArray?) {
        val preview = decodeAssistPayload(data)
        Log.i(TAG, "Assist data channel=$channel extra=$extra preview=$preview")
        val source = listOfNotNull(channel, extra).joinToString("/")
        val display = preview?.take(120)
        appendSystemMessage(
            if (display.isNullOrBlank()) {
                "AssistData[$source] 已回调"
            } else {
                "AssistData[$source]: $display"
            },
        )
        maybeConsumeAssistTranscript(source = source, payload = preview)
    }

    private fun decodeAssistPayload(data: ByteArray?): String? {
        if (data == null || data.isEmpty()) {
            return null
        }
        return try {
            String(data, StandardCharsets.UTF_8).trim().ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun playListeningCue() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastListeningCueAt < 500L) {
            return
        }
        lastListeningCueAt = now
        val tone =
            listeningCueTone
                ?: runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }
                    .getOrNull()
                    ?.also { listeningCueTone = it }
                ?: return
        runCatching {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
        }.onFailure { error ->
            Log.w(TAG, "Failed to play listening cue", error)
        }
    }

    private fun maybeConsumeAssistTranscript(source: String, payload: String?) {
        if (!assistRecognitionPending || payload.isNullOrBlank()) {
            return
        }
        val parsedPayload = parseAssistPayloadObject(payload)
        if (shouldIgnoreAssistPayload(source = source, payload = payload, json = parsedPayload)) {
            Log.i(TAG, "Assist payload ignored for ASR source=$source payload=$payload")
            return
        }

        val transcript = extractAssistTranscript(payload, parsedPayload) ?: return
        if (transcript == lastHeardText) {
            return
        }

        cancelAssistRecognition()
        isListening = false
        lastHeardText = transcript
        liveTranscriptText.text = transcript
        appendSystemMessage("Rokid ASR 命中: $transcript")
        handleRecognizedText(transcript)
        updateActionState()
    }

    private fun extractAssistTranscript(payload: String, json: JSONObject? = null): String? {
        val direct = payload.trim()
        if (looksLikeTranscript(direct)) {
            return direct
        }
        return (json ?: parseAssistPayloadObject(payload))?.let(::extractTranscriptFromJsonObject)
    }

    private fun parseAssistPayloadObject(payload: String): JSONObject? {
        val candidate = payload.trim()
        if (!candidate.startsWith("{") || !candidate.endsWith("}")) {
            return null
        }

        runCatching { JSONObject(candidate) }
            .getOrNull()
            ?.let { return it }

        val sanitized = sanitizeAssistJson(candidate)
        if (sanitized == candidate) {
            return null
        }

        runCatching { JSONObject(sanitized) }
            .onFailure { error ->
                Log.w(TAG, "Assist payload JSON sanitize failed: $candidate", error)
            }
            .getOrNull()
            ?.let { return it }

        return null
    }

    private fun sanitizeAssistJson(payload: String): String {
        var sanitized = payload
        sanitized = sanitized.replace(Regex("\"([A-Za-z0-9_]+):"), "\"$1\":")
        sanitized = sanitized.replace(
            Regex("([\\{,]\\s*)([A-Za-z_][A-Za-z0-9_]*)\"\\s*:"),
            "$1\"$2\":",
        )
        sanitized = sanitized.replace(
            Regex("([\\{,]\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*:"),
            "$1\"$2\":",
        )
        sanitized = sanitized.replace(Regex("\\bfase\\b", RegexOption.IGNORE_CASE), "false")
        sanitized = sanitized.replace(Regex("\\bflase\\b", RegexOption.IGNORE_CASE), "false")
        sanitized = sanitized.replace(Regex("\\bture\\b", RegexOption.IGNORE_CASE), "true")
        return sanitized
    }

    private fun shouldIgnoreAssistPayload(source: String, payload: String, json: JSONObject?): Boolean {
        val normalizedSource = source.lowercase(Locale.ROOT)
        if (normalizedSource.contains("status") || normalizedSource.contains("scene")) {
            return true
        }

        val normalizedPayload = payload.lowercase(Locale.ROOT)
        if (normalizedPayload.contains("bluetooth") || normalizedPayload.contains("gatt")) {
            return true
        }

        val type = json?.optString("type").orEmpty().lowercase(Locale.ROOT)
        if (
            type.contains("status") ||
            type.contains("bluetooth") ||
            type.contains("gatt") ||
            type == "control_mobile_assistant"
        ) {
            return true
        }

        val data = json?.optJSONObject("data")
        if (data != null && data.has("action")) {
            return true
        }

        return false
    }

    private fun extractTranscriptFromJsonObject(json: JSONObject): String? {
        val preferredKeys =
            listOf(
                "value",
                "text",
                "transcript",
                "query",
                "result",
                "content",
                "message",
            )
        preferredKeys.forEach { key ->
            if (!json.isNull(key)) {
                when (val value = json.opt(key)) {
                    is String -> if (looksLikeTranscript(value)) return value.trim()
                    is JSONObject -> extractTranscriptFromJsonObject(value)?.let { return it }
                    is JSONArray -> extractTranscriptFromJsonArray(value)?.let { return it }
                }
            }
        }

        val iterator = json.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            when (val value = json.opt(key)) {
                is JSONObject -> extractTranscriptFromJsonObject(value)?.let { return it }
                is JSONArray -> extractTranscriptFromJsonArray(value)?.let { return it }
                is String -> if (looksLikeTranscriptKey(key) && looksLikeTranscript(value)) return value.trim()
            }
        }
        return null
    }

    private fun extractTranscriptFromJsonArray(array: JSONArray): String? {
        for (index in 0 until array.length()) {
            when (val value = array.opt(index)) {
                is JSONObject -> extractTranscriptFromJsonObject(value)?.let { return it }
                is JSONArray -> extractTranscriptFromJsonArray(value)?.let { return it }
                is String -> if (looksLikeTranscript(value)) return value.trim()
            }
        }
        return null
    }

    private fun looksLikeTranscript(value: String): Boolean {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            return false
        }
        if (normalized.startsWith("{") || normalized.startsWith("[")) {
            return false
        }
        if (normalized.contains('_') && normalized.none { it.isWhitespace() }) {
            return false
        }
        return normalized !in setOf("start", "stop", "prepare", "running", "resume", "pause")
    }

    private fun looksLikeTranscriptKey(key: String): Boolean {
        val normalized = key.trim().lowercase(Locale.ROOT)
        if (normalized.isBlank()) {
            return false
        }
        if (
            normalized in
            setOf(
                "value",
                "text",
                "transcript",
                "query",
                "result",
                "content",
                "message",
                "question",
                "utterance",
                "prompt",
                "input",
                "asr",
                "nlp",
            )
        ) {
            return true
        }
        return listOf("text", "transcript", "query", "speech", "utterance", "question", "content").any {
            normalized.contains(it)
        }
    }

    private inner class VoiceRecognitionListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isListening = true
            updateStatus(
                state = "聆听中",
                detail = "请直接说话",
            )
            playListeningCue()
            updateActionState()
        }

        override fun onBeginningOfSpeech() {
            liveTranscriptText.text = "正在记录..."
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            isListening = false
            updateStatus(
                state = "理解中",
                detail = "正在理解你的问题...",
            )
            updateActionState()
        }

        override fun onError(error: Int) {
            isListening = false
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "没有听清，再说一次"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到语音，继续等待"
                SpeechRecognizer.ERROR_AUDIO -> "麦克风暂时不可用"
                SpeechRecognizer.ERROR_CLIENT -> "语音会话已中断"
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                -> "语音识别网络异常"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别器忙碌，稍后重试"
                SpeechRecognizer.ERROR_SERVER -> "语音识别服务异常"
                else -> "语音识别失败 ($error)"
            }
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> reportAsrNone()
                SpeechRecognizer.ERROR_CLIENT -> Unit
                else -> reportAsrError()
            }
            liveTranscriptText.text = message
            if (!awaitingAnswer && wsConnected && rokidConnected) {
                scheduleListening(RETRY_LISTEN_DELAY_MS)
            }
            updateActionState()
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val transcript =
                results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()

            if (transcript.isBlank()) {
                reportAsrNone()
                liveTranscriptText.text = "没有听清，再说一次"
                scheduleListening(RETRY_LISTEN_DELAY_MS)
                updateActionState()
                return
            }

            lastHeardText = transcript
            handleRecognizedText(transcript)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial =
                partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
            if (partial.isNotBlank()) {
                lastHeardText = partial
                liveTranscriptText.text = partial
                if (!wakeWordArmed || rokid?.isLocalMode() != true) {
                    reportAsrPartial(partial)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
