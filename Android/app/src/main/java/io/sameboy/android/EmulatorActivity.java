package io.sameboy.android;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.widget.TextView;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.os.VibrationEffect;
import android.hardware.input.InputManager;
import android.view.View;

import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EmulatorActivity extends Activity implements EmulatorSurfaceView.Listener {
    public static final String EXTRA_ZIP_ENTRY = "io.sameboy.zipEntry";
    public static final String EXTRA_ROM_KEY = "io.sameboy.romKey";
    private long ctx = 0;
    private File savFile;
    private String romName = "rom";
    private boolean menuOpen = false;
    private boolean printerConnected = false;
    private Settings settings;
    private TouchOverlayView overlay;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable batteryPoll = new Runnable() {
        @Override public void run() {
            if (ctx != 0 && !menuOpen && NativeBridge.nativeIsBatteryDirty(ctx)) {
                NativeBridge.nativePause(ctx, true);   // save+clear as one parked unit
                SaveStore.write(savFile, NativeBridge.nativeSaveBattery(ctx));
                NativeBridge.nativeClearBatteryDirty(ctx);
                NativeBridge.nativePause(ctx, false);
            }
            handler.postDelayed(this, 2000);
        }
    };
    private GamepadMapper pad;
    private Vibrator vibrator;
    private boolean rumbling = false;
    private final boolean[] axisState = new boolean[4];   // right,left,up,down
    private final Runnable rumblePoll = new Runnable() {
        @Override public void run() {
            if (ctx != 0) {
                int amp = menuOpen ? 0 : NativeBridge.nativeRumbleAmplitude(ctx);  // paused emu can't zero it
                driveRumble(amp);
            }
            handler.postDelayed(this, 50);
        }
    };
    private final InputManager.InputDeviceListener padListener = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) { refreshOverlayVisibility(); }
        @Override public void onInputDeviceRemoved(int id) { releaseAllKeys(); refreshOverlayVisibility(); }
        @Override public void onInputDeviceChanged(int id) { refreshOverlayVisibility(); }
    };
    private static final int REQ_CAMERA = 42;
    private android.hardware.camera2.CameraDevice cameraDevice;
    private android.hardware.camera2.CameraCaptureSession cameraSession;
    private android.media.ImageReader cameraReader;
    private android.os.HandlerThread cameraThread;
    private android.os.Handler cameraHandler;
    private boolean cameraRunning = false;
    private long lastCameraWant = 0;
    private boolean cameraDenied = false;   // latch a CAMERA denial so we don't re-prompt every poll
    private final byte[] cameraGray = new byte[128 * 112];
    private final Runnable cameraPoll = new Runnable() {
        @Override public void run() {
            if (ctx != 0 && NativeBridge.nativeCameraWanted(ctx)) {
                lastCameraWant = android.os.SystemClock.uptimeMillis();
                if (!cameraRunning) ensureCameraPermissionAndStart();
            } else if (cameraRunning &&
                       android.os.SystemClock.uptimeMillis() - lastCameraWant > 1500) {
                stopCamera();   // idle → release the camera (mirrors iOS 1s disable timer)
            }
            handler.postDelayed(this, 200);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        TextView loading = new TextView(this);
        loading.setText("Loading…");
        loading.setGravity(android.view.Gravity.CENTER);
        setContentView(loading);

        Uri data = getIntent().getData();
        String zipEntry = getIntent().getStringExtra(EXTRA_ZIP_ENTRY);
        String keyExtra = getIntent().getStringExtra(EXTRA_ROM_KEY);
        // Save key: CRC for library launches, display-name for external one-shot opens (M2 behavior).
        romName = (keyExtra != null && !keyExtra.isEmpty()) ? keyExtra : displayName(data);
        savFile = SaveStore.savFile(this, romName);
        settings = new Settings(this);
        pad = new GamepadMapper(this);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            vibrator = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        }

        // Read the ROM off the main thread (SAF read can ANR on slow providers).
        io.execute(() -> {
            byte[] rom = readRom(data, zipEntry);
            byte[] sav = SaveStore.read(savFile);
            runOnUiThread(() -> finishSetup(rom, sav));
        });
    }

    private void finishSetup(byte[] rom, byte[] sav) {
        if (isFinishing() || isDestroyed()) return;
        // Native layer assumes a real ROM; reject null/empty and files smaller
        // than a GB cartridge header + entry point (0x150 bytes).
        if (rom == null || rom.length < 0x150) {
            Toast.makeText(this, "Could not read ROM", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        ctx = NativeBridge.nativeCreate(settings.modelForLaunch(), rom, sav, getAssets());
        if (ctx == 0) {
            Toast.makeText(this, "Could not load ROM", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        settings.apply(ctx);    // batch Core settings + volume before threads start
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(android.graphics.Color.BLACK);
        EmulatorSurfaceView surface = new EmulatorSurfaceView(this, this);
        FrameLayout.LayoutParams surfaceLp = new FrameLayout.LayoutParams(0, 0);
        overlay = new TouchOverlayView(this, new TouchOverlayView.ControlListener() {
            @Override public void onKey(int k, boolean pressed) {
                if (ctx != 0) NativeBridge.nativeSetKey(ctx, k, pressed);
            }
            @Override public void onSpecial(int what, boolean pressed) {
                if (ctx == 0) return;
                switch (what) {
                    case TouchOverlayView.SPECIAL_REWIND:
                        NativeBridge.nativeSetRewinding(ctx, pressed); break;
                    case TouchOverlayView.SPECIAL_TURBO:
                        NativeBridge.nativeSetTurbo(ctx, pressed); break;
                    case TouchOverlayView.SPECIAL_MENU:
                        if (pressed && !menuOpen) openMenu(); break;
                }
            }
        }, r -> {
            surfaceLp.width = Math.round(r.width());
            surfaceLp.height = Math.round(r.height());
            surfaceLp.leftMargin = Math.round(r.left);
            surfaceLp.topMargin = Math.round(r.top);
            surface.setLayoutParams(surfaceLp);
        });
        overlay.setOpacity(settings.buttonOpacity());
        overlay.setHaptics(settings.haptics());
        overlay.setConsoleTheme(settings.consoleIsDark(this));
        overlay.setSwipePad(settings.swipeDpad());
        root.addView(surface, surfaceLp);
        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        refreshOverlayVisibility();
    }

    private byte[] readRom(Uri uri, String zipEntry) {
        if (uri == null) return null;
        if (zipEntry != null) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                return ZipRoms.extract(in, zipEntry);
            } catch (Exception e) { return null; }
        }
        return readAll(uri);
    }

    @Override public void onSurfaceReady(Surface s) {
        if (ctx != 0) {
            NativeBridge.nativeStart(ctx, s);
            if (menuOpen) NativeBridge.nativePause(ctx, true);
        }
    }
    @Override public void onSurfaceGone() { if (ctx != 0) NativeBridge.nativeStop(ctx); }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(batteryPoll);
        handler.removeCallbacks(rumblePoll);
        handler.removeCallbacks(cameraPoll);
        stopCamera();
        InputManager im = (InputManager) getSystemService(INPUT_SERVICE);
        if (im != null) im.unregisterInputDeviceListener(padListener);
        if (vibrator != null) { try { vibrator.cancel(); } catch (Exception ignored) {} }
        releaseAllKeys();
        rumbling = false;
        if (ctx != 0) {
            NativeBridge.nativePause(ctx, true);
            SaveStore.write(savFile, NativeBridge.nativeSaveBattery(ctx));
        }
    }
    @Override protected void onResume() {
        super.onResume();
        if (ctx != 0 && !menuOpen) {
            settings.apply(ctx);                       // self-parks once; picks up Settings changes
            if (overlay != null) {
                overlay.setOpacity(settings.buttonOpacity());
                overlay.setHaptics(settings.haptics());
                overlay.setConsoleTheme(settings.consoleIsDark(this));
                overlay.setSwipePad(settings.swipeDpad());
            }
            NativeBridge.nativePause(ctx, false);
        }
        handler.postDelayed(batteryPoll, 2000);
        if (ctx != 0) pad.load();   // pick up remap changes made in GamepadRemapActivity
        InputManager im = (InputManager) getSystemService(INPUT_SERVICE);
        if (im != null) im.registerInputDeviceListener(padListener, handler);
        handler.postDelayed(rumblePoll, 50);
        handler.postDelayed(cameraPoll, 500);
        refreshOverlayVisibility();
    }

    private void ensureCameraPermissionAndStart() {
        if (cameraDenied) return;   // user said no this foreground; don't nag every poll
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ android.Manifest.permission.CAMERA }, REQ_CAMERA);
            return;   // onRequestPermissionsResult starts it if granted
        }
        openCamera();
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == REQ_CAMERA) {
            if (r.length > 0 && r[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                cameraDenied = false;
                openCamera();
            } else {
                cameraDenied = true;   // latch; ROM falls back to Core noise
                Toast.makeText(this, "Camera denied; using noise", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    private void openCamera() {
        if (cameraRunning) return;
        android.hardware.camera2.CameraManager cm =
                (android.hardware.camera2.CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String pick = null;
            for (String id : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(id).get(
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                if (pick == null) pick = id;
                if (f != null && f == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) { pick = id; break; }
            }
            if (pick == null) return;   // no camera (e.g. Waydroid) → ROM keeps its noise
            /* Pick the smallest supported YUV size that still covers the 128x112 sensor
               (hardcoding 176x144 fails on devices that dropped sub-QVGA). deliverFrame
               downscales whatever we get. */
            int cw = 176, chh = 144;
            android.hardware.camera2.params.StreamConfigurationMap map =
                cm.getCameraCharacteristics(pick).get(
                    android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                android.util.Size[] sizes = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888);
                if (sizes != null && sizes.length > 0) {
                    android.util.Size best = null;
                    for (android.util.Size s : sizes)
                        if (s.getWidth() >= 128 && s.getHeight() >= 112 &&
                            (best == null || (long)s.getWidth()*s.getHeight() < (long)best.getWidth()*best.getHeight()))
                            best = s;
                    if (best == null)   // none big enough; take the largest offered
                        for (android.util.Size s : sizes)
                            if (best == null || (long)s.getWidth()*s.getHeight() > (long)best.getWidth()*best.getHeight())
                                best = s;
                    if (best != null) { cw = best.getWidth(); chh = best.getHeight(); }
                }
            }
            cameraThread = new android.os.HandlerThread("sb-cam");
            cameraThread.start();
            cameraHandler = new android.os.Handler(cameraThread.getLooper());
            cameraReader = android.media.ImageReader.newInstance(
                    cw, chh, android.graphics.ImageFormat.YUV_420_888, 2);
            cameraReader.setOnImageAvailableListener(reader -> {
                try {
                    android.media.Image img = reader.acquireLatestImage();   // may throw if closing
                    if (img != null) { deliverFrame(img); img.close(); }
                } catch (Exception ignored) {}   // reader closed under us on stop → ignore
            }, cameraHandler);
            cameraRunning = true;
            /* StateCallback + session callbacks on the MAIN handler so a stop's quitSafely
               on the camera thread can't drop them (and so device/session fields are only
               touched on one thread). Per-frame work stays on cameraThread via the reader. */
            cm.openCamera(pick, new android.hardware.camera2.CameraDevice.StateCallback() {
                @Override public void onOpened(android.hardware.camera2.CameraDevice d) {
                    if (!cameraRunning) { d.close(); return; }   // stopped during the open window
                    cameraDevice = d;
                    try {
                        final android.hardware.camera2.CaptureRequest.Builder rb =
                            d.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW);
                        rb.addTarget(cameraReader.getSurface());
                        d.createCaptureSession(
                            java.util.Collections.singletonList(cameraReader.getSurface()),
                            new android.hardware.camera2.CameraCaptureSession.StateCallback() {
                                @Override public void onConfigured(android.hardware.camera2.CameraCaptureSession s) {
                                    if (!cameraRunning) { try { s.close(); } catch (Exception ignored) {} return; }
                                    cameraSession = s;
                                    try { s.setRepeatingRequest(rb.build(), null, cameraHandler); }
                                    catch (Exception ignored) {}
                                }
                                @Override public void onConfigureFailed(android.hardware.camera2.CameraCaptureSession s) {
                                    stopCamera();   // main thread; frees device+thread, allows a later retry
                                }
                            }, handler);
                    } catch (Exception e) { stopCamera(); }
                }
                @Override public void onDisconnected(android.hardware.camera2.CameraDevice d) { stopCamera(); }
                @Override public void onError(android.hardware.camera2.CameraDevice d, int e) { stopCamera(); }
            }, handler);
        } catch (Exception e) { stopCamera(); }   // idempotent: frees any partial thread/reader
    }

    /** Y plane → 8:7 center-crop → nearest-neighbor downscale to 128x112 grayscale → native. */
    private void deliverFrame(android.media.Image img) {
        android.media.Image.Plane y = img.getPlanes()[0];
        java.nio.ByteBuffer buf = y.getBuffer();
        int rowStride = y.getRowStride();
        int w = img.getWidth(), h = img.getHeight();
        int cropW = Math.min(w, h * 128 / 112);   // largest 128:112 region that fits
        int cropH = Math.min(h, w * 112 / 128);
        int cropX = (w - cropW) / 2, cropY = (h - cropH) / 2;
        for (int ry = 0; ry < 112; ry++) {
            int sy = Math.min(h - 1, cropY + ry * cropH / 112);
            for (int rx = 0; rx < 128; rx++) {
                int sx = Math.min(w - 1, cropX + rx * cropW / 128);
                cameraGray[ry * 128 + rx] = buf.get(sy * rowStride + sx);
            }
        }
        long c = ctx;   // snapshot: stopCamera joins this thread before onDestroy frees ctx
        if (c != 0) NativeBridge.nativeCameraDeliver(c, cameraGray);
    }

    private void stopCamera() {
        cameraRunning = false;
        try { if (cameraSession != null) cameraSession.close(); } catch (Exception ignored) {}
        try { if (cameraDevice != null) cameraDevice.close(); } catch (Exception ignored) {}
        cameraSession = null; cameraDevice = null;
        android.os.HandlerThread t = cameraThread;
        android.media.ImageReader reader = cameraReader;
        cameraThread = null; cameraHandler = null; cameraReader = null;
        if (t != null) {
            t.quitSafely();
            try { t.join(200); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        /* Close the reader only after the camera thread is dead, so no in-flight
           onImageAvailable can touch it (the listener's try/catch covers the rest). */
        if (reader != null) { try { reader.close(); } catch (Exception ignored) {} }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int gb = (ctx != 0) ? pad.gbKeyForKeycode(event.getKeyCode()) : -1;
        if (gb >= 0 && event.getRepeatCount() == 0) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) { NativeBridge.nativeSetKey(ctx, gb, true); return true; }
            if (event.getAction() == KeyEvent.ACTION_UP)   { NativeBridge.nativeSetKey(ctx, gb, false); return true; }
        } else if (gb >= 0) {
            return true;   // swallow auto-repeat for a held mapped key
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (ctx != 0 && (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                && event.getAction() == MotionEvent.ACTION_MOVE) {
            boolean[] now = GamepadMapper.axisDpad(event);
            int[] gbForAxis = { GamepadMapper.RIGHT, GamepadMapper.LEFT, GamepadMapper.UP, GamepadMapper.DOWN };
            for (int i = 0; i < 4; i++) {
                if (now[i] != axisState[i]) { NativeBridge.nativeSetKey(ctx, gbForAxis[i], now[i]); axisState[i] = now[i]; }
            }
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    private void driveRumble(int amp) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (amp > 0) {
            int a = Math.max(1, Math.min(255, amp));
            try {
                if (vibrator.hasAmplitudeControl()) vibrator.vibrate(VibrationEffect.createOneShot(70, a));
                else vibrator.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE));
                rumbling = true;
            } catch (Exception ignored) {}
        } else if (rumbling) {
            try { vibrator.cancel(); } catch (Exception ignored) {}
            rumbling = false;
        }
    }

    /** Release every GB key + clear axis latch (gamepad unplug / pause: no ACTION_UP arrives). */
    private void releaseAllKeys() {
        if (ctx != 0) for (int i = 0; i < 8; i++) NativeBridge.nativeSetKey(ctx, i, false);
        for (int i = 0; i < axisState.length; i++) axisState[i] = false;
    }

    private void refreshOverlayVisibility() {
        if (overlay != null) overlay.setControlsHidden(GamepadMapper.anyGamepadConnected());
    }

    private void openMenu() {
        menuOpen = true;
        NativeBridge.nativePause(ctx, true);
        GameMenuDialog.show(this, new GameMenuDialog.Host() {
            @Override public void onMenuClosed() {
                menuOpen = false;
                if (ctx != 0) NativeBridge.nativePause(ctx, false);
            }
            @Override public void onSaveSlot(int slot) { saveStateToSlot(slot); }
            @Override public void onLoadSlot(int slot) { loadStateFromSlot(slot); }
            @Override public void onResetGame() { if (ctx != 0) NativeBridge.nativeReset(ctx); }
            @Override public void onSwitchModel(int model) { if (ctx != 0) NativeBridge.nativeSwitchModel(ctx, model); }
            @Override public void onExitGame() { finish(); }
            @Override public void onOpenSettings() {
                menuOpen = false;   // menu is closing; SettingsActivity takes over, onResume re-applies
                startActivity(new android.content.Intent(EmulatorActivity.this, SettingsActivity.class));
            }
            @Override public void onConnectAccessory(int which) {
                if (ctx == 0) return;
                if (which == 1) { NativeBridge.nativeConnectPrinter(ctx); printerConnected = true; }
                else            { NativeBridge.nativeDisconnectPrinter(ctx); printerConnected = false; }
            }
            @Override public void onPrinterFeed() {
                menuOpen = false;   // menu closing; PrinterFeedActivity takes over, onResume re-applies
                android.content.Intent i = new android.content.Intent(EmulatorActivity.this, PrinterFeedActivity.class);
                i.putExtra(PrinterFeedActivity.EXTRA_CTX, ctx);
                startActivity(i);
            }
            @Override public void onLinkCable() {
                menuOpen = false;   // LinkActivity takes over; onResume re-applies
                android.content.Intent i = new android.content.Intent(EmulatorActivity.this, LinkActivity.class);
                i.putExtra(LinkActivity.EXTRA_CTX, ctx);
                startActivity(i);
            }
            @Override public boolean printerConnected() { return printerConnected; }
            @Override public java.io.File stateFile(int slot) {
                return SaveStore.stateFile(EmulatorActivity.this, romName, slot);
            }
            @Override public Bitmap thumbnail(int slot) {
                java.io.File t = SaveStore.stateThumb(EmulatorActivity.this, romName, slot);
                return t.exists() ? android.graphics.BitmapFactory.decodeFile(t.getPath()) : null;
            }
        });
    }

    private void saveStateToSlot(int slot) {
        byte[] state = NativeBridge.nativeSaveState(ctx);
        if (state == null) {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show();
            return;
        }
        SaveStore.write(SaveStore.stateFile(this, romName, slot), state);
        int[] f = NativeBridge.nativeCopyFrame(ctx);
        if (f != null && f.length >= 2) {
            int w = f[0], h = f[1];
            int[] px = new int[w * h];
            for (int i = 0; i < w * h; i++) {
                int p = f[2 + i];   // native ABGR → Bitmap ARGB
                px[i] = (p & 0xFF00FF00) | ((p & 0xFF) << 16) | ((p >>> 16) & 0xFF);
            }
            Bitmap bmp = Bitmap.createBitmap(px, 0, w, w, h, Bitmap.Config.ARGB_8888);
            try (java.io.FileOutputStream out =
                     new java.io.FileOutputStream(SaveStore.stateThumb(this, romName, slot))) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (java.io.IOException ignored) {}
        }
    }

    private void loadStateFromSlot(int slot) {
        byte[] state = SaveStore.read(SaveStore.stateFile(this, romName, slot));
        if (state == null || !NativeBridge.nativeLoadState(ctx, state)) {
            Toast.makeText(this, "Load failed", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (ctx != 0) { NativeBridge.nativeDestroy(ctx); ctx = 0; }
    }

    private byte[] readAll(Uri uri) {
        if (uri == null) return null;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) { return null; }
    }

    private String displayName(Uri uri) {
        String name = null;
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{ android.provider.OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        } catch (Exception ignored) {}
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "rom";
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.replaceAll("[/:\\\\]", "_");
        return name.isEmpty() ? "rom" : name;
    }
}
