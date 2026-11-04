from contextlib import asynccontextmanager
import ctypes
import ipaddress
import math
import getpass
import os
import platform
import subprocess
from pathlib import Path
import asyncio
import io
import json
import hashlib
import socket
import struct
import threading
import time
import uuid
from fractions import Fraction

import psutil
import qrcode
import mss
from PIL import Image, ImageDraw
import av
from aiortc import RTCPeerConnection, RTCSessionDescription, VideoStreamTrack
from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, StreamingResponse, Response
from fastapi.staticfiles import StaticFiles
from zeroconf import IPVersion, ServiceBrowser, ServiceInfo, ServiceListener, Zeroconf
import websocket as websocket_client
from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer

BASE_DIR = Path(__file__).resolve().parent
STORAGE_DIR = BASE_DIR / "shared_files"
DEVICE_IDENTITY_FILE = BASE_DIR / "device_identity.json"


def load_device_identity():
    try:
        identity = json.loads(DEVICE_IDENTITY_FILE.read_text(encoding="utf-8"))
        device_id = str(identity.get("id") or "")
        if device_id and len(device_id) <= 64 and all(char in "0123456789abcdef-" for char in device_id.lower()):
            return {"id": device_id, "name": str(identity.get("name") or socket.gethostname()), "platform": "windows"}
    except (OSError, ValueError, AttributeError):
        pass
    identity = {"id": uuid.uuid4().hex, "name": socket.gethostname(), "platform": "windows"}
    temporary = DEVICE_IDENTITY_FILE.with_suffix(".tmp")
    temporary.write_text(json.dumps(identity, indent=2), encoding="utf-8")
    temporary.replace(DEVICE_IDENTITY_FILE)
    return identity


DEVICE_IDENTITY = load_device_identity()
DEVICE_ID = DEVICE_IDENTITY["id"]
DEVICE_NAME = DEVICE_IDENTITY["name"]
DEVICE_NAMES_FILE = BASE_DIR / "device_names.json"
device_names_lock = threading.Lock()
try:
    device_names = json.loads(DEVICE_NAMES_FILE.read_text(encoding="utf-8")) if DEVICE_NAMES_FILE.exists() else {}
except (OSError, ValueError):
    device_names = {}
WEB_DIR = BASE_DIR / "legacy-web"
STORAGE_DIR.mkdir(exist_ok=True)
started_at = time.time()
mdns = None
peer_browser = None
peer_targets = {}
peer_service_names = {}
peer_devices = {}
peer_known_nodes = {}
peer_remote_links = {}
peer_transfer_activity = set()
peer_workers = {}
peer_lock = threading.RLock()
peer_stop = threading.Event()
ftp_server = None
ftp_thread = None
connected_devices = {}
connected_lock = threading.Lock()
uploads = {}
uploads_lock = threading.Lock()
events = []
events_lock = threading.Lock()
clipboard = {"text": "", "updated": 0, "source": ""}
clipboard_lock = threading.Lock()
clipboard_clients = set()
screen_peers = set()
screen_peers_lock = asyncio.Lock()


def log_event(message, kind="info"):
    entry = {"id": uuid.uuid4().hex, "time": time.time(), "message": message, "kind": kind}
    with events_lock:
        events.insert(0, entry)
        del events[100:]
    return entry


def local_ip():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("1.1.1.1", 80))
        return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


PEER_SERVICE_TYPE = "_scinet._tcp.local."
PEER_PROTOCOL_VERSION = 1
PEER_PORT = 8000




def _peer_link_key(a, b):
    return "|".join(sorted((str(a), str(b))))


def _merge_peer_topology(topology):
    if not isinstance(topology, dict):
        return
    now = time.time() * 1000.0
    with peer_lock:
        for node in topology.get("nodes") or []:
            if not isinstance(node, dict):
                continue
            peer_id = str(node.get("id") or "")
            if not peer_id or peer_id == DEVICE_ID or len(peer_id) > 64:
                continue
            current = peer_known_nodes.get(peer_id)
            incoming = dict(node)
            if current and float(current.get("updatedAt") or 0) > float(incoming.get("updatedAt") or 0):
                continue
            incoming["id"] = peer_id
            incoming["updatedAt"] = float(incoming.get("updatedAt") or now)
            peer_known_nodes[peer_id] = incoming

        for link in topology.get("links") or []:
            if not isinstance(link, dict):
                continue
            a, b = str(link.get("a") or ""), str(link.get("b") or "")
            if not a or not b or a == b or len(a) > 64 or len(b) > 64:
                continue
            updated = float(link.get("updatedAt") or now)
            key = _peer_link_key(a, b)
            current = peer_remote_links.get(key)
            if current and float(current.get("updatedAt") or 0) > updated:
                continue
            peer_remote_links[key] = {
                "a": a,
                "b": b,
                "connected": bool(link.get("connected")),
                "transferring": bool(link.get("transferring")),
                "updatedAt": updated,
            }


def _peer_topology_snapshot():
    now = time.time() * 1000.0
    with peer_lock:
        targets = {key: dict(value) for key, value in peer_targets.items()}
        known = {key: dict(value) for key, value in peer_known_nodes.items()}
        direct = {key: dict(value) for key, value in peer_devices.items()}
        remote_links = {key: dict(value) for key, value in peer_remote_links.items()}

    nodes = {
        DEVICE_ID: {
            "id": DEVICE_ID, "name": DEVICE_NAME, "platform": "windows",
            "connected": True, "self": True, "updatedAt": now,
        }
    }
    ids = set(targets) | set(known) | set(direct)
    direct_links = {}
    for peer_id in ids:
        if peer_id == DEVICE_ID:
            continue
        peer = direct.get(peer_id, {})
        target = targets.get(peer_id) or known.get(peer_id) or peer
        peer_seen = float(peer.get("last_seen") or 0) * 1000.0
        updated = peer_seen or float(target.get("updatedAt") or target.get("discoveredAt") or now)
        connected = bool(peer.get("connected")) and now - (peer_seen or now) <= 20000
        nodes[peer_id] = {
            "id": peer_id,
            "name": str(peer.get("name") or target.get("name") or "SciNET device"),
            "platform": str(peer.get("platform") or target.get("platform") or "unknown"),
            "connected": connected,
            "self": False,
            "updatedAt": updated,
        }
        key = _peer_link_key(DEVICE_ID, peer_id)
        direct_links[key] = {
            "a": DEVICE_ID, "b": peer_id,
            "connected": connected,
            "transferring": peer_id in peer_transfer_activity,
            "updatedAt": updated,
        }

    links = dict(direct_links)
    for key, link in remote_links.items():
        if key in direct_links:
            continue
        updated = float(link.get("updatedAt") or 0)
        fresh = now - updated <= 20000
        links[key] = {
            "a": link["a"], "b": link["b"],
            "connected": bool(link.get("connected")) and fresh,
            "transferring": bool(link.get("transferring")) and fresh,
            "updatedAt": updated,
        }

    for link in links.values():
        for peer_id in (link["a"], link["b"]):
            if peer_id not in nodes and peer_id != DEVICE_ID:
                remote = known.get(peer_id, {})
                nodes[peer_id] = {
                    "id": peer_id,
                    "name": str(remote.get("name") or "SciNET device"),
                    "platform": str(remote.get("platform") or "unknown"),
                    "connected": False,
                    "self": False,
                    "updatedAt": float(remote.get("updatedAt") or 0),
                }
    for peer_id, node in nodes.items():
        if peer_id == DEVICE_ID:
            continue
        node["connected"] = any(
            (link["a"] == peer_id or link["b"] == peer_id) and link["connected"]
            for link in links.values()
        )
    return {
        "selfId": DEVICE_ID,
        "nodes": list(nodes.values()),
        "links": list(links.values()),
        "updatedAt": now,
    }

def _peer_hello():
    return {
        "type": "hello",
        "protocolVersion": PEER_PROTOCOL_VERSION,
        "deviceId": DEVICE_ID,
        "deviceName": DEVICE_NAME,
        "platform": "windows",
        "port": PEER_PORT,
        "capabilities": ["control-v1", "http-stream-v1", "webrtc-v1"],
        "topology": _peer_topology_snapshot(),
    }


def _peer_welcome():
    return {
        "type": "welcome",
        "protocolVersion": PEER_PROTOCOL_VERSION,
        "deviceId": DEVICE_ID,
        "deviceName": DEVICE_NAME,
        "platform": "windows",
        "capabilities": ["control-v1", "http-stream-v1", "webrtc-v1"],
        "topology": _peer_topology_snapshot(),
    }


def _service_property(info, key, default=""):
    value = info.properties.get(key.encode("utf-8"), info.properties.get(key))
    if value is None:
        return default
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return str(value)


def _set_peer_presence(peer_id, payload, connected, connection_id=None):
    now = time.time()
    with peer_lock:
        record = dict(peer_devices.get(peer_id, {}))
        record.update(payload)
        record.update({
            "id": peer_id,
            "connected": bool(connected),
            "last_seen": now,
        })
        if connected:
            record["connected_at"] = record.get("connected_at") or now
            if connection_id:
                record["connection_id"] = connection_id
        else:
            record["connected_at"] = None
            record.pop("connection_id", None)
        peer_devices[peer_id] = record


def _peer_connector(peer_id):
    while not peer_stop.is_set():
        with peer_lock:
            target = dict(peer_targets.get(peer_id) or {})
        if not target:
            peer_stop.wait(2)
            continue

        connection = None
        connection_id = uuid.uuid4().hex
        welcomed = False
        started = time.monotonic()
        last_heartbeat = 0.0
        last_received = time.monotonic()
        try:
            url = f"ws://{target['host']}:{target['port']}/ws/peer"
            connection = websocket_client.create_connection(url, timeout=6)
            connection.settimeout(1)
            connection.send(json.dumps(_peer_hello()))

            while not peer_stop.is_set():
                if not peer_targets.get(peer_id):
                    break
                now = time.monotonic()
                if welcomed and now - last_heartbeat >= 5:
                    connection.send(json.dumps({
                        "type": "heartbeat", "sentAt": time.time(),
                        "topology": _peer_topology_snapshot(),
                    }))
                    last_heartbeat = now
                if not welcomed and now - started > 8:
                    break
                if welcomed and now - last_received > 30:
                    break
                try:
                    raw = connection.recv()
                except websocket_client.WebSocketTimeoutException:
                    continue
                if not raw:
                    break
                last_received = time.monotonic()
                try:
                    message = json.loads(raw)
                except (TypeError, ValueError):
                    continue

                kind = message.get("type")
                if isinstance(message.get("topology"), dict):
                    _merge_peer_topology(message["topology"])
                if kind == "welcome" and message.get("protocolVersion") == PEER_PROTOCOL_VERSION:
                    if str(message.get("deviceId") or "") != peer_id:
                        raise ValueError("Peer identity mismatch")
                    welcomed = True
                    _set_peer_presence(peer_id, {
                        "name": str(message.get("deviceName") or target.get("name") or "SciNET device"),
                        "platform": str(message.get("platform") or target.get("platform") or "unknown"),
                        "ip": target["host"],
                        "port": target["port"],
                        "transport": "websocket",
                    }, True, connection_id)
                elif kind == "heartbeat":
                    connection.send(json.dumps({
                        "type": "heartbeat_ack",
                        "deviceId": DEVICE_ID,
                        "sentAt": message.get("sentAt"),
                        "topology": _peer_topology_snapshot(),
                    }))
                elif kind == "heartbeat_ack":
                    with peer_lock:
                        record = peer_devices.get(peer_id)
                        if record and record.get("connection_id") == connection_id:
                            record["last_seen"] = time.time()
        except Exception as exc:
            if not peer_stop.is_set():
                print(f"SciNET peer connection to {peer_id[:8]} failed: {type(exc).__name__}")
        finally:
            if connection is not None:
                try:
                    connection.close()
                except Exception:
                    pass
            if welcomed:
                with peer_lock:
                    record = peer_devices.get(peer_id)
                    if record and record.get("connection_id") == connection_id:
                        _set_peer_presence(peer_id, record, False)
        peer_stop.wait(3)


def _ensure_peer_worker(peer_id):
    with peer_lock:
        worker = peer_workers.get(peer_id)
        if worker is not None and worker.is_alive():
            return
        worker = threading.Thread(
            target=_peer_connector,
            args=(peer_id,),
            name=f"SciNET-peer-{peer_id[:8]}",
            daemon=True,
        )
        peer_workers[peer_id] = worker
        worker.start()


def _remember_peer_service(zeroconf, service_type, service_name):
    try:
        info = zeroconf.get_service_info(service_type, service_name, timeout=3000)
        if info is None:
            return
        peer_id = _service_property(info, "id")
        version = _service_property(info, "protocol")
        if not peer_id or peer_id == DEVICE_ID or version != str(PEER_PROTOCOL_VERSION):
            return
        addresses = info.parsed_addresses(IPVersion.V4Only)
        if not addresses:
            return
        target = {
            "id": peer_id,
            "name": _service_property(info, "name", service_name),
            "platform": _service_property(info, "platform", "unknown"),
            "host": addresses[0],
            "port": int(info.port),
            "service_name": service_name,
        }
        if not target["host"] or not target["port"]:
            return
        with peer_lock:
            peer_targets[peer_id] = target
            peer_known_nodes[peer_id] = {
                "id": peer_id, "name": target["name"], "platform": target["platform"],
                "connected": bool(peer_devices.get(peer_id, {}).get("connected")),
                "updatedAt": time.time() * 1000.0, "discoveredAt": time.time() * 1000.0,
            }
            peer_service_names[service_name] = peer_id
        # The lower device ID dials. This avoids two competing WebSocket sessions.
        if DEVICE_ID < peer_id:
            _ensure_peer_worker(peer_id)
    except Exception as exc:
        print(f"SciNET peer discovery failed: {type(exc).__name__}")


class SciNETPeerServiceListener(ServiceListener):
    def add_service(self, zeroconf, service_type, name):
        _remember_peer_service(zeroconf, service_type, name)

    def update_service(self, zeroconf, service_type, name):
        _remember_peer_service(zeroconf, service_type, name)

    def remove_service(self, zeroconf, service_type, name):
        with peer_lock:
            peer_id = peer_service_names.pop(name, None)
            if peer_id and not any(value == peer_id for value in peer_service_names.values()):
                peer_targets.pop(peer_id, None)
                known = peer_known_nodes.get(peer_id)
                if known:
                    known["connected"] = False
                    known["updatedAt"] = time.time() * 1000.0




def register_mdns():
    global mdns, peer_browser
    peer_stop.clear()
    address = local_ip()
    if address == "127.0.0.1":
        return
    try:
        mdns = Zeroconf(ip_version=IPVersion.V4Only)
        web_info = ServiceInfo(
            "_http._tcp.local.",
            "SciNET Link._http._tcp.local.",
            addresses=[socket.inet_aton(address)],
            port=8000,
            server="scinet.local.",
            properties={"path": "/", "app": "SciNET Link"},
        )
        peer_info = ServiceInfo(
            PEER_SERVICE_TYPE,
            f"SciNET-{DEVICE_ID[:8]}.{PEER_SERVICE_TYPE}",
            addresses=[socket.inet_aton(address)],
            port=PEER_PORT,
            server="scinet.local.",
            properties={
                b"id": DEVICE_ID.encode("utf-8"),
                b"name": DEVICE_NAME.encode("utf-8")[:60],
                b"platform": b"windows",
                b"protocol": str(PEER_PROTOCOL_VERSION).encode("ascii"),
                b"ws": b"/ws/peer",
            },
        )
        mdns.async_register_service(web_info)
        mdns.async_register_service(peer_info)
        peer_browser = ServiceBrowser(mdns, PEER_SERVICE_TYPE, listener=SciNETPeerServiceListener())
        print("SciNET Link: http://scinet.local:8000")
        print(f"SciNET Link: http://{address}:8000")
        print(f"SciNET peer node: {DEVICE_ID[:8]} ({address}:{PEER_PORT})")
    except Exception as exc:
        print(f"SciNET mDNS unavailable: {exc}")
        if mdns:
            mdns.close()
            mdns = None
            peer_browser = None


def start_ftp():
    global ftp_server, ftp_thread
    address = local_ip()
    authorizer = DummyAuthorizer()
    authorizer.add_user("scinet", "scinet", str(STORAGE_DIR), perm="elradfmwMT")
    authorizer.add_anonymous(str(STORAGE_DIR), perm="elr")
    class SciNETFTPHandler(FTPHandler):
        def on_connect(self):
            device_id = f"ftp:{self.remote_ip}:{self.remote_port}"
            with connected_lock: connected_devices[device_id] = {"id": device_id, "ip": self.remote_ip, "port": self.remote_port, "type": "FTP", "agent": "FTP client", "connected": time.time()}
            log_event(f"FTP device connected from {self.remote_ip}", "connect")
        def on_disconnect(self):
            with connected_lock: connected_devices.pop(f"ftp:{self.remote_ip}:{self.remote_port}", None)
            log_event(f"FTP device disconnected from {self.remote_ip}", "disconnect")
    SciNETFTPHandler.authorizer = authorizer
    SciNETFTPHandler.passive_ports = range(30000, 30010)
    SciNETFTPHandler.masquerade_address = address
    ftp_server = FTPServer((address, 2121), SciNETFTPHandler)
    ftp_server.max_cons = 20
    ftp_server.max_cons_per_ip = 5
    def serve():
        try:
            print(f"SciNET FTP: ftp://{address}:2121")
            ftp_server.serve_forever(timeout=1, blocking=True, handle_exit=False)
        except Exception as exc: print(f"SciNET FTP unavailable: {exc}")
    ftp_thread = threading.Thread(target=serve, name="SciNET-FTP", daemon=True)
    ftp_thread.start()


def stop_ftp():
    global ftp_server
    if ftp_server: ftp_server.close_all(); ftp_server = None


def unregister_mdns():
    global mdns, peer_browser
    peer_stop.set()
    if peer_browser:
        try:
            peer_browser.cancel()
        except Exception:
            pass
        peer_browser = None
    with peer_lock:
        peer_targets.clear()
        peer_service_names.clear()
        peer_known_nodes.clear()
        peer_remote_links.clear()
    if mdns:
        mdns.close()
        mdns = None


def safe_path(relative=""):
    raw = str(relative or "").replace("\\", "/")
    path = (STORAGE_DIR / raw).resolve(); root = STORAGE_DIR.resolve()
    if path != root and root not in path.parents: raise ValueError("Invalid path")
    return path


def file_entry(path):
    stat = path.stat()
    return {"name": path.name, "path": path.relative_to(STORAGE_DIR).as_posix(), "type": "folder" if path.is_dir() else "file", "size": stat.st_size if path.is_file() else 0, "modified": stat.st_mtime}


@asynccontextmanager
async def lifespan(app):
    register_mdns(); start_ftp(); log_event("SciNET Link started", "system")
    yield
    log_event("SciNET Link stopped", "system"); stop_ftp(); unregister_mdns()


app = FastAPI(title="SciNET Link", docs_url=None, redoc_url=None, lifespan=lifespan)
app.mount("/static", StaticFiles(directory=WEB_DIR), name="static")


def system_info():
    memory = psutil.virtual_memory(); disk = psutil.disk_usage(str(BASE_DIR)); net = psutil.net_io_counters()
    now = time.time()
    with connected_lock:
        for device_id, device in list(connected_devices.items()):
            if device.get("type") == "Android" and now - device.get("connected", now) > 12:
                connected_devices.pop(device_id, None)
        clients = list(connected_devices.values())
    unique = {}
    for device in clients:
        ip = device.get("ip", "unknown")
        if ip not in unique or device.get("type") == "Web":
            unique[ip] = device
    with peer_lock:
        peer_snapshot = [dict(peer) for peer in peer_devices.values()]
    host = {"id": DEVICE_ID, "ip": local_ip(), "port": 8000, "type": "Host", "agent": socket.gethostname(), "connected": started_at}
    for peer in peer_snapshot:
        if not peer.get("connected") or not peer.get("ip") or peer.get("ip") == host["ip"]:
            continue
        unique[peer["ip"]] = {
            "id": peer["id"],
            "ip": peer["ip"],
            "port": peer.get("port", 0),
            "type": str(peer.get("platform") or "Peer").title(),
            "agent": peer.get("name") or "SciNET peer",
            "connected": peer.get("connected_at") or started_at,
        }
    devices = [host] + [d for d in unique.values() if d.get("ip") != host["ip"]]
    with device_names_lock:
        for device in devices:
            device["name"] = device_names.get(device.get("id"), device.get("agent") or device.get("type") or "Device")
    with uploads_lock: active_uploads = [dict(item) for item in uploads.values() if item["status"] in {"uploading", "paused"}]
    return {"hostname": socket.gethostname(), "cpu": psutil.cpu_percent(interval=None), "memory": {"percent": memory.percent, "used": memory.used, "total": memory.total}, "disk": {"percent": disk.percent, "used": disk.used, "total": disk.total}, "network": {"sent": net.bytes_sent, "received": net.bytes_recv}, "uptime": int(time.time() - started_at), "devices": devices, "device_count": len(devices), "uploads": active_uploads, "peer_network": {"device_id": DEVICE_ID, "device_name": DEVICE_NAME, "protocol_version": PEER_PROTOCOL_VERSION, "peers": peer_snapshot, "topology": _peer_topology_snapshot()}}


@app.get("/", response_class=HTMLResponse)
async def dashboard(): return (WEB_DIR / "index.html").read_text(encoding="utf-8")

@app.get("/files", response_class=HTMLResponse)
async def file_manager_page(): return (WEB_DIR / "files.html").read_text(encoding="utf-8")

@app.put("/api/device-name")
async def set_device_name(request: Request):
    client = request.client
    if not client:
        return JSONResponse({"error": "Could not identify this device"}, status_code=400)
    is_android = request.headers.get("user-agent", "").startswith("SciNET Android")
    device_id = f"android:{client.host}" if is_android else f"web:{client.host}"
    data = await request.json()
    name = str(data.get("name") or "").strip()[:40]
    if not name:
        return JSONResponse({"error": "Device name cannot be empty"}, status_code=400)
    with connected_lock:
        if device_id not in connected_devices:
            return JSONResponse({"error": "This browser is not connected to the dashboard"}, status_code=409)
    with device_names_lock:
        device_names[device_id] = name
        try:
            DEVICE_NAMES_FILE.write_text(json.dumps(device_names, ensure_ascii=False, indent=2), encoding="utf-8")
        except OSError:
            return JSONResponse({"error": "Could not save device name"}, status_code=500)
    log_event(f"Device renamed: {name}", "system")
    return {"ok": True, "name": name}

@app.get("/api/peer/info")
async def api_peer_info():
    with peer_lock:
        peers = [dict(peer) for peer in peer_devices.values()]
    return {
        "deviceId": DEVICE_ID,
        "deviceName": DEVICE_NAME,
        "platform": "windows",
        "protocolVersion": PEER_PROTOCOL_VERSION,
        "port": PEER_PORT,
        "peers": peers,
        "topology": _peer_topology_snapshot(),
    }


@app.get("/api/peer/topology")
async def api_peer_topology():
    return _peer_topology_snapshot()


@app.get("/api/system")
async def api_system(request: Request):
    client = request.client
    if client and request.headers.get("user-agent", "").startswith("SciNET Android"):
        device_id = f"android:{client.host}"
        with connected_lock:
            connected_devices[device_id] = {
                "id": device_id, "ip": client.host, "port": client.port,
                "type": "Android", "agent": "SciNET Android", "connected": time.time()
            }
    payload = system_info()
    if client and request.headers.get("user-agent", "").startswith("SciNET Android"):
        payload["self_device_id"] = device_id
    return payload

@app.get("/control", response_class=HTMLResponse)
async def control_panel_page(): return (WEB_DIR / "control.html").read_text(encoding="utf-8")

class DesktopVideoTrack(VideoStreamTrack):
    """Low-latency Windows desktop track for WebRTC."""
    def __init__(self, width=1280, height=720, fps=30):
        super().__init__()
        self.width = width
        self.height = height
        self.fps = fps
        self._monitor = None
        self._capture = None
        self._next_pts = 0
        self._frame_step = max(1, int(90000 / fps))
        self._frame_interval = 1.0 / fps
        self._next_frame_time = 0.0
        self._closed = False

    async def recv(self):
        if self._closed:
            raise asyncio.CancelledError
        now = time.monotonic()
        if self._next_frame_time > now:
            await asyncio.sleep(self._next_frame_time - now)
        self._next_frame_time = max(self._next_frame_time + self._frame_interval, time.monotonic())
        if self._capture is None:
            self._capture = mss.mss()
            self._monitor = self._capture.monitors[1]
        shot = await asyncio.to_thread(self._capture.grab, self._monitor)
        image = Image.frombytes("RGB", shot.size, shot.rgb)
        # Resolution is a maximum quality tier, not a forced 16:9 canvas. Preserve
        # the source monitor aspect ratio so 1080p does not add oversized letterboxes.
        scale = min(self.width / shot.width, self.height / shot.height)
        output_w = max(2, int(shot.width * scale) // 2 * 2)
        output_h = max(2, int(shot.height * scale) // 2 * 2)
        if image.size != (output_w, output_h):
            image = image.resize((output_w, output_h), Image.Resampling.BILINEAR)
        class POINT(ctypes.Structure):
            _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]
        point = POINT()
        if ctypes.windll.user32.GetCursorPos(ctypes.byref(point)):
            px = point.x - self._monitor["left"]
            py = point.y - self._monitor["top"]
            screen_w, screen_h = shot.size
            # The output frame now uses the monitor's aspect ratio with no padding.
            scale_x = image.width / screen_w
            scale_y = image.height / screen_h
            px = int(px * scale_x)
            py = int(py * scale_y)
            draw = ImageDraw.Draw(image)
            cursor = [(px, py), (px, py + 22), (px + 5, py + 17), (px + 9, py + 25),
                      (px + 13, py + 23), (px + 9, py + 15), (px + 17, py + 15)]
            draw.polygon(cursor, fill="white", outline="black", width=2)
        frame = av.VideoFrame.from_image(image)
        frame.pts = self._next_pts
        frame.time_base = Fraction(1, 90000)
        self._next_pts += self._frame_step
        return frame

    async def stop(self):
        self._closed = True
        if self._capture is not None:
            try:
                self._capture.close()
            except Exception:
                pass
            self._capture = None
        super().stop()


@app.get("/api/screen/stream")
async def screen_stream(request: Request, resolution: str = "720p", fps: int = 24):
    """Stream desktop JPEG frames directly to the native Android viewer."""
    client = request.client
    if not client:
        return JSONResponse({"error": "Could not identify this device"}, status_code=400)
    try:
        remote_ip = ipaddress.ip_address(client.host)
        if not (remote_ip.is_private or remote_ip.is_loopback or remote_ip.is_link_local):
            return JSONResponse({"error": "Screen viewing is only available on a private local network"}, status_code=403)
    except ValueError:
        return JSONResponse({"error": "Invalid client address"}, status_code=400)
    if platform.system() != "Windows":
        return JSONResponse({"error": "Screen mirroring is supported on Windows only"}, status_code=501)

    resolutions = {
        "480p": (854, 480),
        "720p": (1280, 720),
        "1080p": (1920, 1080),
    }
    if resolution not in resolutions:
        return JSONResponse({"error": "Unsupported resolution"}, status_code=400)
    if fps not in (15, 24, 30):
        return JSONResponse({"error": "FPS must be 15, 24, or 30"}, status_code=400)

    width, height = resolutions[resolution]
    track = DesktopVideoTrack(width, height, fps)

    async def generate_frames():
        try:
            while not await request.is_disconnected():
                frame = await track.recv()
                image = frame.to_image()
                buffer = io.BytesIO()
                image.save(buffer, format="JPEG", quality=76, optimize=False)
                jpeg = buffer.getvalue()
                yield (
                    b"--frame\r\n"
                    b"Content-Type: image/jpeg\r\n"
                    + f"Content-Length: {len(jpeg)}\r\n\r\n".encode("ascii")
                    + jpeg
                    + b"\r\n"
                )
        finally:
            await track.stop()

    return StreamingResponse(
        generate_frames(),
        media_type="multipart/x-mixed-replace; boundary=frame",
        headers={
            "Cache-Control": "no-store, no-cache, must-revalidate",
            "Pragma": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )


@app.post("/api/screen/offer")
async def screen_offer(request: Request):
    client = request.client
    if not client:
        return JSONResponse({"error": "Could not identify this device"}, status_code=400)
    try:
        remote_ip = ipaddress.ip_address(client.host)
        if not (remote_ip.is_private or remote_ip.is_loopback or remote_ip.is_link_local):
            return JSONResponse({"error": "Screen viewing is only available on a private local network"}, status_code=403)
    except ValueError:
        return JSONResponse({"error": "Invalid client address"}, status_code=400)
    if platform.system() != "Windows":
        return JSONResponse({"error": "Screen mirroring is supported on Windows only"}, status_code=501)
    try:
        data = await request.json()
        sdp = str(data.get("sdp") or "")
        kind = str(data.get("type") or "")
        if not sdp or kind != "offer":
            return JSONResponse({"error": "Invalid WebRTC offer"}, status_code=400)
        resolutions = {
            "480p": (854, 480),
            "720p": (1280, 720),
            "1080p": (1920, 1080),
        }
        resolution = str(data.get("resolution") or "720p")
        if resolution not in resolutions:
            return JSONResponse({"error": "Unsupported resolution"}, status_code=400)
        try:
            fps = int(data.get("fps", 30))
        except (TypeError, ValueError):
            fps = 30
        if fps not in (15, 24, 30):
            return JSONResponse({"error": "FPS must be 15, 24, or 30"}, status_code=400)
        width, height = resolutions[resolution]
        pc = RTCPeerConnection()
        track = DesktopVideoTrack(width, height, fps)
        pc.addTrack(track)
        pc._scinet_client_host = client.host
        screen_peers.add(pc)

        @pc.on("connectionstatechange")
        async def on_connection_state_change():
            if pc.connectionState in ("failed", "closed", "disconnected"):
                screen_peers.discard(pc)
                await pc.close()

        await pc.setRemoteDescription(RTCSessionDescription(sdp=sdp, type=kind))
        answer = await pc.createAnswer()
        await pc.setLocalDescription(answer)
        deadline = time.monotonic() + 8
        while pc.iceGatheringState != "complete" and time.monotonic() < deadline:
            await asyncio.sleep(0.05)
        return {"sdp": pc.localDescription.sdp, "type": pc.localDescription.type}
    except Exception as exc:
        log_event(f"WebRTC setup failed: {type(exc).__name__}", "control")
        return JSONResponse({"error": "Could not start WebRTC screen stream. Check SciNET dependencies and the Windows desktop session."}, status_code=500)


@app.post("/api/screen/stop")
async def screen_stop(request: Request):
    client = request.client
    if not client:
        return JSONResponse({"error": "Could not identify this device"}, status_code=400)
    try:
        remote_ip = ipaddress.ip_address(client.host)
        if not (remote_ip.is_private or remote_ip.is_loopback or remote_ip.is_link_local):
            return JSONResponse({"error": "Screen viewing is only available on a private local network"}, status_code=403)
    except ValueError:
        return JSONResponse({"error": "Invalid client address"}, status_code=400)
    # WebRTC peer connections are scoped to this server process; closing peers releases
    # capture and encoder resources immediately when the viewer stops.
    for pc in list(screen_peers):
        if getattr(pc, "_scinet_client_host", None) != client.host:
            continue
        try:
            await pc.close()
        finally:
            screen_peers.discard(pc)
    return {"ok": True}




# Process Manager: process data is queried only when the user explicitly requests it.
_PROCESS_PROTECTED_NAMES = {
    "system", "registry", "smss.exe", "csrss.exe", "wininit.exe", "winlogon.exe",
    "services.exe", "lsass.exe", "svchost.exe", "fontdrvhost.exe", "dwm.exe",
    "secure system", "memory compression", "explorer.exe", "spoolsv.exe",
}
_PROCESS_ACTIONS = {"stop", "force-stop", "minimize", "maximize"}


def _process_is_protected(proc, name, exe):
    """Conservative guardrail: never terminate SciNET itself or core Windows processes."""
    if proc.pid in {os.getpid(), os.getppid(), 0, 4} or name.lower() in _PROCESS_PROTECTED_NAMES:
        return True
    try:
        command = " ".join(proc.cmdline()).lower()
        if "server.py" in command and ("uvicorn" in command or "python" in name.lower()):
            return True
    except (psutil.AccessDenied, psutil.NoSuchProcess, psutil.ZombieProcess):
        pass
    windows_root = os.environ.get("WINDIR", r"C:\Windows").rstrip("\\/").lower()
    if exe and (exe.lower() == windows_root or exe.lower().startswith(windows_root + "\\")):
        return True
    return False


def _process_record(proc):
    try:
        with proc.oneshot():
            name = proc.name() or "Unknown process"
            exe = ""
            username = ""
            try:
                exe = proc.exe() or ""
            except (psutil.AccessDenied, psutil.NoSuchProcess, psutil.ZombieProcess, OSError):
                pass
            try:
                username = proc.username() or ""
            except (psutil.AccessDenied, psutil.NoSuchProcess, psutil.ZombieProcess, OSError):
                pass
            try:
                memory = proc.memory_info().rss
            except (psutil.AccessDenied, psutil.NoSuchProcess, psutil.ZombieProcess):
                memory = 0
            try:
                cpu = proc.cpu_percent(interval=None)
            except (psutil.AccessDenied, psutil.NoSuchProcess, psutil.ZombieProcess):
                cpu = 0.0
            try:
                created = proc.create_time()
            except (psutil.AccessDenied, psutil.NoSuchProcess, psutil.ZombieProcess):
                created = None
            try:
                status = proc.status()
            except (psutil.AccessDenied, psutil.NoSuchProcess, psutil.ZombieProcess):
                status = "unknown"
        current_user = getpass.getuser().lower()
        user_short = username.rsplit("\\", 1)[-1].lower()
        name_lower = name.lower()
        windows_root = os.environ.get("WINDIR", r"C:\Windows").rstrip("\\/").lower()
        is_windows_path = bool(exe and (exe.lower().startswith(windows_root + "\\") or exe.lower() == windows_root))
        system_accounts = {"system", "local service", "network service", "nt authority\\system",
                           "nt authority\\local service", "nt authority\\network service"}
        if user_short == current_user and not is_windows_path:
            category = "user"
        elif username.lower() in system_accounts or user_short in {"system", "local service", "network service"} or is_windows_path:
            category = "system"
        else:
            category = "background"
        protected = _process_is_protected(proc, name, exe)
        return {
            "pid": proc.pid, "name": name, "exe": exe, "username": username or "Unavailable",
            "category": category, "status": status, "cpu_percent": round(max(0.0, float(cpu)), 1),
            "memory_bytes": int(memory), "created_at": created, "protected": protected,
            "can_restart": category == "user" and bool(exe) and not protected,
        }
    except (psutil.NoSuchProcess, psutil.ZombieProcess, OSError):
        return None


def _visible_process_windows():
    """Return one useful visible top-level window title for each process."""
    if platform.system() != "Windows":
        return {}
    windows = {}
    user32 = ctypes.windll.user32
    enum_proc_type = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    def collect_window(hwnd, _lparam):
        if not user32.IsWindowVisible(hwnd):
            return True
        length = user32.GetWindowTextLengthW(hwnd)
        if length <= 0:
            return True
        title_buffer = ctypes.create_unicode_buffer(min(length + 1, 1024))
        user32.GetWindowTextW(hwnd, title_buffer, len(title_buffer))
        title = title_buffer.value.strip()
        if not title:
            return True
        window_pid = ctypes.c_ulong()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(window_pid))
        if window_pid.value and window_pid.value not in windows:
            windows[window_pid.value] = title
        return True
    callback = enum_proc_type(collect_window)
    user32.EnumWindows(callback, 0)
    return windows


def _check_process_request(request):
    client = request.client
    if not client:
        return JSONResponse({"error": "Could not identify this device"}, status_code=400)
    try:
        remote_ip = ipaddress.ip_address(client.host)
        # Tailscale commonly assigns 100.64.0.0/10 addresses, which Python's
        # ipaddress module does not classify as RFC1918 private addresses.
        tailscale_range = ipaddress.ip_network("100.64.0.0/10")
        if not (remote_ip.is_private or remote_ip.is_loopback or remote_ip.is_link_local or remote_ip in tailscale_range):
            return JSONResponse({"error": "Process management is only available on a private local network or Tailscale"}, status_code=403)
    except ValueError:
        return JSONResponse({"error": "Invalid client address"}, status_code=400)
    if platform.system() != "Windows":
        return JSONResponse({"error": "Process management is supported on Windows hosts only"}, status_code=501)
    return None


# This event is disabled at startup. Process enumeration only runs while the UI explicitly enables it.
_process_monitoring_enabled = threading.Event()


@app.post("/api/processes/monitor")
async def set_process_monitoring(request: Request):
    denied = _check_process_request(request)
    if denied:
        return denied
    try:
        data = await request.json()
    except Exception:
        return JSONResponse({"error": "Invalid monitoring request"}, status_code=400)
    if bool(data.get("enabled")):
        _process_monitoring_enabled.set()
        return {"ok": True, "enabled": True}
    _process_monitoring_enabled.clear()
    return {"ok": True, "enabled": False}


def _collect_process_snapshot():
    records = []
    window_titles = _visible_process_windows()
    for proc in psutil.process_iter():
        # Hiding the UI clears this event; stop collecting as soon as practical.
        if not _process_monitoring_enabled.is_set():
            return None
        record = _process_record(proc)
        if record is not None:
            record["window_title"] = window_titles.get(record["pid"], "")
            record["app_name"] = Path(record["exe"]).stem if record["exe"] else record["name"]
            records.append(record)
    if not _process_monitoring_enabled.is_set():
        return None
    records.sort(key=lambda item: (item["category"], item["name"].lower(), item["pid"]))
    memory = psutil.virtual_memory()
    cpu_total = psutil.cpu_percent(interval=None)
    return {
        "ok": True, "processes": records, "total": len(records),
        "cpu_percent": round(cpu_total, 1), "memory_total": int(memory.total),
        "memory_used": int(memory.used), "memory_percent": round(memory.percent, 1),
        "current_user": getpass.getuser(),
    }


@app.get("/api/processes")
async def list_processes(request: Request):
    denied = _check_process_request(request)
    if denied:
        return denied
    if not _process_monitoring_enabled.is_set():
        return JSONResponse({"error": "Process monitoring is not enabled"}, status_code=409)
    # Keep synchronous Windows/psutil enumeration off SciNET's asyncio event loop.
    result = await asyncio.to_thread(_collect_process_snapshot)
    if result is None or not _process_monitoring_enabled.is_set():
        return JSONResponse({"error": "Process monitoring was stopped"}, status_code=409)
    log_event("Process list viewed from Control page", "control")
    return result

@app.post("/api/processes/action")
async def process_action(request: Request):
    denied = _check_process_request(request)
    if denied:
        return denied
    try:
        data = await request.json()
        pid = int(data.get("pid"))
        action = str(data.get("action") or "").strip().lower()
    except (ValueError, TypeError, AttributeError):
        return JSONResponse({"error": "Provide a valid process ID and action"}, status_code=400)
    if pid <= 0 or action not in _PROCESS_ACTIONS:
        return JSONResponse({"error": "Invalid process ID or action"}, status_code=400)
    try:
        proc = psutil.Process(pid)
        record = _process_record(proc)
        if record is None:
            return JSONResponse({"error": "That process is no longer running. Refresh the list."}, status_code=404)
        if record["protected"]:
            return JSONResponse({"error": "This process is protected because it is critical to Windows or SciNET."}, status_code=403)
        if action in {"minimize", "maximize"}:
            user32 = ctypes.windll.user32
            windows = []
            enum_proc_type = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
            def collect_window(hwnd, _lparam):
                window_pid = ctypes.c_ulong()
                user32.GetWindowThreadProcessId(hwnd, ctypes.byref(window_pid))
                if window_pid.value == pid and user32.IsWindowVisible(hwnd):
                    windows.append(hwnd)
                return True
            callback = enum_proc_type(collect_window)
            user32.EnumWindows(callback, 0)
            if not windows:
                return JSONResponse({"error": "No visible window was found for this process."}, status_code=404)
            user32.ShowWindow(windows[0], 6 if action == "minimize" else 3)
            log_event(f"Process window {action}: {record['name']} (PID {pid})", "control")
            return {"ok": True, "message": f"{record['name']} window {action}d."}
        if action == "force-stop":
            proc.kill()
        else:
            proc.terminate()
            try:
                proc.wait(timeout=3)
            except psutil.TimeoutExpired:
                return JSONResponse({"error": "The app did not exit within 3 seconds. Use Force stop if you accept the risk of losing unsaved work."}, status_code=409)
        log_event(f"Process {action}: {record['name']} (PID {pid})", "control")
        return {"ok": True, "message": f"{record['name']} (PID {pid}) stopped."}
    except psutil.NoSuchProcess:
        return JSONResponse({"error": "That process has already exited. Refresh the list."}, status_code=404)
    except psutil.AccessDenied:
        return JSONResponse({"error": "Windows denied access to this process. Try running SciNET with appropriate permissions only if you trust the request."}, status_code=403)
    except (OSError, psutil.Error) as exc:
        return JSONResponse({"error": f"Could not complete process action: {type(exc).__name__}"}, status_code=500)


@app.get("/manifest.webmanifest")
async def pwa_manifest(): return FileResponse(WEB_DIR / "manifest.webmanifest", media_type="application/manifest+json")

@app.get("/sw.js")
async def service_worker():
    return FileResponse(WEB_DIR / "sw.js", media_type="application/javascript", headers={"Service-Worker-Allowed": "/"})

def _send_windows_mouse(dx, dy):
    """Inject uncoalesced relative mouse movement using cached Windows SendInput types."""
    if not hasattr(_send_windows_mouse, "_api"):
        user32 = ctypes.WinDLL("user32", use_last_error=True)

        class KEYBDINPUT(ctypes.Structure):
            _fields_ = [
                ("wVk", ctypes.c_uint16),
                ("wScan", ctypes.c_uint16),
                ("dwFlags", ctypes.c_uint32),
                ("time", ctypes.c_uint32),
                ("dwExtraInfo", ctypes.c_size_t),
            ]

        class MOUSEINPUT(ctypes.Structure):
            _fields_ = [
                ("dx", ctypes.c_int32),
                ("dy", ctypes.c_int32),
                ("mouseData", ctypes.c_uint32),
                ("dwFlags", ctypes.c_uint32),
                ("time", ctypes.c_uint32),
                ("dwExtraInfo", ctypes.c_size_t),
            ]

        class HARDWAREINPUT(ctypes.Structure):
            _fields_ = [
                ("uMsg", ctypes.c_uint32),
                ("wParamL", ctypes.c_uint16),
                ("wParamH", ctypes.c_uint16),
            ]

        class INPUTUNION(ctypes.Union):
            _fields_ = [
                ("ki", KEYBDINPUT),
                ("mi", MOUSEINPUT),
                ("hi", HARDWAREINPUT),
            ]

        class INPUT(ctypes.Structure):
            _fields_ = [("type", ctypes.c_uint32), ("union", INPUTUNION)]

        user32.SendInput.argtypes = (ctypes.c_uint, ctypes.POINTER(INPUT), ctypes.c_int)
        user32.SendInput.restype = ctypes.c_uint
        _send_windows_mouse._api = user32
        _send_windows_mouse._input_type = INPUT
        _send_windows_mouse._mouse_type = MOUSEINPUT

    packet = _send_windows_mouse._input_type()
    packet.type = 0  # INPUT_MOUSE
    packet.union.mi = _send_windows_mouse._mouse_type(
        int(dx), int(dy), 0,
        0x0001 | 0x2000,  # MOUSEEVENTF_MOVE | MOUSEEVENTF_MOVE_NOCOALESCE
        0, 0
    )
    sent = _send_windows_mouse._api.SendInput(
        1, ctypes.byref(packet), ctypes.sizeof(packet)
    )
    if sent != 1:
        error_code = ctypes.get_last_error()
        raise OSError(error_code, "Windows rejected the camera movement event.")


def _send_windows_key(vk_code, down):
    """Inject a physical-style keyboard event using Windows SendInput scan codes."""
    user32 = ctypes.WinDLL("user32", use_last_error=True)
    user32.MapVirtualKeyW.argtypes = (ctypes.c_uint, ctypes.c_uint)
    user32.MapVirtualKeyW.restype = ctypes.c_uint
    # MAPVK_VK_TO_VSC_EX preserves the extended-key prefix (0xE0/0xE1).
    vk_code = int(vk_code)
    # VK_RETURN has a distinct extended scan code on the numeric keypad.
    scan_code = 0xE01C if vk_code == 0x10D else int(user32.MapVirtualKeyW(vk_code, 4))
    if not scan_code:
        raise OSError("Windows could not map the requested keyboard key.")

    flags = 0x0008  # KEYEVENTF_SCANCODE
    if scan_code & 0xFF00:
        flags |= 0x0001  # KEYEVENTF_EXTENDEDKEY
    if not down:
        flags |= 0x0002  # KEYEVENTF_KEYUP

    class KEYBDINPUT(ctypes.Structure):
        _fields_ = [
            ("wVk", ctypes.c_uint16),
            ("wScan", ctypes.c_uint16),
            ("dwFlags", ctypes.c_uint32),
            ("time", ctypes.c_uint32),
            ("dwExtraInfo", ctypes.c_size_t),
        ]

    class MOUSEINPUT(ctypes.Structure):
        _fields_ = [
            ("dx", ctypes.c_int32),
            ("dy", ctypes.c_int32),
            ("mouseData", ctypes.c_uint32),
            ("dwFlags", ctypes.c_uint32),
            ("time", ctypes.c_uint32),
            ("dwExtraInfo", ctypes.c_size_t),
        ]

    class HARDWAREINPUT(ctypes.Structure):
        _fields_ = [
            ("uMsg", ctypes.c_uint32),
            ("wParamL", ctypes.c_uint16),
            ("wParamH", ctypes.c_uint16),
        ]

    class INPUTUNION(ctypes.Union):
        _fields_ = [
            ("ki", KEYBDINPUT),
            ("mi", MOUSEINPUT),
            ("hi", HARDWAREINPUT),
        ]

    class INPUT(ctypes.Structure):
        _fields_ = [("type", ctypes.c_uint32), ("union", INPUTUNION)]

    packet = INPUT()
    packet.type = 1  # INPUT_KEYBOARD
    packet.union.ki = KEYBDINPUT(0, scan_code & 0xFF, flags, 0, 0)

    user32.SendInput.argtypes = (ctypes.c_uint, ctypes.POINTER(INPUT), ctypes.c_int)
    user32.SendInput.restype = ctypes.c_uint
    sent = user32.SendInput(1, ctypes.byref(packet), ctypes.sizeof(INPUT))
    if sent != 1:
        error_code = ctypes.get_last_error()
        raise OSError(error_code, "Windows rejected the keyboard input event.")
    return True


# Fixed GTA V Story Mode cheat codes exposed by the Android controller.
# Keep this allowlist in sync with gtaVStoryModeCheats in ControllerScreen.kt.
_GTA_V_CONTROLLER_CHEATS = {
    "PAINKILLER", "TURTLE", "POWERUP", "CATCHME",
    "GOTGILLS", "HOPTOIT", "LIQUOR", "SKYFALL",
    "SKYDIVE", "DEADEYE", "TOOLUP", "HOTHANDS",
    "HIGHEX", "INCENDIARY", "FUGITIVE", "LAWYERUP",
    "MAKEITRAIN", "FLOATER", "SNOWDAY", "SLOWMO",
    "BUZZOFF", "COMET", "RAPIDGT", "ROCKET",
    "OFFROAD", "BANDIT", "HOLEIN1", "VINEWOOD",
    "TRASHED", "BARNSTORM", "FLYSPRAY", "EXTINCT",
    "DEATHCAR", "BUBBLES", "JRTALENT",
}


def _is_gta_v_foreground():
    """Only inject game shortcuts while a supported GTA V executable is focused."""
    user32 = ctypes.WinDLL("user32", use_last_error=True)
    user32.GetForegroundWindow.restype = ctypes.c_void_p
    user32.GetWindowThreadProcessId.argtypes = (ctypes.c_void_p, ctypes.POINTER(ctypes.c_ulong))
    user32.GetWindowThreadProcessId.restype = ctypes.c_ulong
    hwnd = user32.GetForegroundWindow()
    if not hwnd:
        return False
    process_id = ctypes.c_ulong()
    user32.GetWindowThreadProcessId(hwnd, ctypes.byref(process_id))
    if not process_id.value:
        return False
    try:
        process_name = psutil.Process(process_id.value).name().lower()
    except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
        return False
    return process_name in {"gta5.exe", "gta5_enhanced.exe"}


def _send_windows_unicode_text(text):
    """Type ASCII cheat text through the Windows Unicode keyboard input path."""
    user32 = ctypes.windll.user32
    for char in text:
        code_unit = ord(char)
        user32.keybd_event(0, code_unit, 0x0004, 0)
        user32.keybd_event(0, code_unit, 0x0004 | 0x0002, 0)


@app.post("/api/game/cheat")
async def activate_gta_v_cheat(request: Request):
    client = request.client
    if not client:
        return JSONResponse({"error": "Could not identify this device"}, status_code=400)
    try:
        remote_ip = ipaddress.ip_address(client.host)
        if not (remote_ip.is_private or remote_ip.is_loopback or remote_ip.is_link_local):
            return JSONResponse({"error": "Game shortcuts are only available on a private local network"}, status_code=403)
    except ValueError:
        return JSONResponse({"error": "Invalid client address"}, status_code=400)
    if platform.system() != "Windows":
        return JSONResponse({"error": "GTA V shortcuts are supported on Windows only"}, status_code=501)
    try:
        data = await request.json()
    except Exception:
        return JSONResponse({"error": "Invalid request"}, status_code=400)

    code = str(data.get("code") or "").strip().upper()
    if code not in _GTA_V_CONTROLLER_CHEATS:
        return JSONResponse({"error": "That GTA V shortcut is not available"}, status_code=400)
    if not _is_gta_v_foreground():
        return JSONResponse({"error": "Bring GTA V Story Mode to the foreground first"}, status_code=409)

    try:
        # VK_OEM_3 is the grave/tilde key on the standard US keyboard layout.
        _send_windows_key(0xC0, True)
        await asyncio.sleep(0.08)
        _send_windows_key(0xC0, False)
        await asyncio.sleep(0.12)
        _send_windows_unicode_text(code)
        await asyncio.sleep(0.06)
        _send_windows_key(0x0D, True)
        await asyncio.sleep(0.03)
        _send_windows_key(0x0D, False)
    except OSError as exc:
        return JSONResponse({"error": str(exc) or "Windows rejected the game shortcut"}, status_code=500)

    log_event(f"GTA V Story Mode shortcut submitted: {code}", "control")
    return {"ok": True, "code": code}


@app.post("/api/control")
async def control_host(request: Request):
    client = request.client
    if not client:
        return JSONResponse({"error": "Could not identify this device"}, status_code=400)
    try:
        remote_ip = ipaddress.ip_address(client.host)
        if not (remote_ip.is_private or remote_ip.is_loopback or remote_ip.is_link_local):
            return JSONResponse({"error": "Controls are only available on a private local network"}, status_code=403)
    except ValueError:
        return JSONResponse({"error": "Invalid client address"}, status_code=400)
    if platform.system() != "Windows":
        return JSONResponse({"error": "Host controls are supported on Windows only"}, status_code=501)
    try:
        data = await request.json()
    except Exception:
        return JSONResponse({"error": "Invalid request"}, status_code=400)
    action = str(data.get("action") or "").strip().lower()
    user32 = ctypes.windll.user32
    media_keys = {
        "play-pause": 0xB3, "next": 0xB0, "previous": 0xB1,
        "volume-up": 0xAF, "volume-down": 0xAE, "mute": 0xAD,
    }
    if action in media_keys:
        key = media_keys[action]
        user32.keybd_event(key, 0, 0, 0)
        user32.keybd_event(key, 0, 2, 0)
        log_event(f"Remote control: {action}", "control")
        return {"ok": True, "action": action}
    modifier_codes = {"ctrl": 0x11, "alt": 0x12, "shift": 0x10, "meta": 0x5B}
    key_codes = {
        "esc": 0x1B, "tab": 0x09, "enter": 0x0D, "backspace": 0x08,
        "delete": 0x2E, "insert": 0x2D, "space": 0x20,
        "shift": 0x10, "ctrl": 0x11, "alt": 0x12, "meta": 0x5B,
        "capslock": 0x14, "apps": 0x5D,
        "up": 0x26, "down": 0x28, "left": 0x25, "right": 0x27,
        "home": 0x24, "end": 0x23, "pageup": 0x21, "pagedown": 0x22,
        "printscreen": 0x2C, "scrolllock": 0x91, "pause": 0x13, "numlock": 0x90,
        "grave": 0xC0, "minus": 0xBD, "equal": 0xBB,
        "left-bracket": 0xDB, "right-bracket": 0xDD,
        "backslash": 0xDC, "semicolon": 0xBA, "apostrophe": 0xDE,
        "comma": 0xBC, "period": 0xBE, "slash": 0xBF, "oem-102": 0xE2,
        "numpad-divide": 0x6F, "numpad-multiply": 0x6A,
        "numpad-subtract": 0x6D, "numpad-add": 0x6B,
        "numpad-enter": 0x10D, "numpad-decimal": 0x6E,
        "numpad-separator": 0x6C,
        **{f"f{i}": 0x70 + i - 1 for i in range(1, 25)},
        **{chr(i): i - 32 for i in range(ord("a"), ord("z") + 1)},
        **{str(i): 0x30 + i for i in range(10)},
        **{f"numpad{i}": 0x60 + i for i in range(10)},
    }
    if action == "modifier":
        key_name = str(data.get("key") or "").lower()
        if key_name not in modifier_codes:
            return JSONResponse({"error": "Unsupported modifier"}, status_code=400)
        down = bool(data.get("down"))
        # Windows logo keys are extended keys; flag them explicitly so the
        # shell receives Win shortcuts consistently (Win+R, Win+E, etc.).
        flags = 0x0001 if key_name == "meta" else 0
        if not down:
            flags |= 0x0002
        user32.keybd_event(modifier_codes[key_name], 0, flags, 0)
        return {"ok": True, "action": f"{key_name}-{'down' if down else 'up'}"}
    if action == "key":
        key_name = str(data.get("key") or "").lower()
        if key_name not in key_codes:
            return JSONResponse({"error": "Unsupported key"}, status_code=400)
        # Apply any modifiers selected in the UI for this one-shot key press.
        temporary = []
        for name in data.get("modifiers", []):
            if name in modifier_codes and name not in {"ctrl", "alt", "shift", "meta"}:
                continue
            if name in modifier_codes:
                code = modifier_codes[name]
                # The Windows logo key is an extended key; set this flag
                # for both key-down and key-up so Win+R / Win+E reach the shell.
                flags = 0x0001 if name == "meta" else 0
                user32.keybd_event(code, 0, flags, 0)
                temporary.append((code, flags))
        code = key_codes[key_name]
        try:
            repeat_count = max(1, min(100, int(data.get("repeat", 1))))
        except (TypeError, ValueError):
            repeat_count = 1
        for _ in range(repeat_count):
            user32.keybd_event(code, 0, 0, 0)
            user32.keybd_event(code, 0, 2, 0)
        for code, flags in reversed(temporary):
            user32.keybd_event(code, 0, flags | 0x0002, 0)
        log_event(f"Remote key: {key_name}", "control")
        return {"ok": True, "action": key_name, "repeat": repeat_count}
    if action == "key-hold":
        key_name = str(data.get("key") or "").lower()
        if key_name not in key_codes:
            return JSONResponse({"error": "Unsupported held key"}, status_code=400)
        down = bool(data.get("down"))
        _send_windows_key(key_codes[key_name], down)
        log_event(f"Controller key: {key_name} {'down' if down else 'up'}", "control")
        return {"ok": True, "action": f"{key_name}-{'down' if down else 'up'}"}
    if action == "type":
        text_to_type = str(data.get("text") or "")[:4000]
        # KEYEVENTF_UNICODE sends text without relying on the host keyboard layout.
        for char in text_to_type:
            code_unit = ord(char)
            if code_unit > 0xFFFF:
                code_unit -= 0x10000
                units = (0xD800 + (code_unit >> 10), 0xDC00 + (code_unit & 0x3FF))
            else:
                units = (code_unit,)
            for unit in units:
                user32.keybd_event(0, unit, 0x0004, 0)
                user32.keybd_event(0, unit, 0x0004 | 0x0002, 0)
        return {"ok": True, "action": "text"}
    if action == "mouse-position":
        try:
            x = max(0.0, min(1.0, float(data.get("x", 0))))
            y = max(0.0, min(1.0, float(data.get("y", 0))))
        except (TypeError, ValueError):
            return JSONResponse({"error": "Invalid mouse position"}, status_code=400)
        # Map normalized viewer coordinates to the primary Windows display.
        width = user32.GetSystemMetrics(0)
        height = user32.GetSystemMetrics(1)
        if width <= 0 or height <= 0:
            return JSONResponse({"error": "Could not determine desktop dimensions"}, status_code=500)
        user32.SetCursorPos(round(x * (width - 1)), round(y * (height - 1)))
        return {"ok": True, "action": "mouse-position"}
    if action == "mouse-move":
        try:
            dx = max(-1200, min(1200, int(data.get("dx", 0))))
            dy = max(-1200, min(1200, int(data.get("dy", 0))))
        except (TypeError, ValueError):
            return JSONResponse({"error": "Invalid mouse movement"}, status_code=400)
        # Send relative motion so games receive camera input rather than an
        # absolute desktop-cursor reposition.
        if dx or dy:
            user32.mouse_event(0x0001, dx, dy, 0, 0)  # MOUSEEVENTF_MOVE
        return {"ok": True, "action": "mouse-move"}
    if action == "mouse-scroll":
        try:
            delta = max(-1200, min(1200, int(data.get("delta", 0))))
        except (TypeError, ValueError):
            return JSONResponse({"error": "Invalid scroll amount"}, status_code=400)
        if delta:
            user32.mouse_event(0x0800, 0, 0, delta, 0)  # MOUSEEVENTF_WHEEL
        return {"ok": True, "action": "mouse-scroll"}
    if action == "mouse-hold":
        button = str(data.get("button") or "left").lower()
        flags = {"left": (0x0002, 0x0004), "right": (0x0008, 0x0010)}
        if button not in flags:
            return JSONResponse({"error": "Unsupported mouse button"}, status_code=400)
        down_flag, up_flag = flags[button]
        user32.mouse_event(down_flag if bool(data.get("down")) else up_flag, 0, 0, 0, 0)
        state = "down" if bool(data.get("down")) else "up"
        return {"ok": True, "action": f"{button}-{state}"}
    if action == "mouse-click":
        button = str(data.get("button") or "left").lower()
        click_flags = {"left": (0x0002, 0x0004), "right": (0x0008, 0x0010)}
        if button not in click_flags:
            return JSONResponse({"error": "Unsupported mouse button"}, status_code=400)
        down_flag, up_flag = click_flags[button]
        user32.mouse_event(down_flag, 0, 0, 0, 0)
        user32.mouse_event(up_flag, 0, 0, 0, 0)
        return {"ok": True, "action": f"{button}-click"}
    if action == "lock":
        if not user32.LockWorkStation():
            return JSONResponse({"error": "Windows could not lock the workstation"}, status_code=500)
        log_event("PC locked remotely", "control")
        return {"ok": True, "action": action}
    if action == "sleep":
        try:
            result = ctypes.windll.powrprof.SetSuspendState(False, True, False)
        except Exception:
            result = False
        if not result:
            return JSONResponse({"error": "Windows could not enter sleep. Check system power settings."}, status_code=500)
        log_event("PC put to sleep remotely", "control")
        return {"ok": True, "action": action}
    if action == "restart":
        try:
            subprocess.Popen(["shutdown.exe", "/r", "/t", "0"], creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        except OSError:
            return JSONResponse({"error": "Could not request Windows restart"}, status_code=500)
        log_event("PC restart requested remotely", "control")
        return {"ok": True, "action": action}
    if action == "shutdown":
        try:
            subprocess.Popen(["shutdown.exe", "/s", "/t", "0"], creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        except OSError:
            return JSONResponse({"error": "Could not request Windows shutdown"}, status_code=500)
        log_event("Immediate PC shutdown requested remotely", "control")
        return {"ok": True, "action": action}
    return JSONResponse({"error": "Unknown control action"}, status_code=400)


@app.get("/api/files")
async def list_files(path: str = ""):
    try: directory = safe_path(path)
    except ValueError: return JSONResponse({"error":"Invalid path"},status_code=400)
    if not directory.is_dir(): return JSONResponse({"error":"Folder not found"},status_code=404)
    entries=[file_entry(item) for item in directory.iterdir() if not item.name.startswith(".scinet-") and item.name != ".gitkeep"]; entries.sort(key=lambda x:(x["type"]!="folder",x["name"].lower()))
    parent=str(Path(path).parent).replace("\\","/") if path else ""
    if parent==".": parent=""
    return {"path":path.replace("\\","/").strip("/"),"parent":parent,"files":entries}

@app.get("/api/files/content")
async def read_text_file(path: str):
    try:
        target = safe_path(path)
        if not target.is_file():
            return JSONResponse({"error": "File not found"}, status_code=404)
        if target.stat().st_size > 2 * 1024 * 1024:
            return JSONResponse({"error": "Text editor supports files up to 2 MB"}, status_code=413)
        raw = target.read_bytes()
        try:
            content = raw.decode("utf-8")
        except UnicodeDecodeError:
            return JSONResponse({"error": "This file is not valid UTF-8 text"}, status_code=415)
        return {"path": target.relative_to(STORAGE_DIR).as_posix(), "name": target.name, "content": content, "size": len(raw)}
    except ValueError:
        return JSONResponse({"error": "Invalid path"}, status_code=400)


@app.put("/api/files/content")
async def write_text_file(request: Request):
    data = await request.json()
    try:
        target = safe_path(data.get("path", ""))
        content = data.get("content")
        if not target.is_file():
            return JSONResponse({"error": "File not found"}, status_code=404)
        if not isinstance(content, str):
            return JSONResponse({"error": "Text content is required"}, status_code=400)
        encoded = content.encode("utf-8")
        if len(encoded) > 2 * 1024 * 1024:
            return JSONResponse({"error": "Text editor supports files up to 2 MB"}, status_code=413)
        target.write_bytes(encoded)
        log_event(f"Edited: {target.name}", "file")
        return {"ok": True, "size": len(encoded)}
    except ValueError:
        return JSONResponse({"error": "Invalid path"}, status_code=400)
    except OSError:
        return JSONResponse({"error": "Could not save file"}, status_code=500)


@app.get("/api/files/preview")
async def preview_file(path: str):
    try:
        target = safe_path(path)
        if not target.is_file():
            return JSONResponse({"error": "File not found"}, status_code=404)
        import mimetypes
        media_type = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        if media_type.startswith(("text/", "image/")) or media_type == "application/pdf":
            return FileResponse(target, media_type=media_type, headers={"Content-Disposition": f'inline; filename="{target.name.replace(chr(34), "")}"'})
        return FileResponse(target, media_type=media_type, filename=target.name)
    except ValueError:
        return JSONResponse({"error": "Invalid path"}, status_code=400)


@app.post("/api/folders")
async def create_folder(request: Request):
    data=await request.json(); name=Path(str(data.get("name") or "New Folder")).name
    try: (safe_path(data.get("path",""))/name).mkdir(exist_ok=False)
    except FileExistsError: return JSONResponse({"error":"Folder already exists"},status_code=409)
    except (ValueError,OSError): return JSONResponse({"error":"Could not create folder"},status_code=400)
    log_event(f"Folder created: {name}","file"); return {"ok":True}

@app.post("/api/files/upload")
async def upload_files(request: Request, path: str = ""):
    try:
        directory = safe_path(path)
        if not directory.is_dir():
            return JSONResponse({"error": "Destination folder not found"}, status_code=404)
        form = await request.form()
        incoming = form.getlist("files")
        if not incoming:
            return JSONResponse({"error": "Choose at least one file"}, status_code=400)
        uploaded = []
        for item in incoming:
            if not hasattr(item, "filename") or not item.filename:
                continue
            name = Path(str(item.filename).replace("\\", "/")).name
            if not name or name in {".", ".."}:
                continue
            target = directory / name
            if target.exists():
                for prior in uploaded:
                    (directory / prior["name"]).unlink(missing_ok=True)
                return JSONResponse({"error": f"A file named '{name}' already exists in this folder. No files were uploaded."}, status_code=409)
            temp = directory / f".scinet-{uuid.uuid4().hex}.part"
            try:
                with temp.open("xb") as output:
                    while chunk := await item.read(1024 * 1024):
                        output.write(chunk)
                if target.exists():
                    raise FileExistsError(name)
                temp.replace(target)
                uploaded.append({"name": name, "size": target.stat().st_size})
            except Exception:
                temp.unlink(missing_ok=True)
                for prior in uploaded:
                    (directory / prior["name"]).unlink(missing_ok=True)
                raise
            finally:
                await item.close()
        if not uploaded:
            return JSONResponse({"error": "No valid files were selected"}, status_code=400)
        for item in uploaded:
            log_event(f"File uploaded: {item['name']}", "file")
        return {"ok": True, "path": directory.relative_to(STORAGE_DIR).as_posix() if directory != STORAGE_DIR else "", "files": uploaded}
    except ValueError:
        return JSONResponse({"error": "Invalid destination path"}, status_code=400)
    except FileExistsError as exc:
        return JSONResponse({"error": f"A file named '{exc.args[0]}' already exists in this folder"}, status_code=409)
    except OSError:
        return JSONResponse({"error": "Could not save uploaded files"}, status_code=500)

@app.delete("/api/files")
async def delete_file(path: str):
    try:
        target=safe_path(path)
        if target==STORAGE_DIR: raise ValueError()
        if target.is_dir():
            import shutil; shutil.rmtree(target)
        elif target.is_file(): target.unlink()
        else: return JSONResponse({"error":"Not found"},status_code=404)
    except (ValueError,OSError): return JSONResponse({"error":"Could not delete"},status_code=400)
    log_event(f"Deleted: {Path(path).name}","file"); return {"ok":True}

@app.post("/api/files/rename")
async def rename_file(request: Request):
    data=await request.json()
    try:
        source=safe_path(data.get("path","")); name=Path(str(data.get("name") or "")).name
        if not name: raise ValueError()
        source.rename(source.parent/name)
    except (ValueError,OSError): return JSONResponse({"error":"Could not rename"},status_code=400)
    log_event(f"Renamed: {source.name} → {name}","file"); return {"ok":True}

@app.post("/api/upload/start")
async def upload_start(request: Request):
    data=await request.json(); filename=Path(str(data.get("name") or "unnamed")).name; relative_path=str(data.get("path") or "").strip("/\\"); total=max(0,int(data.get("size") or 0)); upload_id=uuid.uuid4().hex
    try: directory=safe_path(relative_path); directory.mkdir(parents=True,exist_ok=True)
    except (ValueError,OSError): return JSONResponse({"error":"Invalid destination"},status_code=400)
    temp=directory/f".scinet-{upload_id}.part"; temp.touch()
    with uploads_lock: uploads[upload_id]={"id":upload_id,"name":filename,"path":relative_path,"size":0,"total":total,"speed":0,"status":"uploading","started":time.monotonic(),"last_chunk":time.monotonic(),"temp":str(temp)}
    log_event(f"Upload started: {filename}","transfer"); return {"id":upload_id}

@app.post("/api/upload/{upload_id}/chunk")
async def upload_chunk(upload_id: str,request: Request):
    with uploads_lock:
        item=uploads.get(upload_id)
        if not item: return JSONResponse({"error":"Upload not found"},status_code=404)
        if item["status"]!="uploading": return JSONResponse({"error":"Upload is not active"},status_code=409)
        temp=Path(item["temp"])
    chunk=await request.body()
    if not chunk: return {"received":0,"total":item["total"]}
    supplied=request.headers.get("x-chunk-sha256","").lower()
    actual=hashlib.sha256(chunk).hexdigest()
    if not supplied or supplied != actual:
        return JSONResponse({"error":"Chunk integrity check failed; retry this chunk"},status_code=422)
    with uploads_lock:
        item=uploads.get(upload_id)
        if not item: return JSONResponse({"error":"Upload not found"},status_code=404)
        if item["status"]!="uploading": return JSONResponse({"error":"Upload is not active"},status_code=409)
        if item["size"] + len(chunk) > item["total"]:
            return JSONResponse({"error":"Chunk exceeds expected file size"},status_code=413)
        with temp.open("ab") as output:
            output.write(chunk)
            output.flush()
        item["size"]+=len(chunk); now=time.monotonic(); elapsed=max(now-item["last_chunk"],.001); item["speed"]=len(chunk)/elapsed; item["last_chunk"]=now
        return {"received":item["size"],"total":item["total"]}

@app.post("/api/upload/{upload_id}/pause")
async def pause_upload(upload_id: str):
    with uploads_lock:
        if upload_id not in uploads: return JSONResponse({"error":"Upload not found"},status_code=404)
        current=uploads[upload_id]["status"]
        if current=="uploading": uploads[upload_id]["status"]="paused"
        elif current=="paused": uploads[upload_id]["status"]="uploading"
        else: return JSONResponse({"status":current})
        return {"status":uploads[upload_id]["status"]}

@app.post("/api/upload/{upload_id}/finish")
async def finish_upload(upload_id: str):
    with uploads_lock:
        item=uploads.get(upload_id)
        if not item: return JSONResponse({"error":"Upload not found"},status_code=404)
        if item["status"]!="uploading": return JSONResponse({"error":"Upload is paused or inactive"},status_code=409)
        temp=Path(item["temp"]); target=safe_path(item["path"])/item["name"]
        if item["size"]!=item["total"] or not temp.is_file() or temp.stat().st_size!=item["total"]:
            return JSONResponse({"error":"Upload is incomplete or size verification failed"},status_code=400)
        if target.exists():
            return JSONResponse({"error":"A file with this name already exists"},status_code=409)
    temp.replace(target)
    with uploads_lock: uploads.pop(upload_id,None)
    log_event(f"Upload completed: {item['name']}","transfer"); return {"id":upload_id,"name":item["name"],"size":item["size"],"status":"complete"}

@app.delete("/api/upload/{upload_id}")
async def cancel_upload(upload_id: str):
    with uploads_lock: item=uploads.pop(upload_id,None)
    if not item: return JSONResponse({"error":"Upload not found"},status_code=404)
    Path(item["temp"]).unlink(missing_ok=True); log_event(f"Upload cancelled: {item['name']}","transfer"); return {"id":upload_id,"status":"cancelled"}

@app.get("/api/download/{filename}")
async def download_file(filename: str):
    target=STORAGE_DIR/Path(filename).name
    if not target.is_file(): return JSONResponse({"error":"File not found"},status_code=404)
    log_event(f"Download requested: {target.name}","transfer"); return FileResponse(target,filename=target.name)

@app.get("/api/download")
async def download_path(path: str):
    try: target=safe_path(path)
    except ValueError: return JSONResponse({"error":"Invalid path"},status_code=400)
    if not target.is_file(): return JSONResponse({"error":"File not found"},status_code=404)
    log_event(f"Download requested: {target.name}","transfer"); return FileResponse(target,filename=target.name)

@app.get("/api/qr")
async def qr_code():
    image=qrcode.make(f"http://{local_ip()}:8000"); buffer=io.BytesIO(); image.save(buffer,format="PNG"); buffer.seek(0); return StreamingResponse(buffer,media_type="image/png")

@app.get("/api/events")
async def get_events():
    with events_lock: return {"events":list(events)}

@app.delete("/api/events")
async def clear_events():
    with events_lock: events.clear()
    return {"ok":True}

@app.get("/api/clipboard")
async def get_clipboard():
    with clipboard_lock: return dict(clipboard)

@app.post("/api/clipboard")
async def set_clipboard(request: Request):
    data=await request.json(); text=str(data.get("text") or "")
    with clipboard_lock: clipboard.update({"text":text,"updated":time.time(),"source":str(data.get("source") or "SciNET")}); payload=dict(clipboard)
    stale=[]
    for client in tuple(clipboard_clients):
        try: await client.send_json(payload)
        except Exception: stale.append(client)
    for client in stale: clipboard_clients.discard(client)
    log_event("Clipboard updated","system"); return {"ok":True}

@app.websocket("/ws/peer")
async def peer_control_socket(websocket: WebSocket):
    """Versioned peer presence channel. Commands and file bytes are intentionally not handled here."""
    client = websocket.client
    if not client:
        await websocket.close(code=1008, reason="Could not identify this device")
        return
    try:
        remote_ip = ipaddress.ip_address(client.host)
        if not (remote_ip.is_private or remote_ip.is_loopback or remote_ip.is_link_local):
            await websocket.close(code=1008, reason="SciNET peers must be on a private local network")
            return
    except ValueError:
        await websocket.close(code=1008, reason="Invalid peer address")
        return

    await websocket.accept()
    try:
        hello = await asyncio.wait_for(websocket.receive_json(), timeout=8)
    except Exception:
        await websocket.close(code=1008, reason="Peer hello timed out")
        return

    peer_id = str(hello.get("deviceId") or "")
    version = hello.get("protocolVersion")
    if (
        hello.get("type") != "hello"
        or not peer_id
        or len(peer_id) > 64
        or any(char not in "0123456789abcdef-" for char in peer_id.lower())
        or peer_id == DEVICE_ID
        or version != PEER_PROTOCOL_VERSION
    ):
        await websocket.close(code=1008, reason="Unsupported SciNET peer handshake")
        return

    connection_id = uuid.uuid4().hex
    if isinstance(hello.get("topology"), dict):
        _merge_peer_topology(hello["topology"])
    peer_payload = {
        "name": str(hello.get("deviceName") or "SciNET device")[:80],
        "platform": str(hello.get("platform") or "unknown")[:24],
        "ip": client.host,
        "port": int(hello.get("port") or (8765 if str(hello.get("platform", "")).lower() == "android" else PEER_PORT)),
        "transport": "websocket",
    }
    _set_peer_presence(peer_id, peer_payload, True, connection_id)
    await websocket.send_json(_peer_welcome())
    log_event(f"SciNET peer connected: {peer_payload['name']}", "connect")

    try:
        while True:
            message = await websocket.receive_json()
            if not isinstance(message, dict):
                continue
            kind = message.get("type")
            if kind == "heartbeat":
                with peer_lock:
                    record = peer_devices.get(peer_id)
                    if record and record.get("connection_id") == connection_id:
                        record["last_seen"] = time.time()
                if isinstance(message.get("topology"), dict):
                    _merge_peer_topology(message["topology"])
                await websocket.send_json({
                    "type": "heartbeat_ack",
                    "deviceId": DEVICE_ID,
                    "sentAt": message.get("sentAt"),
                    "topology": _peer_topology_snapshot(),
                })
    except (WebSocketDisconnect, Exception):
        pass
    finally:
        with peer_lock:
            record = peer_devices.get(peer_id)
            if record and record.get("connection_id") == connection_id:
                _set_peer_presence(peer_id, record, False)
        log_event(f"SciNET peer disconnected: {peer_payload['name']}", "disconnect")



@app.websocket("/ws/clipboard")
async def clipboard_socket(websocket: WebSocket):
    await websocket.accept()
    clipboard_clients.add(websocket)
    try:
        with clipboard_lock:
            await websocket.send_json(dict(clipboard))
        while True:
            data=await websocket.receive_json()
            text=str(data.get("text") or "")
            source=str(data.get("source") or "SciNET")
            with clipboard_lock:
                clipboard.update({"text":text,"updated":time.time(),"source":source})
                payload=dict(clipboard)
            stale=[]
            for client in tuple(clipboard_clients):
                if client is websocket:
                    continue
                try:
                    await client.send_json(payload)
                except Exception:
                    stale.append(client)
            for client in stale:
                clipboard_clients.discard(client)
            log_event("Clipboard updated","system")
    except (WebSocketDisconnect, Exception):
        pass
    finally:
        clipboard_clients.discard(websocket)

@app.websocket("/ws/controller")
async def controller_socket(websocket: WebSocket):
    """Persistent private-LAN channel for low-latency relative game-camera input."""
    client = websocket.client
    if not client:
        await websocket.close(code=1008, reason="Could not identify this device")
        return
    try:
        remote_ip = ipaddress.ip_address(client.host)
        if not (remote_ip.is_private or remote_ip.is_loopback or remote_ip.is_link_local):
            await websocket.close(code=1008, reason="Controller is available on a private local network only")
            return
    except ValueError:
        await websocket.close(code=1008, reason="Invalid client address")
        return
    if platform.system() != "Windows":
        await websocket.close(code=1011, reason="Host controls are supported on Windows only")
        return

    await websocket.accept()
    user32 = ctypes.windll.user32
    try:
        while True:
            payload = await websocket.receive_bytes()
            if len(payload) != 8:
                await websocket.send_json({"error": "Invalid camera movement packet"})
                continue
            dx, dy = struct.unpack("<ii", payload)
            dx = max(-1200, min(1200, dx))
            dy = max(-1200, min(1200, dy))
            if dx or dy:
                _send_windows_mouse(dx, dy)
    except WebSocketDisconnect:
        pass
    except Exception:
        pass


@app.websocket("/ws/joystick")
async def joystick_socket(websocket: WebSocket):
    """Expose a real analog left stick through a virtual Xbox 360 controller."""
    client = websocket.client
    if not client:
        await websocket.close(code=1008, reason="Could not identify this device")
        return
    try:
        remote_ip = ipaddress.ip_address(client.host)
        if not (remote_ip.is_private or remote_ip.is_loopback or remote_ip.is_link_local):
            await websocket.close(code=1008, reason="Joystick is available on a private local network only")
            return
    except ValueError:
        await websocket.close(code=1008, reason="Invalid client address")
        return
    if platform.system() != "Windows":
        await websocket.close(code=1011, reason="Virtual gamepad output is supported on Windows only")
        return

    await websocket.accept()
    try:
        import vgamepad as vg
        gamepad = vg.VX360Gamepad()
    except Exception:
        await websocket.send_json({"error": "Joystick mode needs the ViGEmBus virtual gamepad driver installed on this PC. Install ViGEmBus, then restart SciNET."})
        await websocket.close(code=1011, reason="Virtual Xbox controller unavailable")
        return

    try:
        while True:
            payload = await websocket.receive_bytes()
            if len(payload) != 8:
                await websocket.send_json({"error": "Invalid joystick input packet"})
                continue
            axis_x, axis_y = struct.unpack("<ff", payload)
            if not (math.isfinite(axis_x) and math.isfinite(axis_y)):
                axis_x = axis_y = 0.0
            axis_x = max(-1.0, min(1.0, axis_x))
            axis_y = max(-1.0, min(1.0, axis_y))
            magnitude = math.hypot(axis_x, axis_y)
            if magnitude < 0.06:
                axis_x = axis_y = 0.0
            elif magnitude > 0.0:
                scaled = min(1.0, (magnitude - 0.06) / 0.94) / magnitude
                axis_x *= scaled
                axis_y *= scaled
            gamepad.left_joystick(
                x_value=round(axis_x * 32767),
                y_value=round(axis_y * 32767)
            )
            gamepad.update()
    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        try:
            gamepad.reset()
            gamepad.update()
        except Exception:
            pass


@app.websocket("/ws")
async def system_socket(websocket: WebSocket):
    await websocket.accept(); client=websocket.client; ip=client.host if client else "unknown"; device_id=f"web:{ip}"
    user_agent=websocket.headers.get("user-agent","Browser")
    with connected_lock: connected_devices[device_id]={"id":device_id,"ip":ip,"port":client.port if client else 0,"type":"Web","agent":user_agent[:160],"connected":time.time()}
    log_event(f"Web device connected from {ip}","connect")
    try:
        while True:
            with connected_lock:
                has_dashboard = any(item.get("type") == "Web" for item in connected_devices.values())
            if not has_dashboard:
                break
            payload = system_info(); payload["self_device_id"] = device_id; await websocket.send_json(payload)
            await asyncio.sleep(1)
    except (WebSocketDisconnect,Exception): pass
    finally:
        with connected_lock: connected_devices.pop(device_id,None)
        log_event(f"Web device disconnected from {ip}","disconnect")
