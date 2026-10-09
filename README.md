# ⚡ AeroCast Native

> **Touch-Free Air Gesture File Sharing between Windows & Android**  
> *Inspired by Huawei Air Gesture & Apple AirDrop. Zero cords. Zero cloud. Zero friction.*

[![Platform](https://img.shields.io/badge/Platform-Windows%2010%2F11%20%7C%20Android%208.0%2B-blue.svg)](#system-architecture)
[![Python](https://img.shields.io/badge/Python-3.10%2B-3776AB.svg?logo=python&logoColor=white)](#windows-agent-setup)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-7F52FF.svg?logo=kotlin&logoColor=white)](#android-client-setup)
[![MediaPipe](https://img.shields.io/badge/Vision-Google%20MediaPipe%200.10.9-4285F4.svg)](#air-gesture-interaction-mechanics)
[![Network](https://img.shields.io/badge/Transport-TCP%20%7C%20UDP%20%7C%20Bluetooth%20RFCOMM-success.svg)](#triple-tier-hybrid-networking)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](#license)

---

## 📖 Table of Contents
1. [Project Overview](#-project-overview)
2. [Key Ideas & Design Philosophy](#-key-ideas--design-philosophy)
3. [System Architecture](#-system-architecture)
4. [Air Gesture Interaction Mechanics](#-air-gesture-interaction-mechanics)
5. [Triple-Tier Hybrid Networking](#-triple-tier-hybrid-networking)
6. [Repository Structure](#-repository-structure)
7. [Installation & Setup](#-installation--setup)
   - [Windows Host Agent (One-Click Setup)](#1-windows-host-agent-one-click-setup)
   - [Android Client Setup](#2-android-client-setup)
   - [Building the Android APK](#3-building-the-android-apk)
8. [Step-by-Step Usage Guide](#-step-by-step-usage-guide)
   - [Workflow A: Windows PC ➔ Android Phone (Air Send)](#workflow-a-windows-pc--android-phone-air-send)
   - [Workflow B: Android Phone ➔ Windows PC (Air Drop)](#workflow-b-android-phone--windows-pc-air-drop)
   - [Workflow C: Browser / Quick Share HTTP Fallback](#workflow-c-browser--quick-share-http-fallback)
9. [Verification & Test Suite](#-verification--test-suite)
10. [Troubleshooting & FAQ](#-troubleshooting--faq)
11. [Security & Privacy](#-security--privacy)

---

## 🌟 Project Overview

**AeroCast** is a cross-device file sharing system that bridges the gap between Windows PCs and Android devices using natural, touch-free hand gestures.

Instead of hunting for USB cables, emailing files to yourself, or uploading sensitive media to third-party cloud servers, AeroCast lets you:
1. **Right-click any file** in Windows Explorer or tap **Share** on Android.
2. Make a natural **Fist ("Grab" ✊)** gesture to snatch the file into the air.
3. Show an **Open Palm ("Drop" ✋)** gesture to deposit it onto the destination screen.

The file transfers across your local network in milliseconds via high-speed raw TCP streaming, with automatic Bluetooth RFCOMM fallback if your Wi-Fi router isolates clients.

```
┌─────────────────────────────────┐               ┌─────────────────────────────────┐
│       Windows 10 / 11 Host      │               │       Android 8.0+ Client       │
│  ┌───────────────────────────┐  │               │  ┌───────────────────────────┐  │
│  │   Dynamic Capsule HUD     │  │   UDP Beacon  │  │    Modern Glassmorphic    │  │
│  │   (Huawei / Dynamic Island│◄─┼───────────────┼─►│        Control UI         │  │
│  └─────────────┬─────────────┘  │   Signaling   │  └─────────────┬─────────────┘  │
│                │                │               │                │                │
│  ┌─────────────▼─────────────┐  │  Raw TCP Chnk │  ┌─────────────▼─────────────┐  │
│  │  DirectShow Webcam Feed   │  │   (64 KB)     │  │   CameraX Viewfinder      │  │
│  │  MediaPipe HandLandmarker │  │◄═════════════►│  │   MediaPipe HandLandmarker│  │
│  └─────────────┬─────────────┘  │               │  └─────────────┬─────────────┘  │
│                │                │  BT RFCOMM    │                │                │
│  ┌─────────────▼─────────────┐  │   Fallback    │  ┌─────────────▼─────────────┐  │
│  │ Windows Explorer Context  │  │«┄┄┄┄┄┄┄┄┄┄┄┄┄»│  │  Scoped Storage Target    │  │
│  │ Menu + Silent Tray Daemon │  │               │  │  (Downloads / AeroCast)   │  │
│  └───────────────────────────┘  │               │  └───────────────────────────┘  │
└─────────────────────────────────┘               └─────────────────────────────────┘
```

---

## 💡 Key Ideas & Design Philosophy

### 1. Zero Friction ("Air Drop for Windows + Android")
Apple AirDrop proved the immense value of ambient, zero-configuration file sharing. However, Windows and Android have historically lacked a seamless, native-feeling bridge. AeroCast bridges both ecosystems natively:
- **Windows**: Clean system tray daemon with an iOS/Huawei-style floating Dynamic Capsule HUD and native Windows Explorer right-click integration (`Software\Classes\*\shell\AeroCast`).
- **Android**: Modern Kotlin app integrating directly with the Android System Share Sheet (`SEND` / `SEND_MULTIPLE`).

### 2. On-Demand Vision with Battery & Privacy Respect
Continuous camera surveillance drains laptop batteries and raises privacy alarms. AeroCast implements an **On-Demand Vision Watch Window**:
- Webcams/cameras are **closed 99% of the time**.
- When a file is staged or an incoming broadcast arrives, the camera wakes up strictly for a **10-second countdown window**.
- Countdown timing features **DirectShow warm-up compensation**: the timer only begins once the camera has delivered its first decoded frame.
- Instant fallback: If a user cannot make a gesture (poor lighting or no camera), clicking the floating capsule HUD or screen button immediately confirms the transfer.

### 3. Pure Local-First Architecture
- **No Internet Required**: Operates entirely over local Wi-Fi subnet broadcasts or peer-to-peer Bluetooth.
- **End-to-End Cryptographic Validation**: Every payload is checksummed in real-time with SHA-256 to guarantee bit-for-bit file integrity before writing to disk.

---

## 📐 System Architecture

### Windows Host Subsystem (`windows_agent/`)
- **`tray_app.py`**: The central daemon coordinating background threads, IPC sockets, tray icons, and UI events via PyQt6's thread-safe `DaemonBridge` queued signals.
- **`core/dynamic_hud.py`**: A frameless, translucent, glassmorphic capsule that animates down from the top edge of the display. Accompanied by a floating webcam companion viewfinder showing skeleton overlays.
- **`core/gesture_engine.py`**: DirectShow / OpenCV webcam capture worker feeding landmarks into calibrated mathematical gesture classifiers.
- **`core/network_service.py`**: Multi-protocol networking hub managing UDP beacon broadcasts (`42424`), TCP chunk streaming (`42425`), Quick Share HTTP endpoints, and Bluetooth RFCOMM.
- **`explorer_context.py`**: Modifies the Windows Registry (HKCU) to inject "⚡ Air Send with AeroCast" into Explorer's context menu and configures silent auto-start on Windows login.

### Android Client Subsystem (`android_app/`)
- **`MainActivity.kt`**: Jetpack AppCompat coordinator handling runtime permissions, share intents, status cards, and the live CameraX viewfinder.
- **`VisionService.kt`**: High-performance camera pipeline running Google MediaPipe Tasks Vision (`hand_landmarker.task`) with real-time landmark normalization.
- **`GestureOverlayView.kt`**: Custom rendering canvas that projects hand skeletons and joint connection lines over the live camera preview.
- **`NetworkManager.kt`**: Dual-channel network manager coordinating Android `WifiManager.MulticastLock`, UDP broadcast discovery, high-speed TCP streaming, and RFCOMM sockets.

### Shared Layer (`shared/`)
- **`protocol.py`**: Shared binary frame schemas, magic headers (`AERO_CAST_V1`), JSON beacon definitions, network port configurations, and SHA-256 calculation routines.
- **`gesture_math.py`**: Cross-platform landmark geometric definitions and distance ratios.
- **`hand_landmarker.task`**: Pre-compiled MediaPipe hand landmark detection neural model.

---

## ✋ Air Gesture Interaction Mechanics

AeroCast utilizes real-time 3D landmark tracking (21 keypoints per hand) computed by Google MediaPipe. Rather than relying on rigid, failure-prone templates, AeroCast computes normalized anatomical distance ratios relative to the wrist keypoint (`Landmark 0`).

```
         (8) INDEX_TIP        (12) MIDDLE_TIP      (16) RING_TIP
              │                    │                    │
         (7) INDEX_DIP        (11) MIDDLE_DIP      (15) RING_DIP       (20) PINKY_TIP
              │                    │                    │                   │
         (6) INDEX_PIP        (10) MIDDLE_PIP      (14) RING_PIP       (19) PINKY_DIP
              │                    │                    │                   │
         (5) INDEX_MCP        (9)  MIDDLE_MCP      (13) RING_MCP       (18) PINKY_PIP
              ╲                    │                    ╱                   │
   (4) TIP     ╲                   │                   ╱               (17) PINKY_MCP
        │       ╲                  │                  ╱                     ╱
   (3) IP        └─────────────────┴─────────────────┘─────────────────────┘
        │                                  │
   (2) MCP                                 │
        │                                  │
   (1) CMC ──────────────────────── (0) WRIST
```

### 1. Fist / Grab Gesture (`GRAB` ✊) — Air Send Trigger
- **Purpose**: "Snatch" a file into the air from the active screen.
- **Geometric Rule**:
  $$\text{Dist}(\text{Fingertip}_i, \text{Wrist}) \le \text{Dist}(\text{PIP}_i, \text{Wrist}) \times 1.10$$
  *(or relative to $\text{MCP}_i$)*
- **Threshold**: Requires at least **3 out of 4 fingers** curled tightly into the palm.
- **Debounce**: 2 consecutive frames for instantaneous reaction without false positives.

### 2. Open Palm Gesture (`DROP` ✋) — Air Drop Trigger
- **Purpose**: "Release / Catch" an incoming staged file onto the receiving device.
- **Geometric Rule**:
  $$\text{Dist}(\text{Fingertip}_i, \text{Wrist}) \ge \text{Dist}(\text{MCP}_i, \text{Wrist}) \times 1.18$$
- **Threshold**: Requires at least **4 fingers** fully extended away from the wrist with an open hand posture.
- **Debounce**: 2 consecutive frames.

---

## 🌐 Triple-Tier Hybrid Networking

AeroCast ensures files reach their destination regardless of network topology:

| Tier | Protocol / Channel | Port / UUID | Purpose |
| :--- | :--- | :--- | :--- |
| **Tier 1 (Primary)** | UDP Broadcast + High-Speed Raw TCP | UDP `42424`<br>TCP `42425` | 64 KB binary streaming over local Wi-Fi with sub-millisecond signaling latency. |
| **Tier 2 (Web/HTTP)** | Quick Share HTTP Endpoints | TCP `42425`<br>(`/download`, `/upload`) | Allows direct file download or upload from any web browser on the local subnet without the native app. |
| **Tier 3 (Fallback)** | Bluetooth RFCOMM (SPP) | Channel `5`<br>UUID `00001101-0000-1000-8000-00805F9B34FB` | Bypasses router **Wi-Fi Client Isolation** (AP Isolation in universities/hotels) and restrictive firewall rules. |

### Binary Framing Protocol Specification
TCP transmissions begin with a standard 17-byte framed packet:
```
┌─────────────────────────────────┬───────────┬──────────────────┬─────────────────┐
│     MAGIC HEADER (12 Bytes)     │ TYPE (1B) │ LENGTH (4B, BE)  │ PAYLOAD (N Bytes│
│         "AERO_CAST_V1"          │ 0x01-0x0A │ Big-Endian Int32 │ JSON / Raw Data │
└─────────────────────────────────┴───────────┴──────────────────┴─────────────────┘
```

#### Message Types
- `0x01` (`MSG_TYPE_DISCOVERY`): Subnet peer discovery request.
- `0x02` (`MSG_TYPE_DISCOVERY_ACK`): Discovery response with device metadata.
- `0x03` (`MSG_TYPE_STAGE_ARMED`): Beacon signaling a file has been grabbed and is armed in the air.
- `0x04` (`MSG_TYPE_DROP_CONFIRMED`): Receiver signaled open palm drop confirmation.
- `0x05` (`MSG_TYPE_FILE_HEADER`): Metadata header (Filename, Size, SHA-256 Checksum).
- `0x06` (`MSG_TYPE_FILE_DATA`): Raw file data chunk (64 KB).
- `0x07` (`MSG_TYPE_FILE_COMPLETE`): Transfer finalized notification followed by `b"OK"` handshake.
- `0x08` (`MSG_TYPE_CANCEL`): Abort active transfer session.

---

## 📁 Repository Structure

```
Aerocast/
├── .github/
│   └── workflows/
│       └── build_apk.yml           # Automated GitHub Actions CI workflow for Android APK
├── AeroCast_Native/
│   ├── android_app/                # Native Android application
│   │   ├── app/
│   │   │   ├── src/main/
│   │   │   │   ├── java/com/aerocast/app/
│   │   │   │   │   ├── MainActivity.kt       # UI controller & lifecycle manager
│   │   │   │   │   ├── VisionService.kt      # CameraX & MediaPipe hand detection
│   │   │   │   │   ├── GestureOverlayView.kt # Skeleton drawing canvas
│   │   │   │   │   └── NetworkManager.kt     # Wi-Fi TCP/UDP & Bluetooth engine
│   │   │   │   ├── assets/
│   │   │   │   │   └── hand_landmarker.task  # MediaPipe neural model for Android
│   │   │   │   ├── res/                      # Layouts, themes, animations, drawables
│   │   │   │   └── AndroidManifest.xml       # Permissions, Share Sheet intents
│   │   │   ├── build.gradle                  # App dependencies & configurations
│   │   │   └── proguard-rules.pro
│   │   ├── gradle/wrapper/                   # Gradle wrapper binaries
│   │   ├── build.gradle                      # Root project build script
│   │   └── settings.gradle
│   ├── shared/                     # Cross-platform shared core
│   │   ├── protocol.py             # Wire protocol, packet schemas, ports, hashes
│   │   ├── gesture_math.py         # Hand landmark math calculations
│   │   └── hand_landmarker.task    # MediaPipe neural network model
│   ├── windows_agent/              # Windows background daemon & GUI
│   │   ├── assets/                 # Sound effects (.wav) and icons (.ico, .png)
│   │   │   ├── grab.wav / drop.wav # Haptic audio feedback chimes
│   │   │   └── generate_assets.py  # Procedural audio chime & icon generator
│   │   ├── core/
│   │   │   ├── dynamic_hud.py      # Floating capsule HUD & companion camera window
│   │   │   ├── gesture_engine.py   # DirectShow webcam worker & gesture math
│   │   │   └── network_service.py  # Multi-threaded TCP, UDP, HTTP, Bluetooth service
│   │   ├── explorer_context.py     # Windows Explorer right-click integration
│   │   └── tray_app.py             # PyQt6 system tray application
│   ├── build_apk.bat               # 1-Click local Android APK compiler
│   ├── install_windows.bat         # 1-Click Windows installer & auto-start config
│   ├── main.py                     # CLI launcher for Windows agent
│   ├── requirements.txt            # Python dependencies (PyQt6, OpenCV, MediaPipe, etc.)
│   ├── run.bat                     # Quick launcher for background agent
│   ├── run_silent.vbs              # Silent VBS launcher (no console window)
│   └── test_pipeline.py            # Comprehensive unit & integration verification suite
└── README.md                       # Complete documentation & project guide
```

---

## 🚀 Installation & Setup

### 1. Windows Host Agent (One-Click Setup)

#### Requirements
- **OS**: Windows 10 or Windows 11 (64-bit).
- **Python**: Python 3.10 or newer ([python.org](https://www.python.org/downloads/)).  
  *(Make sure **"Add Python to PATH"** is checked during installation!)*
- **Hardware**: Integrated webcam or external USB camera; Wi-Fi and/or Bluetooth adapter.

#### Installation Steps
1. Open PowerShell or Command Prompt inside the `AeroCast_Native` directory:
   ```powershell
   cd c:\Users\ansht\Desktop\Aerocast\AeroCast_Native
   ```
2. Run the automated installer:
   ```cmd
   install_windows.bat
   ```
   **What `install_windows.bat` does automatically:**
   - Creates an isolated Python virtual environment (`venv`).
   - Installs all dependencies from `requirements.txt` (`PyQt6`, `opencv-python`, `mediapipe`, `sounddevice`, `pystray`, etc.).
   - Generates and verifies audio feedback chimes and application icons.
   - Registers **"⚡ Air Send with AeroCast"** directly into Windows Explorer's right-click context menu (no admin rights required).
   - Configures permanent silent auto-start upon Windows boot via the User Registry (`HKCU\...\Run`).
   - Configures Windows Firewall rules for UDP `42424` and TCP `42425`.
   - Starts the background daemon silently via `pythonw.exe`.

3. Look at your Windows System Tray (bottom right near the clock): you will see the **AeroCast** icon active!

---

### 2. Android Client Setup

#### Requirements
- **OS**: Android 8.0 (Oreo / API 26) or higher.
- **Hardware**: Rear or front camera; Wi-Fi connected to the same local network as the PC (or Bluetooth paired).

---

### 3. Building the Android APK

You can build the Android APK in **two different ways**:

#### Method A: Automated Cloud Build via GitHub Actions (Zero Local Setup)
No Android Studio or Java setup required on your computer!
1. Push this repository to your GitHub account:
   ```bash
   git add .
   git commit -m "Initialize AeroCast"
   git push origin main
   ```
2. Navigate to your repository on GitHub and click the **Actions** tab.
3. Select **"Build AeroCast Android APK"** and click **Run workflow**.
4. Once completed (~2 minutes), download the ready-to-install **`AeroCast-debug.apk`** under the **Artifacts** section!
5. Transfer the APK to your phone and install it.

#### Method B: Local Build on Windows via `build_apk.bat`
If you have Java JDK 17+ and the Android SDK installed:
1. Double-click `AeroCast_Native\build_apk.bat` or run:
   ```cmd
   cd AeroCast_Native
   build_apk.bat
   ```
2. The batch script detects your Java and Android SDK installations, invokes Gradle, and outputs:
   ```
   AeroCast_Native\AeroCast-debug.apk
   ```
3. Install the APK onto your device using ADB:
   ```cmd
   adb install -r AeroCast-debug.apk
   ```

---

## 🎮 Step-by-Step Usage Guide

### Workflow A: Windows PC ➔ Android Phone (Air Send)

```
[Windows File Explorer]
      │
      ├─► Right-click any file (.pdf, .mp4, .png, etc.)
      ├─► Select "⚡ Air Send with AeroCast"
      │
[Windows Screen]
      ├─► Dynamic Capsule slides down: "✊ Grab to Cast: document.pdf"
      ├─► Camera viewfinder opens (10s watch window)
      ├─► Make a FIST (✊) in front of the webcam
      ├─► Chime sounds! Capsule turns Blue: "Staged! Finding Devices..."
      │
[Android Phone]
      ├─► Notification / Screen Alert: "📥 Incoming: document.pdf"
      ├─► Camera viewfinder activates (10s watch window)
      ├─► Show an OPEN PALM (✋) to your phone camera
      │
[Transfer Executes]
      ├─► Progress bar fills in real-time (64 KB TCP chunks)
      ├─► Success Chime! File automatically saved in:
      │   Android Downloads/AeroCast/document.pdf
```

---

### Workflow B: Android Phone ➔ Windows PC (Air Drop)

```
[Android Phone]
      │
      ├─► Open AeroCast and tap "Select File"
      │   (or Share any file from Gallery / Files app to AeroCast)
      ├─► Tap "Send Now (Fist)" or make a FIST (✊) at the camera
      ├─► Phone broadcasts UDP beacon: MSG_STAGE_ARMED
      │
[Windows PC]
      ├─► Dynamic Capsule slides down from top edge:
      │   "📥 Incoming: photo.jpg from Mobile - ✋ Show Palm to Drop"
      ├─► Companion webcam preview window pops up
      ├─► Show an OPEN PALM (✋) to your PC webcam
      │   (or simply click the purple Dynamic Capsule)
      │
[Transfer Executes]
      ├─► Real-time transfer speed indicator (e.g., "⚡ 48.2 MB/s")
      ├─► Emerald Green banner: "Transfer Complete!"
      ├─► File automatically saved in:
      │   C:\Users\<Username>\Downloads\AeroCast\photo.jpg
```

---

### Workflow C: Browser / Quick Share HTTP Fallback

If you want to transfer files to an iPad, Mac, Linux laptop, or a phone without the native app installed:
1. Stage any file on your Windows PC via right-click or System Tray ➔ **"Pick File to Air Send"**.
2. Open any web browser on any device connected to the same Wi-Fi.
3. Navigate to:
   ```
   http://<PC_LOCAL_IP>:42425/download
   ```
   *(Your PC local IP is displayed in the system tray menu or status window, e.g., `http://192.168.1.50:42425/download`)*
4. The file streams directly into your browser at maximum Wi-Fi bandwidth!
5. To upload from a browser to your PC:
   ```bash
   curl -X POST --data-binary @my_file.zip -H "X-Filename: my_file.zip" http://<PC_LOCAL_IP>:42425/upload
   ```

---

## 🧪 Verification & Test Suite

AeroCast includes a verification suite testing all core subsystems in isolation.

To run the verification suite:
```powershell
cd c:\Users\ansht\Desktop\Aerocast\AeroCast_Native
venv\Scripts\python.exe test_pipeline.py
```

### Test Coverage Summary:
```
==================================================================
       AEROCAST FULL PIPELINE VERIFICATION SUITE
==================================================================
[TEST] 1. Protocol Packet Encoders & Decoders...
  -> Verified magic header (AERO_CAST_V1), 64KB chunking
  -> Verified binary framing & 17-byte header unpack
  -> Verified JSON beacon schemas and v1 backwards compatibility
  -> Protocol tests PASSED.
[TEST] 2. TCP High-Speed Chunk Streamer (transmit_file & _handle_tcp_client)...
  -> Transmitted & received 128KB successfully with progress callbacks
  -> Verified SHA-256 byte-for-byte integrity on disk
  -> TCP transmission tests PASSED.
[TEST] 3. UDP Signaling & Beacon Discovery...
  -> Verified beacon packet serialization
  -> Verified remote subnet staging & armed-drop event callbacks
  -> UDP Signaling tests PASSED.
[TEST] 4. Windows Explorer Registry Hooks & IPC...
  -> Verified pythonw executable path resolution
  -> Verified explorer_context.py and tray_app.py target files
  -> Explorer hooks tests PASSED.
[TEST] 5. Quick Share HTTP Streaming (GET /download & POST /upload)...
  -> Verified GET /download stream integrity
  -> Verified POST /upload file persistence
  -> Quick Share HTTP tests PASSED.
==================================================================
   ALL AEROCAST TESTS PASSED CLEANLY (ZERO FAILURES)
==================================================================
```

---

## 🔧 Troubleshooting & FAQ

### 1. Devices do not discover each other over Wi-Fi
- **Wi-Fi Subnet Check**: Ensure both your PC and Android device are connected to the same Wi-Fi network (not one on cellular and one on Wi-Fi).
- **AP / Client Isolation**: Some routers (especially in public places, student dorms, or hotels) prevent devices on the same Wi-Fi from talking to each other.
  - *Fix*: Pair your phone and PC via **Bluetooth**. AeroCast automatically falls back to Bluetooth RFCOMM (Channel 5) when Wi-Fi is unreachable!
- **Windows Firewall**: Make sure Windows Firewall is not blocking UDP `42424` or TCP `42425`. Run `install_windows.bat` to automatically apply the firewall rules.

### 2. Gestures are not triggering
- **Lighting**: Ensure your hand is reasonably well-lit and within 0.4m to 1.5m of the camera lens.
- **Hand Posture**:
  - For **Fist (`GRAB`)**: Tightly curl all 4 fingers inward with your knuckles facing the camera.
  - For **Palm (`DROP`)**: Spread your 5 fingers wide, showing the open palm directly towards the camera.
- **Manual Click**: You never have to stay blocked. Simply click anywhere on the floating capsule HUD or tap the on-screen button on Android to trigger the transfer manually.

### 3. Right-Click Context Menu does not appear in Windows Explorer
- AeroCast registers in `HKEY_CURRENT_USER\Software\Classes\*\shell\AeroCast`.
- If Explorer doesn't refresh immediately, restart Windows Explorer via Task Manager or re-run:
  ```cmd
  venv\Scripts\python.exe windows_agent\explorer_context.py --install
  ```

---

## 🔒 Security & Privacy

- **No Remote Servers**: AeroCast has zero analytics, zero external telemetry, and zero third-party cloud backends. No file bytes or metadata ever leave your private local area network.
- **Camera Ephemerality**: The webcam is only powered on for an active 10-second gesture window and shuts down immediately upon gesture detection or timeout. No video frames are ever recorded, written to disk, or transmitted.
- **Cryptographic Integrity**: All transmissions include SHA-256 hashes computed on the fly. Corrupted or incomplete chunks are instantly rejected.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE) — free for personal, educational, and commercial use.
