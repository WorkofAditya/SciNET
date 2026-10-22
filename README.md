# SciNET-Link

**SciNET-Link** is a lightweight, self-hosted web companion for a Windows PC and the phones or computers connected to the same trusted local network. It brings system monitoring, file sharing, clipboard synchronization, connected-device visibility, and remote PC controls into one browser-based interface.

The application runs on the Windows host. Other devices open it in a browser—no separate mobile app is required. The control interface can also be installed as a Progressive Web App (PWA) on supported browsers.

> **Network and security:** SciNET-Link is designed for a trusted private network, such as a personal phone hotspot or home LAN. It does not provide a full user-account or authentication layer. Do not expose its web server or FTP service directly to the public internet or an untrusted network.

## Contents

- [Features](#features)
- [How it works](#how-it-works)
- [Requirements](#requirements)
- [Installation and startup](#installation-and-startup)
- [Connecting from a phone or another computer](#connecting-from-a-phone-or-another-computer)
- [Using SciNET-Link](#using-scinet-link)
- [File storage](#file-storage)
- [Network services and ports](#network-services-and-ports)
- [Security guidance](#security-guidance)
- [Troubleshooting](#troubleshooting)
- [Project structure](#project-structure)
- [Technology stack](#technology-stack)

## Features

### System dashboard
- Live host system information, including CPU, memory, disk, and uptime details.
- Connected browser and FTP device visibility, with support for naming devices.
- Event activity for important system, connection, file, transfer, and control actions.
- QR code to make opening the local web interface on another device easier.

### File manager and transfers
- Browse the dedicated shared-storage area from a browser.
- Upload and download files, create folders, rename or delete items, and edit supported text files.
- Preview supported files in the browser.
- Chunk-based uploads with transfer tracking and integrity/size checks.
- A separate storage directory keeps shared files apart from application source code.

### Clipboard synchronization
- Synchronize text copied in one connected browser with other connected browsers through a WebSocket connection.
- Clipboard content is held by the running server in memory; it is not intended as permanent storage.

### Remote PC control
The dedicated **PC Control** page includes:
- An on-demand process manager, shown only after selecting **Display current processes**.
- Separate sections for user applications, Windows system activity, and other background processes.
- Process search, sorting by name/CPU/memory/runtime, app grouping by default, manual refresh, and live one-second refresh only while the process list is visible.
- Per-process memory, CPU, PID, account, status, executable path, and runtime details where Windows permissions allow.
- Immediate End task and Force stop actions without confirmation, plus minimize/maximize window controls. Critical Windows and SciNET processes remain protected.
- Media playback controls and Windows media-volume keys.
- A touchpad for pointer movement and left/right mouse clicks.
- Text input from the phone keyboard, plus Ctrl, Alt, Shift, and Windows modifiers.
- Special keys and F1–F12 function keys.
- Windows lock, sleep, restart, and shutdown actions.

Restart and shutdown requests use Windows `shutdown.exe` with a zero-second timer. These are powerful actions: use them only when you intend to affect the host PC.

### Installable web app
- Web app manifest, app icon, and service-worker caching.
- An install prompt where supported by the browser.
- A responsive interface designed for desktop and mobile screens.

## How it works

SciNET-Link runs as a Python web application on the Windows host. The FastAPI application serves the browser interface and API, while Uvicorn runs the web server. A phone or other computer on the same reachable private network opens the host's local address.

The application also advertises a local service name through mDNS when network support permits it, and starts an FTP service for compatible file-transfer clients. Name discovery varies by operating system, router, hotspot, and client device; using the host's local IP address is the fallback.

## Requirements

- Windows PC to act as the host.
- Python **3.11 or newer**, with the Python launcher (`py`) available in Command Prompt.
- Network connectivity between the host and the devices that will use SciNET-Link.
- Internet access on first startup so Python packages can be installed.
- A modern browser on each client device.

## Installation and startup

1. Download or clone this repository to a folder on the Windows host.
2. Install Python 3.11 or newer if it is not already installed. During installation, enable the Python launcher.
3. Double-click **`start.bat`**.
4. Wait for the first startup to finish. The launcher creates a local `.venv` environment if needed, installs the packages from `requirements.txt`, creates `shared_files`, and starts the server.
5. Open the app on the host or a connected device using the address described below.

The launcher starts the worker in a hidden console. Startup and server output are appended to **`scinet.log`** in the project directory. The first run can take longer while dependencies are installed.

### Stop the server

Double-click **`stop.bat`** to stop SciNET-Link processes associated with this project directory. This script is intended to target this project's server rather than unrelated Python processes.

To restart the application, run `start.bat` again after stopping it.

## Connecting from a phone or another computer

1. Connect the phone and the Windows host to the same trusted local network. A phone hotspot is one possible setup: connect the PC to the phone's hotspot, then open the page on the phone.
2. On the host, find its local IPv4 address if you do not already know it.
3. In the client device's browser, open:

   `http://<HOST-IP>:8000`

   Replace `<HOST-IP>` with the Windows host's actual local IP address. For example, if the host address is `192.168.1.25`, open `http://192.168.1.25:8000`.

4. You can also try **`http://scinet.local:8000`** if mDNS name resolution works on your network.

If the page does not load, check Windows Firewall, confirm both devices can reach one another, and use the host IP address instead of the local name. Do not create public router port-forwarding rules for SciNET-Link.

## Using SciNET-Link

### Dashboard
Open the root page to view the system dashboard, connection activity, and available navigation. Use the device controls to assign recognizable names to connected clients where needed.

### Files
Open **Files** from the navigation or visit `/files`. The file manager works inside the application's `shared_files` directory. Use it to manage the files you intentionally share through SciNET-Link.

### PC Control
Open **PC Control** from the dashboard navigation or visit `/control`. The page is designed for use from a phone as well as a desktop browser. For keyboard shortcuts, select a modifier and then tap or type the key combination. Power actions affect the Windows host itself; a sleep, restart, or shutdown will interrupt the connection until the host becomes available again.

#### Process Manager
Process enumeration is deliberately **on-demand**. Press **Display current processes** before the list or system resource summary is fetched or shown. User apps, Windows system activity, and other background activity are shown in separate sections. Search and sorting work across the visible sections; app grouping is enabled by default. While the list is visible, process data refreshes every second. Hiding the list immediately stops polling and clears the displayed snapshot; no process enumeration occurs while the list is hidden.

- **End task** requests a normal process termination and waits briefly.
- **Force stop** immediately kills the selected process. It can cause loss of unsaved work or data corruption.
- **Minimize / Maximize** controls the selected process window when Windows exposes a visible top-level window for it.
- Critical Windows processes and the SciNET server are protected by conservative checks. Windows may still deny access to other processes depending on the account running SciNET.
- Process access is restricted to private/local clients, but the app does not have user authentication. Only use it on a trusted network and do not expose it to the public internet.

### Clipboard
Keep SciNET-Link open in the browsers you want to synchronize. Clipboard synchronization uses the application's live connection; browser clipboard permissions and platform restrictions may affect copying or pasting.

### FTP
FTP clients can connect to the host's local IP address on port **2121**. The current server configuration includes the account `scinet` with password `scinet`, plus anonymous read-only access. FTP passive connections use ports **30000–30009**. These defaults are intended only for a trusted private network and should be changed or disabled before any broader exposure.

## File storage

Shared transfers are stored in **`shared_files/`**, which is created automatically. This directory is separate from the application source tree so that the browser file manager stays within the intended shared area.

- Keep important files backed up elsewhere; SciNET-Link is not a backup system.
- Files in `shared_files/` are user data and should not be committed to source control.
- Temporary upload data may be created while transfers are in progress.
- Device display names are stored in `device_names.json` when the application has permission to write the file.
- Logs are written to `scinet.log` in the project directory.

## Network services and ports

| Service | Default address / port | Purpose |
| --- | --- | --- |
| Web dashboard and API | TCP `8000` | Browser UI, system information, file management, clipboard, and remote control |
| FTP | TCP `2121` | FTP clients and file transfer |
| FTP passive data ports | TCP `30000–30009` | FTP data connections in passive mode |
| Local service discovery | mDNS | Makes `scinet.local` discovery possible where supported |

Only allow the required services on a trusted local network. Firewall configuration and hotspot isolation vary by Windows version and network configuration.

## Security guidance

SciNET-Link can read, modify, transfer, and delete files in its shared directory and can send input or power commands to the host PC. Treat access to the interface as access to those capabilities.

- Use SciNET-Link only on networks you trust.
- Do not expose ports `8000`, `2121`, or the FTP passive port range to the public internet.
- The current FTP configuration uses simple default credentials and allows anonymous read-only access. Anyone who can reach the FTP service may be able to read shared files. Restrict network access and review the FTP configuration before use.
- Anyone who can reach the web interface may be able to use its available functions; do not assume the local network itself provides authentication.
- Be especially careful with remote restart and shutdown controls.
- Keep Windows, Python, and project dependencies updated.

## Troubleshooting

**The browser cannot connect**
- Confirm the SciNET-Link server is running.
- Verify the client and host are on the same reachable network.
- Try the host's local IP address on port `8000`.
- Check Windows Firewall and hotspot/client-isolation settings.

**`scinet.local` does not resolve**
- Local name discovery depends on mDNS support. Use the host's local IP address instead.

**SciNET-Link fails to start**
- Open `scinet.log` in the project directory and look for the first error.
- Confirm Python 3.11+ and the `py` launcher are installed.
- Check that the first-start package installation can access the internet.
- Make sure ports `8000` and `2121` are not already occupied by another process.

**FTP connects but transfers fail**
- Use the correct host IP and port `2121`.
- In passive mode, ensure ports `30000–30009` are reachable on the trusted LAN.
- Review Windows Firewall and the FTP client's connection mode.

**Remote power controls or keyboard input do not work**
- Remote PC controls are intended for a Windows host.
- Run SciNET-Link on the host PC itself and check `scinet.log` for errors.
- Windows power settings, permissions, or other system policy can affect whether an action succeeds.

## Project structure

```text
SciNET-Link/
├── server.py                 # FastAPI application, APIs, FTP and system integrations
├── requirements.txt          # Python dependencies
├── start.bat                 # Hidden Windows launcher and dependency setup
├── stop.bat                  # Stops this project's server processes
├── shared_files/             # User-shared files (created automatically)
├── device_names.json         # Saved display names for connected devices (created as needed)
├── scinet.log                # Startup and runtime log (created at launch)
└── web/
    ├── index.html            # Main dashboard
    ├── files.html            # Browser file manager
    ├── control.html          # Dedicated PC control page
    ├── app.js                # Dashboard behavior
    ├── files.js              # File manager behavior
    ├── control.js            # Remote control and keyboard behavior
    ├── style.css             # Shared application styling
    ├── files.css              # File manager styling
    ├── manifest.webmanifest  # PWA metadata
    ├── sw.js                 # Service worker
    └── icon.svg              # Application icon
```

Some runtime files and directories are generated automatically and may not exist in a fresh checkout.

## Technology stack

- **Python** for the host-side application.
- **FastAPI** for HTTP APIs and WebSocket endpoints.
- **Uvicorn** as the ASGI server.
- **psutil** for system metrics.
- **zeroconf** for local service discovery.
- **pyftpdlib** for FTP service support.
- **qrcode** for QR-code generation.
- **HTML, CSS, and JavaScript** for the browser interface and installable PWA.

## License

No license has been specified in this repository. Unless a license is added, reuse and redistribution are subject to the default copyright rules.
