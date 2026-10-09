@echo off
cd /d "%~dp0"
if exist "%~dp0venv\Scripts\pythonw.exe" (
    start "" "%~dp0venv\Scripts\pythonw.exe" "%~dp0main.py"
    echo [AEROCAST] AeroCast Native Tray Agent launched in background.
) else (
    call install_windows.bat
)
