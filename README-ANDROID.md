# SciNET Android client

SciNET's Android client is a native Kotlin + Jetpack Compose application. It does not load the website in a WebView. The Python server, shared storage, Windows input injection, process enumeration, FTP service, and desktop capture remain on the Windows PC.

## Open in Android Studio

1. Open this repository as a Gradle project.
2. Use JDK 17 and install Android SDK 36 if Android Studio prompts for it.
3. Let Gradle sync. The first sync downloads the Android Gradle Plugin, Compose dependencies, and WebRTC SDK.
4. Select the `app` run configuration and launch it on your Android device or emulator.

## Connect

1. Start SciNET on the Windows PC with `start.bat`.
2. Connect the phone and PC to the same reachable private network.
3. Find the PC's local IPv4 address and enter `http://<PC-IP>:8000` in the app.
4. Tap **Connect to PC** the first time. The address is saved on the phone and the app attempts to reconnect automatically on later launches. Use **Log Out / Change PC** at the end of the dashboard to clear it and choose another host.

If the app cannot connect, check Windows Firewall, hotspot/client-isolation settings, and confirm that the server is listening on port 8000.

## Native modules

- **Dashboard:** polls the existing system API once per second while visible and shows host metrics, a live network traffic graph, connected devices, and a compact scrollable activity log.
- **Remote control:** touchpad, scroll-mode toggle, mouse clicks, live text input and special keys, media controls, volume, and confirmed power actions. The live shared clipboard is embedded after System Volume in this screen.
- **Files:** browse shared folders in a thumbnail card grid, upload and download files using Android's document picker, create folders, rename/delete items, preview files externally, and edit supported UTF-8 text files up to the server's 2 MB limit.
- **Live clipboard:** text changes sync automatically with SciNET and connected browser clients; incoming shared text updates the phone clipboard while the app is open. Android OS restrictions mean clipboard monitoring is foreground-only.
- **Processes:** starts monitoring only after explicit user action; supports search, sorting, app grouping, window controls, end-task, and force-stop actions subject to server-side protection.
- **Screen mirroring:** uses the Android WebRTC SDK to receive the existing on-demand desktop stream, with resolution/frame-rate selection and pointer interaction.
- **Keyboard controller:** sends held keyboard keys for games that accept keyboard input. This is not XInput or a virtual Xbox controller.

## Important network and security notes

SciNET is intended for a trusted private network. The server does not provide a full user-account/authentication layer. Anyone who can reach the service may be able to use remote controls and shared-file APIs. Do not expose ports 8000, 2121, or FTP passive ports directly to the public internet.

## Verification status

The native modules and Gradle configuration have been added on `feature/native-android-app`. The project still needs a Gradle sync and an actual Android build/device test before the branch can be considered verified. In particular, validate the native WebRTC receiver against the Windows host and test uploads/downloads on a real Android device.
