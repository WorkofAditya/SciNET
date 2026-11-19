# SciNET Remote Android app

SciNET Remote is the native Kotlin + Jetpack Compose Android client for the SciNET Windows host.

## Build

- JDK 17
- Android SDK 36
- Gradle 8.11.1

Run:

```bash
gradle :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Install identity

- Display name: **SciNET Remote**
- Application ID: `com.workofaditya.scinetremote`

This distinct application ID allows SciNET Remote and SciNET Link to coexist on one Android device.

## Connect

1. Start the Windows host with `start.bat`.
2. Connect Android and Windows to the same reachable private network.
3. Enter the Windows host address (port 8000) in the app and connect.

## Features

Dashboard and metrics, remote touchpad/input, keyboard and media keys, shared files, clipboard, process manager, WebRTC screen streaming and game-controller modes. Feature availability depends on the Windows host and required drivers.

## Security

Use only on trusted networks. The host has powerful remote-control functionality and should not be exposed publicly.
