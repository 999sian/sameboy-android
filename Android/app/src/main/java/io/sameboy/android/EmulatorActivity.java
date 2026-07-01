package io.sameboy.android;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

public class EmulatorActivity extends Activity implements EmulatorSurfaceView.Listener {
    private long ctx = 0;
    private File savFile;
    private String romName = "rom";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        byte[] rom = readAll(getIntent().getData());
        // Native layer assumes a real ROM; reject null/empty and files smaller
        // than a GB cartridge header + entry point (0x150 bytes).
        if (rom == null || rom.length < 0x150) {
            Toast.makeText(this, "Could not read ROM", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        romName = displayName(getIntent().getData());
        savFile = SaveStore.savFile(this, romName);
        byte[] sav = SaveStore.read(savFile);

        // Model auto: CGB_E plays both DMG and CGB well for M1.
        ctx = NativeBridge.nativeCreate(NativeBridge.MODEL_CGB_E, rom, sav, getAssets());
        if (ctx == 0) {
            Toast.makeText(this, "Could not load ROM", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        EmulatorSurfaceView surface = new EmulatorSurfaceView(this, this);
        TouchOverlayView overlay = new TouchOverlayView(this, new TouchOverlayView.ControlListener() {
            @Override public void onKey(int k, boolean pressed) {
                if (ctx != 0) NativeBridge.nativeSetKey(ctx, k, pressed);
            }
            @Override public void onSpecial(int what, boolean pressed) {
                // wired fully in the activity task
            }
        });
        root.addView(surface);
        root.addView(overlay);
        setContentView(root);
    }

    @Override public void onSurfaceReady(Surface s) { if (ctx != 0) NativeBridge.nativeStart(ctx, s); }
    @Override public void onSurfaceGone() { if (ctx != 0) NativeBridge.nativeStop(ctx); }

    @Override protected void onPause() {
        super.onPause();
        if (ctx != 0) {
            NativeBridge.nativePause(ctx, true);
            SaveStore.write(savFile, NativeBridge.nativeSaveBattery(ctx));
        }
    }
    @Override protected void onResume() {
        super.onResume();
        if (ctx != 0) NativeBridge.nativePause(ctx, false);
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
