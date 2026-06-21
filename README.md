# MoQ ScreenCast

[English](README_EN.md) | 简体中文

用于学习和验证 [Media over QUIC](https://moq.dev/) 的原生 Android 示例项目。项目通过 MoQ relay 订阅并播放 broadcast，采集 Android 屏幕、编码为 H.264，然后发布到指定 broadcast。

本项目基于 [moq-dev/moq](https://github.com/moq-dev/moq) 提供的 MoQ 实现和 Kotlin/Android 绑定。感谢 Luke Curley以及所有 `moq-dev` contributors 对 MoQ 协议实现、原生绑定和开源生态的持续投入。

> 本项目仍处于实验阶段，适合协议学习、功能验证。

## 功能

- 配置并持久化 MoQ relay URL
- 输入 broadcast 名称并订阅
- 使用 Android `MediaCodec` 硬件解码并播放视频
- 使用 `MediaCodec` 编码 H.264 并发布屏幕画面

## 环境要求

- Android Studio
- JDK 17
- Android SDK 33
- Android 10（API 29）或更高版本
- 可访问的 MoQ relay （需自己部署moq-relay服务）

## 已验证环境

本项目当前使用以下版本组合完成 Android 与 Web 端的发布、订阅互操作验证：

| 组件                         | 版本     | 用途                                  |
| ---------------------------- | -------- | ------------------------------------- |
| `dev.moq:moq`                | `0.2.18` | Android 端 MoQ、Hang 和 UniFFI 绑定   |
| `kotlinx-coroutines-android` | `1.9.0`  | Android 协程支持                      |
| `@moq/watch`                 | `0.2.14` | Web 端订阅 Android 发布的 broadcast   |
| `@moq/publish`               | `0.2.10` | Web 端发布供 Android 订阅的 broadcast |
| `@moq/hang`                  | `0.2.7`  | Web 端 Hang catalog 和媒体容器        |
| `@moq/net`                   | `0.1.2`  | Web 端 MoQ 网络层                     |

`@moq/watch`、`@moq/publish`、`@moq/hang` 和 `@moq/net` 用于 Web 端联调，不是 Android APK 的直接依赖。当前版本组合已经验证可以正常使用。上游已有更新版本，本项目暂时保持已验证配置。

## 构建

克隆仓库后，用 Android Studio 打开项目并等待 Gradle 同步，也可以在命令行构建。

macOS 或 Linux：

```bash
./gradlew :app:assembleDebug
```

Windows：

```bat
gradlew.bat :app:assembleDebug
```

生成的调试 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 启动应用。
2. 输入 relay URL，例如 `https://relay.example.com/anon`。
3. 输入 broadcast 名称，例如 `screen.hang`。
4. 选择 `Subscribe` 订阅并播放，或者选择 `Publish screen` 发布当前设备屏幕。
5. 发布屏幕时，接受系统的通知和屏幕录制授权。

relay URL 仅保存在应用自己的 `SharedPreferences` 中，仓库内不包含默认服务器地址、访问令牌或证书指纹。

## 项目结构

底层 MoQ 网络能力由 `dev.moq:moq` 提供。该依赖包含 Rust 实现、UniFFI 生成的 Kotlin 接口以及 Android 原生动态库。应用层不需要直接编写 JNI 调用。

## 权限

- `INTERNET`：连接 MoQ relay
- `FOREGROUND_SERVICE`：在前台服务中保持屏幕发布
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`：声明屏幕采集服务类型
- `POST_NOTIFICATIONS`：显示正在发布的前台服务通知

## 当前限制

- 屏幕发布仅支持 H.264 视频，暂不支持发布音频
- 暂无编码协商
- 尚未加入自动化测试和签名配置

## 相关项目

- [moq-dev/moq](https://github.com/moq-dev/moq)
- [MoQ 官方网站](https://moq.dev/)

## 上游依赖与声明

本项目使用 `moq-dev/moq` 提供的 Kotlin/Android 绑定：

- Maven 坐标：`dev.moq:moq:0.2.18`
- 源码：[github.com/moq-dev/moq](https://github.com/moq-dev/moq)
- 作者：Luke Curley、Brian Medley 及其他 MoQ contributors
- 许可证：[Apache License 2.0](https://github.com/moq-dev/moq/blob/main/LICENSE-APACHE) 或 [MIT License](https://github.com/moq-dev/moq/blob/main/LICENSE-MIT)

`dev.moq:moq` 包含 Rust 实现、UniFFI 生成的 Kotlin 接口以及 Android 原生动态库。本项目只是独立开发的 Android 示例。

重新分发本项目源码或 APK 时，请同时保留本项目与 `moq-dev/moq` 的许可证和版权声明。
