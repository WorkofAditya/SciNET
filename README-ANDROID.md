# SciNET Android client

The Android application is being developed as a native Kotlin + Jetpack Compose client. The Python server remains on the Windows PC.

## Open in Android Studio

Open this repository as a Gradle project. Use JDK 17 and install Android SDK 36 if Android Studio prompts for it. The first Gradle sync requires internet access.

## Connect

1. Start SciNET on the Windows PC with `start.bat`.
2. Connect the phone and PC to the same reachable private network.
3. Enter the PC's local address, for example `http://192.168.1.25:8000`, and tap **Connect to PC**.

The native client currently provides the app shell and a host connectivity check. Dashboard metrics, remote control, file management, WebRTC screen viewing, clipboard synchronization, and the gaming-controller mode are planned for native implementation in subsequent steps.

Only use SciNET on a trusted private network. The current server does not provide a full authentication layer; do not expose it directly to the public internet.
