@echo off
chcp 936 >nul
setlocal
cd /d "%~dp0"

echo ============================================
echo   倒计时安卓版  -^>  GitHub 同步
echo ============================================
echo.
echo 说明：本脚本只把令牌放进当前窗口的环境变量，
echo       不会写入任何文件，也不会提交到仓库。
echo.
set /p GH_TOKEN=请粘贴 GitHub 令牌(PAT)后回车: 

if "%GH_TOKEN%"=="" (
    echo.
    echo [错误] 令牌为空，已取消。
    pause
    exit /b 1
)

echo.
echo 正在同步，请稍候...
echo.

where python >nul 2>nul
if %errorlevel%==0 (
    python sync_to_github.py
) else (
    "C:\Users\BDJ\.workbuddy\binaries\python\versions\3.13.12\python.exe" sync_to_github.py
)

echo.
echo ============================================
pause
