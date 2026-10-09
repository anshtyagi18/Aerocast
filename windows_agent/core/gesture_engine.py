"""
AeroCast On-Demand Gesture Engine (Fixed Warm-Up & Calibrated Math)
- DirectShow camera warm-up timer fix: Countdown starts strictly AFTER first valid frame decoded.
- Relaxed classifier:
  * Fist (Grab): Fingertips (8, 12, 16, 20) distance to wrist <= PIP distance * 1.10. Requires >= 3 curled fingers.
  * Open Palm (Drop): Fingertips distance to wrist >= MCP distance * 1.18. Requires >= 4 extended fingers.
  * Debounce: 2 consecutive frames for instant response.
  * Timeout: 10.0 seconds.
"""

import os
import sys
import time
import math
import threading
from typing import Optional, Callable, Dict, Any, Tuple
from pathlib import Path

# Silence C++ logs
os.environ["GLOG_minloglevel"] = "2"
os.environ["TF_CPP_MIN_LOG_LEVEL"] = "2"

import cv2

SHARED_DIR = Path(__file__).resolve().parent.parent.parent / "shared"
if str(SHARED_DIR) not in sys.path:
    sys.path.insert(0, str(SHARED_DIR))

# MediaPipe Hand Landmarks
WRIST = 0
THUMB_CMC = 1
THUMB_MCP = 2
THUMB_IP = 3
THUMB_TIP = 4

INDEX_MCP = 5
INDEX_PIP = 6
INDEX_DIP = 7
INDEX_TIP = 8

MIDDLE_MCP = 9
MIDDLE_PIP = 10
MIDDLE_DIP = 11
MIDDLE_TIP = 12

RING_MCP = 13
RING_PIP = 14
RING_DIP = 15
RING_TIP = 16

PINKY_MCP = 17
PINKY_PIP = 18
PINKY_DIP = 19
PINKY_TIP = 20

# Finger tip, PIP & MCP triplet mappings: (Tip, PIP, MCP)
FINGER_TRIPLETS = [
    (INDEX_TIP, INDEX_PIP, INDEX_MCP),
    (MIDDLE_TIP, MIDDLE_PIP, MIDDLE_MCP),
    (RING_TIP, RING_PIP, RING_MCP),
    (PINKY_TIP, PINKY_PIP, PINKY_MCP),
]

GESTURE_TIMEOUT_SECONDS = 10.0
DEBOUNCE_FRAMES = 2


def get_point(landmark_item: Any) -> Tuple[float, float, float]:
    """Extracts (x, y, z) coordinates safely from landmark item."""
    if hasattr(landmark_item, "x"):
        return (landmark_item.x, landmark_item.y, getattr(landmark_item, "z", 0.0))
    if isinstance(landmark_item, (list, tuple)):
        z = landmark_item[2] if len(landmark_item) > 2 else 0.0
        return (landmark_item[0], landmark_item[1], z)
    return (0.0, 0.0, 0.0)


def euclidean_dist(p1: Any, p2: Any) -> float:
    """Computes Euclidean distance."""
    x1, y1, _ = get_point(p1)
    x2, y2, _ = get_point(p2)
    return math.hypot(x1 - x2, y1 - y2)


def check_fist_gesture(landmarks: Any) -> Tuple[bool, float]:
    """
    Grab (Fist):
    Fingertips (8, 12, 16, 20) distance to wrist <= PIP distance * 1.10.
    Requires >= 3 curled fingers.
    """
    wrist = landmarks[WRIST]
    curled_count = 0

    for tip_idx, pip_idx, mcp_idx in FINGER_TRIPLETS:
        d_tip = euclidean_dist(landmarks[tip_idx], wrist)
        d_pip = euclidean_dist(landmarks[pip_idx], wrist)
        d_mcp = euclidean_dist(landmarks[mcp_idx], wrist)

        # Calibrated relaxed check: tip <= PIP * 1.10 or tip <= MCP * 1.10
        if (d_pip > 0 and d_tip <= d_pip * 1.10) or (d_mcp > 0 and d_tip <= d_mcp * 1.10):
            curled_count += 1

    # Optional thumb curl bonus check
    d_thumb_tip = euclidean_dist(landmarks[THUMB_TIP], wrist)
    d_thumb_ip = euclidean_dist(landmarks[THUMB_IP], wrist)
    if d_thumb_ip > 0 and d_thumb_tip <= d_thumb_ip * 1.15:
        curled_count += 1

    if curled_count >= 3:
        return True, min(1.0, max(0.5, curled_count / 4.0))
    return False, 0.0


def check_open_palm_gesture(landmarks: Any) -> Tuple[bool, float]:
    """
    Drop (Open Palm):
    Fingertips (8, 12, 16, 20) distance to wrist >= MCP distance * 1.18.
    Requires >= 4 extended fingers.
    """
    wrist = landmarks[WRIST]
    extended_count = 0

    for tip_idx, pip_idx, mcp_idx in FINGER_TRIPLETS:
        d_tip = euclidean_dist(landmarks[tip_idx], wrist)
        d_mcp = euclidean_dist(landmarks[mcp_idx], wrist)

        # Calibrated relaxed check: tip >= MCP * 1.18
        if d_mcp > 0 and d_tip >= d_mcp * 1.18:
            extended_count += 1

    if extended_count >= 4:
        return True, min(1.0, max(0.5, extended_count / 4.0))
    return False, 0.0


class OnDemandGestureEngine:
    def __init__(self):
        self._thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()
        self._is_active = False
        self._detector = None
        self._init_detector()

    def _init_detector(self):
        try:
            import mediapipe as mp
            if hasattr(mp, "solutions") and hasattr(mp.solutions, "hands"):
                mp_hands = mp.solutions.hands
                hands = mp_hands.Hands(
                    static_image_mode=False,
                    max_num_hands=1,
                    min_detection_confidence=0.50,
                    min_tracking_confidence=0.50
                )
                def detect_fn(rgb_frame):
                    res = hands.process(rgb_frame)
                    if res.multi_hand_landmarks and len(res.multi_hand_landmarks) > 0:
                        return res.multi_hand_landmarks[0].landmark
                    return None
                self._detector = detect_fn
                return
        except Exception:
            pass

        try:
            import mediapipe as mp
            from mediapipe.tasks.python import vision, BaseOptions

            model_path = SHARED_DIR / "hand_landmarker.task"
            if not model_path.exists():
                import urllib.request
                url = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
                urllib.request.urlretrieve(url, str(model_path))

            options = vision.HandLandmarkerOptions(
                base_options=BaseOptions(model_asset_path=str(model_path)),
                running_mode=vision.RunningMode.IMAGE,
                num_hands=1,
                min_hand_detection_confidence=0.50,
                min_tracking_confidence=0.50
            )
            landmarker = vision.HandLandmarker.create_from_options(options)

            def detect_tasks_fn(rgb_frame):
                mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb_frame)
                result = landmarker.detect(mp_image)
                if result.hand_landmarks and len(result.hand_landmarks) > 0:
                    return result.hand_landmarks[0]
                return None

            self._detector = detect_tasks_fn
        except Exception as e:
            print(f"[AeroCast Vision] Error initializing HandLandmarker: {e}", file=sys.stderr)
            self._detector = None

    @property
    def is_active(self) -> bool:
        return self._is_active

    def start_watch(
        self,
        mode: str = "DROP",
        timeout: float = GESTURE_TIMEOUT_SECONDS,
        on_gesture: Optional[Callable[[str], None]] = None,
        on_timeout: Optional[Callable[[], None]] = None,
        on_progress: Optional[Callable[[int, int, float], None]] = None,
    ):
        self.stop()
        self._stop_event.clear()

        def worker():
            self._is_active = True
            cap = None
            consecutive_streak = 0
            start_time: Optional[float] = None

            try:
                # Open webcam with DirectShow backend for rapid Windows capture
                cap = cv2.VideoCapture(0, cv2.CAP_DSHOW if sys.platform == "win32" else cv2.CAP_ANY)
                if not cap.isOpened():
                    cap = cv2.VideoCapture(0)

                if not cap.isOpened():
                    print("[AeroCast Vision] Could not open webcam", file=sys.stderr)
                    if on_timeout:
                        on_timeout()
                    return

                cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
                cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
                cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)

                while not self._stop_event.is_set():
                    ret, frame = cap.read()
                    if not ret or frame is None or frame.size == 0:
                        time.sleep(0.015)
                        continue

                    # CRITICAL FIX: Timer starts ONLY after camera has warmed up and returned first valid frame
                    if start_time is None:
                        start_time = time.time()

                    elapsed = time.time() - start_time
                    time_remaining = max(0.0, timeout - elapsed)

                    if time_remaining <= 0:
                        if on_timeout:
                            on_timeout()
                        break

                    frame = cv2.flip(frame, 1)
                    rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)

                    landmarks = self._detector(rgb_frame) if self._detector else None
                    matched = False

                    if landmarks and len(landmarks) >= 21:
                        target = mode.upper()
                        if target in ("GRAB", "FIST"):
                            matched, _ = check_fist_gesture(landmarks)
                        elif target in ("DROP", "PALM"):
                            matched, _ = check_open_palm_gesture(landmarks)

                    if matched:
                        consecutive_streak += 1
                    else:
                        consecutive_streak = 0

                    if on_progress:
                        on_progress(consecutive_streak, DEBOUNCE_FRAMES, time_remaining)

                    if consecutive_streak >= DEBOUNCE_FRAMES:
                        if on_gesture:
                            on_gesture(mode)
                        break

                    time.sleep(0.015)
            except Exception as e:
                print(f"[AeroCast Vision] Error during gesture tracking: {e}", file=sys.stderr)
                if on_timeout:
                    on_timeout()
            finally:
                if cap is not None:
                    try:
                        cap.release()
                    except Exception:
                        pass
                self._is_active = False

        self._thread = threading.Thread(target=worker, daemon=True, name="AeroCastVisionWorker")
        self._thread.start()

    def stop(self):
        self._stop_event.set()
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=1.0)
        self._is_active = False


try:
    from PyQt6.QtCore import QThread, pyqtSignal

    class GestureEngineThread(QThread):
        gesture_detected = pyqtSignal(str)
        watch_timeout = pyqtSignal()
        watch_progress = pyqtSignal(int, int, float)

        def __init__(self, parent=None):
            super().__init__(parent)
            self._engine = OnDemandGestureEngine()

        def start_watch(
            self,
            mode: str = "DROP",
            timeout: float = GESTURE_TIMEOUT_SECONDS,
            on_gesture: Optional[Callable[[str], None]] = None,
            on_timeout: Optional[Callable[[], None]] = None,
            on_progress: Optional[Callable[[int, int, float], None]] = None
        ):
            def handle_gesture(m: str):
                self.gesture_detected.emit(m)
                if on_gesture:
                    on_gesture(m)

            def handle_timeout():
                self.watch_timeout.emit()
                if on_timeout:
                    on_timeout()

            def handle_progress(s: int, r: int, t: float):
                self.watch_progress.emit(s, r, t)
                if on_progress:
                    on_progress(s, r, t)

            self._engine.start_watch(
                mode=mode,
                timeout=timeout,
                on_gesture=handle_gesture,
                on_timeout=handle_timeout,
                on_progress=handle_progress
            )

        def stop(self):
            self._engine.stop()

        @property
        def is_active(self) -> bool:
            return self._engine.is_active

except ImportError:
    class GestureEngineThread(OnDemandGestureEngine):
        pass