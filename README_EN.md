# MoQ ScreenCast

English | [简体中文](README.md)

[![Android CI](https://github.com/shermerL/moq-screencast/actions/workflows/android.yml/badge.svg)](https://github.com/shermerL/moq-screencast/actions/workflows/android.yml)

The primary use case of this project is screen sharing across networks. Deploying your own `moq-relay` service can provide connectivity in complex NAT environments. It is a native Android application built on [Media over QUIC](https://moq.dev/). The app captures the Android screen, encodes it as H.264, and publishes it to a specified broadcast. Other clients can connect to the `moq-relay` to subscribe and play the stream. MoQ is not limited to screen sharing. Visit [Media over QUIC](https://moq.dev/) to learn more.

Supported interoperability:

- Android -> Android
- Android -> Web
- Web -> Android

> This project is still experimental and is intended for functional evaluation.

## Features

- Start screen sharing
- Publish Android system audio (Android 10 or later)
- Subscribe to and play video streams

## Requirements

- Android Studio
- JDK 17
- Android SDK 35
- Android 8.0 (API 26) or later
- An accessible MoQ relay (you need to deploy a `moq-relay` service)

## Verified Environment

The following version combination has been verified for publishing and subscribing interoperability across Android, the Web, and the relay server.

### Android Dependencies

The following dependencies are packaged directly into the Android APK:

| Component | Version | Purpose |
| --- | --- | --- |
| `dev.moq:moq` | `0.2.24` | MoQ, Hang, and UniFFI bindings for Android |
| `kotlinx-coroutines-android` | `1.9.0` | Coroutine support for Android |

### Web Dependencies

| Component | Version | Purpose |
| --- | --- | --- |
| `@moq/watch` | `0.2.14` | Subscribe to broadcasts on the Web |
| `@moq/publish` | `0.2.10` | Publish broadcasts from the Web |
| `@moq/hang` | `0.2.7` | Hang catalog and media container support for the Web |
| `@moq/net` | `0.1.2` | MoQ networking layer for the Web |

These packages are used for Web interoperability testing. They are not direct dependencies of the Android APK.

### Relay Server

| Item | Verified configuration |
| --- | --- |
| Deployment | Docker |
| Docker image | `kixelated/moq-relay` |
| Protocol version | `moq-lite-03` |

This project currently stays on the verified configuration. If a newer version is confirmed to work correctly, an upgrade will be considered after compatibility testing is complete.

## Build

After cloning the repository, open it with Android Studio and wait for Gradle synchronization. You can also build it from the command line.

macOS or Linux:

```bash
./gradlew :app:assembleDebug
```

Windows:

```bat
gradlew.bat :app:assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Launch the app.
2. Enter a relay URL, for example `https://relay.example.com/anon`.
3. Enter a broadcast name, for example `screen.hang`.
4. Select `Subscribe` to subscribe and play, or select `Publish screen` to publish the current device screen.
5. When publishing the screen, grant the notification and screen capture permissions requested by Android.

The relay URL is stored only in the app's own `SharedPreferences`. The repository does not contain a default server address, access token, or certificate fingerprint.

## Project Structure

The underlying MoQ networking functionality is provided by `dev.moq:moq`. This dependency includes the Rust implementation, UniFFI-generated Kotlin interfaces, and Android native dynamic libraries. The application layer does not need to call JNI directly.

## Permissions

- `INTERNET`: Connect to a MoQ relay
- `FOREGROUND_SERVICE`: Keep screen publishing active in a foreground service
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`: Declare the screen capture service type
- `POST_NOTIFICATIONS`: Display the foreground service notification while publishing
- `RECORD_AUDIO`: Capture system playback audio on Android 10 or later

## Current Limitations

- Screen publishing supports H.264 video only
- System audio publishing requires Android 10 or later, and the captured app must allow audio playback capture
- Microphone audio capture is not currently supported
- Codec negotiation is not currently supported
- Automated tests and release signing have not been added

## Related Projects

- [moq-dev/moq](https://github.com/moq-dev/moq)
- [MoQ official website](https://moq.dev/)

## Upstream Dependency and Attribution

This project uses the Kotlin/Android bindings provided by `moq-dev/moq`:

- Maven coordinates: `dev.moq:moq:0.2.24`
- Source: [github.com/moq-dev/moq](https://github.com/moq-dev/moq)
- License: [Apache License 2.0](https://github.com/moq-dev/moq/blob/main/LICENSE-APACHE) or [MIT License](https://github.com/moq-dev/moq/blob/main/LICENSE-MIT)

The `dev.moq:moq` package includes the Rust implementation, UniFFI-generated Kotlin interfaces, and Android native dynamic libraries. This project is an independently developed Android sample based on the MoQ implementation and Kotlin/Android bindings provided by [moq-dev/moq](https://github.com/moq-dev/moq). Thanks to Luke Curley and all `moq-dev` contributors for their continued work on the MoQ protocol implementation, native bindings, and open source ecosystem.

When redistributing this project's source code or APK, retain the license and copyright notices for `moq-dev/moq`.
