@echo off
setlocal enabledelayedexpansion
title AeroCast - Android APK Builder

cd /d "%~dp0"

echo ================================================================
echo       AEROCAST NATIVE: ANDROID DEBUG APK COMPILER
echo ================================================================
echo.

:: 1. Check for Java JDK
set JAVA_FOUND=0
javac -version >nul 2>&1
if not errorlevel 1 (
    set JAVA_FOUND=1
) else (
    if defined JAVA_HOME (
        if exist "%JAVA_HOME%\bin\javac.exe" (
            set "PATH=%JAVA_HOME%\bin;%PATH%"
            set JAVA_FOUND=1
        )
    )
)

:: 2. Check for Android SDK
set SDK_FOUND=0
if defined ANDROID_HOME (
    if exist "%ANDROID_HOME%" set SDK_FOUND=1
)
if defined ANDROID_SDK_ROOT (
    if exist "%ANDROID_SDK_ROOT%" set SDK_FOUND=1
)
if exist "%LOCALAPPDATA%\Android\Sdk" (
    set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    set SDK_FOUND=1
)

:: 3. Check for Gradle / Gradlew
cd android_app
set GRADLE_CMD=
if exist "gradlew.bat" (
    set GRADLE_CMD=call gradlew.bat
) else (
    gradle -version >nul 2>&1
    if not errorlevel 1 (
        set GRADLE_CMD=gradle
    )
)

if "%JAVA_FOUND%"=="1" if "%SDK_FOUND%"=="1" if defined GRADLE_CMD (
    echo [BUILD] Local Android SDK and Java detected.
    echo [BUILD] Compiling AeroCast Native Debug APK...
    echo.
    %GRADLE_CMD% assembleDebug
    if errorlevel 1 (
        echo [ERROR] Gradle build encountered an error.
        cd ..
        pause
        exit /b 1
    )

    if exist "app\build\outputs\apk\debug\app-debug.apk" (
        copy /Y "app\build\outputs\apk\debug\app-debug.apk" "..\AeroCast-debug.apk" >nul
        cd ..
        echo.
        echo ================================================================
        echo   SUCCESS! AeroCast Debug APK compiled successfully:
        echo   Path: %~dp0AeroCast-debug.apk
        echo ================================================================
        echo.
        pause
        exit /b 0
    )
)

cd ..

:: Local environment missing Java / Android SDK: Fallback to GitHub Actions CI Workflow
echo [INFO] Local Android SDK / Java JDK not found in Windows PATH.
echo [INFO] AeroCast includes a pre-configured GitHub Actions CI workflow:
echo        Path: .github\workflows\build_apk.yml
echo.
echo ================================================================
echo   AUTOMATED 1-CLICK CLOUD APK BUILD (NO LOCAL SETUP NEEDED)
echo ================================================================
echo   1. Push this repository to GitHub.
echo   2. Navigate to the "Actions" tab on GitHub.
echo   3. Click "Build AeroCast Android APK" ^> "Run workflow".
echo   4. Download the compiled "AeroCast-debug.apk" from Artifacts!
echo ================================================================
echo.
echo To build locally on Windows instead:
echo   - Install Java 17+:  winget install Oracle.JDK.17
echo   - Install Android Studio / SDK Command Line Tools
echo.
pause
exit /b 0
