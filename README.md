# SciNET

SciNET is a decentralized local-network mesh app for Android. Every installation is both a **server** and a **client**: it advertises itself, discovers other SciNET nodes, accepts incoming peer connections, and initiates outgoing connections when required.

There is no central host, cloud relay, Windows companion server, or manual IP entry.

## Current scope

- Automatic local discovery using Android NSD / mDNS (DNS-SD).
- A persistent device identity for each installation.
- One direct WebSocket connection per device pair.
- Deterministic connection arbitration and a live mesh topology graph.
- Tap a connected device name to choose and send a file.
- Direct binary file delivery: the sender's HTTP server serves 1 MiB chunks and the receiver's client downloads them.
- Live progress on both devices, with pause, resume and cancel controls.
- Hold a connected device name to send a text message.
- Incoming text appears in a popup with a Copy action.
- Received files are saved to Downloads/SciNET on Android 10+.

This branch is focused on peer discovery, topology, direct file transfer and peer text messages. PC input, keyboard/mouse control, game-controller shortcuts and cheats, process management, screen mirroring, clipboard synchronization and the old Python/legacy-web host are not part of the app.

## Requirements

- Android 8.0 (API 26) or newer.
- Android Studio with Android SDK 36.
- JDK 17.

## Build

Open the repository in Android Studio and run the app configuration, or build from the repository root:

~~~bash
gradle :app:assembleDebug
~~~

The debug APK is created at:

~~~text
app/build/outputs/apk/debug/app-debug.apk
~~~

GitHub Actions builds the APK whenever `feature/multi-device-peer-network` is pushed to. The workflow uploads `scinet-mesh-debug-apk` as a downloadable Actions artifact. It can also be started manually from the Actions tab.

## Network behavior

1. On launch, SciNET starts its embedded HTTP/WebSocket peer server on TCP port 8765.
2. The service advertises `_scinet._tcp.` with its device ID, name, platform and protocol version.
3. Devices discover other SciNET advertisements on the same local multicast network.
4. One direct WebSocket connection is opened for each pair of peers.
5. WebSockets carry discovery metadata, topology, text messages and transfer-control signals.
6. For a file transfer, the receiving client downloads raw binary chunks from the sending device's HTTP server. File data never passes through another mesh peer or through Base64 JSON.

The app is designed for a shared LAN / multicast domain. Guest Wi-Fi client isolation, VLAN separation, VPN routing or router multicast filtering can prevent automatic discovery even when ordinary IP connectivity works.

## Security

The peer listener accepts connections only from loopback, link-local or private-network addresses. File chunk requests are restricted to directly connected peers. The current protocol does not yet provide cryptographic peer authentication or end-to-end encryption. Use SciNET only on a trusted local network. Do not port-forward port 8765 or expose the peer server to the public internet.

## Project structure

~~~text
SciNET/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/workofaditya/scinet/
│           ├── MainActivity.kt
│           ├── PeerNetworkService.kt
│           └── FileTransferManager.kt
├── .github/workflows/android-build.yml
├── PEER-NETWORK.md
├── build.gradle.kts
├── gradle.properties
└── settings.gradle.kts
~~~
