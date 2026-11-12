# SciNET

SciNET is a decentralized local-network mesh app for Android. Every installation is both a **server** and a **client**: it advertises itself, discovers other SciNET nodes, accepts incoming peer connections, and initiates outgoing connections when required.

There is no central host, cloud relay, Windows companion server, or manual IP entry.

## Current scope

- Automatic local discovery using Android NSD / mDNS (DNS-SD).
- A persistent device identity for each installation.
- One direct WebSocket connection per device pair.
- Deterministic connection arbitration: the node with the lexicographically smaller device ID dials, while the other accepts.
- Versioned peer hello / welcome handshake and periodic heartbeats.
- Topology exchange, including nodes and direct links learned from neighboring peers.
- A live mesh graph showing the devices and their connection state.

This branch is focused on peer discovery and mesh topology. PC input, keyboard/mouse control, game-controller shortcuts and cheats, process management, screen mirroring, clipboard access, and the old Python/legacy-web host have been removed.

## Requirements

- Android 8.0 (API 26) or newer.
- Android Studio with Android SDK 36.
- JDK 17.
- Gradle 8.11.1 or newer compatible with the Android Gradle Plugin in this project.

## Build

Open the repository in Android Studio and run the app configuration, or build from the repository root:

~~~bash
gradle :app:assembleDebug
~~~

The debug APK is created at:

~~~text
app/build/outputs/apk/debug/app-debug.apk
~~~

GitHub Actions also builds this APK whenever feature/multi-device-peer-network is pushed to. The workflow uploads scinet-mesh-debug-apk as a downloadable Actions artifact. It can also be started manually from the Actions tab.

## Network behavior

1. On launch, SciNET starts its embedded HTTP/WebSocket peer server on TCP port 8765.
2. The service advertises _scinet._tcp. with its device ID, name, platform, and protocol version.
3. Each device continuously discovers other SciNET advertisements on the same local multicast network.
4. For each pair of nodes, the smaller stable device ID initiates a WebSocket connection to /ws/peer. The socket is full-duplex.
5. Peers exchange their topology during the handshake and heartbeat messages, allowing devices to learn about links reported by neighboring peers.

The app is designed for a shared LAN / multicast domain. Guest Wi-Fi client isolation, VLAN separation, VPN routing, or router multicast filtering can prevent automatic discovery even when ordinary IP connectivity works.

## Security

The peer listener accepts connections only from loopback, link-local, or private-network addresses. The current protocol validates device identity and protocol version, but does not yet provide cryptographic peer authentication or end-to-end encryption. Use SciNET only on a trusted local network. Do not port-forward port 8765 or expose the peer server to the public internet.

## Project structure

~~~text
SciNET/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/workofaditya/scinet/
│           ├── MainActivity.kt
│           └── PeerNetworkService.kt
├── .github/workflows/android-build.yml
├── PEER-NETWORK.md
├── build.gradle.kts
├── gradle.properties
└── settings.gradle.kts
~~~
