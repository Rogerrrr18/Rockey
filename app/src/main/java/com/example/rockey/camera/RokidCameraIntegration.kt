package com.example.rockey.camera

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import com.rokid.arsdk.connection.DeviceInfo
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.extend.callbacks.PhotoResultCallback
import com.rokid.cxr.client.extend.listeners.AiEventListener
import com.rokid.cxr.client.extend.listeners.AudioStreamListener
import com.rokid.cxr.client.utils.ValueUtil
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RokidCameraIntegration(
    context: Context,
) {
    companion object {
        private const val TAG = "RokidIntegration"
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
        private const val DEFAULT_QUALITY = 90
        private const val CAMERA_TIMEOUT_MS = 8000L
        private const val LOCAL_DEVICE_NAME = "Rokid Glasses (Local Device)"
        private const val LOCAL_DEVICE_ADDRESS = "local:camera0"
        private const val DEFAULT_AUDIO_STREAM_TYPE = "AI_assistant"
        private const val DEFAULT_AUDIO_CODEC_TYPE = 2
    }

    private enum class RuntimeMode {
        LOCAL_CAMERA,
        CXR_BLUETOOTH,
    }

    interface AiSceneListener {
        fun onAiKeyDown()
        fun onAiKeyUp()
        fun onAiExit()
    }

    interface CompanionAudioListener {
        fun onStart(codecType: Int, streamType: String?)
        fun onAudio(data: ByteArray, offset: Int, length: Int)
    }

    private val appContext = context.applicationContext
    private val cxrApi = CxrApi.getInstance()
    private val bondedDevices = linkedMapOf<String, BluetoothDevice>()
    private val runtimeMode = detectRuntimeMode()
    private var initialized = false
    private var connectedDevice: DeviceInfo? = null
    private var aiSceneListener: AiSceneListener? = null
    private var companionAudioListener: CompanionAudioListener? = null

    private val internalAiEventListener =
        object : AiEventListener {
            override fun onAiKeyDown() {
                aiSceneListener?.onAiKeyDown()
            }

            override fun onAiKeyUp() {
                aiSceneListener?.onAiKeyUp()
            }

            override fun onAiExit() {
                aiSceneListener?.onAiExit()
            }
        }

    private val internalAudioStreamListener =
        object : AudioStreamListener {
            override fun onStartAudioStream(codecType: Int, streamType: String?) {
                companionAudioListener?.onStart(codecType, streamType)
            }

            override fun onAudioStream(data: ByteArray?, offset: Int, length: Int) {
                val bytes = data ?: return
                companionAudioListener?.onAudio(bytes, offset, length)
            }
        }

    fun init(): Boolean {
        initialized = true
        Log.i(TAG, "Initialized in mode=$runtimeMode")
        return true
    }

    fun isLocalMode(): Boolean = runtimeMode == RuntimeMode.LOCAL_CAMERA

    fun supportsAiScene(): Boolean = runtimeMode == RuntimeMode.CXR_BLUETOOTH

    fun startScan(
        onScanResult: (List<DeviceInfo>) -> Unit,
        onScanError: (Int, String) -> Unit,
    ) {
        if (!initialized) {
            onScanError(-1, "SDK not initialized")
            return
        }

        if (runtimeMode == RuntimeMode.LOCAL_CAMERA) {
            onScanResult(
                listOf(
                    DeviceInfo(
                        name = LOCAL_DEVICE_NAME,
                        address = LOCAL_DEVICE_ADDRESS,
                    ),
                ),
            )
            return
        }

        if (!hasBluetoothConnectPermission()) {
            onScanError(-1, "缺少蓝牙连接权限")
            return
        }

        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            onScanError(-1, "当前设备不支持蓝牙")
            return
        }

        try {
            val devices = collectBondedDevices(adapter)
            if (devices.isEmpty()) {
                onScanError(-1, "未找到已配对的 Rokid 眼镜，请先在系统蓝牙中完成配对")
                return
            }
            onScanResult(devices)
        } catch (e: SecurityException) {
            onScanError(-1, "蓝牙权限不足: ${e.message}")
        }
    }

    fun connect(
        device: DeviceInfo,
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onConnectFailed: (Int, String) -> Unit,
    ) {
        if (!initialized) {
            onConnectFailed(-1, "SDK not initialized")
            return
        }

        if (runtimeMode == RuntimeMode.LOCAL_CAMERA) {
            if (!hasCameraPermission()) {
                onConnectFailed(-1, "缺少相机权限")
                return
            }
            connectedDevice = device
            onConnected()
            return
        }

        val bluetoothDevice = bondedDevices[device.address]
        if (bluetoothDevice == null) {
            onConnectFailed(-1, "未找到目标蓝牙设备: ${device.address}")
            return
        }

        cxrApi.initBluetooth(
            appContext,
            bluetoothDevice,
            object : BluetoothStatusCallback {
                override fun onConnectionInfo(
                    name: String,
                    address: String,
                    uuid: String,
                    deviceType: Int,
                ) {
                    Log.i(TAG, "Connection info: name=$name address=$address uuid=$uuid type=$deviceType")
                }

                override fun onConnected() {
                    connectedDevice = device
                    onConnected()
                }

                override fun onDisconnected() {
                    connectedDevice = null
                    onDisconnected()
                }

                override fun onFailed(error: ValueUtil.CxrBluetoothErrorCode) {
                    connectedDevice = null
                    onConnectFailed(error.getErrorCode(), error.name)
                }
            },
        )
    }

    fun isConnected(): Boolean {
        return if (runtimeMode == RuntimeMode.LOCAL_CAMERA) {
            connectedDevice != null
        } else {
            connectedDevice != null && cxrApi.isBluetoothConnected()
        }
    }

    fun takePhoto(
        savePath: String,
        onCaptured: (String, ByteArray) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!isConnected()) {
            onError("未连接到 Rokid 设备")
            return
        }

        if (runtimeMode == RuntimeMode.LOCAL_CAMERA) {
            captureWithLocalCamera(savePath, onCaptured, onError)
            return
        }

        val callback = object : PhotoResultCallback {
            override fun onPhotoResult(status: ValueUtil.CxrStatus, data: ByteArray?) {
                val imageData = data
                if (status != ValueUtil.CxrStatus.RESPONSE_SUCCEED || imageData == null || imageData.isEmpty()) {
                    onError("拍照失败: ${status.name}")
                    return
                }

                try {
                    writeImage(savePath, imageData)
                    onCaptured(savePath, imageData)
                } catch (e: Exception) {
                    onError("保存失败: ${e.message}")
                }
            }
        }

        var status = cxrApi.takeGlassPhoto(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_QUALITY, callback)
        if (status == ValueUtil.CxrStatus.REQUEST_SUCCEED) {
            return
        }

        val openStatus = cxrApi.openGlassCamera(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_QUALITY)
        if (openStatus != ValueUtil.CxrStatus.REQUEST_SUCCEED) {
            onError("打开眼镜相机失败: ${openStatus.name}")
            return
        }

        status = cxrApi.takeGlassPhoto(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_QUALITY, callback)
        if (status != ValueUtil.CxrStatus.REQUEST_SUCCEED) {
            onError("拍照请求失败: ${status.name}")
        }
    }

    fun disconnect() {
        setAiEventListener(false, null)
        setAudioStreamListener(false, null)
        if (runtimeMode == RuntimeMode.CXR_BLUETOOTH && connectedDevice != null) {
            cxrApi.deinitBluetooth()
        }
        connectedDevice = null
    }

    fun destroy() {
        disconnect()
        bondedDevices.clear()
        initialized = false
    }

    fun setAiEventListener(
        set: Boolean,
        listener: AiSceneListener?,
    ) {
        if (runtimeMode != RuntimeMode.CXR_BLUETOOTH) {
            aiSceneListener = null
            return
        }
        aiSceneListener = if (set) listener else null
        cxrApi.setAiEventListener(if (set) internalAiEventListener else null)
    }

    fun setAudioStreamListener(
        set: Boolean,
        listener: CompanionAudioListener?,
    ) {
        if (runtimeMode != RuntimeMode.CXR_BLUETOOTH) {
            companionAudioListener = null
            return
        }
        companionAudioListener = if (set) listener else null
        cxrApi.setAudioStreamListener(if (set) internalAudioStreamListener else null)
    }

    fun setPhotoParams(
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
    ): ValueUtil.CxrStatus? = runCxrCommand { cxrApi.setPhotoParams(width, height) }

    fun openAudioRecord(
        codecType: Int = DEFAULT_AUDIO_CODEC_TYPE,
        streamType: String = DEFAULT_AUDIO_STREAM_TYPE,
    ): ValueUtil.CxrStatus? = runCxrCommand { cxrApi.openAudioRecord(codecType, streamType) }

    fun closeAudioRecord(streamType: String = DEFAULT_AUDIO_STREAM_TYPE): ValueUtil.CxrStatus? =
        runCxrCommand { cxrApi.closeAudioRecord(streamType) }

    fun notifyAiStart(): ValueUtil.CxrStatus? = runCxrCommand { cxrApi.notifyAiStart() }

    fun sendExitEvent(): ValueUtil.CxrStatus? = runCxrCommand { cxrApi.sendExitEvent() }

    fun sendAsrContent(content: String): ValueUtil.CxrStatus? =
        runCxrCommand { cxrApi.sendAsrContent(content) }

    fun notifyAsrNone(): ValueUtil.CxrStatus? = runCxrCommand { cxrApi.notifyAsrNone() }

    fun notifyAsrError(): ValueUtil.CxrStatus? = runCxrCommand { cxrApi.notifyAsrError() }

    fun notifyAsrEnd(): ValueUtil.CxrStatus? = runCxrCommand { cxrApi.notifyAsrEnd() }

    fun sendTtsContent(content: String): ValueUtil.CxrStatus? =
        runCxrCommand { cxrApi.sendTtsContent(content) }

    fun notifyTtsAudioFinished(): ValueUtil.CxrStatus? =
        runCxrCommand { cxrApi.notifyTtsAudioFinished() }

    fun notifyNoNetwork(): ValueUtil.CxrStatus? = runCxrCommand { cxrApi.notifyNoNetwork() }

    fun notifyPicUploadError(): ValueUtil.CxrStatus? =
        runCxrCommand { cxrApi.notifyPicUploadError() }

    fun notifyAiError(): ValueUtil.CxrStatus? = runCxrCommand { cxrApi.notifyAiError() }

    @SuppressLint("MissingPermission")
    private fun collectBondedDevices(adapter: BluetoothAdapter): List<DeviceInfo> {
        bondedDevices.clear()
        return adapter.bondedDevices
            .filterNotNull()
            .sortedBy { it.name ?: it.address }
            .map { device ->
                bondedDevices[device.address] = device
                DeviceInfo(
                    name = device.name ?: "Unknown Bluetooth Device",
                    address = device.address,
                )
            }
    }

    @SuppressLint("MissingPermission")
    private fun captureWithLocalCamera(
        savePath: String,
        onCaptured: (String, ByteArray) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!hasCameraPermission()) {
            onError("缺少相机权限")
            return
        }

        val cameraManager = appContext.getSystemService(CameraManager::class.java)
        if (cameraManager == null) {
            onError("无法获取 CameraManager")
            return
        }

        val cameraId = selectLocalCameraId(cameraManager)
        if (cameraId == null) {
            onError("当前设备没有可用相机")
            return
        }

        val captureThread = HandlerThread("rokid-local-camera").apply { start() }
        val captureHandler = Handler(captureThread.looper)

        val openLatch = CountDownLatch(1)
        val sessionLatch = CountDownLatch(1)
        val imageLatch = CountDownLatch(1)
        val failureRef = AtomicReference<String?>(null)
        val cameraRef = AtomicReference<CameraDevice?>()
        val sessionRef = AtomicReference<CameraCaptureSession?>()
        val imageDataRef = AtomicReference<ByteArray?>()

        try {
            val captureSize = selectCaptureSize(cameraManager, cameraId)
            val imageReader = ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.JPEG, 1)

            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    imageDataRef.set(bytes)
                } catch (e: Exception) {
                    failureRef.compareAndSet(null, "读取图片失败: ${e.message}")
                } finally {
                    image.close()
                    imageLatch.countDown()
                }
            }, captureHandler)

            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraRef.set(camera)
                        openLatch.countDown()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        failureRef.compareAndSet(null, "相机已断开")
                        camera.close()
                        openLatch.countDown()
                        sessionLatch.countDown()
                        imageLatch.countDown()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        failureRef.compareAndSet(null, "打开相机失败: $error")
                        camera.close()
                        openLatch.countDown()
                        sessionLatch.countDown()
                        imageLatch.countDown()
                    }
                },
                captureHandler,
            )

            if (!openLatch.await(CAMERA_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                onError("打开相机超时")
                return
            }

            val cameraDevice = cameraRef.get()
            if (cameraDevice == null) {
                onError(failureRef.get() ?: "相机打开失败")
                return
            }

            cameraDevice.createCaptureSession(
                listOf(imageReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        sessionRef.set(session)
                        sessionLatch.countDown()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        failureRef.compareAndSet(null, "相机会话配置失败")
                        sessionLatch.countDown()
                    }
                },
                captureHandler,
            )

            if (!sessionLatch.await(CAMERA_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                onError("配置相机会话超时")
                return
            }

            val session = sessionRef.get()
            if (session == null) {
                onError(failureRef.get() ?: "相机会话配置失败")
                return
            }

            val captureRequest =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(imageReader.surface)
                    set(CaptureRequest.JPEG_ORIENTATION, 270)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                }.build()

            session.capture(
                captureRequest,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        Log.i(TAG, "Local camera capture completed")
                    }
                },
                captureHandler,
            )

            if (!imageLatch.await(CAMERA_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                onError("等待图片返回超时")
                return
            }

            val bytes = imageDataRef.get()
            if (bytes == null || bytes.isEmpty()) {
                onError(failureRef.get() ?: "本地相机未返回有效图片")
                return
            }

            writeImage(savePath, bytes)
            onCaptured(savePath, bytes)
            imageReader.close()
        } catch (e: Exception) {
            onError("本地拍照失败: ${e.message}")
        } finally {
            try {
                sessionRef.get()?.close()
            } catch (_: Exception) {
            }
            try {
                cameraRef.get()?.close()
            } catch (_: Exception) {
            }
            captureThread.quitSafely()
        }
    }

    private fun selectCaptureSize(cameraManager: CameraManager, cameraId: String): Size {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)
            .orEmpty()
        if (jpegSizes.isEmpty()) {
            return Size(DEFAULT_WIDTH, DEFAULT_HEIGHT)
        }

        val exactOrLarger = jpegSizes
            .filter { it.width >= DEFAULT_WIDTH && it.height >= DEFAULT_HEIGHT }
            .sortedBy { (it.width - DEFAULT_WIDTH) + (it.height - DEFAULT_HEIGHT) }
            .firstOrNull()
        return exactOrLarger ?: jpegSizes.maxByOrNull { it.width * it.height } ?: Size(DEFAULT_WIDTH, DEFAULT_HEIGHT)
    }

    private fun selectLocalCameraId(cameraManager: CameraManager): String? {
        val preferred = cameraManager.cameraIdList.firstOrNull { cameraId ->
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        return preferred ?: cameraManager.cameraIdList.firstOrNull()
    }

    private fun writeImage(savePath: String, imageData: ByteArray) {
        val outputFile = File(savePath)
        outputFile.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }
        FileOutputStream(outputFile).use { fos ->
            fos.write(imageData)
        }
    }

    private fun detectRuntimeMode(): RuntimeMode {
        val isRokidGlasses =
            Build.MANUFACTURER.equals("Rokid", ignoreCase = true) ||
                Build.MODEL.contains("glasses", ignoreCase = true) ||
                Build.DEVICE.contains("glasses", ignoreCase = true)

        val hasAnyCamera = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

        return if (isRokidGlasses && hasAnyCamera) {
            RuntimeMode.LOCAL_CAMERA
        } else {
            RuntimeMode.CXR_BLUETOOTH
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun runCxrCommand(command: () -> ValueUtil.CxrStatus): ValueUtil.CxrStatus? {
        if (runtimeMode != RuntimeMode.CXR_BLUETOOTH || !isConnected()) {
            return null
        }
        return try {
            command()
        } catch (e: Exception) {
            Log.w(TAG, "CXR command failed: ${e.message}")
            ValueUtil.CxrStatus.REQUEST_FAILED
        }
    }
}
