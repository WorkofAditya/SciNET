# SciNET Peer Network Protocol

This document defines the first version of SciNET's device-to-device network protocol. The design is decentralized: every participating device runs a server and can initiate connections to other devices. No always-on central host is required.

## Design boundaries

SciNET uses three independent channels:

| Channel | Transport | Purpose | Current status |
|---|---|---|---|
| Control | Persistent WebSocket | Identity, presence, heartbeats, events, command coordination, transfer coordination, and screen-session signaling | Discovery, handshake, and heartbeats implemented |
| Data | Direct HTTP streaming | Direct file payloads between paired devices; file bytes never travel over the control socket | Android-to-Android streaming upload and receiver inbox implemented; resumable transfers and Windows peer transfers remain future work |
| Screen | WebRTC | Low-latency screen and optional audio media | Planned for peer-to-peer sessions; the legacy Windows-to-Android viewer remains separate |

A channel may be unavailable without taking the other channels or the local device server offline.

## Discovery

All peers advertise the DNS-SD service:

- Service type: `_scinet._tcp.local.`
- TXT keys:
  - `id`: stable device ID
  - `name`: display name
  - `platform`: `android` or `windows`
  - `protocol`: protocol version
  - `ws`: control WebSocket path, currently `/ws/peer`

The advertised service port is the device's HTTP/control listener:

- Android: `8765`, served by the embedded Ktor CIO server.
- Windows: `8000`, served by the existing FastAPI server.

The original Windows `_http._tcp.local.` announcement remains in place for backward compatibility with existing SciNET discovery.

## Stable identity and connection arbitration

Each installation generates and persists its own 32-character UUID-derived identifier:

- Android stores it in the app's private preferences.
- Windows stores it in the local, git-ignored `device_identity.json` file.

For any pair of different device IDs, the lexicographically smaller ID initiates the WebSocket. The other peer only accepts the incoming connection. Both peers use this same rule, which prevents duplicate control sockets for a pair.

A peer that disappears from mDNS is removed from the discovery target list. Failed connections are retried while the peer remains discoverable.

## Control protocol v1

All control messages are UTF-8 JSON text frames. A connection begins with a client hello:

```json
{
  "type": "hello",
  "protocolVersion": 1,
  "deviceId": "32-character-device-id",
  "deviceName": "Device name",
  "platform": "android",
  "capabilities": ["control-v1", "http-stream-v1", "webrtc-v1"]
}
```

The receiver responds with a welcome containing its own identity, protocol version, and capabilities:

```json
{
  "type": "welcome",
  "protocolVersion": 1,
  "deviceId": "32-character-device-id",
  "deviceName": "Device name",
  "platform": "windows",
  "capabilities": ["control-v1", "http-stream-v1", "webrtc-v1"]
}
```

Both sides validate the remote device ID and protocol version before marking the connection online.

A heartbeat is sent approximately every five seconds:

```json
{
  "type": "heartbeat",
  "sentAt": 0
}
```

The receiver replies with `heartbeat_ack`. A missing peer connection is retried; a connection that stops responding is discarded.

## Pairing, trust, and security boundary

Discovery and the v1 WebSocket hello/welcome exchange establish presence only. A discovered or connected device is not automatically trusted.

Android-to-Android pairing currently uses the phone QR code:
1. The receiving phone displays a random one-time token that expires after five minutes.
2. The scanning phone submits its device ID and token to `POST /api/pair/claim`.
3. The receiving phone consumes the token, creates a random per-peer key, stores it locally, and returns the key to the scanner. Both phones store the same key under the other phone's device ID.
4. The direct file-transfer endpoint requires the sender's device ID and its paired key. A device that is merely discovered cannot upload files.

Pair claims and peer control connections are restricted to private, loopback, or link-local addresses. File transfer authorization uses the per-peer bearer key. This is an initial LAN trust model, not end-to-end encryption: the Android HTTP data path is currently cleartext on the local network. Do not port-forward SciNET ports or expose these services directly to the public internet. TLS and stronger device-key authentication remain future hardening work.

## Compatibility

- Protocol version is an integer and must match exactly for v1 handshakes.
- Future incompatible changes require a protocol version increment.
- Capabilities are negotiated independently of the version, so later peers can add optional features without assuming every peer supports them.
- Each peer's local server must remain usable while all remote peers are offline.


## Phone QR sharing

The Android home screen displays a QR code for the phone's current private IPv4 address, peer identity, and short-lived pairing token.

- Scan with SciNET on another Android phone to establish trust and connect the two peers.
- The token is one-time and expires after five minutes; the displayed QR refreshes as tokens expire or are consumed.
- Ordinary camera/browser scanning opens the phone's existing SciNET web dashboard on port 8765. The Android server packages and serves the same `legacy-web` HTML, CSS, JavaScript, and PWA assets used by the PC host.

## Android-to-Android file transfer

- Open the paired phone from the **PEERS** screen.
- Choose **SEND FILES TO THIS DEVICE** and select one or more files.
- The sender streams each file directly to the receiving phone over HTTP. The receiver stores it in SciNET's private incoming queue, not a user-shared directory.
- The receiver chooses **DOWNLOAD TO THIS PHONE** and selects the final save location through Android's document picker.
- Incoming items are persisted with their private staged file so they remain available after the app process restarts.
- The initial implementation supports streamed transfers up to 2 GB per file. Resume, cancellation, checksums, and Windows peer-transfer support are not implemented yet.
