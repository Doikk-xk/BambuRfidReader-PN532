@echo off
REM ===================================================================
REM  PN532 BLE - Sync upstream release  (double-click to run)
REM
REM  Base remote is Gitee (origin) - direct connection, no proxy needed.
REM  GitHub (gh-upstream) is the fallback and uses the local proxy.
REM
REM  Usage:
REM     double-click this file
REM     sync-upstream.bat --check      show upstream changes only
REM     sync-upstream.bat --no-build   merge but skip the build
REM ===================================================================
setlocal enabledelayedexpansion

REM Git Bash outputs UTF-8; switch the console to UTF-8 so Chinese shows correctly.
chcp 65001 >nul 2>&1

set "REPO=G:\windows\workbuddy\2026-11-22-11-10-33\BambuRfidReader"
set "ADB=G:\windows\workbuddy\toolchain\android-sdk\platform-tools\adb.exe"
set "DEVICE=192.168.100.199:39295"
set "APK=app\build\outputs\apk\debug\app-debug.apk"

cd /d "%REPO%" || (echo [ERROR] repo folder not found & pause & exit /b 1)

echo.
echo ================================================
echo   PN532 BLE - Sync Upstream
echo ================================================
echo.

call bash sync-upstream.sh %*
set RC=%ERRORLEVEL%

echo.
echo ================================================
if %RC% NEQ 0 (
    echo   Sync stopped with errors.
    echo.
    echo   How to recover:
    echo     [A] Conflict - edit the file, then run:
    echo         git add ^<file^>
    echo         git rebase --continue
    echo     [B] Abort this update entirely:
    echo         git rebase --abort
    echo     [C] Network - Gitee needs no proxy; GitHub needs the proxy on.
    echo.
    echo   Full guide: PN532_BLE_UPSTREAM_GUIDE.md
    goto :done
)

echo   Sync finished.
echo.
if exist "%APK%" (
    echo   New APK:
    echo   %REPO%\%APK%
    echo.
    set /p INSTALL="   Install to phone now? Y/N : "
    if /i "!INSTALL!"=="Y" (
        echo   Installing...
        "%ADB%" -s %DEVICE% install -r -t "%APK%"
        echo   Done. Open the app and test.
    )
)

:done
echo.
pause
