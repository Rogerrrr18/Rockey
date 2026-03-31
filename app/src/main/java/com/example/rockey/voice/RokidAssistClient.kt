package com.example.rockey.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import com.rokid.os.sprite.assist.basic.AssistMessage
import com.rokid.os.sprite.assist.basic.RegisterResult
import com.rokid.os.sprite.assist.client.IAssistClient
import com.rokid.os.sprite.assist.server.IAssistServer
import org.json.JSONObject

class RokidAssistClient(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onBound()

        fun onRegistered(result: RegisterResult?)

        fun onBindingFailed(reason: String)

        fun onDisconnected(reason: String)

        fun onMessageReceived(message: AssistMessage)

        fun onDataReceived(channel: String?, extra: String?, data: ByteArray?)
    }

    companion object {
        const val ASSIST_PACKAGE = "com.rokid.os.sprite.assistserver"
        private const val SERVICE_CLASS = "com.rokid.os.sprite.assist.MasterAssistService"
        private const val CONTROL_TYPE_MOBILE_ASSISTANT = "control_mobile_assistant"
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isBound = false
    private var isRegistered = false
    private var remote: IAssistServer? = null

    private val assistClient =
        object : IAssistClient.Stub() {
            override fun onRegisterResult(result: RegisterResult?) {
                isRegistered = true
                mainHandler.post { listener.onRegistered(result) }
            }

            override fun onMessageReceive(message: AssistMessage?): Boolean {
                if (message == null) {
                    return false
                }
                mainHandler.post { listener.onMessageReceived(message) }
                return true
            }

            override fun onDataReceive(channel: String?, extra: String?, data: ByteArray?) {
                mainHandler.post { listener.onDataReceived(channel, extra, data) }
            }
        }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = IAssistServer.Stub.asInterface(service)
                if (binder == null) {
                    remote = null
                    isBound = false
                    isRegistered = false
                    mainHandler.post { listener.onBindingFailed("IAssistServer binder 为空") }
                    return
                }

                remote = binder
                isBound = true
                mainHandler.post { listener.onBound() }
                registerClient()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                remote = null
                isBound = false
                isRegistered = false
                mainHandler.post { listener.onDisconnected("服务连接已断开") }
            }

            override fun onNullBinding(name: ComponentName?) {
                remote = null
                isBound = false
                isRegistered = false
                mainHandler.post { listener.onBindingFailed("服务返回空绑定") }
            }

            override fun onBindingDied(name: ComponentName?) {
                remote = null
                isBound = false
                isRegistered = false
                mainHandler.post { listener.onDisconnected("服务绑定已失效") }
            }
        }

    fun connect(): Boolean {
        if (isBound || remote != null) {
            return true
        }

        val intent =
            Intent().apply {
                component = ComponentName(ASSIST_PACKAGE, SERVICE_CLASS)
            }

        return try {
            val bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                mainHandler.post { listener.onBindingFailed("bindService 返回 false") }
            }
            bound
        } catch (error: Exception) {
            mainHandler.post {
                listener.onBindingFailed(error.message ?: error.javaClass.simpleName)
            }
            false
        }
    }

    fun isReady(): Boolean = isBound && remote != null && isRegistered

    fun startVoiceRecognition(): Boolean = sendControlAction(ACTION_START)

    fun stopVoiceRecognition(): Boolean = sendControlAction(ACTION_STOP)

    fun release() {
        if (isRegistered) {
            try {
                remote?.unRegisterClient(appContext.packageName)
            } catch (_: RemoteException) {
            }
        }
        remote = null
        isRegistered = false
        if (!isBound) {
            return
        }
        try {
            appContext.unbindService(connection)
        } catch (_: IllegalArgumentException) {
        } finally {
            isBound = false
        }
    }

    private fun registerClient() {
        val binder = remote ?: return
        try {
            binder.registerClient(appContext.packageName, assistClient)
        } catch (error: RemoteException) {
            remote = null
            isBound = false
            isRegistered = false
            mainHandler.post {
                listener.onDisconnected(error.message ?: "registerClient 调用失败")
            }
        }
    }

    private fun sendControlAction(action: String): Boolean {
        val binder = remote ?: return false
        if (!isRegistered) {
            return false
        }

        val json =
            JSONObject()
                .put("type", CONTROL_TYPE_MOBILE_ASSISTANT)
                .put("data", JSONObject().put("action", action))
                .toString()

        return try {
            binder.controlMsgJson(appContext.packageName, json)
            true
        } catch (error: RemoteException) {
            remote = null
            isBound = false
            isRegistered = false
            mainHandler.post {
                listener.onDisconnected(error.message ?: "controlMsgJson 调用失败")
            }
            false
        }
    }
}
