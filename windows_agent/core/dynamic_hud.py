"""
AeroCast Native Dynamic Capsule HUD (PyQt6)
Huawei-style floating pill capsule with slide-down animation from screen top edge,
translucent dark glassmorphism, animated pulse, and responsive color accents:
- Yellow/Amber Accent: File armed for sending ("✊ Grab to Cast: [Filename]")
- Bright Blue Accent: Staged & broadcasting ("Staged! Finding Devices...")
- Purple Accent: Incoming transfer from Phone ("📥 Incoming: [Filename] - ✋ Show Palm to Drop")
- Cyan/Blue with Progress Bar: Active LAN/Bluetooth streaming ("⚡ Transferring: 64%")
- Emerald Green: Transfer complete ("Transfer Complete! Saved to Downloads/AeroCast")
"""

import os
import sys
import time
from pathlib import Path
from typing import Optional

from PyQt6.QtCore import (
    Qt, QPoint, QPropertyAnimation, QEasingCurve, QTimer, pyqtSignal, QObject
)
from PyQt6.QtWidgets import (
    QWidget, QHBoxLayout, QVBoxLayout, QLabel, QProgressBar, QGraphicsDropShadowEffect, QApplication
)
from PyQt6.QtGui import QColor, QFont, QCursor

ASSETS_DIR = Path(__file__).resolve().parent.parent / "assets"


class HUDController(QObject):
    """Thread-safe signal dispatcher for HUD actions."""
    signal_armed_drop = pyqtSignal(str, int)            # filename, size (Mobile -> PC)
    signal_armed_grab = pyqtSignal(str, int)            # filename, size (PC -> Mobile)
    signal_staged = pyqtSignal(str)                     # filename
    signal_transferring = pyqtSignal(str, float, float) # filename, progress_pct, speed_mbps
    signal_success = pyqtSignal(str)                    # filename
    signal_hide = pyqtSignal()
    signal_progress_dots = pyqtSignal(int, int)         # streak, required


class DynamicCapsuleHUD(QWidget):
    """
    Floating pill capsule HUD anchored to the top-center of the primary display.
    Completely zero overhead when hidden.
    """
    def __init__(self, controller: Optional[HUDController] = None, parent=None):
        super().__init__(parent)
        self.controller = controller or HUDController()
        self.is_visible_state = False
        self._current_filename = ""
        self._slide_anim = None
        self._pulse_timer = QTimer(self)
        self._pulse_step = 0

        self._init_window()
        self._init_ui()
        self._connect_signals()

    def _init_window(self):
        self.setWindowFlags(
            Qt.WindowType.FramelessWindowHint |
            Qt.WindowType.WindowStaysOnTopHint |
            Qt.WindowType.SubWindow |
            Qt.WindowType.Tool
        )
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground, True)
        self.setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating, True)

        self.capsule_width = 380
        self.capsule_height = 54
        self.target_y = 20
        self.hidden_y = -70

        self.resize(self.capsule_width, self.capsule_height)

    def _init_ui(self):
        self.container = QWidget(self)
        self.container.setGeometry(0, 0, self.capsule_width, self.capsule_height)
        self.container.setObjectName("CapsuleContainer")
        self._apply_theme_neutral()

        # Drop shadow
        self.shadow = QGraphicsDropShadowEffect(self)
        self.shadow.setBlurRadius(28)
        self.shadow.setColor(QColor(56, 189, 248, 80))
        self.shadow.setOffset(0, 6)
        self.container.setGraphicsEffect(self.shadow)

        # Main Layout
        layout = QHBoxLayout(self.container)
        layout.setContentsMargins(14, 5, 16, 5)
        layout.setSpacing(10)

        # Left Icon badge
        self.icon_badge = QLabel("✊", self.container)
        self.icon_badge.setFixedSize(36, 36)
        self.icon_badge.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self._apply_badge_style("#38bdf8", "rgba(56, 189, 248, 0.20)")
        layout.addWidget(self.icon_badge)

        # Text Column
        text_layout = QVBoxLayout()
        text_layout.setContentsMargins(0, 2, 0, 2)
        text_layout.setSpacing(2)

        self.title_label = QLabel("AeroCast", self.container)
        self.title_label.setStyleSheet("color: #ffffff; font-weight: bold; font-size: 12px; font-family: 'Segoe UI', sans-serif;")
        text_layout.addWidget(self.title_label)

        self.sub_label = QLabel("Standby", self.container)
        self.sub_label.setStyleSheet("color: #94a3b8; font-size: 10px; font-family: 'Segoe UI', sans-serif;")
        text_layout.addWidget(self.sub_label)

        # Mini Progress Bar (shown during streaming)
        self.progress_bar = QProgressBar(self.container)
        self.progress_bar.setFixedHeight(3)
        self.progress_bar.setTextVisible(False)
        self.progress_bar.setStyleSheet("""
            QProgressBar {
                background: rgba(255, 255, 255, 0.15);
                border-radius: 1.5px;
            }
            QProgressBar::chunk {
                background: #38bdf8;
                border-radius: 1.5px;
            }
        """)
        self.progress_bar.hide()
        text_layout.addWidget(self.progress_bar)

        layout.addLayout(text_layout, stretch=1)

        # Right pulse indicator / close button
        self.pulse_label = QLabel("•••", self.container)
        self.pulse_label.setStyleSheet("color: #38bdf8; font-size: 11px; font-weight: bold;")
        self.pulse_label.setCursor(QCursor(Qt.CursorShape.PointingHandCursor))
        self.pulse_label.mousePressEvent = lambda e: self.slide_out()
        layout.addWidget(self.pulse_label)

        self._pulse_timer.timeout.connect(self._on_pulse_tick)

    def _connect_signals(self):
        self.controller.signal_armed_drop.connect(self.show_armed_drop)
        self.controller.signal_armed_grab.connect(self.show_armed_grab)
        self.controller.signal_staged.connect(self.show_staged)
        self.controller.signal_transferring.connect(self._handle_transferring_signal)
        self.controller.signal_success.connect(self.show_success)
        self.controller.signal_hide.connect(self.slide_out)
        self.controller.signal_progress_dots.connect(self.set_progress_streak)

    def _handle_transferring_signal(self, filename: str, pct: float, speed: float = 0.0):
        self.show_transferring(filename, pct, speed)

    def _apply_theme_neutral(self):
        self.container.setStyleSheet("""
            QWidget#CapsuleContainer {
                background: qlineargradient(x1:0, y1:0, x2:0, y2:1,
                    stop:0 rgba(15, 23, 42, 0.95),
                    stop:1 rgba(7, 10, 18, 0.98));
                border-radius: 27px;
                border: 1.5px solid rgba(56, 189, 248, 0.65);
            }
        """)

    def _apply_badge_style(self, text_color: str, bg_color: str):
        self.icon_badge.setStyleSheet(f"""
            background: {bg_color};
            color: {text_color};
            font-size: 18px;
            border-radius: 18px;
            border: 1px solid {text_color}88;
        """)

    def _center_x(self) -> int:
        screen = QApplication.primaryScreen()
        screen_width = screen.geometry().width() if screen else 1920
        return (screen_width - self.capsule_width) // 2

    def _play_audio(self, filename: str):
        sound_path = ASSETS_DIR / filename
        if sound_path.exists():
            try:
                import winsound
                winsound.PlaySound(str(sound_path), winsound.SND_FILENAME | winsound.SND_ASYNC)
            except Exception:
                pass

    def _on_pulse_tick(self):
        self._pulse_step = (self._pulse_step + 1) % 4
        dots = ["•  ", "•• ", "•••", " ••"][self._pulse_step]
        if not self.progress_bar.isVisible():
            self.pulse_label.setText(dots)

    def set_progress_streak(self, streak: int, required: int):
        if streak > 0:
            self.pulse_label.setText(f"{streak}/{required}")
        else:
            self.pulse_label.setText("•••")

    def slide_in(self):
        x = self._center_x()
        self.move(x, self.hidden_y)
        self.show()

        if self._slide_anim:
            self._slide_anim.stop()

        self._slide_anim = QPropertyAnimation(self, b"pos")
        self._slide_anim.setDuration(320)
        self._slide_anim.setStartValue(QPoint(x, self.hidden_y))
        self._slide_anim.setEndValue(QPoint(x, self.target_y))
        self._slide_anim.setEasingCurve(QEasingCurve.Type.OutCubic)
        self._slide_anim.start()

        self._pulse_timer.start(250)
        self.is_visible_state = True

    def slide_out(self):
        if not self.isVisible():
            return

        self._pulse_timer.stop()
        x = self._center_x()

        if self._slide_anim:
            self._slide_anim.stop()

        self._slide_anim = QPropertyAnimation(self, b"pos")
        self._slide_anim.setDuration(260)
        self._slide_anim.setStartValue(self.pos())
        self._slide_anim.setEndValue(QPoint(x, self.hidden_y))
        self._slide_anim.setEasingCurve(QEasingCurve.Type.InCubic)
        self._slide_anim.finished.connect(self.hide)
        self._slide_anim.start()
        self.is_visible_state = False

    # ================= HUD EVENT STATES =================
    def show_armed_grab(self, filename: str, size: int):
        """
        Sender Flow (PC -> Phone): File selected for sending.
        Yellow/Amber Accent: "✊ Grab to Cast: [Filename]".
        """
        self._current_filename = filename
        display_name = filename if len(filename) <= 22 else filename[:19] + "..."
        size_str = self._format_size(size)

        self.container.setStyleSheet("""
            QWidget#CapsuleContainer {
                background: qlineargradient(x1:0, y1:0, x2:0, y2:1,
                    stop:0 rgba(26, 18, 7, 0.96),
                    stop:1 rgba(15, 10, 4, 0.98));
                border-radius: 27px;
                border: 1.5px solid rgba(245, 158, 11, 0.85);
            }
        """)
        self.shadow.setColor(QColor(245, 158, 11, 100))
        self.icon_badge.setText("✊")
        self._apply_badge_style("#f59e0b", "rgba(245, 158, 11, 0.22)")

        self.title_label.setText(f"✊ Grab to Cast: {display_name}")
        self.sub_label.setText(f"Make Fist (✊) to Cast • {size_str}")
        self.progress_bar.hide()
        self.slide_in()

    def show_staged(self, filename: str):
        """
        Fist match confirmed on PC -> turns bright blue: "Staged! Finding Devices...".
        """
        self.container.setStyleSheet("""
            QWidget#CapsuleContainer {
                background: qlineargradient(x1:0, y1:0, x2:0, y2:1,
                    stop:0 rgba(8, 25, 48, 0.96),
                    stop:1 rgba(4, 15, 30, 0.98));
                border-radius: 27px;
                border: 1.5px solid rgba(56, 189, 248, 0.90);
            }
        """)
        self.shadow.setColor(QColor(56, 189, 248, 120))
        self.icon_badge.setText("⚡")
        self._apply_badge_style("#38bdf8", "rgba(56, 189, 248, 0.25)")

        self.title_label.setText("Staged! Finding Devices...")
        self.sub_label.setText("Broadcasting via Wi-Fi & Bluetooth...")
        self.pulse_label.setText("•••")
        self.progress_bar.hide()
        self._play_audio("grab.wav")
        self.slide_in()

    def show_armed_drop(self, filename: str, size: int):
        """
        Receiver Flow (Phone -> PC): Phone staged file.
        Purple Accent: "📥 Incoming: [Filename] - ✋ Show Palm to Drop".
        """
        self._current_filename = filename
        display_name = filename if len(filename) <= 22 else filename[:19] + "..."
        size_str = self._format_size(size)

        self.container.setStyleSheet("""
            QWidget#CapsuleContainer {
                background: qlineargradient(x1:0, y1:0, x2:0, y2:1,
                    stop:0 rgba(28, 12, 44, 0.96),
                    stop:1 rgba(16, 7, 26, 0.98));
                border-radius: 27px;
                border: 1.5px solid rgba(168, 85, 247, 0.85);
            }
        """)
        self.shadow.setColor(QColor(168, 85, 247, 100))
        self.icon_badge.setText("📥")
        self._apply_badge_style("#c084fc", "rgba(168, 85, 247, 0.25)")

        self.title_label.setText(f"📥 Incoming: {display_name}")
        self.sub_label.setText(f"✋ Show Palm to Drop • {size_str}")
        self.progress_bar.hide()
        self._play_audio("grab.wav")
        self.slide_in()

    def show_transferring(self, filename: str, progress_pct: float, speed_mbps: float = 0.0):
        """Shows active LAN or Bluetooth chunk streaming with live percentage and speed."""
        display_name = filename if len(filename) <= 18 else filename[:15] + "..."

        self.container.setStyleSheet("""
            QWidget#CapsuleContainer {
                background: qlineargradient(x1:0, y1:0, x2:0, y2:1,
                    stop:0 rgba(10, 24, 40, 0.96),
                    stop:1 rgba(5, 14, 25, 0.98));
                border-radius: 27px;
                border: 1.5px solid rgba(6, 182, 212, 0.85);
            }
        """)
        self.shadow.setColor(QColor(6, 182, 212, 100))
        self.icon_badge.setText("⚡")
        self._apply_badge_style("#06b6d4", "rgba(6, 182, 212, 0.22)")

        speed_text = f" • {speed_mbps:.1f} MB/s" if speed_mbps > 0 else ""
        self.title_label.setText(f"⚡ Transferring: {progress_pct:.0f}%")
        self.sub_label.setText(f"{display_name}{speed_text}")
        self.pulse_label.setText(f"{progress_pct:.0f}%")

        self.progress_bar.show()
        self.progress_bar.setValue(int(progress_pct))

        if not self.is_visible_state:
            self.slide_in()

    def show_success(self, filename: str):
        """
        Transfer complete -> Turns Emerald Green with checkmark, plays drop.wav,
        fades out after 2.5s.
        """
        display_name = filename if len(filename) <= 22 else filename[:19] + "..."

        self.container.setStyleSheet("""
            QWidget#CapsuleContainer {
                background: qlineargradient(x1:0, y1:0, x2:0, y2:1,
                    stop:0 rgba(6, 78, 59, 0.96),
                    stop:1 rgba(4, 47, 46, 0.98));
                border-radius: 27px;
                border: 1.5px solid rgba(52, 211, 153, 0.90);
            }
        """)
        self.shadow.setColor(QColor(52, 211, 153, 120))
        self.icon_badge.setText("✓")
        self._apply_badge_style("#34d399", "rgba(52, 211, 153, 0.30)")

        self.title_label.setText("Transfer Complete!")
        self.sub_label.setText(f"Saved to Downloads/AeroCast • {display_name}")
        self.pulse_label.setText("100%")
        self.progress_bar.hide()

        self._play_audio("drop.wav")
        QTimer.singleShot(2500, self.slide_out)

    @staticmethod
    def _format_size(size_bytes: int) -> str:
        if size_bytes < 1024:
            return f"{size_bytes} B"
        elif size_bytes < 1024 * 1024:
            return f"{size_bytes / 1024:.1f} KB"
        elif size_bytes < 1024 * 1024 * 1024:
            return f"{size_bytes / (1024 * 1024):.1f} MB"
        return f"{size_bytes / (1024 * 1024 * 1024):.2f} GB"
