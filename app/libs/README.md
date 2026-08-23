# Local moq-ffi

更新时间：2026-08-19

`moq-ffi-0.3.8-dev-request-path-android-arm64-v7a.aar` 是从 `moq-dev/moq`
commit `68cf64603f067f64bc02fe800464aa59710a258c` 加本地
`MoqRequest.path()` getter 补丁构建的双 ABI AAR。它包含：

- `arm64-v8a`：从原
  `moq-ffi-0.3.8-dev+64.g68cf6460.request-path-android-arm64.aar` 原样保留；
- `armeabi-v7a`：使用 cargo-ndk `4.1.2`、Android NDK
  `29.0.14206865`、platform `26`、Rust target
  `armv7-linux-androideabi` 以 `release --locked` 构建；
- 两个 ABI 均包含同一 NDK 对应的 `libc++_shared.so`。

对应的 Git describe 为 `moq-ffi-v0.3.7-64-g68cf6460`，crate 开发版本为
`0.3.8`。`path()` 用于读取所有 transport 统一的 MoQ request path，特别是
没有 request URL 的原生 QUIC。待上游发布匹配版本后，应替换这份本地产物并
重新执行互操作验证。

真机已确认 `armeabi-v7a` ABI 适配可以正常加载并运行。本轮未记录设备型号和
Android 版本，因此这条证据不代表 LAN listener、NSD、request path 鉴权或完整
发布/播放互操作已经验收。

旧的 arm64 AAR 仅作为历史构建留存，当前 Gradle 配置不再加载它们。
