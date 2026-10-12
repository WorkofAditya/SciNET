# SciNET Link

SciNET Link is a local dashboard and file-transfer server for the SciNET PC and phone ecosystem.

## Features

- Live CPU, RAM, disk and uptime monitoring
- Local file uploads and downloads with SHA-256 chunk verification and size checks
- A dedicated `shared_files` directory for transferred files, separate from application source
- Live clipboard sync across connected browsers with no send/receive buttons
- Browser-based dashboard for phones and PCs
- Silent Windows launcher and optional automatic sign-in startup
- Designed for a private phone-hotspot network

## Run on Windows

1. Install Python 3.11+.
2. Download or clone this repository to the SciNET PC.
3. Double-click `start.bat` to start SciNET Link silently in the background.
4. On the phone connected to the PC hotspot, open `http://scinet.local:8000` or the PC local IP address on port `8000`.

The first launch creates a `.venv` and installs the dependencies automatically. The background server writes startup and runtime output to `scinet.log` in the repository folder. Since the launcher is intentionally silent, check this log if the server does not start. Stop the server from Task Manager by ending its Python process.

## Start automatically when you sign in

Run `install_startup.bat` once. It creates a shortcut in your Windows Startup folder, so SciNET Link starts silently each time you sign in. To disable automatic startup, remove `SciNET Link.lnk` from the Startup folder. This installs a per-user startup shortcut; it does not configure SciNET as a Windows service.

## Custom local address

SciNET Link is intended to use a local hostname such as `scinet.local`. Local hostname discovery depends on mDNS support on the network and device. The server is also reachable through the PC local IP address on port `8000`.

## Storage

Files uploaded through the dashboard and FTP are stored in `shared_files`. This folder is created automatically and is separate from the source code, so the file manager only exposes the intended shared storage area. Its `.gitkeep` file tracks the empty directory without tracking transferred files.

## Network note

The server listens on the PC network interfaces so a phone on the same private hotspot can reach it. Do not expose this server to an untrusted network. FTP currently uses the configured local credentials and should only be used on a trusted private network.
