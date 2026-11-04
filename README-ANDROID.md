# SciNET Android peer node

SciNET for Android is a native Kotlin + Jetpack Compose application. The standard SciNET dashboard and HOME, CONTROL, FILES, PROCESSES, CONTROLLER, and SCREEN navigation stay available even when no remote device is connected; the phone's own server is the default target. A top-bar device selector switches the same screens between the phone, a configured PC, and paired peers. The app also starts an embedded HTTP/WebSocket server in a foreground service, advertises the device over local-network mDNS, and discovers other SciNET nodes.

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
- The `/ws/peer` channel performs a versioned hello/welcome handshake and heartbeat exchange. It is the presence/control path, not a file-payload channel.
- Phone QR pairing uses a one-time five-minute token and establishes a per-peer trust key. Presence alone does not authorize file uploads.
- Android exposes `/health`, `/api/peer/info`, `/api/pair/claim`, and the authenticated `/api/peer/transfers` streaming receiver.
- The Android server also serves the existing `legacy-web` frontend at `http://phone-ip:8765`, including the dashboard, file manager, static assets, PWA shell, and QR endpoint. The same source files are packaged directly by the Android Gradle asset source set.

## Existing Android features

- **Dashboard:** reads Windows host metrics, network traffic, connected-device information, and activity.
- **Remote control:** touchpad, keyboard, media, volume, and power actions on the configured Windows host.
- **Files:** browse, upload, download, rename, delete, preview, and edit files on the Windows host. When a paired Android peer is selected, the same Files screen sends files directly to that phone and shows incoming peer transfers for receiver-selected download.
- **Processes:** on-demand Windows process inspection and actions.
- **Screen mirroring:** receives the existing Windows desktop stream.
- **Game controller:** keyboard and joystick input for supported Windows games.

The existing PC server remains independently usable. It continues to host its current dashboard, file service, remote-control APIs, FTP service, and screen capture even when no Android device is present.

### Android-hosted web interface

- Open `http://<phone-lan-ip>:8765` from a browser on the same reachable network. This serves the same dashboard UI that the Windows host uses, not a separate connection/status page.
- The dashboard displays Android CPU, memory, storage, network totals, uptime, connected peers, and activity. Its live updates use `/ws`.
- The clipboard panel uses `/ws/clipboard` for live browser-to-browser clipboard updates.
- The dashboard upload and file-manager APIs write to the phone's app-scoped external Documents folder, `SciNET-Web`. This is the Android web host's own storage, separate from Android-to-Android peer inbox transfers and separate from the PC's configured shared directory.
- The file manager supports listing, folder creation, multipart upload, chunked upload with SHA-256 chunk verification, download, preview, rename, delete, and UTF-8 text editing.
- The existing control page and process/screen UI are served too. Remote input, Android process actions, and browser-initiated Android screen capture are not implemented by this web host yet; those routes return an explicit unsupported response rather than pretending to work.

## Channel boundaries and implementation status

SciNET is being developed around three separate paths:

1. **Control:** persistent bidirectional WebSocket for peer identity, liveness, events, command coordination, and screen-session signaling.
2. **Data:** direct HTTP streaming between peer servers. Android-to-Android file sending, a private incoming queue, receiver-selected downloads, and per-peer authorization are implemented. Resume, cancellation, checksums, and Windows peer-to-peer file transfer remain future work. File payloads never travel through the control socket.
3. **Screen:** WebRTC media transport for low-latency screen/audio streaming. Peer-to-peer screen-session negotiation, Android screen capture, and cross-platform remote input remain future work. The existing Windows-to-Android viewer is not being represented as full bidirectional mirroring.

## Network and security notes

SciNET is designed for a trusted private LAN. Android pairing uses one-time QR tokens and per-peer bearer keys for file uploads, but the Android HTTP data path is currently cleartext on the local network. The existing Windows web APIs do not provide a complete user-account/authentication layer. Do not port-forward SciNET ports or expose these services directly to the public internet.

Android may require the user to exempt SciNET from battery optimization for longer background availability. A foreground service improves continuity but cannot override a force-stop or every vendor power-management decision.

## Verification

The Android debug build is checked by GitHub Actions. An actual browser-on-phone-LAN test, Android-to-Android transfer test, and Windows-to-Android LAN test still need to be run on physical devices.
