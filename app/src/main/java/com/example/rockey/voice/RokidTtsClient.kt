package com.example.rockey.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.rokid.os.sprite.tts.ITtsListener
import com.rokid.os.sprite.tts.ITtsServer

class RokidTtsClient(
    context: Context,
    private val listener: Listener,
) {
    companion object {
        private const val TAG = "RokidTtsClient"
        const val ASSIST_PACKAGE = "com.rokid.os.sprite.assistserver"
        const val ACTION_TTS_SERVICE = "com.rokid.os.sprite.tts.TTS_SERVICE"
    }

    interface Listener {
        fun onBound()

        fun onBindingFailed(reason: String)

        fun onDisconnected(reason: String)

        fun onPlaybackStarted(utteranceId: String)

        fun onPlaybackStopped(utteranceId: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isBound = false
    private var remote: ITtsServer? = null
    private var activeUtteranceId: String? = null

    private val ttsListener =
        object : ITtsListener.Stub() {
            override fun onTtsStart(utteranceId: String?) {
                val id = utteranceId ?: activeUtteranceId
                if (id == null) {
                    Log.w(TAG, "onTtsStart without utteranceId")
                    return
                }
                mainHandler.post { listener.onPlaybackStarted(id) }
            }

            override fun onTtsStop(utteranceId: String?) {
                val id = utteranceId ?: activeUtteranceId
                if (id == null) {
                    Log.w(TAG, "onTtsStop without utteranceId")
                    return
                }
                activeUtteranceId = null
                mainHandler.post { listener.onPlaybackStopped(id) }
            }
        }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = ITtsServer.Stub.asInterface(service)
                if (binder == null) {
                    isBound = false
                    remote = null
                    mainHandler.post { listener.onBindingFailed("ITtsServer binder 为空") }
                    return
                }

                remote = binder
                isBound = true
                mainHandler.post { listener.onBound() }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                remote = null
                activeUtteranceId = null
                isBound = false
                mainHandler.post { listener.onDisconnected("服务连接已断开") }
            }

            override fun onNullBinding(name: ComponentName?) {
                remote = null
                activeUtteranceId = null
                isBound = false
                mainHandler.post { listener.onBindingFailed("服务返回空绑定") }
            }

            override fun onBindingDied(name: ComponentName?) {
                remote = null
                activeUtteranceId = null
                isBound = false
                mainHandler.post { listener.onDisconnected("服务绑定已失效") }
            }
        }

    fun connect(): Boolean {
        if (isBound || remote != null) {
            return true
        }

        val intent =
            Intent(ACTION_TTS_SERVICE).apply {
                setPackage(ASSIST_PACKAGE)
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

    fun speak(message: String, utteranceId: String): Boolean {
        val binder = remote ?: return false
        return try {
            activeUtteranceId?.let(binder::stopTtsPlay)
            activeUtteranceId = utteranceId
            binder.playTtsMsg(message, utteranceId, ttsListener)
            true
        } catch (error: Exception) {
            remote = null
            activeUtteranceId = null
            isBound = false
            mainHandler.post {
                listener.onDisconnected(error.message ?: "调用 Rokid TtsService 失败")
            }
            false
        }
    }

    fun stop() {
        val binder = remote ?: return
        val utteranceId = activeUtteranceId ?: return
        try {
            binder.stopTtsPlay(utteranceId)
        } catch (_: RemoteException) {
        } finally {
            activeUtteranceId = null
        }
    }

    fun release() {
        stop()
        remote = null
        activeUtteranceId = null
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
}
