"""
AeroCast Windows System Tray Daemon (Overhauled)
Coordinates Dynamic Capsule HUD, On-Demand Gesture Engine, and Hybrid Network Signaling.
- 10-second camera watch windows with warm-up compensation.
- Instant responsive pop-up capsule with iOS/Huawei styling.
- Complete bi-directional flows (Air Send with Fist Grab and Incoming with Open Palm Drop).
"""

import os
import sys
import time
import socket
import argparse
import threading
from pathlib import Path
from typing import Optional

# Silence C++ logs
os.environ["GLOG_minloglevel"] = "2"
os.environ["TF_CPP_MIN_LOG_LEVEL"] = "2"

# Paths
AGENT_DIR = Path(__file__).resolve().parent
ROOT_DIR = AGENT_DIR.parent
SHARED_DIR = ROOT_DIR / "shared"
ASSETS_DIR = AGENT_DIR / "assets"

if str(AGENT_DIR) not in sys.path:
    sys.path.insert(0, str(AGENT_DIR))
if str(SHARED_DIR) not in sys.path:
    sys.path.insert(0, str(SHARED_DIR))

from PyQt6.QtWidgets import (
    QApplication, QFileDialog, QSystemTrayIcon, QMenu
)
from PyQt6.QtGui import QIcon, QAction
from PyQt6.QtCore import QObject, pyqtSignal, Qt, QTimer

from protocol import (
    get_local_ip,
    get_default_download_dir,
    IPC_LOCAL_PORT,
    TCP_TRANSFER_PORT,
    BT_DEFAULT_RFCOMM_PORT
)
from core.gesture_engine import GestureEngineThread, OnDemandGestureEngine, GESTURE_TIMEOUT_SECONDS
from core.network_service import NetworkService
from core.dynamic_hud import DynamicCapsuleHUD, HUDController
from explorer_context import send_ipc_file, install_context_menu


class DaemonBridge(QObject):
    """
    Thread-safe bridge between asynchronous background network/vision threads
    and the PyQt6 main GUI thread using QueuedConnection signals.
    """
    signal_mobile_armed = pyqtSignal(dict)
    signal_drop_confirmed = pyqtSignal(dict)
    signal_stage_requested = pyqtSignal(str)
    signal_transfer_progress = pyqtSignal(str, float, float) # filename, pct, speed_mbps
    signal_transfer_complete = pyqtSignal(str, str)
    signal_pick_file = pyqtSignal()
    signal_toggle_monitoring = pyqtSignal()
    signal_show_status = pyqtSignal()
    signal_exit = pyqtSignal()


class AeroCastTrayApp:
    def __init__(self, stage_file_arg: Optional[str] = None):
        self.stage_file_arg = stage_file_arg
        self.bridge = DaemonBridge()
        self.hud_controller = HUDController()
        self.gesture_engine = GestureEngineThread()
        self.network_service: Optional[NetworkService] = None
        self.tray_icon: Optional[QSystemTrayIcon] = None
        self.pystray_icon = None
        self.hud: Optional[DynamicCapsuleHUD] = None

        # State tracking
        self._staged_local_file: str = ""
        self._active_incoming_meta: Optional[dict] = None
        self._monitoring_enabled: bool = True
        self._action_monitor: Optional[QAction] = None

    def setup(self):
        """Initializes and wires all AeroCast subsystems."""
        # 1. Connect thread-safe bridge signals
        self.bridge.signal_mobile_armed.connect(
            self._handle_mobile_armed, Qt.ConnectionType.QueuedConnection
        )
        self.bridge.signal_drop_confirmed.connect(
            self._handle_drop_confirmed, Qt.ConnectionType.QueuedConnection
        )
        self.bridge.signal_stage_requested.connect(
            self.stage_file_for_grab, Qt.ConnectionType.QueuedConnection
        )
        self.bridge.signal_transfer_progress.connect(
            self._handle_transfer_progress, Qt.ConnectionType.QueuedConnection
        )
        self.bridge.signal_transfer_complete.connect(
            self._handle_transfer_complete, Qt.ConnectionType.QueuedConnection
        )
        self.bridge.signal_pick_file.connect(
            self._do_pick_file, Qt.ConnectionType.QueuedConnection
        )
        self.bridge.signal_toggle_monitoring.connect(
            self._toggle_monitoring, Qt.ConnectionType.QueuedConnection
        )
        self.bridge.signal_show_status.connect(
            self._show_status, Qt.ConnectionType.QueuedConnection
        )
        self.bridge.signal_exit.connect(
            self._exit_app, Qt.ConnectionType.QueuedConnection
        )

        # Helper for progress signal
        def on_prog(fn, pct, speed=0.0):
            self.bridge.signal_transfer_progress.emit(fn, pct, speed)

        # 2. Initialize network service
        self.network_service = NetworkService(
            on_armed_drop_received=lambda packet: self.bridge.signal_mobile_armed.emit(packet),
            on_drop_confirmed_received=lambda packet: self.bridge.signal_drop_confirmed.emit(packet),
            on_transfer_progress=on_prog,
            on_transfer_complete=lambda fn, path: self.bridge.signal_transfer_complete.emit(fn, str(path)),
            on_stage_file_requested=lambda fpath: self.bridge.signal_stage_requested.emit(fpath),
            file_received=lambda fn, path: self.bridge.signal_transfer_complete.emit(fn, str(path)),
            incoming_transfer_staged=lambda fn, sz, sdr: self.bridge.signal_mobile_armed.emit({
                "filename": fn, "size": sz, "device_name": sdr
            })
        )
        self.network_service.start()

        # 3. Build HUD
        self.hud = DynamicCapsuleHUD(controller=self.hud_controller)

        # 4. Setup System Tray
        self._setup_tray()

        # 5. Handle startup staging argument if provided
        if self.stage_file_arg and os.path.exists(self.stage_file_arg):
            QTimer.singleShot(400, lambda: self.stage_file_for_grab(self.stage_file_arg))

    # ================= SYSTEM TRAY =================
    def _setup_tray(self):
        """Initializes native responsive system tray icon."""
        icon_path = ASSETS_DIR / "tray_icon.png"
        if not icon_path.exists():
            icon_path = ASSETS_DIR / "tray_icon.ico"

        qicon = QIcon(str(icon_path))

        if QSystemTrayIcon.isSystemTrayAvailable():
            self.tray_icon = QSystemTrayIcon(qicon, QApplication.instance())
            self.tray_icon.setToolTip("AeroCast - Huawei Air Transfer")

            menu = QMenu()
            menu.setStyleSheet("""
                QMenu {
                    background-color: #0d1424;
                    color: #f8fafc;
                    border: 1.5px solid #1e293b;
                    border-radius: 8px;
                    padding: 4px;
                    font-family: 'Segoe UI', sans-serif;
                    font-size: 12px;
                }
                QMenu::item {
                    padding: 6px 20px;
                    border-radius: 4px;
                }
                QMenu::item:selected {
                    background-color: #0284c7;
                    color: #ffffff;
                }
                QMenu::item:disabled {
                    color: #64748b;
                }
                QMenu::separator {
                    height: 1px;
                    background: #1e293b;
                    margin: 4px 6px;
                }
            """)

            local_ip = get_local_ip()
            header_action = QAction(f"⚡ AeroCast (LAN: {local_ip})", menu)
            header_action.setEnabled(False)
            menu.addAction(header_action)

            menu.addSeparator()

            # Status Action
            act_status = QAction("📊 Status", menu)
            act_status.triggered.connect(self._show_status)
            menu.addAction(act_status)

            # Toggle Monitoring Action
            self._action_monitor = QAction("👁️ Monitoring: ON", menu)
            self._action_monitor.setCheckable(True)
            self._action_monitor.setChecked(True)
            self._action_monitor.triggered.connect(self._toggle_monitoring)
            menu.addAction(self._action_monitor)

            menu.addSeparator()

            # Air Send File
            act_send = QAction("📤 Air Send File...", menu)
            act_send.triggered.connect(self._do_pick_file)
            menu.addAction(act_send)

            # Open Downloads Folder
            act_downloads = QAction("📁 Open Downloads Folder", menu)
            act_downloads.triggered.connect(self._open_downloads)
            menu.addAction(act_downloads)

            # Register Context Menu
            act_context = QAction("⚙️ Register Context Menu", menu)
            act_context.triggered.connect(lambda: install_context_menu())
            menu.addAction(act_context)

            menu.addSeparator()

            # Exit Action
            act_exit = QAction("❌ Exit AeroCast", menu)
            act_exit.triggered.connect(self._exit_app)
            menu.addAction(act_exit)

            self.tray_icon.setContextMenu(menu)
            self.tray_icon.activated.connect(self._on_tray_activated)
            self.tray_icon.show()
        else:
            self._start_pystray_fallback()

    def _start_pystray_fallback(self):
        """Fallback pystray implementation."""
        import pystray
        from PIL import Image

        icon_path = ASSETS_DIR / "tray_icon.png"
        if not icon_path.exists():
            icon_path = ASSETS_DIR / "tray_icon.ico"

        try:
            image = Image.open(str(icon_path))
        except Exception:
            image = Image.new("RGBA", (16, 16), (56, 189, 248, 255))

        local_ip = get_local_ip()

        menu = pystray.Menu(
            pystray.MenuItem(f"⚡ AeroCast (LAN: {local_ip})", lambda: None, enabled=False),
            pystray.MenuItem("📊 Status", lambda: self.bridge.signal_show_status.emit()),
            pystray.MenuItem("👁️ Toggle Monitoring", lambda: self.bridge.signal_toggle_monitoring.emit()),
            pystray.MenuItem("📁 Open Downloads Folder", lambda: self._open_downloads()),
            pystray.MenuItem("📤 Air Send File...", lambda: self.bridge.signal_pick_file.emit()),
            pystray.MenuItem("⚙️ Register Context Menu", lambda: install_context_menu()),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("❌ Exit AeroCast", lambda: self.bridge.signal_exit.emit())
        )

        self.pystray_icon = pystray.Icon("AeroCast", image, "AeroCast - Air Transfer", menu)
        threading.Thread(target=self.pystray_icon.run, daemon=True, name="AeroCastPystray").start()

    def _on_tray_activated(self, reason):
        if reason == QSystemTrayIcon.ActivationReason.DoubleClick:
            self._open_downloads()
        elif reason == QSystemTrayIcon.ActivationReason.Trigger:
            self._show_status()

    def _toggle_monitoring(self):
        self._monitoring_enabled = not self._monitoring_enabled
        status_text = "ON" if self._monitoring_enabled else "OFF"
        if self._action_monitor:
            self._action_monitor.setText(f"👁️ Monitoring: {status_text}")
            self._action_monitor.setChecked(self._monitoring_enabled)

        if not self._monitoring_enabled and self.gesture_engine:
            self.gesture_engine.stop()
            self.hud_controller.signal_hide.emit()

        if self.tray_icon:
            self.tray_icon.showMessage(
                "AeroCast Radar",
                f"Air gesture monitoring is now {status_text}.",
                QSystemTrayIcon.MessageIcon.Information,
                2000
            )

    def _show_status(self):
        local_ip = get_local_ip()
        staged = Path(self._staged_local_file).name if self._staged_local_file else "None"
        mon_text = "Active" if self._monitoring_enabled else "Paused"

        msg = f"LAN IP: {local_ip}\nMonitoring: {mon_text}\nStaged File: {staged}"
        if self.tray_icon:
            self.tray_icon.showMessage("⚡ AeroCast Status", msg, QSystemTrayIcon.MessageIcon.Information, 3000)

    def _open_downloads(self):
        dl_path = get_default_download_dir()
        if sys.platform == "win32":
            os.startfile(str(dl_path))
        else:
            import subprocess
            subprocess.Popen(["xdg-open", str(dl_path)])

    def _do_pick_file(self):
        filepath, _ = QFileDialog.getOpenFileName(
            None, "Select File to Air Send", "", "All Files (*.*)"
        )
        if filepath:
            self.stage_file_for_grab(filepath)

    def _exit_app(self):
        if self.tray_icon:
            self.tray_icon.hide()
        if self.pystray_icon:
            self.pystray_icon.stop()
        if self.network_service:
            self.network_service.stop()
        if self.gesture_engine:
            self.gesture_engine.stop()
        QApplication.quit()

    # ================= 1. SENDER FLOW: LAPTOP -> MOBILE (GRAB TO CAST) =================
    def stage_file_for_grab(self, filepath: str):
        """
        User right-clicked file or clicked Send:
        1. Dynamic capsule pops down immediately: "✊ Grab to Cast: [Filename]" (Yellow Accent).
        2. Camera opens for full 10-second window.
        3. Upon Fist match: turns bright blue: "Staged! Finding Devices...",
           broadcasts stage beacon via Wi-Fi and Bluetooth.
        """
        if not os.path.isfile(filepath):
            return

        self._staged_local_file = filepath
        path = Path(filepath)
        filename = path.name
        filesize = path.stat().st_size

        # Show Yellow Accent HUD
        self.hud_controller.signal_armed_grab.emit(filename, filesize)

        def on_fist_detected(mode: str):
            print(f"[AeroCast] Fist Grab confirmed! Staged '{filename}'. Broadcasting...")
            self._play_chime("grab.wav")
            # Update HUD to Bright Blue Staged
            self.hud_controller.signal_staged.emit(filename)
            if self.network_service:
                self.network_service.broadcast_armed_drop_laptop(filepath)

        def on_timeout():
            print("[AeroCast] Fist grab watch window timed out.")
            self.hud_controller.signal_hide.emit()

        def on_progress(streak, required, time_left):
            self.hud_controller.signal_progress_dots.emit(streak, required)

        self.gesture_engine.start_watch(
            mode="GRAB",
            timeout=GESTURE_TIMEOUT_SECONDS, # 10.0 seconds
            on_gesture=on_fist_detected,
            on_timeout=on_timeout,
            on_progress=on_progress
        )

    # ================= 2. RECEIVER FLOW: MOBILE -> LAPTOP (DROP CONFIRMATION) =================
    def _handle_mobile_armed(self, packet: dict):
        """
        Phone staged a file in the air (Incoming to PC):
        1. Laptop capsule pops down immediately on top of all windows:
           "📥 Incoming: [Filename] - ✋ Show Palm to Drop" (Purple Accent).
        2. Camera opens for full 10-second window.
        3. Upon Palm match: pulls stream via TCP or Bluetooth fallback,
           shows live percentage and speed bar in capsule, saves to Downloads/AeroCast.
        """
        if not self._monitoring_enabled:
            return

        filename = packet.get("filename", "Unknown File")
        size = int(packet.get("size", packet.get("filesize", 0)))
        sender_ip = packet.get("sender_ip", "")
        tcp_port = packet.get("tcp_port", TCP_TRANSFER_PORT)

        self._active_incoming_meta = packet

        # Show Purple Accent HUD immediately
        self.hud_controller.signal_armed_drop.emit(filename, size)

        def on_palm_detected(mode: str):
            print(f"[AeroCast] Open Palm confirmed! Pulling '{filename}' from {sender_ip}")
            self._play_chime("grab.wav")
            if self.network_service:
                self.network_service.pull_file_from_sender(
                    sender_ip=sender_ip,
                    port=tcp_port,
                    filename=filename,
                    expected_size=size
                )

        def on_timeout():
            print("[AeroCast] Drop gesture watch window timed out.")
            self.hud_controller.signal_hide.emit()

        def on_progress(streak, required, time_left):
            self.hud_controller.signal_progress_dots.emit(streak, required)

        self.gesture_engine.start_watch(
            mode="DROP",
            timeout=GESTURE_TIMEOUT_SECONDS, # 10.0 seconds
            on_gesture=on_palm_detected,
            on_timeout=on_timeout,
            on_progress=on_progress
        )

    def _handle_drop_confirmed(self, packet: dict):
        """Mobile detected drop gesture and signaled DROP_CONFIRMED."""
        target_ip = packet.get("sender_ip")
        if self._staged_local_file and target_ip and self.network_service:
            self.network_service.transmit_file_async(
                target_ip=target_ip,
                filepath=self._staged_local_file
            )

    def _handle_transfer_progress(self, filename: str, progress_pct: float, speed_mbps: float = 0.0):
        """Updates capsule HUD with real-time transfer percentage and speed."""
        self.hud_controller.signal_transferring.emit(filename, progress_pct, speed_mbps)

    def _handle_transfer_complete(self, filename: str, filepath: str):
        """Emits success state to capsule HUD, plays audio chime."""
        self.hud_controller.signal_success.emit(filename)
        self._play_chime("drop.wav")
        if self.tray_icon:
            self.tray_icon.showMessage(
                "AeroCast Drop Complete",
                f"Successfully transferred: {filename}",
                QSystemTrayIcon.MessageIcon.Information,
                2500
            )

    def _play_chime(self, filename: str):
        sound_path = ASSETS_DIR / filename
        if sound_path.exists():
            try:
                import winsound
                winsound.PlaySound(str(sound_path), winsound.SND_FILENAME | winsound.SND_ASYNC)
            except Exception:
                pass


def main():
    parser = argparse.ArgumentParser(description="AeroCast Silent Background Agent")
    parser.add_argument("--stage", type=str, help="Stage a file immediately for Air Send", default=None)
    args = parser.parse_args()

    if args.stage:
        if send_ipc_file(args.stage):
            sys.exit(0)

    app = QApplication(sys.argv)
    app.setQuitOnLastWindowClosed(False)

    tray_app = AeroCastTrayApp(stage_file_arg=args.stage)
    tray_app.setup()

    sys.exit(app.exec())


if __name__ == "__main__":
    main()
