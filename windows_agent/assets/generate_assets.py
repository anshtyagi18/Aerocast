import os
import math
import struct
import wave
from pathlib import Path
from PIL import Image, ImageDraw

ASSETS_DIR = Path(__file__).parent.resolve()

def generate_wav(filepath: Path, duration: float, sample_rate: int = 44100, tones: list = None):
    """Synthesizes high quality PCM 16-bit stereo/mono chime waveforms."""
    num_samples = int(duration * sample_rate)
    with wave.open(str(filepath), "wb") as wav_file:
        wav_file.setnchannels(1)  # Mono
        wav_file.setsampwidth(2)  # 16-bit
        wav_file.setframerate(sample_rate)

        frames = bytearray()
        for i in range(num_samples):
            t = float(i) / sample_rate
            # Exponential decay envelope
            envelope = math.exp(-3.5 * (t / duration))

            sample_val = 0.0
            if tones:
                for freq, amp, start_t, end_t in tones:
                    if start_t <= t <= end_t:
                        phase = 2.0 * math.pi * freq * (t - start_t)
                        sample_val += amp * math.sin(phase) * envelope

            sample_val = max(-1.0, min(1.0, sample_val))
            int_sample = int(sample_val * 32767.0)
            frames.extend(struct.pack("<h", int_sample))

        wav_file.writeframes(frames)

def create_all_assets():
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)

    # 1. Drop Success Chime (880Hz A5 -> 1320Hz E6 ascending futuristic bell)
    drop_wav = ASSETS_DIR / "drop_success.wav"
    generate_wav(
        drop_wav,
        duration=0.55,
        tones=[
            (880.0, 0.45, 0.0, 0.25),
            (1320.0, 0.55, 0.08, 0.55),
            (2640.0, 0.15, 0.08, 0.35),
        ]
    )

    # 2. Grab Ready Chime (587.3Hz D5 -> 880Hz A5 pleasant chime)
    grab_wav = ASSETS_DIR / "grab_ready.wav"
    generate_wav(
        grab_wav,
        duration=0.45,
        tones=[
            (587.33, 0.45, 0.0, 0.25),
            (880.0, 0.55, 0.06, 0.45),
        ]
    )

    # 3. Timeout Click (Soft click/drop blip)
    timeout_wav = ASSETS_DIR / "timeout_click.wav"
    generate_wav(
        timeout_wav,
        duration=0.20,
        tones=[
            (440.0, 0.35, 0.0, 0.08),
            (220.0, 0.45, 0.05, 0.20),
        ]
    )

    # 4. System Tray Icon (PNG & ICO)
    size = 64
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Rounded outer pill/badge
    draw.rounded_rectangle([4, 4, size - 4, size - 4], radius=14, fill=(9, 13, 22, 255), outline=(56, 189, 248, 255), width=3)
    # Glowing concentric radar circles
    center = size // 2
    draw.ellipse([center - 16, center - 16, center + 16, center + 16], outline=(99, 102, 241, 200), width=2)
    draw.ellipse([center - 9, center - 9, center + 9, center + 9], fill=(56, 189, 248, 255), outline=(255, 255, 255, 255), width=2)

    # Air teleport wings / arrows
    draw.line([center - 18, center, center - 22, center], fill=(56, 189, 248, 255), width=2)
    draw.line([center + 18, center, center + 22, center], fill=(56, 189, 248, 255), width=2)
    draw.line([center, center - 18, center, center - 22], fill=(56, 189, 248, 255), width=2)

    png_path = ASSETS_DIR / "tray_icon.png"
    ico_path = ASSETS_DIR / "tray_icon.ico"
    img.save(str(png_path), "PNG")
    img.save(str(ico_path), format="ICO", sizes=[(16, 16), (32, 32), (48, 48), (64, 64)])

    print(f"[ASSETS] Generated audio chimes and icons in: {ASSETS_DIR}")

if __name__ == "__main__":
    create_all_assets()
