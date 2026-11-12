# SciNET Peer Mesh Protocol

This document describes the peer-discovery and topology protocol used by SciNET. Every running installation is a node: it listens for incoming peers and can initiate connections to other nodes. No always-on central host is required.

## Current scope

The current branch implements:

- Stable per-installation device identity.
- mDNS / DNS-SD discovery on a local multicast network.
- An embedded HTTP and WebSocket listener on TCP port 8765.
- Direct full-duplex peer WebSockets.
- Versioned hello / welcome messages.
- Periodic heartbeats.
- Topology snapshots containing node identities and direct links.
- Topology merging, so a node can learn about links reported by its connected peers.

This protocol currently carries peer identity, presence, heartbeats, and topology metadata only. It does not expose remote PC controls, arbitrary command execution, file payloads, clipboard data, process management, screen capture, or game-controller input.

## Discovery

Every node advertises the DNS-SD service:

- Service type: _scinet._tcp.local.
- TCP port: 8765.
- TXT attributes:
  - id: stable device ID
  - name: display name
  - platform: android
  - protocol: protocol version
  - ws: peer WebSocket path, currently /ws/peer

The Android app uses the platform NSD service to advertise and discover peers. The service holds a Wi-Fi multicast lock while it runs so multicast discovery is less likely to be suspended.

## Automatic mesh connections

Every node both accepts inbound WebSockets and discovers remote nodes. For any pair of device IDs, only one side dials:

- If localDeviceId is lexicographically smaller than remoteId, the local node opens the WebSocket.
- Otherwise, the local node waits for the remote node's incoming connection.

The comparison is deterministic and identical on every node. It prevents both devices from opening duplicate sockets for the same pair. A connected WebSocket is full-duplex, so both peers can exchange messages over that direct link.

Failed connections are retried while the peer's mDNS advertisement remains visible. When an advertisement disappears, the endpoint is removed and its socket is closed. The next advertisement starts discovery and connection again.

## Identity and handshake

Each installation generates a 32-character UUID-derived identifier and persists it in the app's private preferences. The device name defaults to the Android model name.

A connection begins with a JSON hello message:

~~~json
{
  "type": "hello",
  "protocolVersion": 1,
  "deviceId": "32-character-device-id",
  "deviceName": "Android device",
  "platform": "android",
  "port": 8765,
  "capabilities": ["mesh-v1", "topology-v1"],
  "topology": {
    "selfId": "32-character-device-id",
    "nodes": [],
    "links": []
  }
}
~~~

The receiver validates the message type, device ID, and protocol version, then replies with a welcome message containing its own identity and topology snapshot. A peer is considered online only after the expected welcome response is received.

## Heartbeats and topology exchange

Peers exchange a heartbeat approximately every five seconds. Each heartbeat includes the sender's current topology snapshot; the receiver replies with a heartbeat acknowledgment and its own snapshot.

A topology snapshot contains:

- selfId: the local device ID.
- nodes: known device identities, names, platform, online state, and last update time.
- links: unordered pairs of device IDs and whether their direct link is currently connected.

Direct links are built from local peer sessions. Links reported by remote nodes are merged into the local graph. Reported links become stale after 20 seconds without a fresh topology update and are displayed as disconnected. A reported link is informational only: SciNET does not route traffic through an intermediate peer.

## Local API

The embedded server exposes only the endpoints needed to inspect node health and mesh state:

| Method | Path | Purpose |
| --- | --- | --- |
| GET | /health | Server health, local device ID, and protocol version |
| GET | /api/peer/info | Local identity, listening port, and directly connected peers |
| GET | /api/peer/topology | Current merged topology snapshot |
| WebSocket | /ws/peer | Peer handshake, heartbeats, and topology exchange |

## Network constraints and security

Automatic discovery requires multicast DNS to work between the devices. It is intended for peers on the same LAN or multicast domain. Guest Wi-Fi isolation, VLAN boundaries, VPN routing, or router multicast filtering can stop discovery.

The WebSocket listener rejects connections that do not originate from loopback, link-local, or private-network addresses. The current handshake checks identity and protocol compatibility, but it is not cryptographic authentication and the transport is not end-to-end encrypted. Do not port-forward port 8765 or expose SciNET directly to the public internet.

## Planned channel separation

The mesh protocol is the foundation for future independent data and media channels. Those channels are not implemented in this branch. Adding them must not turn a peer into a central relay or move payload data through the topology heartbeat channel.
