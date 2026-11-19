# SciNET Remote

**SciNET Remote** is the Android companion for remotely interacting with a Windows PC running the SciNET host. It provides a native Android interface for system monitoring, remote input, shared files, clipboard, process tools, screen streaming and game-controller input.

The Windows host/server and native Android client live in this repository. The Android app is named **SciNET Remote** and uses the distinct Android application ID `com.workofaditya.scinetremote`, so it can be installed alongside SciNET Link.

## Android app

- Kotlin and Jetpack Compose native client (not a WebView).
- Connects to the SciNET Windows host on a trusted local network.
- Dashboard, touchpad and keyboard controls, file management, clipboard, process controls, screen streaming and game-controller modes.

See [README-ANDROID.md](README-ANDROID.md) for Android setup and usage.

## Windows host

The Python host serves the client APIs and legacy web/PWA interface. Run `start.bat` on Windows and connect the Android app to the host's local address.

**Network safety:** SciNET Remote is intended for trusted private networks. Do not expose its host services to the public internet.

## Repository

- `app/`: native Android client.
- `server.py`: Windows host server.
- `legacy-web/`: legacy browser/PWA client.
