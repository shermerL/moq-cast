# Local moq-ffi

更新时间：2026-08-23

`moq-ffi-0.3.12-dev-android-arm64-v7a.aar` 来自 `moq-dev/moq`
`dev` commit `81d39f7bf04c82aae324a9ee4251b7f8aa08fb53`，未包含本地源码补丁。
`MoqRequest.path()` 已由该上游版本原生提供。AAR 包含：

- `arm64-v8a` 和 `armeabi-v7a` 的 `libmoq_ffi.so`；
- 两个 ABI 均包含同一 NDK 对应的 `libc++_shared.so`。

构建使用 Rust `1.95.0`、cargo-ndk `4.1.2`、Android NDK
`29.0.14206865`、platform `24` 和 release thin LTO。AAR SHA-256 为
`06beb1e0c86c7929b8fdea6962d1e9a851f2b42f0e6e4ad6194b614cd5d6968f`。
应用仍保持 minSdk 26。

该产物已完成静态 AAR 内容核验，但本次升级尚未完成 Android 真机互操作验证。
