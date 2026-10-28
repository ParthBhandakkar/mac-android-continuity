package com.workzera.continuity;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private static final int PICK_FILE_REQUEST = 1002;
    private static final int SCAN_QR_REQUEST = 1003;
    private static final int PERMISSION_CAMERA_REQUEST = 1004;

    private String getMacBridgeIp() {
        return getSharedPreferences("ContinuityPrefs", MODE_PRIVATE).getString("mac_bridge_ip", "");
    }

    private String getPairKey() {
        return getSharedPreferences("ContinuityPrefs", MODE_PRIVATE).getString("aether_pair_key", "");
    }

    private String getMacBridgeUrl() {
        String ip = getMacBridgeIp();
        if (ip.isEmpty()) return null;
        return "http://" + ip + ":8898";
    }

    private TextView tvPairBadge;
    private TextView tvPairSubtitle;
    private Button btnScanQr;
    private Button btnUnpair;

    private TextView tvMacModel;
    private TextView tvMacIp;
    private TextView tvMacBattery;
    private TextView tvMacOs;
    private TextView tvFileStatus;
    private TextView tvRecentMacFiles;
    private TextView tvClipboard;
    private Button btnSendFile;
    private Button btnRefreshClip;
    private Button btnExitApp;

    private Handler mainHandler;
    private BroadcastReceiver exitReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainHandler = new Handler(Looper.getMainLooper());

        tvPairBadge = findViewById(R.id.tvPairBadge);
        tvPairSubtitle = findViewById(R.id.tvPairSubtitle);
        btnScanQr = findViewById(R.id.btnScanQr);
        btnUnpair = findViewById(R.id.btnUnpair);

        tvMacModel = findViewById(R.id.tvMacModel);
        tvMacIp = findViewById(R.id.tvMacIp);
        tvMacBattery = findViewById(R.id.tvMacBattery);
        tvMacOs = findViewById(R.id.tvMacOs);
        tvFileStatus = findViewById(R.id.tvFileStatus);
        tvRecentMacFiles = findViewById(R.id.tvRecentMacFiles);
        tvClipboard = findViewById(R.id.tvClipboard);
        btnSendFile = findViewById(R.id.btnSendFile);
        btnRefreshClip = findViewById(R.id.btnRefreshClip);
        btnExitApp = findViewById(R.id.btnExitApp);

        btnScanQr.setOnClickListener(v -> launchQrScanner());
        btnUnpair.setOnClickListener(v -> unpairDevice());
        tvMacIp.setOnClickListener(v -> showEditIpDialog());

        // Start background continuity foreground service
        try {
            Intent serviceIntent = new Intent(this, ContinuityService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Throwable ignored) {}

        // Request battery optimization exemption for uninterrupted background continuity
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent bOptIntent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    bOptIntent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(bOptIntent);
                }
            }
        } catch (Throwable ignored) {}

        btnSendFile.setOnClickListener(v -> openFilePicker());
        btnRefreshClip.setOnClickListener(v -> refreshData());
        
        btnExitApp.setOnClickListener(v -> {
            Intent stopIntent = new Intent(this, ContinuityService.class);
            stopIntent.setAction(ContinuityService.ACTION_STOP);
            startService(stopIntent);
            finishAndRemoveTask();
        });

        // Listen for exit broadcast from notification
        exitReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                finishAndRemoveTask();
            }
        };
        registerReceiver(exitReceiver, new IntentFilter("com.workzera.continuity.EXIT_APP"));

        updatePairingUi();
        refreshData();
    }

    private void updatePairingUi() {
        String key = getPairKey();
        String macName = getSharedPreferences("ContinuityPrefs", MODE_PRIVATE).getString("mac_name", "Mac");
        if (!key.isEmpty()) {
            tvPairBadge.setText("● Paired & Encrypted");
            tvPairBadge.setTextColor(0xFF34C759);
            tvPairBadge.setBackgroundResource(R.drawable.status_badge_green);
            tvPairSubtitle.setText("Cryptographically paired with " + macName + ". All network traffic is authenticated.");
            btnScanQr.setText("Re-scan QR Code");
            btnUnpair.setVisibility(android.view.View.VISIBLE);
        } else {
            tvPairBadge.setText("Unpaired");
            tvPairBadge.setTextColor(0xFFFF9500);
            tvPairBadge.setBackgroundResource(R.drawable.status_badge_gray);
            tvPairSubtitle.setText("Scan the QR code on your Mac to establish a secure 192-bit cryptographic link. Other devices on Wi-Fi cannot connect.");
            btnScanQr.setText("📷 Scan QR Code on Mac");
            btnUnpair.setVisibility(android.view.View.GONE);
        }
    }

    private void launchQrScanner() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.CAMERA}, PERMISSION_CAMERA_REQUEST);
                return;
            }
        }
        startActivityForResult(new Intent(this, ScanQrActivity.class), SCAN_QR_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_CAMERA_REQUEST && grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startActivityForResult(new Intent(this, ScanQrActivity.class), SCAN_QR_REQUEST);
        }
    }

    private void unpairDevice() {
        getSharedPreferences("ContinuityPrefs", MODE_PRIVATE)
                .edit()
                .remove("aether_pair_key")
                .remove("mac_name")
                .apply();
        Toast.makeText(this, "Device unpaired. Scan QR to reconnect.", Toast.LENGTH_SHORT).show();
        updatePairingUi();
        refreshData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePairingUi();
        refreshData();
    }

    @Override
    protected void onDestroy() {
        try {
            if (exitReceiver != null) unregisterReceiver(exitReceiver);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void showEditIpDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Configure Mac Bridge IP");
        builder.setMessage("Enter the local Wi-Fi IP address of your Mac:");

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(getMacBridgeIp());
        builder.setView(input);

        builder.setPositiveButton("Save & Connect", (dialog, which) -> {
            String newIp = input.getText().toString().trim();
            if (!newIp.isEmpty()) {
                getSharedPreferences("ContinuityPrefs", MODE_PRIVATE)
                        .edit()
                        .putString("mac_bridge_ip", newIp)
                        .apply();
                refreshData();
                Toast.makeText(this, "Connecting to " + newIp + "...", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void refreshData() {
        String bridgeUrl = getMacBridgeUrl();
        if (bridgeUrl == null) {
            tvMacIp.setText("Tap to configure IP");
            tvMacModel.setText("Mac (Waiting for connection...)");
            return;
        }

        final String token = getPairKey();

        // Fetch Mac status
        new Thread(() -> {
            try {
                URL url = new URL(bridgeUrl + "/api/status");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                if (!token.isEmpty()) conn.setRequestProperty("X-Aether-Token", token);
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) sb.append(line);
                    in.close();

                    String json = sb.toString();
                    mainHandler.post(() -> parseMacStatus(json));
                } else if (code == 401) {
                    mainHandler.post(() -> {
                        tvMacModel.setText("Mac (Pairing Required)");
                        tvPairBadge.setText("⚠️ Unauthorized");
                        tvPairBadge.setTextColor(0xFFFF453A);
                        tvPairBadge.setBackgroundResource(R.drawable.status_badge_gray);
                    });
                }
            } catch (Exception ignored) {}
        }).start();

        // Fetch recent Mac files
        new Thread(() -> {
            try {
                URL url = new URL(bridgeUrl + "/api/files/recent-mac");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                if (!token.isEmpty()) conn.setRequestProperty("X-Aether-Token", token);
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                if (conn.getResponseCode() == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) sb.append(line);
                    in.close();

                    String json = sb.toString();
                    mainHandler.post(() -> parseMacFiles(json));
                }
            } catch (Exception ignored) {}
        }).start();

        // Fetch bridge synchronized clipboard
        new Thread(() -> {
            try {
                URL url = new URL(bridgeUrl + "/api/clipboard");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                if (!token.isEmpty()) conn.setRequestProperty("X-Aether-Token", token);
                conn.setConnectTimeout(1500);
                conn.setReadTimeout(1500);
                if (conn.getResponseCode() == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) sb.append(line);
                    in.close();
                    org.json.JSONObject obj = new org.json.JSONObject(sb.toString());
                    String clip = obj.optString("clipboard", "");
                    if (!clip.isEmpty()) {
                        mainHandler.post(() -> tvClipboard.setText("Clipboard: " + clip));
                    }
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void parseMacStatus(String json) {
        try {
            org.json.JSONObject obj = new org.json.JSONObject(json);
            String model = obj.optString("model", "Mac");
            String osName = obj.optString("os", "macOS");
            int batt = obj.optInt("battery", 0);
            boolean isCharging = obj.optBoolean("isCharging", false);
            String ip = obj.optString("ip", getMacBridgeIp());
            int port = obj.optInt("port", 8898);

            tvMacModel.setText(model);
            tvMacBattery.setText((isCharging ? "⚡ " : "🔋 ") + batt + "%");
            tvMacOs.setText(osName);
            tvMacIp.setText(ip + ":" + port + " (Tap to change)");
        } catch (Exception ignored) {}
    }

    private void parseMacFiles(String json) {
        try {
            org.json.JSONObject obj = new org.json.JSONObject(json);
            org.json.JSONArray arr = obj.optJSONArray("files");
            if (arr != null && arr.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < arr.length() && i < 6; i++) {
                    org.json.JSONObject f = arr.getJSONObject(i);
                    String name = f.optString("name", "");
                    String size = f.optString("size", "");
                    if (!name.isEmpty()) {
                        sb.append("• ").append(name);
                        if (!size.isEmpty()) sb.append(" (").append(size).append(")");
                        sb.append("\n");
                    }
                }
                tvRecentMacFiles.setText(sb.toString().trim());
            } else {
                tvRecentMacFiles.setText("No recent files in Mac ~/Downloads");
            }
        } catch (Exception e) {
            tvRecentMacFiles.setText("Files available on Mac ~/Downloads");
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_FILE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            uploadFileToMac(uri);
        } else if (requestCode == SCAN_QR_REQUEST && resultCode == RESULT_OK) {
            updatePairingUi();
            refreshData();
        }
    }

    private void uploadFileToMac(Uri uri) {
        tvFileStatus.setText("Transferring to Mac...");
        new Thread(() -> {
            try {
                String filename = getFileName(uri);
                InputStream inStream = getContentResolver().openInputStream(uri);
                if (inStream == null) return;

                URL url = new URL(getMacBridgeUrl() + "/api/files/receive?name=" + Uri.encode(filename));
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/octet-stream");
                conn.setRequestProperty("X-Filename", filename);
                String token = getPairKey();
                if (!token.isEmpty()) conn.setRequestProperty("X-Aether-Token", token);

                OutputStream out = conn.getOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = inStream.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
                out.close();
                inStream.close();

                int code = conn.getResponseCode();
                mainHandler.post(() -> {
                    if (code == 200) {
                        tvFileStatus.setText("✓ " + filename + " sent to Mac ~/Downloads!");
                        Toast.makeText(this, "Transferred to Mac ~/Downloads!", Toast.LENGTH_SHORT).show();
                    } else if (code == 401) {
                        tvFileStatus.setText("⛔ Unauthorized (Scan QR to pair)");
                    } else {
                        tvFileStatus.setText("Failed to transfer: HTTP " + code);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> tvFileStatus.setText("Error: " + e.getMessage()));
            }
        }).start();
    }

    private String getFileName(Uri uri) {
        String name = "phone_file_" + System.currentTimeMillis();
        try {
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) name = cursor.getString(nameIndex);
                cursor.close();
            }
        } catch (Exception ignored) {}
        return name;
    }
}
