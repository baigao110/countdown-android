@echo off
chcp 65001 >nul
setlocal
title Countdown - capture real screenshots via adb
rem ---------------------------------------------------------------
rem Real device screenshots. Plug the phone in, allow USB debugging
rem (tap Allow on the phone), then double click this file.
rem The script waits up to 300 seconds for the device, then installs
rem the APK and grabs 4 real screenshots into screenshots/.
rem Waiting seconds can be passed as the first argument, e.g. xxx.bat 900
rem NOTE: this file must stay pure ASCII on purpose - all Chinese
rem messages are printed by the python helper instead.
rem ---------------------------------------------------------------
set "ANDROID_HOME=C:\Users\BDJ\.workbuddy\binaries\android-toolchain\android-sdk"
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
set "PY=C:\Users\BDJ\.workbuddy\binaries\python\versions\3.13.12\python.exe"
cd /d "%~dp0"
if not exist "%ADB%" (
  echo adb not found: %ADB%
  pause
  exit /b 1
)
echo.
echo ---- step 1: device check ----
"%ADB%" devices -l
echo.
"%PY%" "..\_tmp_scripts\_capture_real.py" howto
echo ---- step 2: waiting for the device and shooting ----
"%PY%" "..\_tmp_scripts\_capture_real.py" wait %~1
echo.
echo ---- done ----
pause
