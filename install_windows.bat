@echo off
title AeroCast Native - Windows Installer

cd /d "%~dp0"

echo ================================================================
echo       AEROCAST NATIVE: HUAWEI AIR-GESTURE DAEMON INSTALLER
echo ================================================================
echo.

:: 1. Verify Python
python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python is not installed or not in system PATH.
    echo Please install Python 3.10+ from python.org with Add to PATH checked.
    pause
    exit /b 1
)

:: 2. Setup Virtual Environment
if not exist "venv\Scripts\activate.bat" (
    echo [SETUP] Creating isolated virtual environment...
    python -m venv venv
    if errorlevel 1 (
        echo [ERROR] Failed to create virtual environment.
        pause
        exit /b 1
    )
)

echo [SETUP] Activating virtual environment...
call venv\Scripts\activate.bat

:: 3. Install requirements
echo [SETUP] Installing dependencies from requirements.txt...
pip install -r requirements.txt

:: 4. Verify assets (audio chimes and icons)
echo [SETUP] Checking assets...
if exist "windows_agent\assets\generate_assets.py" (
    python windows_agent\assets\generate_assets.py
)
if exist "windows_agent\assets\drop_success.wav" (
    if not exist "windows_agent\assets\drop.wav" (
        copy /Y "windows_agent\assets\drop_success.wav" "windows_agent\assets\drop.wav" >nul
    )
)
if exist "windows_agent\assets\grab_ready.wav" (
    if not exist "windows_agent\assets\grab.wav" (
        copy /Y "windows_agent\assets\grab_ready.wav" "windows_agent\assets\grab.wav" >nul
    )
)

:: 5. Register Windows Explorer Right-Click Context Menu & Permanent Auto-Start
echo [SETUP] Registering AeroCast into Windows Explorer & Windows Startup...
python windows_agent\explorer_context.py --install

:: 6. Configure Windows Firewall (UDP 42424 & TCP 42425)
echo [SETUP] Ensuring Windows Firewall allows local communication...
netsh advfirewall firewall add rule name="AeroCast UDP Signaling" dir=in action=allow protocol=UDP localport=42424 >nul 2>&1
netsh advfirewall firewall add rule name="AeroCast TCP Transfer" dir=in action=allow protocol=TCP localport=42425 >nul 2>&1

:: 7. Launch Windows Background Tray Agent Silently
echo.
echo ================================================================
echo   AeroCast Native successfully installed and registered!
echo   - Permanent Auto-Start: Enabled (starts on Windows boot)
echo   - Right-click any file in Explorer: "Air Send with AeroCast"
echo   - Subnet Auto-Discovery: Listening on UDP 42424 / TCP 42425
echo   - Background Tray Agent: Running silently via pythonw
echo ================================================================
echo.

start "" "%~dp0venv\Scripts\pythonw.exe" "%~dp0windows_agent\tray_app.py"

echo [AEROCAST] Agent is running silently in the Windows system tray.
echo You can close this window now.
echo.
pause
exit /b 0
