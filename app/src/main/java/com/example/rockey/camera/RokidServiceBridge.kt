package com.example.rockey.camera

import android.util.Log
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps

class RokidServiceBridge {
    companion object {
        private const val TAG = "RokidServiceBridge"
    }

    private val bridge = CXRServiceBridge()
    private var started = false

    interface BridgeListener {
        fun onConnected(name: String, deviceType: Int)
        fun onDisconnected()
    }

    fun start(listener: BridgeListener? = null) {
        if (started) {
            return
        }
        bridge.setStatusListener(object : CXRServiceBridge.StatusListener {
            override fun onConnected(name: String, deviceType: Int) {
                Log.i(TAG, "connected name=$name type=$deviceType")
                listener?.onConnected(name, deviceType)
            }

            override fun onDisconnected() {
                Log.i(TAG, "disconnected")
                listener?.onDisconnected()
            }

            override fun onARTCStatus(bitrate: Float, ready: Boolean) {
                Log.d(TAG, "artc bitrate=$bitrate ready=$ready")
            }

            override fun onRokidAccountChanged(account: String) {
                Log.i(TAG, "rokid account changed: $account")
            }
        })
        started = true
    }

    fun subscribe(topic: String, callback: (topic: String, caps: Caps, payload: ByteArray?) -> Unit): Int {
        return bridge.subscribe(topic, object : CXRServiceBridge.MsgCallback {
            override fun onReceive(topicName: String, caps: Caps, data: ByteArray?) {
                callback(topicName, caps, data)
            }
        })
    }

    fun sendMessage(topic: String, caps: Caps = Caps(), payload: ByteArray? = null): Int {
        return if (payload == null) {
            bridge.sendMessage(topic, caps)
        } else {
            bridge.sendMessage(topic, caps, payload)
        }
    }

    fun disconnect() {
        bridge.disconnectCXRDevice()
    }
}
