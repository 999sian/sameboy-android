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
        EmulatorSurfaceView surface = new EmulatorSurfaceView(this, this);
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
        root.addView(surface);
        root.addView(overlay);
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
            if (overlay != null) { overlay.setOpacity(settings.buttonOpacity()); overlay.setHaptics(settings.haptics()); }
            NativeBridge.nativePause(ctx, false);
        }
        handler.postDelayed(batteryPoll, 2000);
        if (ctx != 0) pad.load();   // pick up remap changes made in GamepadRemapActivity
        InputManager im = (InputManager) getSystemService(INPUT_SERVICE);
        if (im != null) im.registerInputDeviceListener(padListener, handler);
        handler.postDelayed(rumblePoll, 50);
        refreshOverlayVisibility();
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
        if (overlay != null) overlay.setVisibility(GamepadMapper.anyGamepadConnected() ? View.GONE : View.VISIBLE);
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
                android.content.Intent i = new android.content.Intent(EmulatorActivity.this, PrinterFeedActivity.class);
                i.putExtra(PrinterFeedActivity.EXTRA_CTX, ctx);
                startActivity(i);
            }
            @Override public boolean printerConnected() { return printerConnected; }
            @Override public boolean hasPrintouts() { return ctx != 0 && NativeBridge.nativePrinterGeneration(ctx) > 0; }
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
