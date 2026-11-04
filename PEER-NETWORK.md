# SciNET Peer Network Protocol

This document defines the first version of SciNET's device-to-device network protocol. The design is decentralized: every participating device runs a server and can initiate connections to other devices. No always-on central host is required.

## Design boundaries

SciNET uses three independent channels:

| Channel | Transport | Purpose | Current status |
|---|---|---|---|
| Control | Persistent WebSocket | Identity, presence, heartbeats, events, command coordination, transfer coordination, and screen-session signaling | Discovery, handshake, and heartbeats implemented |
| Data | Direct HTTP streaming | File listing and file payloads, including chunked/resumable transfers | Planned; do not send file bytes over the control socket |
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

## Trust and security boundary

Protocol v1 currently provides discovery and liveness, not authentication. A device being shown as connected means only that a WebSocket handshake completed. It does **not** mean that the peer is paired, trusted, or authorized to control the device.

The Android and Windows peer-control listeners reject non-private, non-loopback, and non-link-local client addresses. This is a network boundary, not an authentication mechanism. No privileged remote commands, file writes, or screen-control actions should be enabled over this channel until an authenticated pairing and authorization layer is implemented.

SciNET is intended for trusted private networks. Do not port-forward SciNET ports or expose these services directly to the public internet.

## Compatibility

- Protocol version is an integer and must match exactly for v1 handshakes.
- Future incompatible changes require a protocol version increment.
- Capabilities are negotiated independently of the version, so later peers can add optional features without assuming every peer supports them.
- Each peer's local server must remain usable while all remote peers are offline.
