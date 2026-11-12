# SciNET Peer Mesh Protocol

SciNET is a local-network peer mesh. Every running Android installation is both a server and a client. Peers discover one another over mDNS and open a direct WebSocket for mesh control. File contents use a separate direct HTTP binary channel.

## Current capabilities

- Stable per-installation device identity.
- mDNS / DNS-SD discovery on the local network.
- Embedded HTTP and WebSocket server on TCP port 8765.
- Direct, full-duplex peer WebSockets for identity, topology, text messages and transfer control.
- Topology exchange and direct-link status.
- Direct peer-to-peer file downloads using raw binary HTTP chunks.
- Per-transfer progress, pause, resume and cancel controls on both devices.
- Incoming text-message popup in the Android client, with clipboard copy.

SciNET does not route file payloads through another peer. A device shown in the graph is interactive only while it has a direct active connection.

## Discovery and connection

Each node advertises DNS-SD service type `_scinet._tcp.` on port 8765. TXT attributes contain its stable ID, display name, platform, protocol version and WebSocket path.

For each pair of device IDs, only the lexicographically lower ID opens the WebSocket. This deterministic rule avoids duplicate connections. The WebSocket begins with a versioned `hello` and `welcome` exchange, then carries heartbeats and topology snapshots.

Protocol version 2 is required. Version 1 peers are not compatible with file transfer and are ignored by discovery.

## WebSocket control messages

The `/ws/peer` WebSocket carries small JSON control messages only:

- `hello`, `welcome`, `heartbeat`, `heartbeat_ack`: identity, presence and topology.
- `text_message`: a text message delivered to the peer's app.
- `file_offer`: transfer ID, file name, MIME type and total byte count.
- `file_accept`, `file_reject`: receiver decision.
- `file_progress`: acknowledged byte count.
- `file_control`: `pause`, `resume` or `cancel`.
- `file_complete`, `file_error`: final transfer result.

No file bytes are placed in WebSocket JSON messages.

## Binary file channel

The sender keeps the selected document URI and serves requested ranges from its own HTTP server. After the receiver accepts an offer, its client requests sequential chunks directly from the sender:

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/transfers/{transferId}/chunk?offset={bytes}&length={bytes}` | Read one raw binary chunk from the sender |

The receiver sends `X-SciNET-From` with its device ID. The server accepts a chunk request only from a private-LAN address belonging to a directly connected peer and only for an accepted outgoing transfer.

Chunks are currently 1 MiB (maximum accepted request: 2 MiB). Data is transferred as `application/octet-stream`, not Base64. The receiver writes each acknowledged chunk to a temporary partial file, updates both progress bars over the WebSocket, and publishes the completed file to `Downloads/SciNET` on Android 10 and newer. On Android 8 and 9, it uses the app-specific external Downloads/SciNET folder.

Pausing stops new chunk requests after any in-flight chunk finishes. Resuming continues from the receiver's saved byte offset. Cancel removes the receiver's partial file and stops the transfer on both devices. Transfers are limited to 50 GiB per file.

## Other local endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/health` | Server health and protocol version |
| GET | `/api/peer/info` | Local identity and directly connected peers |
| GET | `/api/peer/topology` | Current merged mesh topology |
| WebSocket | `/ws/peer` | Peer handshake, topology, text and transfer control |

## Security and network constraints

Automatic discovery and file transfer are intended for a trusted local LAN. Guest Wi-Fi isolation, VLAN boundaries, VPN routing or multicast filtering can prevent discovery. HTTP file chunks and WebSocket control traffic are not end-to-end encrypted, and the current peer identity exchange is not cryptographic authentication. Do not port-forward port 8765 or expose SciNET to the public internet.
