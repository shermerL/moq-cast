# 更新日志 / Changelog

本文件记录项目各版本的重要变更。

This file documents notable changes for each project version.

## Unreleased

尚无变更。

No changes yet.

## 0.4.1-dev.4 (Debug) - 2026-08-30

### 中文

- 改进停止屏幕共享时的资源清理和状态同步

### English

- Improve resource cleanup and state synchronization when stopping screen sharing

## 0.4.1-dev.3 (Debug) - 2026-08-26

### 中文

- 修复局域网 Mesh 的直连媒体路由

### English

- Fix direct-only media routing in LAN mesh

## 0.4.1-dev.2 (Debug) - 2026-08-24

### 中文

- 同步当前 MoQ 开发版 FFI 绑定，并适配更新后的局域网连接接口

### English

- Sync the current MoQ development FFI bindings and adapt to the updated LAN connection APIs

## 0.4.1-dev.1 (Debug) - 2026-08-23

### 中文

- 支持发现并直连同一局域网内的 MoQCast 设备
- 支持 `arm64-v8a` 和 `armeabi-v7a` Android 设备
- 增加 TextureView 兼容播放模式
- 改善屏幕和系统音频实时发布的稳定性
- 移除尚未成熟的 Android CMAF/fMP4 文件发布功能

### English

- Discover and connect directly to MoQCast devices on the same LAN
- Support `arm64-v8a` and `armeabi-v7a` Android devices
- Add a TextureView compatibility playback mode
- Improve real-time screen and system audio publishing stability
- Remove the experimental Android CMAF/fMP4 file publishing path

## 0.4.0 (Debug) - 2026-08-10

### 中文

- 完善摄像头方向元数据、前后镜头选择
- 支持 CMAF/fMP4 文件发布

### English

- Improve camera orientation metadata and lens selection
- Support CMAF/fMP4 file publishing

## 0.3.0 (Debug) - 2026-07-16

### 中文

- 新增后置摄像头视频发布和可选麦克风音频
- 支持屏幕共享横竖屏切换

### English

- Add rear-camera video publishing with optional microphone audio
- Support portrait and landscape transitions during screen sharing

## 0.2.2 (Debug) - 2026-07-03

### 中文

- 重构发布端 source/pipeline
- 拆分编码实现和屏幕采集实现

### English

- Refactor the publish source/pipeline
- Split encoder and screen capture implementations

## 0.2.1 (Debug) - 2026-07-01

### 中文

- 修复屏幕发布进入后台一段时间后断开的问题

### English

- Fix screen publishing stopping after the app stays in the background

## 0.2.0 (Debug) - 2026-06-29

### 中文

- 使用 Compose Material 3 重构主界面，发布与订阅入口分离
- 支持发布 Android 系统音频，使用 Opus 进行音频编码

### English

- Rebuild the main UI with Compose Material 3 and separate publish and subscribe entry points
- Support publishing Android system audio with Opus encoding

## 0.1.0 (Debug) - 2026-06-21

### 初始化 / Init

#### 中文

- 支持在 Android 上订阅和播放 MoQ broadcast
- 使用 Android `MediaCodec` 进行视频解码
- 将 Android 屏幕编码为 H.264 并发布

#### English

- Subscribe to and play MoQ broadcasts on Android
- Decode video with Android `MediaCodec`
- Encode and publish the Android screen as H.264
