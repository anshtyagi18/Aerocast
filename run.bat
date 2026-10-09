@echo off
cd /d "%~dp0"
if exist "venv\Scripts\pythonw.exe" (
    start "" venv\Scripts\pythonw.exe windows_agent\tray_app.py
    echo [AEROCAST] AeroCast Native Tray Agent launched in background.
) else (
    call install_windows.bat
)
