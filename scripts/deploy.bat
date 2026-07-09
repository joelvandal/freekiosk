@echo off
REM ============================================================================
REM  deploy.bat  -  STANDALONE FreeKiosk deployer (copy anywhere; no repo needed)
REM ============================================================================
REM  On a Windows PC with the target Android device connected over USB
REM  (USB debugging ON), this single file:
REM    1. installs Android platform-tools (adb) if adb is not already on PATH
REM    2. downloads the LATEST release APK from GitHub
REM    3. installs it and provisions the device: Device Owner, permissions,
REM       default Home, clock/NTP, kiosk URL/PIN, optional Website Basic-auth,
REM       and (on ROOTED panels) removes the software navigation bar.
REM    Auto-recovers from a signature mismatch on ROOTED panels.
REM
REM  Usage: deploy.bat [device-serial] [--url URL] [--pin PIN] [--username U] [--password P] [--debug]
REM    --url       Kiosk URL (default below). Quote URLs that contain & or ?.
REM    --pin       Kiosk PIN / password (default: 1234).
REM    --username  Website (HTTP Basic) auth username (optional).
REM    --password  Website (HTTP Basic) auth password (optional; stored in Keychain).
REM    --debug     Verbose: trace commands, show hidden errors, dump diagnostics.
REM    If no serial is given, the only connected device is used.
REM ============================================================================

setlocal enabledelayedexpansion

REM ---- Configuration (edit these if you fork the project) ----
set "REPO=joelvandal/freekiosk"
set "PKG=com.freekiosk"
set "ADMIN=%PKG%/.DeviceAdminReceiver"
set "APK=%TEMP%\FreeKiosk-latest.apk"
set "OUT=%TEMP%\fk_deploy.txt"

REM ---- Defaults ----
set "DEBUG="
set "SERIAL="
set "KIOSK_URL=https://kiosk.dev.sirsteward.com"
set "KIOSK_PIN=1234"

REM ---- Parse args from %* (for/f splits on spaces only, keeps '=' in URLs) ----
set "ARGS=%*"
:argloop
if not defined ARGS goto :argsdone
for /f "tokens=1*" %%A in ("!ARGS!") do (set "TOK=%%A" & set "ARGS=%%B")
if /I "!TOK!"=="--debug" (
    set "DEBUG=1"
) else if /I "!TOK!"=="--url" (
    call :popval KIOSK_URL
) else if /I "!TOK!"=="--pin" (
    call :popval KIOSK_PIN
) else if /I "!TOK!"=="--username" (
    call :popval KIOSK_USER
) else if /I "!TOK!"=="--password" (
    call :popval KIOSK_PASS
) else (
    set "SERIAL=!TOK!"
)
goto :argloop
:argsdone

if "%SERIAL%"=="" (set "ADB=adb") else (set "ADB=adb -s %SERIAL%")
if defined DEBUG (set "Q=") else (set "Q=2>nul")
if defined DEBUG (set "RQ=") else (set "RQ=>nul 2>&1")

REM ---- 1. Ensure adb (download Android platform-tools if missing) ----
where adb >nul 2>&1 && goto :adbready
set "TOOLS_ROOT=%LOCALAPPDATA%\FreeKiosk"
set "TOOLS_DIR=%TOOLS_ROOT%\platform-tools"
if exist "%TOOLS_DIR%\adb.exe" (
    set "PATH=%TOOLS_DIR%;%PATH%"
    goto :adbready
)
echo ==^> adb not found -- downloading Android platform-tools...
if not exist "%TOOLS_ROOT%" mkdir "%TOOLS_ROOT%"
curl -L -o "%TEMP%\platform-tools.zip" https://dl.google.com/android/repository/platform-tools-latest-windows.zip
if errorlevel 1 (echo ==^> Download failed ^(need curl^). Install adb manually and re-run. & goto :fail)
tar -xf "%TEMP%\platform-tools.zip" -C "%TOOLS_ROOT%"
if errorlevel 1 (echo ==^> Extraction failed ^(need tar, Windows 10+^). Install adb manually. & goto :fail)
del "%TEMP%\platform-tools.zip" 2>nul
if not exist "%TOOLS_DIR%\adb.exe" (echo ==^> adb.exe missing after extract. & goto :fail)
set "PATH=%TOOLS_DIR%;%PATH%"
echo ==^> platform-tools installed to %TOOLS_DIR%
:adbready

REM ---- 2. Download the LATEST release APK from GitHub ----
echo ==^> Fetching latest FreeKiosk release from github.com/%REPO% ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; $h=@{'User-Agent'='FreeKiosk-deploy'}; $r=Invoke-RestMethod -Uri 'https://api.github.com/repos/%REPO%/releases/latest' -Headers $h; $a=$r.assets | Where-Object { $_.name -like '*.apk' } | Select-Object -First 1; if(-not $a){ Write-Error 'No APK asset in latest release'; exit 1 }; Write-Host ('    ' + $r.tag_name + ' - ' + $a.name); Invoke-WebRequest -Uri $a.browser_download_url -Headers $h -OutFile '%APK%' } catch { Write-Error $_.Exception.Message; exit 1 }"
if errorlevel 1 (echo ==^> Failed to download the APK from GitHub. & goto :fail)
if not exist "%APK%" (echo ==^> APK was not downloaded. & goto :fail)
echo ==^> Downloaded to %APK%

if defined DEBUG (
    echo [debug] verbose mode ON  ^(ADB=%ADB%^)
    call :diag
    echo on
)

REM ---- 3. Provision the device ----
echo ==^> Removing existing Device Admin (ignored if absent or already Device Owner)
%ADB% shell dpm remove-active-admin %ADMIN% %Q%

echo ==^> Installing (-d allows reinstalling over a higher versionCode)
%ADB% install -r -d "%APK%" > "%OUT%" 2>&1
type "%OUT%"
findstr /C:"Success" "%OUT%" >nul && goto :installed

REM Only a signature mismatch is auto-recoverable (and only on a rooted device).
findstr /C:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" "%OUT%" >nul || (
    echo ==^> Install failed for a reason other than a signature mismatch.
    goto :fail
)

echo.
echo ==^> Signature mismatch: the installed app is Device Owner and signed with a
echo     different key. Attempting an automatic clean reset ^(REQUIRES ROOT^)...
echo.

echo ==^> [reset] Switching adbd to root
%ADB% root
if errorlevel 1 (
    echo ==^> 'adb root' failed -- cannot auto-remove Device Owner on a non-rooted device.
    goto :fail
)

echo ==^> [reset] Removing Device Owner / device policies
%ADB% shell "rm -f /data/system/device_owner_2.xml /data/system/device_owner.xml /data/system/device_policies.xml"

echo ==^> [reset] Rebooting the device
%ADB% reboot
echo ==^> [reset] Waiting for the device to come back online...
%ADB% wait-for-device

echo ==^> [reset] Waiting for boot to complete...
set /a _tries=0
:waitboot
set "BOOT="
for /f "usebackq delims=" %%i in (`%ADB% shell getprop sys.boot_completed 2^>nul`) do set "BOOT=%%i"
if defined DEBUG echo [debug] boot_completed='!BOOT!' try=!_tries!
echo !BOOT! | findstr "1" >nul && goto :booted
set /a _tries+=1
if !_tries! GEQ 60 (echo ==^> Timed out waiting for boot. & goto :fail)
timeout /t 3 /nobreak >nul
goto :waitboot
:booted
timeout /t 3 /nobreak >nul

echo ==^> [reset] Uninstalling the old app
%ADB% uninstall %PKG%

echo ==^> [reset] Reinstalling
%ADB% install -r -d "%APK%" > "%OUT%" 2>&1
type "%OUT%"
findstr /C:"Success" "%OUT%" >nul || (echo ==^> Reinstall still failed. & goto :fail)

:installed
if defined DEBUG call :diag
echo ==^> Setting Device Owner (%ADMIN%)
%ADB% shell dpm set-device-owner %ADMIN% %Q%

echo ==^> Granting WRITE_SECURE_SETTINGS to %PKG%
%ADB% shell pm grant %PKG% android.permission.WRITE_SECURE_SETTINGS

echo ==^> Granting WRITE_SETTINGS to %PKG%
%ADB% shell appops set %PKG% android:write_settings allow

echo ==^> Granting camera + microphone permissions to %PKG%
%ADB% shell pm grant %PKG% android.permission.CAMERA %Q%
%ADB% shell pm grant %PKG% android.permission.RECORD_AUDIO %Q%

echo ==^> Setting FreeKiosk as the default Home / launcher
%ADB% shell cmd package set-home-activity com.freekiosk/com.freekiosk.MainActivity %Q%

echo ==^> Enabling auto date/time (NTP) and auto timezone
%ADB% shell settings put global auto_time 1
%ADB% shell settings put global auto_time_zone 1
%ADB% shell settings put global ntp_server time.google.com
%ADB% shell setprop persist.sys.timezone "America/Montreal"

echo ==^> Preconfiguring kiosk URL: !KIOSK_URL!
set "AUTH="
if defined KIOSK_USER set "AUTH=!AUTH! --es basic_auth_username "'!KIOSK_USER!'""
if defined KIOSK_PASS set "AUTH=!AUTH! --es basic_auth_password "'!KIOSK_PASS!'""
if defined KIOSK_USER echo ==^> Website auth username: !KIOSK_USER!
%ADB% shell am start -n com.freekiosk/.MainActivity --es url "'%KIOSK_URL%'" --es pin "'%KIOSK_PIN%'"!AUTH! --ez kiosk_enabled true --es auto_relaunch "true"

echo ==^> Removing software navigation bar (ROOTED panels only; skipped otherwise)
%ADB% root %RQ%
%ADB% shell "id" | find "uid=0" >nul
if errorlevel 1 (
    echo     Device not rooted -- skipping nav-bar removal.
) else (
    %ADB% remount
    %ADB% shell "grep -q qemu.hw.mainkeys /system/build.prop || echo qemu.hw.mainkeys=1 >> /system/build.prop"
    echo ==^> Verifying build.prop entry (should print qemu.hw.mainkeys=1)
    %ADB% shell "grep mainkeys /system/build.prop"
    %ADB% unroot
    echo ==^> Rebooting to apply
    %ADB% reboot
)

echo ==^> Done
del "%OUT%" 2>nul
del "%APK%" 2>nul
endlocal
exit /b 0

:popval
set "%~1="
for /f "tokens=1*" %%A in ("!ARGS!") do (set "%~1=%%A" & set "ARGS=%%B")
goto :eof

:diag
echo(
echo [debug] ---- diagnostics ----
%ADB% devices -l
echo [debug] installed %PKG%:
%ADB% shell dumpsys package %PKG% | findstr /C:"versionName=" /C:"versionCode="
echo [debug] device owner:
%ADB% shell dumpsys device_policy | findstr /I /C:"Device Owner" /C:"admin="
echo [debug] ---------------------
echo(
goto :eof

:fail
echo.
echo ==^> FAILED
del "%OUT%" 2>nul
endlocal
exit /b 1
