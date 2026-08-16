# Local moq-ffi

更新时间：2026-08-10

`moq-ffi-0.3.8-dev+64.g68cf6460.request-path-android-arm64.aar` 是从
`moq-dev/moq` commit `68cf6460` 加本地 `MoqRequest.path()` 补丁构建的
arm64-only AAR。

对应的 Git describe 为 `moq-ffi-v0.3.7-64-g68cf6460`，crate 开发版本为
`0.3.8`。`path()` 用于读取所有 transport 统一的 MoQ request path，特别是
没有 request URL 的原生 QUIC。待上游发布匹配版本后，应替换这份本地产物并
重新执行互操作验证。

旧的 `moq-ffi-0.3.4+7.gda45f8a6-android-arm64.aar` 仅作为历史构建留存，
当前 Gradle 配置不再加载它。
