@echo off
setlocal enabledelayedexpansion
chcp 437 >nul

set PKG=com.autoclicker.app
set SVC=%PKG%/%PKG%.service.ClickAccessibilityService
set APK=%~dp0..\..\dist\AutoClicker-0.5.0-release.apk
if not exist "%APK%" set APK=%~dp0..\dist\AutoClicker-0.5.0-release.apk

set ADB=
where adb >nul 2>nul && set ADB=adb
if not defined ADB if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB (
  echo adb not found. Install platform-tools or add it to PATH.
  goto :end
)

echo [1/5] adb = %ADB%
"%ADB%" start-server >nul 2>nul

echo [2/5] waiting for device ...
"%ADB%" wait-for-device
for /f "tokens=*" %%d in ('"%ADB%" shell getprop ro.product.model 2^>nul') do echo       model: %%d

echo [3/5] installing "%APK%"
"%ADB%" install -r -g "%APK%"

echo [4/5] enabling accessibility service ...
"%ADB%" shell settings put secure enabled_accessibility_services %SVC%
"%ADB%" shell settings put secure accessibility_enabled 1
timeout /t 2 >nul
"%ADB%" shell dumpsys accessibility | findstr /i "ClickAccessibility"

echo [5/5] launching app ...
"%ADB%" shell am start -n %PKG%/.MainActivity

echo.
echo   live log : "%ADB%" shell run-as %PKG% cat files/clicks.log
echo   crashes  : "%ADB%" logcat -s AndroidRuntime:E
echo   stop app : "%ADB%" shell am force-stop %PKG%

:end
endlocal
