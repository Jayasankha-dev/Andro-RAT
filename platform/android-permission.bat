@echo off
setlocal EnableDelayedExpansion
set PKG=com.thunderx.telegramagent

echo ============================================
echo   TelegramAgent - Full Permission Grant
echo   Package: %PKG%
echo ============================================
echo.

REM ---------- [0] Check ADB + device ----------
echo [0/5] Checking ADB device...
adb get-state 1>nul 2>&1
if errorlevel 1 (
    echo   [ERROR] No ADB device. Connect USB + enable USB debugging.
    pause
    exit /b 1
)
echo   OK - device connected.

REM ---------- [1] Check package installed ----------
echo.
echo [1/5] Checking if package is installed...
adb shell pm list packages 2>nul | findstr /C:"%PKG%" >nul
if errorlevel 1 (
    echo   [ERROR] Package %PKG% is NOT installed.
    echo   Install first: adb install app.apk
    pause
    exit /b 1
)
echo   OK - package found.

REM ---------- [2] Runtime permissions ----------
echo.
echo [2/5] Granting runtime permissions...
set GOK=0
set GFAIL=0
for %%P in (
  android.permission.READ_CONTACTS android.permission.WRITE_CONTACTS
  android.permission.READ_CALL_LOG android.permission.WRITE_CALL_LOG
  android.permission.CALL_PHONE android.permission.READ_PHONE_STATE
  android.permission.READ_PHONE_NUMBERS android.permission.ANSWER_PHONE_CALLS
  android.permission.READ_SMS android.permission.SEND_SMS
  android.permission.RECEIVE_SMS
  android.permission.READ_CALENDAR android.permission.WRITE_CALENDAR
  android.permission.CAMERA android.permission.RECORD_AUDIO
  android.permission.BODY_SENSORS android.permission.ACTIVITY_RECOGNITION
  android.permission.ACCESS_FINE_LOCATION
  android.permission.ACCESS_COARSE_LOCATION
  android.permission.ACCESS_BACKGROUND_LOCATION
  android.permission.READ_EXTERNAL_STORAGE
  android.permission.WRITE_EXTERNAL_STORAGE
  android.permission.READ_MEDIA_IMAGES
  android.permission.READ_MEDIA_VIDEO
  android.permission.READ_MEDIA_AUDIO
  android.permission.READ_MEDIA_VISUAL_USER_SELECTED
  android.permission.POST_NOTIFICATIONS
  android.permission.BLUETOOTH_CONNECT
  android.permission.BLUETOOTH_SCAN
  android.permission.BLUETOOTH_ADVERTISE
  android.permission.NEARBY_WIFI_DEVICES
  android.permission.SCHEDULE_EXACT_ALARM
  android.permission.USE_EXACT_ALARM
  android.permission.GET_ACCOUNTS
  android.permission.SYSTEM_ALERT_WINDOW
  android.permission.WRITE_SETTINGS
) do (
  adb shell pm grant %PKG% %%P >nul 2>&1
  if !errorlevel! equ 0 (
    set /a GOK+=1
  ) else (
    set /a GFAIL+=1
  )
)
echo   Granted: !GOK!  ^| Skipped: !GFAIL!

REM ---------- [3] AppOps ----------
echo.
echo [3/5] Setting AppOps...
set OOK=0
set OFAIL=0
for %%O in (
  PROJECT_MEDIA READ_CLIPBOARD WRITE_CLIPBOARD LEGACY_STORAGE
  READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE
  READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_AUDIO
  READ_MEDIA_VISUAL_USER_SELECTED
  CAMERA RECORD_AUDIO
  FINE_LOCATION COARSE_LOCATION MONITOR_LOCATION MONITOR_HIGH_POWER_LOCATION
  READ_CONTACTS WRITE_CONTACTS READ_CALL_LOG WRITE_CALL_LOG CALL_PHONE
  READ_SMS SEND_SMS RECEIVE_SMS
  READ_CALENDAR WRITE_CALENDAR
  BODY_SENSORS ACTIVITY_RECOGNITION
  READ_PHONE_STATE READ_PHONE_NUMBERS ANSWER_PHONE_CALLS
  SYSTEM_ALERT_WINDOW REQUEST_INSTALL_PACKAGES
  GET_USAGE_STATS WRITE_SETTINGS
  VIBRATE WAKE_LOCK START_FOREGROUND
  RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND
  TOAST_WINDOW
  BOOT_COMPLETED READ_DEVICE_IDENTIFIERS
  USE_FULL_SCREEN_INTENT TURN_SCREEN_ON
) do (
  adb shell appops set %PKG% %%O allow >nul 2>&1
  if !errorlevel! equ 0 (
    set /a OOK+=1
  ) else (
    set /a OFAIL+=1
  )
)
echo   Set: !OOK!  ^| Skipped: !OFAIL!

REM ---------- [4] Battery whitelist ----------
echo.
echo [4/5] Battery optimization whitelist...
adb shell dumpsys deviceidle whitelist +%PKG% >nul 2>&1
if errorlevel 1 (
    echo   [WARN] Could not whitelist.
) else (
    echo   OK - whitelisted.
)

REM ---------- [5] Verify key permissions ----------
echo.
echo [5/5] Verifying key permissions...
echo.
echo --- CALL_LOG / SMS / CONTACTS ---
adb shell appops get %PKG% 2>nul | findstr /I "CALL_LOG READ_SMS SEND_SMS RECEIVE_SMS CONTACTS"
echo.
echo --- STORAGE / MEDIA ---
adb shell appops get %PKG% 2>nul | findstr /I "STORAGE PROJECT_MEDIA"
echo.
echo --- CAMERA / MIC / LOCATION ---
adb shell appops get %PKG% 2>nul | findstr /I "CAMERA RECORD_AUDIO LOCATION"
echo.

echo ============================================
echo   DONE!
echo ============================================
echo.
echo Force-stop %PKG% now? (Y/N)
choice /C YN /N /T 10 /D N
if errorlevel 2 goto :skip_stop
adb shell am force-stop %PKG%
echo   App force-stopped.
:skip_stop
echo.
echo Press any key to exit.
pause >nul
endlocal