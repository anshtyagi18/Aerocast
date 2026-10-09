#!/usr/bin/env python3
"""
AeroCast Windows Agent - Main Launcher
Usage:
    python main.py                  (Starts background tray agent)
    python main.py --stage <file>   (Directly stages file for Air Send)
"""
import sys
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent
SHARED_DIR = ROOT_DIR / "shared"
AGENT_DIR = ROOT_DIR / "windows_agent"

if str(AGENT_DIR) not in sys.path:
    sys.path.insert(0, str(AGENT_DIR))
if str(SHARED_DIR) not in sys.path:
    sys.path.insert(0, str(SHARED_DIR))

from windows_agent.tray_app import main

if __name__ == "__main__":
    main()
