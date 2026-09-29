@echo off
setlocal enabledelayedexpansion

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

set "TOOLCHAIN=C:\Users\BDJ\.workbuddy\binaries\android-toolchain"

echo ============================================================
echo   ����ʱ - ��׿ APK ����ű�
echo ============================================================
echo.

rem ---- 1. ��λ JDK / Android SDK�������ñ�����װ�õ� toolchain��----
set "JAVA_HOME=%TOOLCHAIN%\jdk\jdk-17"
set "ANDROID_HOME=%TOOLCHAIN%\android-sdk"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [����] δ�ҵ� JDK��%JAVA_HOME%
    echo ��ȷ�Ϲ������Ѱ�װ�� C:\Users\BDJ\.workbuddy\binaries\android-toolchain\
    echo.
    pause
    exit /b 1
)
if not exist "%ANDROID_HOME%" (
    echo [����] δ�ҵ� Android SDK��%ANDROID_HOME%
    echo.
    pause
    exit /b 1
)
echo [OK] JDK��%JAVA_HOME%
echo [OK] SDK��%ANDROID_HOME%
echo.

rem ---- 2. ѡ�� Gradle��������ֱ�Ӱ�װ�ģ���� gradlew��----
set "GRADLE=%TOOLCHAIN%\gradle-8.2\bin\gradle.bat"
if not exist "%GRADLE%" (
    set "GRADLE=%ROOT%\gradlew.bat"
)
if not exist "%GRADLE%" (
    echo [����] δ�ҵ� Gradle������ֱ�Ӱ�װ��Ҳ�� gradlew����
    echo.
    pause
    exit /b 1
)
echo [OK] Gradle��%GRADLE%
echo.

rem ---- 3. ���� Release APK ----
echo [1/3] ���ڱ��� Release APK���״α������������������������ĵȴ���...
echo.
call "%GRADLE%" assembleRelease --no-daemon
if errorlevel 1 (
    echo.
    echo [ʧ��] �����������鿴�Ϸ� Gradle ������Ϣ��
    echo.
    pause
    exit /b 1
)
echo.
echo [2/3] ����ɹ�����ʼǩ�� APK...
echo.

rem ---- 4. zipalign + ǩ�� + ���� ----
set "BT=%ANDROID_HOME%\build-tools\34.0.0"
set "KEYSTORE=%ROOT%\countdown-release.jks"
set "UNSIGNED=%ROOT%\app\build\outputs\apk\release\app-release-unsigned.apk"
set "ALIGNED=%ROOT%\app-release-aligned.apk"
set "OUT=%ROOT%\app-release-signed.apk"

if not exist "%UNSIGNED%" (
    echo [����] δ�ҵ�������%UNSIGNED%
    echo.
    pause
    exit /b 1
)
if not exist "%KEYSTORE%" (
    echo [����] δ�ҵ�ǩ����Կ��%KEYSTORE%
    echo.
    pause
    exit /b 1
)

call "%BT%\zipalign.exe" -f 4 "%UNSIGNED%" "%ALIGNED%"
if errorlevel 1 (
    echo [ʧ��] zipalign ������
    echo.
    pause
    exit /b 1
)

call "%BT%\apksigner.bat" sign --ks "%KEYSTORE%" --ks-key-alias countdown --ks-pass pass:baigao110 --key-pass pass:baigao110 --out "%OUT%" "%ALIGNED%"
if errorlevel 1 (
    echo [ʧ��] ǩ��������
    echo.
    pause
    exit /b 1
)

copy /Y "%OUT%" "%ROOT%\countdown-android-v1.0.0.38-release.apk"
echo [3/3] ���ڸ��Ƶ� ��׿��APK Ŀ¼...
set "DEST=%ROOT%\..\��׿��APK"
if not exist "%DEST%" mkdir "%DEST%"
copy /Y "%OUT%" "%DEST%\����ʱ-��׿��-v1.0.0.38-release.apk"
if errorlevel 1 (
    echo [����] ���Ƶ� %DEST% ʧ�ܣ�APK �������ڣ�%OUT%
) else (
    echo [OK] �����ɣ�%DEST%\����ʱ-��׿��-v1.0.0.38-release.apk
)

echo.
echo ============================================================
echo   �����ɣ�
echo   ��װ����%DEST%\����ʱ-��׿��-v1.0.0.38-release.apk
echo ============================================================
echo.
pause
