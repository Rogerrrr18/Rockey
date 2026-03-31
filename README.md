# Rockey

Rockey 是一个运行在 Rokid 眼镜侧的 Android 应用，目标是打通以下链路：

- 本机相机采集
- 本机 ASR（Rokid Assist）
- 本机 TTS（Rokid TtsService）
- OpenClaw Bridge / Gateway 对话
- 图片问答（拍照 + 问题一起发送）

## 项目结构

- `app/src/main/java/com/example/rockey/MainActivity.kt`
  - 应用主流程、状态机、语音会话、UI 更新
- `app/src/main/java/com/example/rockey/huoyan/HuoyanManager.kt`
  - WebSocket 协议适配、图像上传/图片问答、结果回调
- `app/src/main/java/com/example/rockey/camera/`
  - Rokid 相机与设备连接桥接
- `app/src/main/java/com/example/rockey/voice/`
  - Rokid Assist ASR 与 Rokid TTS Binder 客户端
- `app/src/main/aidl/com/rokid/...`
  - Rokid 内部服务 AIDL 接口定义
- `app/src/main/java/com/example/rockey/ws/OkHttpSocketClient.kt`
  - WebSocket 连接实现

## 当前能力

- 支持连接 `ws://127.0.0.1:2478`（通常经 `adb reverse` 映射到本机 OpenClaw Gateway）
- 支持 Rokid Assist 回调接入和识别结果提取
- 支持 Rokid TTS Binder 播报，失败时回退系统 TTS
- 支持图片问答：拍照后以附件形式随问题发送给 Bridge/Gateway
- 兼容旧慧眼协议与 OpenClaw Bridge 协议

## 本地构建

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 运行提示

1. 确保本机 OpenClaw Gateway 在 `18789` 端口运行。
2. 连接眼镜后执行：

```bash
adb reverse tcp:2478 tcp:18789
```

3. 启动 App，确认状态出现 Bridge 已连接后进行语音或图片问答。
