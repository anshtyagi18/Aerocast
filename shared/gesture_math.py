"""
AeroCast Shared Gesture Mathematics & Evaluation Engine
Calibrated mathematical thresholds for Fist Grab and Open Palm Drop detection.
"""

import math
import time
from typing import List, Tuple, Any, Optional, Dict

# MediaPipe Landmark Index Mapping
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

# Detection Debounce Thresholds
REQUIRED_FIST_STREAK = 2  # 2 consecutive frames for Fist Grab
REQUIRED_PALM_STREAK = 2  # 2 consecutive frames for Open Palm Drop
GESTURE_TIMEOUT_SECONDS = 10.0


def euclidean_dist(p1: Any, p2: Any) -> float:
    """Computes Euclidean distance supporting tuples, lists, or MediaPipe landmark objects."""
    x1 = getattr(p1, "x", p1[0] if isinstance(p1, (list, tuple)) else 0.0)
    y1 = getattr(p1, "y", p1[1] if isinstance(p1, (list, tuple)) else 0.0)
    z1 = getattr(p1, "z", p1[2] if isinstance(p1, (list, tuple)) and len(p1) > 2 else 0.0)

    x2 = getattr(p2, "x", p2[0] if isinstance(p2, (list, tuple)) else 0.0)
    y2 = getattr(p2, "y", p2[1] if isinstance(p2, (list, tuple)) else 0.0)
    z2 = getattr(p2, "z", p2[2] if isinstance(p2, (list, tuple)) and len(p2) > 2 else 0.0)

    return math.hypot(x1 - x2, y1 - y2)


def vector_angle_degrees(v1: Tuple[float, float], v2: Tuple[float, float]) -> float:
    """Computes angle between two 2D vectors in degrees."""
    dot = v1[0] * v2[0] + v1[1] * v2[1]
    mag1 = math.hypot(v1[0], v1[1])
    mag2 = math.hypot(v2[0], v2[1])
    if mag1 * mag2 == 0:
        return 0.0
    cosine = max(-1.0, min(1.0, dot / (mag1 * mag2)))
    return math.degrees(math.acos(cosine))


def check_fist_gesture(landmarks: Any) -> Tuple[bool, float, Dict[str, float]]:
    """
    Evaluates Fist (Grab) gesture:
    Fingertips (8, 12, 16, 20) distance to Wrist (0) must be <= PIP distance * 1.10.
    Requires >= 3 curled fingers.
    """
    wrist = landmarks[WRIST]
    curled_count = 0
    ratios = []

    for tip_idx, pip_idx, mcp_idx in FINGER_TRIPLETS:
        d_tip = euclidean_dist(landmarks[tip_idx], wrist)
        d_pip = euclidean_dist(landmarks[pip_idx], wrist)
        d_mcp = euclidean_dist(landmarks[mcp_idx], wrist)

        if d_pip == 0:
            continue
        ratio = d_tip / d_pip
        ratios.append(ratio)

        # Calibrated relaxed check: tip <= PIP * 1.10 or tip <= MCP * 1.10
        if ratio <= 1.10 or (d_mcp > 0 and d_tip <= d_mcp * 1.10):
            curled_count += 1

    # Optional thumb curl check
    d_thumb_tip = euclidean_dist(landmarks[THUMB_TIP], wrist)
    d_thumb_ip = euclidean_dist(landmarks[THUMB_IP], wrist)
    if d_thumb_ip > 0 and d_thumb_tip <= d_thumb_ip * 1.15:
        curled_count += 1

    matched = curled_count >= 3
    avg_ratio = sum(ratios) / len(ratios) if ratios else 1.0
    confidence = min(1.0, max(0.5, curled_count / 4.0)) if matched else 0.0

    return matched, confidence, {"curled_count": curled_count, "avg_ratio": avg_ratio}


def check_open_palm_gesture(landmarks: Any) -> Tuple[bool, float, Dict[str, float]]:
    """
    Evaluates Open Palm (Drop) gesture:
    Fingertips (8, 12, 16, 20) distance to Wrist (0) must be >= MCP distance * 1.18.
    Requires >= 4 extended fingers.
    """
    wrist = landmarks[WRIST]
    extended_count = 0
    ratios = []

    for tip_idx, pip_idx, mcp_idx in FINGER_TRIPLETS:
        d_tip = euclidean_dist(landmarks[tip_idx], wrist)
        d_mcp = euclidean_dist(landmarks[mcp_idx], wrist)

        if d_mcp == 0:
            continue
        ratio = d_tip / d_mcp
        ratios.append(ratio)

        # Calibrated relaxed check: tip >= MCP * 1.18
        if ratio >= 1.18:
            extended_count += 1

    matched = extended_count >= 4
    avg_ratio = sum(ratios) / len(ratios) if ratios else 0.0
    confidence = min(1.0, max(0.5, extended_count / 4.0)) if matched else 0.0

    return matched, confidence, {"extended_count": extended_count, "avg_ratio": avg_ratio}


class GestureTracker:
    """
    Stateful gesture tracker with debouncing streaks and 10-second window timeout.
    """
    def __init__(self, mode: str = "DROP", timeout: float = GESTURE_TIMEOUT_SECONDS):
        self.mode = mode.upper()  # "GRAB" (Fist) or "DROP" (Open Palm)
        self.timeout = timeout
        self.start_time: Optional[float] = None
        self.streak = 0
        self.required_streak = REQUIRED_FIST_STREAK if self.mode == "GRAB" else REQUIRED_PALM_STREAK
        self.is_completed = False
        self.is_timed_out = False

    def reset(self, mode: Optional[str] = None):
        if mode:
            self.mode = mode.upper()
            self.required_streak = REQUIRED_FIST_STREAK if self.mode == "GRAB" else REQUIRED_PALM_STREAK
        self.start_time = None
        self.streak = 0
        self.is_completed = False
        self.is_timed_out = False

    def get_remaining_time(self) -> float:
        if self.start_time is None:
            return self.timeout
        elapsed = time.time() - self.start_time
        return max(0.0, self.timeout - elapsed)

    def process_frame(self, landmarks: Any) -> Tuple[bool, int, float, Dict[str, Any]]:
        """
        Processes a single video frame landmarks.
        Returns: (is_confirmed, current_streak, confidence, details)
        """
        if self.is_completed:
            return True, self.streak, 1.0, {"status": "ALREADY_COMPLETED"}

        if self.start_time is None:
            self.start_time = time.time()

        # Timeout verification
        if time.time() - self.start_time > self.timeout:
            self.is_timed_out = True
            return False, 0, 0.0, {"status": "TIMED_OUT"}

        if landmarks is None:
            self.streak = max(0, self.streak - 1)
            return False, self.streak, 0.0, {"status": "NO_HAND"}

        # Evaluate target gesture
        if self.mode in ("GRAB", "FIST"):
            match, conf, details = check_fist_gesture(landmarks)
        else:
            match, conf, details = check_open_palm_gesture(landmarks)

        if match:
            self.streak += 1
            if self.streak >= self.required_streak:
                self.is_completed = True
                return True, self.streak, conf, details
        else:
            self.streak = max(0, self.streak - 1)

        return False, self.streak, conf, details
