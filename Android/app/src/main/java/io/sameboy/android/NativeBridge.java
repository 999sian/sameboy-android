package io.sameboy.android;

import android.content.res.AssetManager;
import android.view.Surface;

public final class NativeBridge {
    static { System.loadLibrary("sameboy_core"); }
    private NativeBridge() {}

    public static native long nativeCreate(int model, byte[] rom, byte[] sav, AssetManager assets);
    public static native void nativeStart(long ctx, Surface surface);
    public static native void nativeStop(long ctx);
    public static native void nativePause(long ctx, boolean paused);
    public static native void nativeSetKey(long ctx, int gbKeyIndex, boolean pressed);
    public static native void nativeReset(long ctx);
    public static native byte[] nativeSaveBattery(long ctx);
    public static native byte[]  nativeSaveState(long ctx);
    public static native boolean nativeLoadState(long ctx, byte[] state);
    public static native void    nativeSetTurbo(long ctx, boolean on);
    public static native void    nativeSetRewinding(long ctx, boolean on);
    public static native void    nativeSwitchModel(long ctx, int model);
    public static native boolean nativeIsBatteryDirty(long ctx);
    public static native void    nativeClearBatteryDirty(long ctx);
    /** [0]=width, [1]=height, then width*height ABGR pixels. null on failure. */
    public static native int[]   nativeCopyFrame(long ctx);
    /** Returns { title, crc32Hex8Upper } for a ROM buffer, or null if not a valid ROM. */
    public static native String[] nativeRomInfo(byte[] rom);
    public static native void nativeApplySettings(long ctx, int colorCorrection, double lightTemp,
            int border, int highpass, int rtcMode, double rewindSeconds, double turboCap, double interference,
            int rumbleMode);
    public static native void nativeSetVolume(long ctx, int volume256);
    public static native void nativeSetPalette(long ctx, int builtinIndex, int c0, int c1, int c2, int c3);
    public static native int nativeRumbleAmplitude(long ctx);
    public static native void nativeDestroy(long ctx);

    // GB_key_t indices (Core/joypad.h)
    public static final int KEY_RIGHT = 0, KEY_LEFT = 1, KEY_UP = 2, KEY_DOWN = 3,
                            KEY_A = 4, KEY_B = 5, KEY_SELECT = 6, KEY_START = 7;
    // GB_model_t (Core/model.h)
    public static final int MODEL_DMG_B = 0x002, MODEL_CGB_E = 0x205, MODEL_AGB = 0x207;
}
