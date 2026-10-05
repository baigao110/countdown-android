@echo off
setlocal enabledelayedexpansion

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

set "TOOLCHAIN=C:\Users\BDJ\.workbuddy\binaries\android-toolchain"

echo ============================================================
echo   倒计时 - 安卓 APK 打包脚本
echo ============================================================
echo.

rem ---- 1. 定位 JDK / Android SDK（优先用本机已装好的 toolchain）----
set "JAVA_HOME=%TOOLCHAIN%\jdk\jdk-17"
set "ANDROID_HOME=%TOOLCHAIN%\android-sdk"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [错误] 未找到 JDK：%JAVA_HOME%
    echo 请确认工具链已安装到 C:\Users\BDJ\.workbuddy\binaries\android-toolchain\
    echo.
    pause
    exit /b 1
)
if not exist "%ANDROID_HOME%" (
    echo [错误] 未找到 Android SDK：%ANDROID_HOME%
    echo.
    pause
    exit /b 1
)
echo [OK] JDK：%JAVA_HOME%
echo [OK] SDK：%ANDROID_HOME%
echo.

rem ---- 2. 选择 Gradle（优先用直接安装的，其次 gradlew）----
set "GRADLE=%TOOLCHAIN%\gradle-8.2\bin\gradle.bat"
if not exist "%GRADLE%" (
    set "GRADLE=%ROOT%\gradlew.bat"
)
if not exist "%GRADLE%" (
    echo [错误] 未找到 Gradle（既无直接安装，也无 gradlew）。
    echo.
    pause
    exit /b 1
)
echo [OK] Gradle：%GRADLE%
echo.

rem ---- 3. 编译 Release APK ----
echo [1/3] 正在编译 Release APK（首次编译需联网下载依赖，请耐心等待）...
echo.
call "%GRADLE%" assembleRelease --no-daemon
if errorlevel 1 (
    echo.
    echo [失败] 编译出错，请查看上方 Gradle 报错信息。
    echo.
    pause
    exit /b 1
)
echo.
echo [2/3] 编译成功，开始签名 APK...
echo.

rem ---- 4. zipalign + 签名 + 复制 ----
set "BT=%ANDROID_HOME%\build-tools\34.0.0"
set "KEYSTORE=%ROOT%\countdown-release.jks"
set "UNSIGNED=%ROOT%\app\build\outputs\apk\release\app-release-unsigned.apk"
set "ALIGNED=%ROOT%\app-release-aligned.apk"
set "OUT=%ROOT%\app-release-signed.apk"

if not exist "%UNSIGNED%" (
    echo [错误] 未找到编译产物：%UNSIGNED%
    echo.
    pause
    exit /b 1
)
if not exist "%KEYSTORE%" (
    echo [错误] 未找到签名密钥：%KEYSTORE%
    echo.
    pause
    exit /b 1
)

call "%BT%\zipalign.exe" -f 4 "%UNSIGNED%" "%ALIGNED%"
if errorlevel 1 (
    echo [失败] zipalign 出错。
    echo.
    pause
    exit /b 1
)

call "%BT%\apksigner.bat" sign --ks "%KEYSTORE%" --ks-key-alias countdown --ks-pass pass:baigao110 --key-pass pass:baigao110 --out "%OUT%" "%ALIGNED%"
if errorlevel 1 (
    echo [失败] 签名出错。
    echo.
    pause
    exit /b 1
)

echo [3/3] 正在复制到 安卓版APK 目录...
set "DEST=%ROOT%\..\安卓版APK"
if not exist "%DEST%" mkdir "%DEST%"
copy /Y "%OUT%" "%DEST%\倒计时-安卓版-v1.0.0-release.apk"
if errorlevel 1 (
    echo [警告] 复制到 %DEST% 失败，APK 已生成在：%OUT%
) else (
    echo [OK] 已生成：%DEST%\倒计时-安卓版-v1.0.0-release.apk
)

echo.
echo ============================================================
echo   打包完成！
echo   安装包：%DEST%\倒计时-安卓版-v1.0.0-release.apk
echo ============================================================
echo.
pause
