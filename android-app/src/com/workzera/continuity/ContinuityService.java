package com.workzera.continuity;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.StatFs;
import android.media.MediaScannerConnection;
import android.provider.ContactsContract;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ContinuityService extends Service {
    public static final int PORT = 8899;
    public static final String ACTION_STOP = "com.workzera.continuity.ACTION_STOP";
    public static final String CHANNEL_ID = "mac_continuity_channel";

    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private ClipboardManager clipboardManager;
    private Handler mainHandler;
    private volatile String latestClipboard = "";

    @Override
    public void onCreate() {
        super.onCreate();
        clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
        try {
            startForeground(1001, buildNotification());
        } catch (Throwable ignored) {}

        try {
            if (clipboardManager != null) {
                clipboardManager.addPrimaryClipChangedListener(() -> {
                    String cur = getClipboardText();
                    if (cur != null && !cur.isEmpty() && !cur.equals(latestClipboard)) {
                        latestClipboard = cur;
                        pushToMacClipboard(cur);
                    }
                });
            }
        } catch (Throwable ignored) {}

        startHttpServer();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Mac Continuity Background Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Maintains wireless link, clipboard sync, and file transfer with Mac");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Intent stopIntent = new Intent(this, ContinuityService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle("Mac Continuity Active")
                .setContentText("Connected to MacBook Pro • Port " + PORT)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(openPendingIntent)
                .setOngoing(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            Notification.Action exitAction = new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Disconnect",
                    stopPendingIntent
            ).build();
            builder.addAction(exitAction);
        }

        return builder.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopServiceAndExit();
            return START_NOT_STICKY;
        }

        try {
            startForeground(1001, buildNotification());
        } catch (Throwable ignored) {}

        return START_STICKY;
    }

    private void stopServiceAndExit() {
        isRunning = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}

        try {
            stopForeground(true);
        } catch (Throwable ignored) {}

        // Broadcast to close MainActivity if open
        Intent broadcast = new Intent("com.workzera.continuity.EXIT_APP");
        sendBroadcast(broadcast);

        stopSelf();
    }

    private void startHttpServer() {
        isRunning = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                while (isRunning) {
                    Socket socket = serverSocket.accept();
                    handleClient(socket);
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void handleClient(Socket socket) {
        new Thread(() -> {
            try {
                if (socket.getInetAddress() != null) {
                    String clientIp = socket.getInetAddress().getHostAddress();
                    if (clientIp != null && !clientIp.equals("127.0.0.1") && !clientIp.startsWith("0:")) {
                        getSharedPreferences("ContinuityPrefs", MODE_PRIVATE).edit().putString("mac_bridge_ip", clientIp).apply();
                    }
                }

                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                OutputStream out = socket.getOutputStream();

                String line = in.readLine();
                if (line == null) {
                    socket.close();
                    return;
                }

                String[] parts = line.split(" ");
                String method = parts.length > 0 ? parts[0] : "GET";
                String path = parts.length > 1 ? parts[1] : "/";

                int contentLength = 0;
                String header;
                while ((header = in.readLine()) != null && !header.isEmpty()) {
                    if (header.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(header.substring(15).trim());
                    }
                }

                StringBuilder body = new StringBuilder();
                if (contentLength > 0) {
                    char[] buffer = new char[contentLength];
                    int read = in.read(buffer, 0, contentLength);
                    if (read > 0) {
                        body.append(buffer, 0, read);
                    }
                }

                String responseJson;
                int statusCode = 200;

                if (path.startsWith("/api/ping")) {
                    responseJson = "{\"status\":\"ok\",\"device\":\"" + Build.MODEL + "\"}";
                } else if (path.startsWith("/api/status")) {
                    BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
                    int bat = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : 80;
                    
                    String storageStr = "{}";
                    try {
                        StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getPath());
                        long total = stat.getTotalBytes();
                        long free = stat.getAvailableBytes();
                        long used = total - free;
                        storageStr = "{\"total\":" + total + ",\"used\":" + used + ",\"free\":" + free + "}";
                    } catch (Exception ignored) {}

                    responseJson = "{\"status\":\"active\",\"model\":\"" + Build.MODEL + "\",\"manufacturer\":\"" + Build.MANUFACTURER + "\",\"battery\":" + bat + ",\"storage\":" + storageStr + "}";
                } else if (path.startsWith("/api/clipboard") && method.equals("GET")) {
                    String clipText = getClipboardText();
                    try {
                        org.json.JSONObject obj = new org.json.JSONObject();
                        obj.put("clipboard", clipText);
                        responseJson = obj.toString();
                    } catch (Exception ignored) {
                        responseJson = "{\"clipboard\":\"\"}";
                    }
                } else if (path.startsWith("/api/clipboard") && method.equals("POST")) {
                    String toCopy = "";
                    try {
                        org.json.JSONObject obj = new org.json.JSONObject(body.toString());
                        toCopy = obj.optString("text", "");
                    } catch (Exception e) {
                        toCopy = body.toString();
                    }
                    if (!toCopy.isEmpty()) {
                        final String finalText = toCopy;
                        latestClipboard = finalText;
                        mainHandler.post(() -> setClipboardText(finalText));
                    }
                    responseJson = "{\"success\":true}";
                } else if (path.startsWith("/api/file-received") && method.equals("POST")) {
                    String filename = "file";
                    boolean isImage = false;
                    String b = body.toString();
                    if (b.contains("\"filename\":")) {
                        int idx = b.indexOf("\"filename\":");
                        int start = b.indexOf("\"", idx + 11) + 1;
                        int end = b.indexOf("\"", start);
                        if (start > 0 && end > start) filename = b.substring(start, end);
                    }
                    if (b.contains("\"isImage\":true")) isImage = true;

                    final String finalName = filename;
                    final boolean finalIsImage = isImage;

                    // Trigger MediaStore scan
                    try {
                        String dl = "/sdcard/Download/" + finalName;
                        String pic = "/sdcard/Pictures/" + finalName;
                        String[] paths = finalIsImage ? new String[]{ dl, pic } : new String[]{ dl };
                        MediaScannerConnection.scanFile(getApplicationContext(), paths, null, (p, uri) -> {});
                    } catch (Throwable ignored) {}

                    showFileReceivedNotification(finalName, finalIsImage);
                    responseJson = "{\"success\":true}";
                } else if (path.startsWith("/api/contacts")) {
                    responseJson = getContactsJson();
                } else {
                    statusCode = 404;
                    responseJson = "{\"error\":\"not_found\"}";
                }

                byte[] respBytes = responseJson.getBytes(StandardCharsets.UTF_8);
                String httpHeaders = "HTTP/1.1 " + (statusCode == 200 ? "200 OK" : "404 Not Found") + "\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                        "Content-Length: " + respBytes.length + "\r\n" +
                        "Connection: close\r\n\r\n";

                out.write(httpHeaders.getBytes(StandardCharsets.UTF_8));
                out.write(respBytes);
                out.flush();
                socket.close();
            } catch (Exception ignored) {}
        }).start();
    }

    private void showFileReceivedNotification(String filename, boolean isImage) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        "mac_continuity_files",
                        "File Transfers",
                        NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("Alerts when files or photos are received from Mac");
                nm.createNotificationChannel(channel);
            }

            Intent openIntent = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(
                    this, 2, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
            );

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, "mac_continuity_files");
            } else {
                builder = new Notification.Builder(this);
            }

            String targetFolder = isImage ? "Photos & Downloads" : "Downloads";
            builder.setContentTitle(isImage ? "Photo Received from Mac" : "File Received from Mac")
                    .setContentText(filename + " (Saved to " + targetFolder + ")")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setAutoCancel(true)
                    .setContentIntent(pi);

            nm.notify((int) System.currentTimeMillis(), builder.build());
        } catch (Throwable ignored) {}
    }

    private void pushToMacClipboard(String text) {
        new Thread(() -> {
            try {
                String macIp = getSharedPreferences("ContinuityPrefs", MODE_PRIVATE).getString("mac_bridge_ip", "");
                if (macIp.isEmpty()) return;
                java.net.URL url = new java.net.URL("http://" + macIp + ":8898/api/clipboard");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(1500);
                conn.setReadTimeout(1500);
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("text", text);
                obj.put("source", "phone");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(obj.toString().getBytes(StandardCharsets.UTF_8));
                }
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    private String getClipboardText() {
        try {
            if (clipboardManager != null && clipboardManager.hasPrimaryClip()) {
                ClipData clip = clipboardManager.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence text = clip.getItemAt(0).getText();
                    if (text != null && text.length() > 0) {
                        latestClipboard = text.toString();
                        return latestClipboard;
                    }
                }
            }
        } catch (Exception ignored) {}
        return latestClipboard != null ? latestClipboard : "";
    }

    private void setClipboardText(String text) {
        latestClipboard = text != null ? text : "";
        try {
            if (clipboardManager != null) {
                ClipData clip = ClipData.newPlainText("Continuity", text);
                clipboardManager.setPrimaryClip(clip);
            }
        } catch (Exception ignored) {}
    }

    private String getContactsJson() {
        StringBuilder sb = new StringBuilder("[");
        try {
            Cursor cursor = getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                    },
                    null, null, null
            );
            if (cursor != null) {
                int count = 0;
                while (cursor.moveToNext() && count < 100) {
                    String name = cursor.getString(0);
                    String num = cursor.getString(1);
                    if (count > 0) sb.append(",");
                    sb.append("{\"name\":\"").append(escapeJson(name)).append("\",\"number\":\"").append(escapeJson(num)).append("\"}");
                    count++;
                }
                cursor.close();
            }
        } catch (Exception ignored) {}
        sb.append("]");
        return "{\"contacts\":" + sb.toString() + "}";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    @Override
    public void onDestroy() {
        stopServiceAndExit();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
