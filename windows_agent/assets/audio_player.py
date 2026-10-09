import os
import sys
from pathlib import Path

ASSETS_DIR = Path(__file__).parent.resolve()

def play_chime(name: str):
    """
    Plays an asynchronous system chime without blocking execution.
    Supported: 'drop_success', 'grab_ready', 'timeout_click'
    """
    wav_path = ASSETS_DIR / f"{name}.wav"
    if not wav_path.exists():
        return

    try:
        if sys.platform == "win32":
            import winsound
            winsound.PlaySound(str(wav_path), winsound.SND_FILENAME | winsound.SND_ASYNC)
        else:
            # Non-windows fallback
            pass
    except Exception:
        pass
