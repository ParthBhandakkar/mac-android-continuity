package com.workzera.continuity;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class ScanQrActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    private SurfaceView cameraSurface;
    private TextView tvScanStatus;
    private Button btnCancelScan;

    private Camera camera;
    private boolean isScanning = true;
    private boolean isProcessingFrame = false;
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan_qr);

        mainHandler = new Handler(Looper.getMainLooper());
        cameraSurface = findViewById(R.id.cameraSurface);
        tvScanStatus = findViewById(R.id.tvScanStatus);
        btnCancelScan = findViewById(R.id.btnCancelScan);

        btnCancelScan.setOnClickListener(v -> finish());
        cameraSurface.getHolder().addCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            camera = Camera.open();
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(holder);

            Camera.Parameters params = camera.getParameters();
            if (params.getSupportedFocusModes() != null &&
                params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            camera.setParameters(params);
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (Exception e) {
            Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    private void releaseCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
            } catch (Exception ignored) {}
            camera = null;
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (!isScanning || isProcessingFrame || camera == null) return;
        isProcessingFrame = true;

        new Thread(() -> {
            try {
                Camera.Size size = camera.getParameters().getPreviewSize();
                int width = size.width;
                int height = size.height;

                // Rotate preview frame 90 degrees for portrait orientation
                byte[] rotated = new byte[data.length];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        rotated[x * height + height - y - 1] = data[x + y * width];
                    }
                }

                PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                        rotated, height, width, 0, 0, height, width, false);
                BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

                Result result = new MultiFormatReader().decodeWithState(bitmap);
                if (result != null && result.getText() != null) {
                    isScanning = false;
                    mainHandler.post(() -> onQrCodeDetected(result.getText()));
                    return;
                }
            } catch (Exception ignored) {
                // No QR barcode in this preview frame
            } finally {
                isProcessingFrame = false;
            }
        }).start();
    }

    private void onQrCodeDetected(String rawText) {
        tvScanStatus.setText("Authenticating with Mac...");

        new Thread(() -> {
            try {
                JSONObject obj;
                if (rawText.startsWith("aether://")) {
                    android.net.Uri uri = android.net.Uri.parse(rawText);
                    obj = new JSONObject();
                    obj.put("ip", uri.getQueryParameter("ip"));
                    obj.put("port", Integer.parseInt(uri.getQueryParameter("port")));
                    obj.put("key", uri.getQueryParameter("key"));
                    obj.put("name", uri.getQueryParameter("name"));
                } else {
                    obj = new JSONObject(rawText);
                }

                String macIp = obj.getString("ip");
                int port = obj.optInt("port", 8898);
                String pairKey = obj.getString("key");
                String macName = obj.optString("name", "Mac");

                // Execute pairing handshake with Mac
                URL url = new URL("http://" + macIp + ":" + port + "/api/pair");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("token", pairKey);
                body.put("model", Build.MODEL);
                body.put("manufacturer", Build.MANUFACTURER);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    // Save trusted pairing credentials
                    getSharedPreferences("ContinuityPrefs", MODE_PRIVATE)
                            .edit()
                            .putString("mac_bridge_ip", macIp)
                            .putString("aether_pair_key", pairKey)
                            .putString("mac_name", macName)
                            .apply();

                    mainHandler.post(() -> {
                        Toast.makeText(ScanQrActivity.this, "✓ Paired securely with " + macName, Toast.LENGTH_LONG).show();
                        setResult(RESULT_OK);
                        finish();
                    });
                } else {
                    mainHandler.post(() -> {
                        Toast.makeText(ScanQrActivity.this, "Pairing rejected by Mac (HTTP " + code + ")", Toast.LENGTH_LONG).show();
                        isScanning = true;
                        tvScanStatus.setText("Align QR inside box");
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    Toast.makeText(ScanQrActivity.this, "Pairing failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    isScanning = true;
                    tvScanStatus.setText("Align QR inside box");
                });
            }
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseCamera();
    }
}
