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
import android.os.Build;
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
    /** The live native session, 0 when none. Main-thread only. Printer/Link activities validate
     *  their Intent ctx against this: EmulatorActivity can be destroyed beneath them (e.g.
     *  "Don't keep activities"), freeing ctx — calling native with the stale pointer is a UAF. */
    static long activeCtx = 0;
    private File savFile;
    private String romName = "rom";
    private boolean menuOpen = false;
    private boolean printerConnected = false;
    private Settings settings;
    private TouchOverlayView overlay;
    private FrameLayout root;
    private EmulatorSurfaceView surface;
    private FrameLayout.LayoutParams surfaceLp;
    private boolean controlsHidden = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable batteryPoll = new Runnable() {
        @Override public void run() {
            applyScreenGeometry();   // catches a border appearing/disappearing mid-game (cheap: one int JNI call)
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
        @Override public void onInputDeviceAdded(int id) { refreshControlsVisibility(); }
        @Override public void onInputDeviceRemoved(int id) { releaseAllKeys(); refreshControlsVisibility(); }
        @Override public void onInputDeviceChanged(int id) { refreshControlsVisibility(); }
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
        String keyOrNull = (keyExtra != null && !keyExtra.isEmpty()) ? keyExtra : null;
        settings = new Settings(this);
        pad = new GamepadMapper(this);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            vibrator = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        }

        // Read the ROM (and resolve the display name — a synchronous binder call into a
        // possibly-slow DocumentsProvider) off the main thread; SAF I/O can ANR.
        io.execute(() -> {
            romName = keyOrNull != null ? keyOrNull : displayName(data);
            savFile = SaveStore.savFile(this, romName);
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
        activeCtx = ctx;

        settings.apply(ctx);    // batch Core settings + volume before threads start
        root = new FrameLayout(this);
        root.setBackgroundColor(android.graphics.Color.BLACK);
        surface = new EmulatorSurfaceView(this, this);
        surfaceLp = new FrameLayout.LayoutParams(0, 0);
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
        });
        overlay.setOpacity(settings.buttonOpacity());
        overlay.setHaptics(settings.haptics());
        overlay.setConsoleTheme(settings.consoleIsDark(this));
        overlay.setSwipePad(settings.swipeDpad());
        root.addView(surface, surfaceLp);
        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        /* Screen geometry follows the root's size: in fill-screen mode the overlay is GONE and
           can't report it. Also covers rotation and control-visibility switches. */
        root.addOnLayoutChangeListener((v, l, t, r, b, pl, pt, pr, pb) -> applyScreenGeometry());
        setContentView(root);
        refreshControlsVisibility();
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
        /* Pin the smoothest display mode first (sets pinnedHz — 120 on a 120/144 panel, else
           60), then tell SurfaceFlinger this surface renders at that rate so it picks it as the
           render rate. The emu stays audio-clocked at 59.7275; present-on-produce shows every
           frame at pinnedHz with no drops. */
        pinRefreshRate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { s.setFrameRate(pinnedHz, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT); }
            catch (Exception ignored) {}
        }
        if (ctx != 0) {
            NativeBridge.nativeStart(ctx, s);
            NativeBridge.nativeSetFilter(ctx, settings.filter());
            if (menuOpen) NativeBridge.nativePause(ctx, true);
            recheckGeometrySoon();   // first frame settles the border size
        }
    }
    @Override public void onSurfaceGone() { if (ctx != 0) NativeBridge.nativeStop(ctx); }

    /* Pick the display mode that presents the GB's 59.7275 fps most smoothly and pin it.
       Key finding from on-device measurement: targeting 60 Hz is a trap — it sits just above
       59.7275, so LTPO power governors idle the render rate BELOW it (OnePlus → 50 Hz), which
       drops ~10 emu frames/s (judder). Instead prefer the HIGHEST refresh that's a near-integer
       multiple of 59.7275 (≈120 Hz on a 120/144 panel): with present-on-produce the emu posts
       59.7275 unique frames and every one is shown (≥1 scanout), so nothing drops. 60 Hz is the
       fallback on 60-only panels. We pin the mode + request the rate; the min/max render clamp
       (reflection, public since API 30 but absent from some SDK stubs) keeps the floor there. */
    private float pinnedHz = 60f;
    private void pinRefreshRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;   // Display.Mode since API 23
        try {
            android.view.Display display = getWindow().getDecorView().getDisplay();
            if (display == null) return;
            android.view.Display.Mode cur = display.getMode();
            android.view.Display.Mode best = null;
            float bestScore = Float.MAX_VALUE;
            for (android.view.Display.Mode m : display.getSupportedModes()) {
                if (m.getPhysicalWidth() != cur.getPhysicalWidth()
                        || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;  // same resolution only
                float hz = m.getRefreshRate();
                if (hz < 59f) continue;                        // below GB rate → would drop frames
                float mult = hz / 59.7275f;
                float frac = Math.abs(mult - Math.round(mult));  // distance from an integer multiple
                if (frac > 0.12f) continue;                     // e.g. reject 144 (2.41×, uneven scanout)
                // Among clean multiples, prefer the highest (most idle headroom above 59.73).
                float score = frac - hz / 1000f;                // lower = better; bias toward higher hz
                if (score < bestScore) { bestScore = score; best = m; }
            }
            if (best == null) {   // no clean multiple ≥60 (e.g. only 144): fall back to nearest ≥60
                for (android.view.Display.Mode m : display.getSupportedModes()) {
                    if (m.getPhysicalWidth() != cur.getPhysicalWidth()
                            || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
                    float hz = m.getRefreshRate();
                    if (hz < 59f) continue;
                    if (best == null || hz < best.getRefreshRate()) best = m;
                }
            }
            if (best == null) return;
            pinnedHz = best.getRefreshRate();
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.preferredDisplayModeId = best.getModeId();
            lp.preferredRefreshRate = pinnedHz;
            try {   // render-rate clamp [hz,hz] (reflection; no-op if fields absent)
                lp.getClass().getField("preferredMinDisplayRefreshRate").setFloat(lp, pinnedHz);
                lp.getClass().getField("preferredMaxDisplayRefreshRate").setFloat(lp, pinnedHz);
            } catch (Throwable ignored) {}
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {}
    }

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
        pinRefreshRate();   /* reassert 60Hz clamp; window attrs can reset across pause */
        if (ctx != 0 && !menuOpen) {
            settings.apply(ctx);                       // self-parks once; picks up Settings changes
            NativeBridge.nativeSetFilter(ctx, settings.filter());
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
        refreshControlsVisibility();   // also re-applies geometry
        recheckGeometrySoon();         // Border/model may have changed in Settings
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
        if (gb == GamepadMapper.MENU) {   // frontend action, never a Core key
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && !menuOpen)
                openMenu();
            return true;
        }
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

    /** On-screen controls: Auto (hide while a physical gamepad is connected) / Always / Never.
     *  Hidden means GONE — the emulator screen then gets the whole display (see GBLayout's
     *  fullscreen branch) and the menu is reached with the gamepad's Menu binding. */
    private void refreshControlsVisibility() {
        boolean hide;
        switch (settings.onscreenControls()) {
            case 1:  hide = false; break;                          // Always
            case 2:  hide = true; break;                           // Never
            default: hide = GamepadMapper.anyGamepadConnected();   // Auto
        }
        controlsHidden = hide;
        if (overlay != null) {
            if (hide) overlay.releaseAll();   // no ACTION_CANCEL arrives for a held touch button
            overlay.setVisibility(hide ? View.GONE : View.VISIBLE);
        }
        setSystemBarsHidden(hide);
        applyScreenGeometry();
    }

    /** Core output size: 160x144, or 256x224 while an SGB border is displayed. Only changes
     *  when a border appears/disappears (SGB boot, model switch, Border setting). */
    private int srcW = 160, srcH = 144;

    /** Pull the current Core output size; true when it changed. 0 (no session / no frame yet)
     *  keeps the last known size — the battery poll re-checks every 2s. */
    private boolean refreshSourceSize() {
        if (ctx == 0) return false;
        int packed = NativeBridge.nativeScreenSize(ctx);
        if (packed == 0) return false;
        int w = packed >>> 16, h = packed & 0xFFFF;
        if (w == srcW && h == srcH) return false;
        srcW = w; srcH = h;
        if (overlay != null) overlay.setSourceSize(w, h);   // console body follows the wider well
        return true;
    }

    /** The border only shows up once the Core has produced a frame in the new configuration, so
     *  a start/reset/model/border change can't be measured synchronously. One delayed re-check
     *  covers the common case; the 2s battery poll is the backstop. No new thread, no busy poll. */
    private void recheckGeometrySoon() { handler.postDelayed(this::applyScreenGeometry, 400); }

    /** Size/place the emulator surface for the current mode: the console well when the touch
     *  controls are up, the largest aspect-correct fit of the display when they're hidden.
     *  Sized to the Core's ACTUAL output aspect so the renderer never letterboxes twice. */
    private void applyScreenGeometry() {
        if (root == null || surface == null) return;
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) return;   // pre-layout; the layout listener calls back
        refreshSourceSize();
        android.graphics.RectF r = new GBLayout(w, h, getResources().getDisplayMetrics().density,
                controlsHidden, srcW, srcH).screenRect;
        int nw = Math.round(r.width()), nh = Math.round(r.height());
        int nl = Math.round(r.left), nt = Math.round(r.top);
        if (nw == surfaceLp.width && nh == surfaceLp.height
                && nl == surfaceLp.leftMargin && nt == surfaceLp.topMargin) return;  // no relayout loop
        surfaceLp.width = nw; surfaceLp.height = nh;
        surfaceLp.leftMargin = nl; surfaceLp.topMargin = nt;
        surface.setLayoutParams(surfaceLp);
    }

    /** With the touch controls gone the whole panel is the screen, so drop the status/nav bars
     *  too. setSystemUiVisibility is deprecated but is the one code path that works across
     *  minSdk 26..targetSdk 34 (no edge-to-edge enforcement below 35). */
    @SuppressWarnings("deprecation")
    private void setSystemBarsHidden(boolean hidden) {
        getWindow().getDecorView().setSystemUiVisibility(hidden
                ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                : 0);
    }

    private void openMenu() {
        menuOpen = true;
        NativeBridge.nativePause(ctx, true);
        GameMenuDialog.show(this, new GameMenuDialog.Host() {
            @Override public void onMenuClosed() {
                menuOpen = false;
                if (ctx != 0) NativeBridge.nativePause(ctx, false);
                // Reset / model switch / border toggle all land here, and the Core only
                // reports its new output size once it has run a frame again.
                recheckGeometrySoon();
            }
            @Override public void onSaveSlot(int slot) { saveStateToSlot(slot); }
            @Override public void onLoadSlot(int slot) { loadStateFromSlot(slot); }
            @Override public void onResetGame() { if (ctx != 0) NativeBridge.nativeReset(ctx); }
            @Override public void onSwitchModel(int model) { if (ctx != 0) NativeBridge.nativeSwitchModel(ctx, model); }
            @Override public void onSetBorderMode(int mode) {
                settings.setBorderMode(mode);
                if (ctx != 0) settings.apply(ctx);   // live: GB_set_border_mode applies between frames
            }
            @Override public int borderMode() { return settings.borderMode(); }
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
            return;
        }
        recheckGeometrySoon();   // a state can restore a different model/border → different size
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (ctx != 0) { if (activeCtx == ctx) activeCtx = 0; NativeBridge.nativeDestroy(ctx); ctx = 0; }
        io.shutdown();   // idle single-thread executors are never GC'd; one leaked per launch
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
