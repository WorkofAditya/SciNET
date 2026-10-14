from contextlib import asynccontextmanager
import ctypes
import ipaddress
import platform
import subprocess
from pathlib import Path
import asyncio
import io
import json
import hashlib
import socket
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
from zeroconf import IPVersion, ServiceInfo, Zeroconf
from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer

BASE_DIR = Path(__file__).resolve().parent
STORAGE_DIR = BASE_DIR / "shared_files"
DEVICE_NAMES_FILE = BASE_DIR / "device_names.json"
device_names_lock = threading.Lock()
try:
    device_names = json.loads(DEVICE_NAMES_FILE.read_text(encoding="utf-8")) if DEVICE_NAMES_FILE.exists() else {}
except (OSError, ValueError):
    device_names = {}
WEB_DIR = BASE_DIR / "web"
STORAGE_DIR.mkdir(exist_ok=True)
started_at = time.time()
mdns = None
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


def register_mdns():
    global mdns
    address = local_ip()
    if address == "127.0.0.1": return
    try:
        mdns = Zeroconf(ip_version=IPVersion.V4Only)
        info = ServiceInfo("_http._tcp.local.", "SciNET Link._http._tcp.local.", addresses=[socket.inet_aton(address)], port=8000, server="scinet.local.", properties={"path": "/", "app": "SciNET Link"})
        mdns.async_register_service(info)
        print("SciNET Link: http://scinet.local:8000")
        print(f"SciNET Link: http://{address}:8000")
    except Exception as exc:
        print(f"SciNET mDNS unavailable: {exc}")
        if mdns: mdns.close(); mdns = None


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
    global mdns
    if mdns: mdns.close(); mdns = None


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
    with connected_lock: clients = list(connected_devices.values())
    unique = {}
    for device in clients:
        ip = device.get("ip", "unknown")
        if ip not in unique or device.get("type") == "Web": unique[ip] = device
    host = {"id": "host", "ip": local_ip(), "port": 8000, "type": "Host", "agent": socket.gethostname(), "connected": started_at}
    devices = [host] + [d for d in unique.values() if d.get("ip") != host["ip"]]
    with device_names_lock:
        for device in devices:
            device["name"] = device_names.get(device.get("id"), device.get("agent") or device.get("type") or "Device")
    with uploads_lock: active_uploads = [dict(item) for item in uploads.values() if item["status"] in {"uploading", "paused"}]
    return {"hostname": socket.gethostname(), "cpu": psutil.cpu_percent(interval=None), "memory": {"percent": memory.percent, "used": memory.used, "total": memory.total}, "disk": {"percent": disk.percent, "used": disk.used, "total": disk.total}, "network": {"sent": net.bytes_sent, "received": net.bytes_recv}, "uptime": int(time.time() - started_at), "devices": devices, "device_count": len(devices), "uploads": active_uploads}


@app.get("/", response_class=HTMLResponse)
async def dashboard(): return (WEB_DIR / "index.html").read_text(encoding="utf-8")

@app.get("/files", response_class=HTMLResponse)
async def file_manager_page(): return (WEB_DIR / "files.html").read_text(encoding="utf-8")

@app.put("/api/device-name")
async def set_device_name(request: Request):
    client = request.client
    if not client:
        return JSONResponse({"error": "Could not identify this device"}, status_code=400)
    device_id = f"web:{client.host}"
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

@app.get("/api/system")
async def api_system(): return system_info()

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
        image.thumbnail((self.width, self.height), Image.Resampling.BILINEAR)
        # Pad to an encoder-friendly even size while preserving the entire desktop.
        if image.size != (self.width, self.height):
            canvas = Image.new("RGB", (self.width, self.height), (0, 0, 0))
            left = (self.width - image.width) // 2
            top = (self.height - image.height) // 2
            canvas.paste(image, (left, top))
            image = canvas
        class POINT(ctypes.Structure):
            _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]
        point = POINT()
        if ctypes.windll.user32.GetCursorPos(ctypes.byref(point)):
            px = point.x - self._monitor["left"]
            py = point.y - self._monitor["top"]
            screen_w, screen_h = shot.size
            px = int(px * self.width / screen_w)
            py = int(py * self.height / screen_h)
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


@app.get("/manifest.webmanifest")
async def pwa_manifest(): return FileResponse(WEB_DIR / "manifest.webmanifest", media_type="application/manifest+json")

@app.get("/sw.js")
async def service_worker():
    return FileResponse(WEB_DIR / "sw.js", media_type="application/javascript", headers={"Service-Worker-Allowed": "/"})

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
        "delete": 0x2E, "up": 0x26, "down": 0x28, "left": 0x25,
        "right": 0x27, "home": 0x24, "end": 0x23,
        **{f"f{i}": 0x70 + i - 1 for i in range(1, 13)},
        **{chr(i): i - 32 for i in range(ord("a"), ord("z") + 1)},
        **{str(i): 0x30 + i for i in range(10)},
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
        user32.keybd_event(code, 0, 0, 0)
        user32.keybd_event(code, 0, 2, 0)
        for code, flags in reversed(temporary):
            user32.keybd_event(code, 0, flags | 0x0002, 0)
        log_event(f"Remote key: {key_name}", "control")
        return {"ok": True, "action": key_name}
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
    if action == "mouse-move":
        try:
            dx = max(-1200, min(1200, int(data.get("dx", 0))))
            dy = max(-1200, min(1200, int(data.get("dy", 0))))
        except (TypeError, ValueError):
            return JSONResponse({"error": "Invalid mouse movement"}, status_code=400)
        class POINT(ctypes.Structure):
            _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]
        point = POINT()
        if user32.GetCursorPos(ctypes.byref(point)):
            user32.SetCursorPos(point.x + dx, point.y + dy)
        return {"ok": True, "action": "mouse-move"}
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
    with clipboard_lock: clipboard.update({"text":text,"updated":time.time(),"source":str(data.get("source") or "SciNET")})
    log_event("Clipboard updated","system"); return {"ok":True}

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
