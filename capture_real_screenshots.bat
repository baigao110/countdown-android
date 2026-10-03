@echo off
chcp 65001 >nul
setlocal
title Countdown - capture real screenshots via adb
rem ------------------------------------------------------------------
rem Put phone on USB cable, enable "USB debugging", allow the USB
rem debugging authorization popup on the phone, then just run this file.
rem It installs the APK, grants the overlay permission, and grabs
rem 4 real screenshots into screenshots/.
rem ------------------------------------------------------------------
set "ANDROID_HOME=C:\Users\BDJ\.workbuddy\binaries\android-toolchain\android-sdk"
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
set "PY=C:\Users\BDJ\.workbuddy\binaries\python\versions\3.13.12\python.exe"
cd /d "%~dp0"
if not exist "%ADB%" (
  echo adb not found: %ADB%
  pause
  exit /b 1
)
"%ADB%" devices -l
echo.
echo --- If the list above is empty, plug the phone in and allow USB debugging ---
echo.
"%PY%" "..\_tmp_scripts\_capture_real.py"
echo.
pause
