# SciNET Android peer node

SciNET for Android is a native Kotlin + Jetpack Compose application. It is also a first-class network node: when the app launches, it starts an embedded HTTP/WebSocket server in a foreground service, advertises the device over local-network mDNS, and discovers other SciNET nodes.

## Open in Android Studio

1. Open this repository as a Gradle project.
2. Use JDK 17 and install Android SDK 36 if Android Studio prompts for it.
3. Let Gradle sync and download the Android and Ktor server dependencies.
4. Select the `app` run configuration and launch it on an Android device.

## Current peer-network foundation

- The phone starts its server automatically when `MainActivity` launches. The service uses the Android `connectedDevice` foreground-service type.
- Android advertises `_scinet._tcp.local.` on port `8765`; the Windows server advertises the same service type on port `8000`.
- Both platforms use a persistent device ID and protocol version `1`.
- Devices discover one another through mDNS/NSD. For each device pair, the lexicographically smaller device ID initiates the control WebSocket, avoiding duplicate sessions.
- The `/ws/peer` channel currently performs a versioned hello/welcome handshake and heartbeat exchange. It does not execute remote commands or carry file payloads.
- Android exposes `/health` and `/api/peer/info` on its embedded server. Windows exposes `/api/peer/info` and `/ws/peer` on its existing server.

## Existing Android features

- **Dashboard:** reads Windows host metrics, network traffic, connected-device information, and activity.
- **Remote control:** touchpad, keyboard, media, volume, and power actions on the configured Windows host.
- **Files:** browse, upload, download, rename, delete, preview, and edit files on the Windows host.
- **Processes:** on-demand Windows process inspection and actions.
- **Screen mirroring:** receives the existing Windows desktop stream.
- **Game controller:** keyboard and joystick input for supported Windows games.

The existing PC server remains independently usable. It continues to host its current dashboard, file service, remote-control APIs, FTP service, and screen capture even when no Android device is present.

## Channel boundaries and implementation status

SciNET is being developed around three separate paths:

1. **Control:** persistent bidirectional WebSocket for peer identity, liveness, events, command coordination, and screen-session signaling.
2. **Data:** direct HTTP streaming between peer servers for files, with resumable chunks, integrity verification, progress, and cancellation. File payloads must not be sent through the control socket.
3. **Screen:** WebRTC media transport for low-latency screen/audio streaming. Remote input will use the platform-specific control mechanisms supported by each device.

The current branch implements the discovery and control-channel presence foundation. Direct peer file transfer, authenticated pairing, screen-session negotiation, Android screen capture, and cross-platform remote input are subsequent implementation steps. The existing Windows-to-Android viewer is not being represented as full bidirectional mirroring.

## Network and security notes

SciNET is designed for a trusted private LAN. The new peer WebSocket currently accepts only private/loopback/link-local source addresses and handles identity/liveness messages only. It is not yet an authenticated pairing system. Do not expose SciNET services to the public internet; the legacy Windows APIs still do not provide a complete user-account/authentication layer.

Android may require the user to exempt SciNET from battery optimization for longer background availability. A foreground service improves continuity but cannot override a force-stop or every vendor power-management decision.

## Verification

The peer service and protocol have been added on `feature/multi-device-peer-network`. A Gradle build, Android device test, and Windows-to-Android / Android-to-Android LAN test are still required before this foundation is considered verified.
